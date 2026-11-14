package com.migrateflow.reconciliation;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.migrateflow.common.CanonicalHash;
import com.migrateflow.execution.AcceptedRows;
import com.migrateflow.validation.ValidationEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Builds the reconciliation report for a finished run (spec 8.4): compares
 * the manifest's accepted snapshot rows against the rows actually written in
 * the run's scope (rehearsal tables scoped by run, or the final target rows
 * carrying this run's provenance). Persists an immutable report with an
 * evidence digest.
 */
@Service
public class ReconciliationService {
    private final JdbcTemplate jdbc;
    private final AcceptedRows acceptedRows;
    private final ObjectMapper canonicalMapper = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    public ReconciliationService(JdbcTemplate jdbc, AcceptedRows acceptedRows) {
        this.jdbc = jdbc;
        this.acceptedRows = acceptedRows;
    }

    public record Report(UUID reportId, String status, String summaryJson, String evidenceHash) {}

    public Report reconcile(UUID runId) {
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT kind, manifest_id FROM control.runs WHERE id = ?", runId);
        String kind = (String) run.get("kind");
        UUID manifestId = (UUID) run.get("manifest_id");
        UUID snapshotId = jdbc.queryForObject(
                "SELECT snapshot_id FROM control.manifests WHERE id = ?", UUID.class, manifestId);
        boolean rehearsal = "REHEARSAL".equals(kind);

        List<ReconciliationEngine.CustomerRec> expectedCustomers = acceptedRows.customers(manifestId, snapshotId).stream()
                .map(c -> new ReconciliationEngine.CustomerRec(c.externalCustomerId(), c.normalizedHash())).toList();
        List<ReconciliationEngine.OrderRec> expectedOrders = acceptedRows.orders(manifestId, snapshotId).stream()
                .map(o -> new ReconciliationEngine.OrderRec(o.externalOrderId(), o.externalCustomerId(), o.amountYen(), o.normalizedHash())).toList();

        List<String> provenanceDiffs = new ArrayList<>();
        List<ReconciliationEngine.CustomerRec> actualCustomers;
        List<ReconciliationEngine.OrderRec> actualOrders;
        if (rehearsal) {
            actualCustomers = jdbc.query(
                    "SELECT external_customer_id, full_name, email, joined_on, origin_run_id, origin_manifest_id "
                            + "FROM rehearsal.customers WHERE rehearsal_run_id = ?",
                    (rs, n) -> {
                        checkProvenance(provenanceDiffs, rs.getObject("origin_run_id", UUID.class),
                                rs.getObject("origin_manifest_id", UUID.class), runId, manifestId, "rehearsal customer");
                        return new ReconciliationEngine.CustomerRec(rs.getString("external_customer_id"),
                                CanonicalHash.hashFields(ValidationEngine.customerHashFields(
                                        rs.getString("external_customer_id"), rs.getString("full_name"), rs.getString("email"),
                                        rs.getDate("joined_on") == null ? null : rs.getDate("joined_on").toLocalDate())));
                    }, runId);
            actualOrders = jdbc.query(
                    "SELECT o.external_order_id, c.external_customer_id AS cust_ext, o.order_date, o.amount_yen, o.status, "
                            + "o.origin_run_id, o.origin_manifest_id "
                            + "FROM rehearsal.orders o LEFT JOIN rehearsal.customers c "
                            + "ON c.rehearsal_run_id = o.rehearsal_run_id AND c.id = o.customer_id WHERE o.rehearsal_run_id = ?",
                    (rs, n) -> {
                        checkProvenance(provenanceDiffs, rs.getObject("origin_run_id", UUID.class),
                                rs.getObject("origin_manifest_id", UUID.class), runId, manifestId, "rehearsal order");
                        return orderRec(rs.getString("external_order_id"), rs.getString("cust_ext"),
                                rs.getDate("order_date").toLocalDate(), rs.getLong("amount_yen"), rs.getString("status"));
                    }, runId);
        } else {
            actualCustomers = jdbc.query(
                    "SELECT external_customer_id, full_name, email, joined_on, origin_run_id, origin_manifest_id "
                            + "FROM target.customers WHERE origin_run_id = ?",
                    (rs, n) -> {
                        checkProvenance(provenanceDiffs, rs.getObject("origin_run_id", UUID.class),
                                rs.getObject("origin_manifest_id", UUID.class), runId, manifestId, "target customer");
                        return new ReconciliationEngine.CustomerRec(rs.getString("external_customer_id"),
                                CanonicalHash.hashFields(ValidationEngine.customerHashFields(
                                        rs.getString("external_customer_id"), rs.getString("full_name"), rs.getString("email"),
                                        rs.getDate("joined_on") == null ? null : rs.getDate("joined_on").toLocalDate())));
                    }, runId);
            actualOrders = jdbc.query(
                    "SELECT o.external_order_id, c.external_customer_id AS cust_ext, o.order_date, o.amount_yen, o.status, "
                            + "o.origin_run_id, o.origin_manifest_id "
                            + "FROM target.orders o LEFT JOIN target.customers c ON c.id = o.customer_id WHERE o.origin_run_id = ?",
                    (rs, n) -> {
                        checkProvenance(provenanceDiffs, rs.getObject("origin_run_id", UUID.class),
                                rs.getObject("origin_manifest_id", UUID.class), runId, manifestId, "target order");
                        return orderRec(rs.getString("external_order_id"), rs.getString("cust_ext"),
                                rs.getDate("order_date").toLocalDate(), rs.getLong("amount_yen"), rs.getString("status"));
                    }, runId);
            // Final target must contain exactly this migration's rows (spec 8.4).
            Long totalCustomers = jdbc.queryForObject("SELECT count(*) FROM target.customers", Long.class);
            Long totalOrders = jdbc.queryForObject("SELECT count(*) FROM target.orders", Long.class);
            if (totalCustomers != null && totalCustomers != actualCustomers.size()) {
                provenanceDiffs.add("final target holds " + totalCustomers + " customers but this run wrote " + actualCustomers.size());
            }
            if (totalOrders != null && totalOrders != actualOrders.size()) {
                provenanceDiffs.add("final target holds " + totalOrders + " orders but this run wrote " + actualOrders.size());
            }
        }

        ReconciliationEngine.Result result = ReconciliationEngine.compare(
                expectedCustomers, actualCustomers, expectedOrders, actualOrders);
        List<String> differences = new ArrayList<>(result.differences());
        differences.addAll(provenanceDiffs);
        boolean pass = differences.isEmpty();

        Integer rawCustomers = jdbc.queryForObject("SELECT count(*) FROM stage.customer_validated_rows WHERE snapshot_id = ?", Integer.class, snapshotId);
        Integer rawOrders = jdbc.queryForObject("SELECT count(*) FROM stage.order_validated_rows WHERE snapshot_id = ?", Integer.class, snapshotId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", runId.toString());
        summary.put("kind", kind);
        summary.put("status", pass ? "PASS" : "FAIL");
        summary.put("customers", Map.of(
                "raw", rawCustomers, "accepted", result.expectedCustomers(),
                "excluded", rawCustomers - result.expectedCustomers(), "written", result.writtenCustomers()));
        summary.put("orders", Map.of(
                "raw", rawOrders, "accepted", result.expectedOrders(),
                "excluded", rawOrders - result.expectedOrders(), "written", result.writtenOrders(),
                "acceptedAmountYen", result.expectedOrderAmount(), "writtenAmountYen", result.writtenOrderAmount()));
        summary.put("differences", differences);
        String summaryJson;
        try {
            summaryJson = canonicalMapper.writeValueAsString(summary);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String evidenceHash = CanonicalHash.sha256Hex(summaryJson);
        UUID reportId = UUID.randomUUID();
        jdbc.update("INSERT INTO control.reconciliation_reports (id, run_id, status, summary_json, evidence_hash) VALUES (?,?,?,?::jsonb,?)",
                reportId, runId, pass ? "PASS" : "FAIL", summaryJson, evidenceHash);
        return new Report(reportId, pass ? "PASS" : "FAIL", summaryJson, evidenceHash);
    }

    private ReconciliationEngine.OrderRec orderRec(String externalId, String custExt, java.time.LocalDate date, long amount, String status) {
        return new ReconciliationEngine.OrderRec(externalId, custExt, amount,
                CanonicalHash.hashFields(ValidationEngine.orderHashFields(externalId, custExt, date, amount, status)));
    }

    private void checkProvenance(List<String> diffs, UUID originRun, UUID originManifest,
                                 UUID runId, UUID manifestId, String what) {
        if (!runId.equals(originRun) || !manifestId.equals(originManifest)) {
            diffs.add(what + " carries provenance of a different run/manifest");
        }
    }
}
