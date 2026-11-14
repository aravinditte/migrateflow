package com.migrateflow.validation;

import com.migrateflow.audit.AuditService;
import com.migrateflow.common.ApiException;
import com.migrateflow.common.PageRequestUtil;
import com.migrateflow.identity.AccessControl;
import com.migrateflow.identity.AppUser;
import com.migrateflow.identity.SecurityConfig;
import com.migrateflow.identity.UserRepository;
import com.migrateflow.mapping.MappingDefinition;
import com.migrateflow.mapping.MappingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class PlanController {
    private final MappingService mappings;
    private final ValidationService validation;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AccessControl access;
    private final AuditService audit;

    public PlanController(MappingService mappings, ValidationService validation, JdbcTemplate jdbc,
                          UserRepository users, AccessControl access, AuditService audit) {
        this.mappings = mappings;
        this.validation = validation;
        this.jdbc = jdbc;
        this.users = users;
        this.access = access;
        this.audit = audit;
    }

    public record StartValidationRequest(@NotNull UUID bundleId, @NotNull UUID mappingRevisionId, UUID ruleSetRevisionId) {}
    public record ExclusionRequest(@NotBlank String dataset, @NotNull UUID sourceRowId,
                                   @NotBlank String decision, @NotBlank String reason) {}

    @PostMapping("/projects/{projectId}/mapping-revisions")
    public ResponseEntity<Map<String, Object>> publishMapping(@PathVariable UUID projectId,
                                                              @RequestBody(required = false) MappingDefinition definition,
                                                              Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireProjectRole(actor, projectId, "ENGINEER");
        access.requireActiveProject(projectId);
        MappingDefinition def = definition == null
                ? com.migrateflow.mapping.DefaultMappings.create() : definition;
        MappingService.PublishedRevision published = mappings.publish(actor, projectId, def);
        UUID ruleSetId = mappings.ensureDefaultRuleSet(projectId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "revisionId", published.revisionId().toString(),
                "revisionNo", published.revisionNo(),
                "definitionHash", published.definitionHash(),
                "ruleSetRevisionId", ruleSetId.toString(),
                "definition", published.canonicalJson()));
    }

    @GetMapping("/projects/{projectId}/rule-set-revisions/current")
    public Map<String, Object> currentRuleSet(@PathVariable UUID projectId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, projectId);
        return jdbc.query("SELECT id, revision_no, rule_hash, rule_json::text AS rules, created_at FROM control.rule_set_revisions "
                        + "WHERE project_id = ? ORDER BY revision_no DESC LIMIT 1",
                (rs, n) -> Map.<String, Object>of(
                        "ruleSetRevisionId", rs.getObject("id", UUID.class).toString(),
                        "revisionNo", rs.getInt("revision_no"),
                        "ruleHash", rs.getString("rule_hash").trim(),
                        "rules", rs.getString("rules"),
                        "createdAt", String.valueOf(rs.getTimestamp("created_at").toInstant())),
                projectId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Rule set revision"));
    }

    @GetMapping("/mapping-revisions/{revisionId}")
    public Map<String, Object> getMapping(@PathVariable UUID revisionId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfMappingRevision(revisionId));
        return jdbc.query("SELECT id, revision_no, definition_hash, definition_json::text AS def, created_at FROM control.mapping_revisions WHERE id = ?",
                (rs, n) -> Map.<String, Object>of(
                        "revisionId", rs.getObject("id", UUID.class).toString(),
                        "revisionNo", rs.getInt("revision_no"),
                        "definitionHash", rs.getString("definition_hash"),
                        "definition", rs.getString("def"),
                        "createdAt", String.valueOf(rs.getTimestamp("created_at").toInstant())),
                revisionId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Mapping revision"));
    }

    @PostMapping("/projects/{projectId}/validation-snapshots")
    public ResponseEntity<Map<String, Object>> startValidation(@PathVariable UUID projectId,
                                                               @Valid @RequestBody StartValidationRequest body,
                                                               Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireProjectRole(actor, projectId, "ENGINEER");
        access.requireActiveProject(projectId);
        UUID snapshotId = validation.startValidation(actor, projectId, body.bundleId(), body.mappingRevisionId(), body.ruleSetRevisionId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "snapshotId", snapshotId.toString(), "status", "QUEUED"));
    }

    @GetMapping("/validation-snapshots/{snapshotId}")
    public Map<String, Object> getSnapshot(@PathVariable UUID snapshotId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfSnapshot(snapshotId));
        return jdbc.query("SELECT id, project_id, bundle_id, mapping_revision_id, rule_set_revision_id, status, source_counts_json::text AS counts, validated_at FROM control.validation_snapshots WHERE id = ?",
                (rs, n) -> Map.<String, Object>of(
                        "snapshotId", rs.getObject("id", UUID.class).toString(),
                        "projectId", rs.getObject("project_id", UUID.class).toString(),
                        "bundleId", rs.getObject("bundle_id", UUID.class).toString(),
                        "mappingRevisionId", rs.getObject("mapping_revision_id", UUID.class).toString(),
                        "ruleSetRevisionId", rs.getObject("rule_set_revision_id", UUID.class).toString(),
                        "status", rs.getString("status"),
                        "counts", rs.getString("counts") == null ? "" : rs.getString("counts"),
                        "validatedAt", rs.getTimestamp("validated_at") == null ? "" : String.valueOf(rs.getTimestamp("validated_at").toInstant())),
                snapshotId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Validation snapshot"));
    }

    @GetMapping("/validation-snapshots/{snapshotId}/issues")
    public Map<String, Object> issues(@PathVariable UUID snapshotId,
                                      @RequestParam(required = false) String kind,
                                      @RequestParam(required = false) String code,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfSnapshot(snapshotId));
        StringBuilder sql = new StringBuilder(
                "SELECT i.*, r.data_row_number FROM control.validation_issues i "
                        + "LEFT JOIN stage.customer_input_rows r ON r.id = i.source_row_id AND i.kind = 'CUSTOMERS' "
                        + "WHERE i.snapshot_id = ?");
        // order rows join handled by union of both staging tables for the row number
        sql = new StringBuilder(
                "SELECT i.id, i.kind, i.source_row_id, i.code, i.severity, i.field, i.message, "
                        + "COALESCE(cr.data_row_number, orr.data_row_number) AS data_row_number "
                        + "FROM control.validation_issues i "
                        + "LEFT JOIN stage.customer_input_rows cr ON cr.id = i.source_row_id "
                        + "LEFT JOIN stage.order_input_rows orr ON orr.id = i.source_row_id "
                        + "WHERE i.snapshot_id = ?");
        int validPage = PageRequestUtil.validatePage(page);
        int validSize = PageRequestUtil.validateSize(size);
        int offset = PageRequestUtil.safeOffset(validPage, validSize);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(snapshotId);
        if (kind != null) { sql.append(" AND i.kind = ?"); args.add(kind); }
        if (code != null) { sql.append(" AND i.code = ?"); args.add(code); }
        sql.append(" ORDER BY i.kind, data_row_number, i.code LIMIT ? OFFSET ?");
        args.add(validSize);
        args.add(offset);
        List<Map<String, Object>> rows = jdbc.query(sql.toString(),
                (rs, n) -> Map.<String, Object>of(
                        "issueId", rs.getObject("id", UUID.class).toString(),
                        "kind", rs.getString("kind"),
                        "sourceRowId", rs.getObject("source_row_id", UUID.class).toString(),
                        "dataRowNumber", rs.getObject("data_row_number") == null ? 0 : rs.getInt("data_row_number"),
                        "code", rs.getString("code"),
                        "severity", rs.getString("severity"),
                        "field", rs.getString("field") == null ? "" : rs.getString("field"),
                        "message", rs.getString("message")),
                args.toArray());
        return Map.of("content", rows, "page", validPage, "size", validSize);
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/validation-snapshots/{snapshotId}/exclusions")
    public ResponseEntity<Map<String, Object>> decideExclusion(@PathVariable UUID snapshotId,
                                                               @Valid @RequestBody ExclusionRequest body,
                                                               Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        UUID projectId = access.projectOfSnapshot(snapshotId);
        access.requireProjectRole(actor, projectId, "REVIEWER");
        access.requireActiveProject(projectId);
        // MF-008: Take row lock on validation snapshot to serialize decisions with manifest freeze
        jdbc.queryForObject("SELECT id FROM control.validation_snapshots WHERE id = ? FOR UPDATE", UUID.class, snapshotId);
        if (!List.of("CUSTOMERS", "ORDERS").contains(body.dataset())) {
            throw ApiException.badRequest("INVALID_DATASET", "dataset must be CUSTOMERS or ORDERS");
        }
        if (!List.of("EXCLUDE", "KEEP").contains(body.decision())) {
            throw ApiException.badRequest("INVALID_DECISION", "decision must be EXCLUDE or KEEP");
        }
        String snapshotStatus = jdbc.query("SELECT status FROM control.validation_snapshots WHERE id = ?",
                (rs, n) -> rs.getString("status"), snapshotId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Validation snapshot"));
        if (!"COMPLETED".equals(snapshotStatus)) {
            throw ApiException.conflict("SNAPSHOT_NOT_COMPLETED", "Exclusions can only be decided on a completed snapshot");
        }
        // Once a manifest is frozen over this snapshot, its exclusion set is
        // part of the frozen plan (bound by hash). Late decisions would
        // diverge from every downstream run, so the snapshot is locked;
        // changing the plan means validating a new snapshot.
        Long frozen = jdbc.queryForObject(
                "SELECT count(*) FROM control.manifests WHERE snapshot_id = ? AND status = 'FROZEN'",
                Long.class, snapshotId);
        if (frozen != null && frozen > 0) {
            throw ApiException.conflict("MANIFEST_ALREADY_FROZEN",
                    "This snapshot is bound to a frozen manifest; exclusion decisions are locked");
        }
        // The row must be an invalid row of this snapshot: exclusions approve
        // the omission of invalid rows, they never waive validity (spec 4.4).
        String validatedTable = "CUSTOMERS".equals(body.dataset())
                ? "stage.customer_validated_rows" : "stage.order_validated_rows";
        Boolean isValid = jdbc.query("SELECT is_valid FROM " + validatedTable + " WHERE snapshot_id = ? AND input_row_id = ?",
                (rs, n) -> rs.getBoolean("is_valid"), snapshotId, body.sourceRowId()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Validated row"));
        if (Boolean.TRUE.equals(isValid) && "EXCLUDE".equals(body.decision())) {
            throw ApiException.unprocessable("ROW_NOT_INVALID",
                    "A valid row cannot be excluded; exclusions only approve omitting invalid rows");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO control.exclusion_decisions (id, snapshot_id, kind, source_row_id, decision, reason, reviewer_id) "
                        + "VALUES (?,?,?,?,?,?,?)",
                id, snapshotId, body.dataset(), body.sourceRowId(), body.decision(), body.reason(), actor.id());
        audit.record(projectId, actor.id(), "EXCLUSION_DECIDED", "validation_snapshot", snapshotId,
                Map.of("dataset", body.dataset(), "sourceRowId", body.sourceRowId().toString(), "decision", body.decision()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "decisionId", id.toString(), "snapshotId", snapshotId.toString(),
                "dataset", body.dataset(), "sourceRowId", body.sourceRowId().toString(),
                "decision", body.decision(), "reason", body.reason()));
    }

    @GetMapping("/validation-snapshots/{snapshotId}/exclusions")
    public Map<String, Object> listExclusions(@PathVariable UUID snapshotId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfSnapshot(snapshotId));
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM control.exclusion_decisions WHERE snapshot_id = ? ORDER BY decided_at",
                (rs, n) -> Map.<String, Object>of(
                        "decisionId", rs.getObject("id", UUID.class).toString(),
                        "kind", rs.getString("kind"),
                        "sourceRowId", rs.getObject("source_row_id", UUID.class).toString(),
                        "decision", rs.getString("decision"),
                        "reason", rs.getString("reason"),
                        "reviewerId", rs.getObject("reviewer_id", UUID.class).toString(),
                        "decidedAt", String.valueOf(rs.getTimestamp("decided_at").toInstant())),
                snapshotId);
        return Map.of("content", rows);
    }
}
