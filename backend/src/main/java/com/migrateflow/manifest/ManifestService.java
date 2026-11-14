package com.migrateflow.manifest;

import com.migrateflow.audit.AuditService;
import com.migrateflow.common.ApiException;
import com.migrateflow.common.CanonicalHash;
import com.migrateflow.identity.AppUser;
import com.migrateflow.mapping.DefaultMappings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Freezes immutable manifests (spec sections 5 and 6). */
@Service
public class ManifestService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ManifestService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record FreezeResult(UUID manifestId, String manifestHash, String exclusionHash, int acceptedCustomers, int acceptedOrders) {}

    @Transactional
    public FreezeResult freeze(AppUser actor, UUID projectId, UUID bundleId, UUID mappingRevisionId,
                               UUID ruleSetRevisionId, UUID snapshotId) {
        // MF-009: Serialize concurrent freezes per project
        jdbc.queryForObject("SELECT id FROM control.projects WHERE id = ? FOR UPDATE", UUID.class, projectId);
        // MF-008: Lock validation snapshot to serialize with concurrent exclusion decisions
        jdbc.queryForObject("SELECT id FROM control.validation_snapshots WHERE id = ? FOR UPDATE", UUID.class, snapshotId);

        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT project_id, bundle_id, mapping_revision_id, rule_set_revision_id, status "
                        + "FROM control.validation_snapshots WHERE id = ?", snapshotId);
        if (!projectId.equals(snapshot.get("project_id")) || !bundleId.equals(snapshot.get("bundle_id"))
                || !mappingRevisionId.equals(snapshot.get("mapping_revision_id"))
                || !ruleSetRevisionId.equals(snapshot.get("rule_set_revision_id"))) {
            throw ApiException.unprocessable("PLAN_CHANGED",
                    "The snapshot does not match the bundle, mapping and rule revisions supplied");
        }
        if (!"COMPLETED".equals(snapshot.get("status"))) {
            throw ApiException.conflict("SNAPSHOT_NOT_COMPLETED", "Only a completed validation snapshot can be frozen");
        }

        // Conservation gate: every invalid row needs a latest EXCLUDE decision.
        List<String> invalidKeys = new ArrayList<>();
        invalidKeys.addAll(jdbc.query("SELECT 'CUSTOMERS:' || input_row_id FROM stage.customer_validated_rows WHERE snapshot_id = ? AND is_valid = FALSE",
                (rs, n) -> rs.getString(1), snapshotId));
        invalidKeys.addAll(jdbc.query("SELECT 'ORDERS:' || input_row_id FROM stage.order_validated_rows WHERE snapshot_id = ? AND is_valid = FALSE",
                (rs, n) -> rs.getString(1), snapshotId));

        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        jdbc.query("SELECT kind, source_row_id, decision, reason, reviewer_id FROM control.exclusion_decisions "
                        + "WHERE snapshot_id = ? ORDER BY decided_at ASC, id ASC",
                (rs, n) -> {
                    String key = rs.getString("kind") + ":" + rs.getObject("source_row_id", UUID.class);
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("decision", rs.getString("decision"));
                    d.put("reason", rs.getString("reason"));
                    d.put("reviewerId", rs.getObject("reviewer_id", UUID.class).toString());
                    latest.put(key, d);
                    return null;
                }, snapshotId);

        List<String> unresolved = new ArrayList<>();
        List<String> exclusionLines = new ArrayList<>();
        for (String key : invalidKeys) {
            Map<String, Object> d = latest.get(key);
            if (d == null || !"EXCLUDE".equals(d.get("decision"))) {
                unresolved.add(key);
            } else {
                exclusionLines.add(key + "|" + d.get("reason") + "|" + d.get("reviewerId"));
            }
        }
        if (!unresolved.isEmpty()) {
            throw ApiException.conflict("EXCLUSIONS_UNRESOLVED",
                    unresolved.size() + " invalid row(s) still lack an approved exclusion decision");
        }
        Collections.sort(exclusionLines);
        String exclusionHash = CanonicalHash.sha256Hex(String.join("\n", exclusionLines));

        String customersSha = fileSha(bundleId, "CUSTOMERS");
        String ordersSha = fileSha(bundleId, "ORDERS");
        String mappingHash = jdbc.queryForObject(
                "SELECT definition_hash FROM control.mapping_revisions WHERE id = ?", String.class, mappingRevisionId);
        String ruleHash = jdbc.queryForObject(
                "SELECT rule_hash FROM control.rule_set_revisions WHERE id = ?", String.class, ruleSetRevisionId);
        String targetContractHash = CanonicalHash.sha256Hex(DefaultMappings.targetContract());

        Map<String, Object> hashFields = new LinkedHashMap<>();
        hashFields.put("bundleId", bundleId.toString());
        hashFields.put("customersSha256", customersSha);
        hashFields.put("ordersSha256", ordersSha);
        hashFields.put("mappingRevisionId", mappingRevisionId.toString());
        hashFields.put("mappingHash", mappingHash);
        hashFields.put("ruleSetRevisionId", ruleSetRevisionId.toString());
        hashFields.put("ruleHash", ruleHash);
        hashFields.put("snapshotId", snapshotId.toString());
        hashFields.put("exclusionHash", exclusionHash);
        hashFields.put("targetContractHash", targetContractHash);
        String manifestHash = CanonicalHash.hashFields(hashFields);

        Integer acceptedCustomers = jdbc.queryForObject(
                "SELECT count(*) FROM stage.customer_validated_rows WHERE snapshot_id = ? AND is_valid = TRUE",
                Integer.class, snapshotId);
        Integer acceptedOrders = jdbc.queryForObject(
                "SELECT count(*) FROM stage.order_validated_rows WHERE snapshot_id = ? AND is_valid = TRUE",
                Integer.class, snapshotId);

        jdbc.update("UPDATE control.manifests SET status = 'SUPERSEDED', superseded_at = now() "
                + "WHERE project_id = ? AND status = 'FROZEN'", projectId);
        UUID manifestId = UUID.randomUUID();
        jdbc.update("INSERT INTO control.manifests (id, project_id, bundle_id, mapping_revision_id, rule_set_revision_id, "
                        + "snapshot_id, exclusion_hash, target_contract_hash, manifest_hash, created_by) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                manifestId, projectId, bundleId, mappingRevisionId, ruleSetRevisionId, snapshotId,
                exclusionHash, targetContractHash, manifestHash, actor.id());
        audit.record(projectId, actor.id(), "MANIFEST_FROZEN", "manifest", manifestId,
                Map.of("manifestHash", manifestHash, "acceptedCustomers", acceptedCustomers, "acceptedOrders", acceptedOrders));
        return new FreezeResult(manifestId, manifestHash, exclusionHash, acceptedCustomers, acceptedOrders);
    }

    private String fileSha(UUID bundleId, String kind) {
        return jdbc.queryForObject("SELECT sha256 FROM control.source_files WHERE bundle_id = ? AND kind = ?",
                String.class, bundleId, kind);
    }
}
