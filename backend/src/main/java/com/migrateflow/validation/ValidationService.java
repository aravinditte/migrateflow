package com.migrateflow.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migrateflow.audit.AuditService;
import com.migrateflow.common.ApiException;
import com.migrateflow.identity.AppUser;
import com.migrateflow.mapping.MappingDefinition;
import com.migrateflow.mapping.MappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Creates immutable validation snapshots (spec 5 step 4). Validation runs on a
 * bounded executor; the snapshot row is created QUEUED and transitions to
 * COMPLETED with counts, or FAILED with the error retained privately.
 */
@Service
public class ValidationService {
    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);
    private final JdbcTemplate jdbc;
    private final MappingService mappings;
    private final AuditService audit;
    private final Executor executor;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public ValidationService(JdbcTemplate jdbc, MappingService mappings, AuditService audit,
                             @org.springframework.beans.factory.annotation.Qualifier("migrateflowTaskExecutor") Executor executor,
                             PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.mappings = mappings;
        this.audit = audit;
        this.executor = executor;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    public UUID startValidation(AppUser actor, UUID projectId, UUID bundleId, UUID mappingRevisionId, UUID ruleSetRevisionId) {
        // The bundle and revisions must belong to THIS project: membership in
        // the URL's project must not let a caller validate another project's
        // inputs into it. Foreign or unknown ids are 404, like any resource
        // outside the caller's project.
        requireOwned("SELECT project_id FROM control.dataset_bundles WHERE id = ?", bundleId, projectId, "Bundle");
        requireOwned("SELECT project_id FROM control.mapping_revisions WHERE id = ?", mappingRevisionId, projectId, "Mapping revision");
        if (ruleSetRevisionId != null) {
            requireOwned("SELECT project_id FROM control.rule_set_revisions WHERE id = ?", ruleSetRevisionId, projectId, "Rule set revision");
        }
        UUID rules = ruleSetRevisionId != null ? ruleSetRevisionId : mappings.ensureDefaultRuleSet(projectId);
        UUID snapshotId = UUID.randomUUID();
        jdbc.update("INSERT INTO control.validation_snapshots (id, project_id, bundle_id, mapping_revision_id, rule_set_revision_id, status, created_by) "
                        + "VALUES (?,?,?,?,?,'QUEUED',?)",
                snapshotId, projectId, bundleId, mappingRevisionId, rules, actor.id());
        audit.record(projectId, actor.id(), "VALIDATION_STARTED", "validation_snapshot", snapshotId, Map.of());
        executor.execute(() -> runValidation(snapshotId));
        return snapshotId;
    }

    private void requireOwned(String sql, UUID id, UUID projectId, String what) {
        List<UUID> owners = jdbc.query(sql, (rs, n) -> rs.getObject("project_id", UUID.class), id);
        if (owners.isEmpty() || !projectId.equals(owners.get(0))) {
            throw ApiException.notFound(what);
        }
    }

    /**
     * Runs validation synchronously (also used by tests through the async
     * path + polling). All snapshot writes happen in ONE transaction via the
     * TransactionTemplate: the earlier @Transactional annotation was bypassed
     * by the self-invoking executor lambda, so partial writes could survive
     * a failure. On any exception the transaction rolls back and the
     * snapshot is marked FAILED by a separate, non-transactional update.
     */
    public void runValidation(UUID snapshotId) {
        try {
            txTemplate.executeWithoutResult(status -> doRunValidation(snapshotId));
        } catch (Exception e) {
            log.error("Validation failed for snapshot {}", snapshotId, e);
            jdbc.update("UPDATE control.validation_snapshots SET status = 'FAILED' WHERE id = ?", snapshotId);
        }
    }

    private void doRunValidation(UUID snapshotId) {
        jdbc.update("UPDATE control.validation_snapshots SET status = 'RUNNING' WHERE id = ?", snapshotId);
        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT bundle_id, mapping_revision_id FROM control.validation_snapshots WHERE id = ?", snapshotId);
        UUID bundleId = (UUID) snapshot.get("bundle_id");
        UUID mappingRevisionId = (UUID) snapshot.get("mapping_revision_id");
        MappingDefinition definition = mappings.loadDefinition(mappingRevisionId);

        UUID customersFile = fileId(bundleId, "CUSTOMERS");
        UUID ordersFile = fileId(bundleId, "ORDERS");
        List<ValidationEngine.RawRow> customerRows = loadRows("stage.customer_input_rows", customersFile);
        List<ValidationEngine.RawRow> orderRows = loadRows("stage.order_input_rows", ordersFile);

        ValidationEngine.Outcome outcome = ValidationEngine.validate(definition, customerRows, orderRows);

        for (ValidationEngine.ValidatedCustomer c : outcome.customers()) {
            jdbc.update("INSERT INTO stage.customer_validated_rows (id, snapshot_id, input_row_id, external_customer_id, full_name, email, joined_on, is_valid, normalized_hash) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), snapshotId, c.inputRowId(), c.externalCustomerId(), c.fullName(), c.email(),
                    c.joinedOn() == null ? null : Date.valueOf(c.joinedOn()), c.valid(), c.normalizedHash());
        }
        for (ValidationEngine.ValidatedOrder o : outcome.orders()) {
            jdbc.update("INSERT INTO stage.order_validated_rows (id, snapshot_id, input_row_id, external_order_id, external_customer_id, order_date, amount_yen, status, is_valid, normalized_hash) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), snapshotId, o.inputRowId(), o.externalOrderId(), o.externalCustomerId(),
                    o.orderDate() == null ? null : Date.valueOf(o.orderDate()), o.amountYen(), o.status(),
                    o.valid(), o.normalizedHash());
        }
        for (ValidationEngine.Issue issue : outcome.issues()) {
            jdbc.update("INSERT INTO control.validation_issues (id, snapshot_id, kind, source_row_id, code, severity, field, message) "
                            + "VALUES (?,?,?,?,?,'ERROR',?,?)",
                    UUID.randomUUID(), snapshotId, issue.kind(), issue.sourceRowId(), issue.code(), issue.field(), issue.message());
        }
        long validCustomers = outcome.customers().stream().filter(ValidationEngine.ValidatedCustomer::valid).count();
        long validOrders = outcome.orders().stream().filter(ValidationEngine.ValidatedOrder::valid).count();
        String counts;
        try {
            counts = mapper.writeValueAsString(Map.of(
                    "customers", Map.of("raw", customerRows.size(), "valid", validCustomers, "invalid", customerRows.size() - validCustomers),
                    "orders", Map.of("raw", orderRows.size(), "valid", validOrders, "invalid", orderRows.size() - validOrders)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize source counts", e);
        }
        jdbc.update("UPDATE control.validation_snapshots SET status = 'COMPLETED', source_counts_json = ?::jsonb, validated_at = now() WHERE id = ?",
                counts, snapshotId);
    }

    private UUID fileId(UUID bundleId, String kind) {
        return jdbc.query("SELECT id FROM control.source_files WHERE bundle_id = ? AND kind = ?",
                (rs, n) -> rs.getObject("id", UUID.class), bundleId, kind).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Source file " + kind));
    }

    private List<ValidationEngine.RawRow> loadRows(String table, UUID fileId) {
        return jdbc.query("SELECT id, data_row_number, raw_json::text FROM " + table + " WHERE file_id = ? ORDER BY data_row_number",
                (rs, n) -> {
                    Map<String, String> values;
                    try {
                        values = mapper.readValue(rs.getString("raw_json"), new TypeReference<>() {});
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                    return new ValidationEngine.RawRow(rs.getObject("id", UUID.class), rs.getInt("data_row_number"), values);
                }, fileId);
    }
}
