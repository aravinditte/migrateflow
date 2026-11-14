package com.migrateflow.showcase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * The showcase scenario registry (spec 5.1): pointers to real artifacts
 * plus bootstrap progress. Writes happen ONLY through the token-guarded
 * internal bootstrap endpoint; the public API never mutates this table.
 * A row is never evidence by itself - ShowcaseQueryService re-reads the
 * domain tables and re-verifies lineage on every public read.
 */
@Service
public class ShowcaseRegistryService {

    public record Scenario(
            UUID id, String slug,
            UUID projectId, UUID bundleId, UUID mappingRevisionId, UUID ruleSetRevisionId,
            UUID snapshotId, UUID manifestId, UUID rehearsalRunId, UUID approvalId, UUID cutoverRunId,
            String setupState, String setupStep, String bootstrapVersion, String failureMessage,
            String createdAt, String verifiedAt) {}

    private final JdbcTemplate jdbc;

    public ShowcaseRegistryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Scenario> findBySlug(String slug) {
        return jdbc.query(
                "SELECT id, slug, project_id, bundle_id, mapping_revision_id, rule_set_revision_id, snapshot_id, "
                        + "manifest_id, rehearsal_run_id, approval_id, cutover_run_id, setup_state, setup_step, "
                        + "bootstrap_version, failure_message, created_at, verified_at "
                        + "FROM control.showcase_scenarios WHERE slug = ?",
                (rs, n) -> new Scenario(
                        rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getObject("project_id", UUID.class), rs.getObject("bundle_id", UUID.class),
                        rs.getObject("mapping_revision_id", UUID.class), rs.getObject("rule_set_revision_id", UUID.class),
                        rs.getObject("snapshot_id", UUID.class), rs.getObject("manifest_id", UUID.class),
                        rs.getObject("rehearsal_run_id", UUID.class), rs.getObject("approval_id", UUID.class),
                        rs.getObject("cutover_run_id", UUID.class), rs.getString("setup_state"),
                        rs.getString("setup_step"), rs.getString("bootstrap_version"), rs.getString("failure_message"),
                        ts(rs.getTimestamp("created_at")), ts(rs.getTimestamp("verified_at"))),
                slug).stream().findFirst();
    }

    /** Upsert keyed by slug; verified_at is stamped only on a READY write. */
    public Scenario upsert(Scenario s) {
        UUID id = s.id() != null ? s.id() : UUID.randomUUID();
        jdbc.update(
                "INSERT INTO control.showcase_scenarios (id, slug, project_id, bundle_id, mapping_revision_id, "
                        + "rule_set_revision_id, snapshot_id, manifest_id, rehearsal_run_id, approval_id, cutover_run_id, "
                        + "setup_state, setup_step, bootstrap_version, failure_message, verified_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, CASE WHEN ? = 'READY' THEN now() ELSE NULL END) "
                        + "ON CONFLICT (slug) DO UPDATE SET project_id = EXCLUDED.project_id, bundle_id = EXCLUDED.bundle_id, "
                        + "mapping_revision_id = EXCLUDED.mapping_revision_id, rule_set_revision_id = EXCLUDED.rule_set_revision_id, "
                        + "snapshot_id = EXCLUDED.snapshot_id, manifest_id = EXCLUDED.manifest_id, "
                        + "rehearsal_run_id = EXCLUDED.rehearsal_run_id, approval_id = EXCLUDED.approval_id, "
                        + "cutover_run_id = EXCLUDED.cutover_run_id, setup_state = EXCLUDED.setup_state, "
                        + "setup_step = EXCLUDED.setup_step, bootstrap_version = EXCLUDED.bootstrap_version, "
                        + "failure_message = EXCLUDED.failure_message, "
                        + "verified_at = CASE WHEN EXCLUDED.setup_state = 'READY' THEN now() ELSE control.showcase_scenarios.verified_at END, "
                        + "updated_at = now()",
                id, s.slug(), s.projectId(), s.bundleId(), s.mappingRevisionId(), s.ruleSetRevisionId(),
                s.snapshotId(), s.manifestId(), s.rehearsalRunId(), s.approvalId(), s.cutoverRunId(),
                s.setupState(), s.setupStep(), s.bootstrapVersion(), s.failureMessage(), s.setupState());
        return findBySlug(s.slug()).orElseThrow();
    }

    static String ts(Timestamp t) {
        return t == null ? null : String.valueOf(t.toInstant());
    }
}
