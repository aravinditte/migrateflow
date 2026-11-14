package com.migrateflow.project;

import com.migrateflow.audit.AuditService;
import com.migrateflow.common.ApiException;
import com.migrateflow.common.PageRequestUtil;
import com.migrateflow.identity.AccessControl;
import com.migrateflow.identity.AppUser;
import com.migrateflow.identity.SecurityConfig;
import com.migrateflow.identity.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AccessControl access;
    private final AuditService audit;

    public ProjectController(JdbcTemplate jdbc, UserRepository users, AccessControl access, AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.access = access;
        this.audit = audit;
    }

    public record CreateProjectRequest(@NotBlank String name, String description, String windowNote) {}

    @org.springframework.transaction.annotation.Transactional
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateProjectRequest body, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireRole(actor, "ENGINEER");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO control.projects (id, name, description, window_note, created_by) VALUES (?,?,?,?,?)",
                id, body.name(), body.description(), body.windowNote(), actor.id());
        jdbc.update("INSERT INTO control.project_memberships (id, project_id, user_id, active) VALUES (?,?,?,TRUE)",
                UUID.randomUUID(), id, actor.id());
        audit.record(id, actor.id(), "PROJECT_CREATED", "project", id, Map.of("name", body.name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(get(id, auth));
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        int validPage = PageRequestUtil.validatePage(page);
        int validSize = PageRequestUtil.validateSize(size);
        int offset = PageRequestUtil.safeOffset(validPage, validSize);
        List<Map<String, Object>> rows;
        if (actor.isAdmin()) {
            rows = jdbc.query("SELECT * FROM control.projects ORDER BY created_at DESC LIMIT ? OFFSET ?",
                    this::projectRow, validSize, offset);
        } else {
            rows = jdbc.query("SELECT p.* FROM control.projects p JOIN control.project_memberships m "
                            + "ON m.project_id = p.id AND m.user_id = ? AND m.active = TRUE "
                            + "ORDER BY p.created_at DESC LIMIT ? OFFSET ?",
                    this::projectRow, actor.id(), validSize, offset);
        }
        return Map.of("content", rows, "page", validPage, "size", validSize);
    }

    @GetMapping("/{projectId}")
    public Map<String, Object> get(@PathVariable UUID projectId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, projectId);
        return jdbc.query("SELECT * FROM control.projects WHERE id = ?", this::projectRow, projectId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Project"));
    }

    /**
     * Workflow restoration (spec 11): returns the latest workflow stage IDs
     * for this project derived from a coherent plan lineage so the frontend
     * workspace state can be restored on page refresh or project reopening.
     */
    @GetMapping("/{projectId}/workflow")
    public Map<String, Object> getWorkflow(@PathVariable UUID projectId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, projectId);

        // 1. Look for the active FROZEN manifest for this project
        List<Map<String, Object>> frozenManifests = jdbc.queryForList(
                "SELECT id, bundle_id, mapping_revision_id, rule_set_revision_id, snapshot_id, manifest_hash "
                        + "FROM control.manifests WHERE project_id = ? AND status = 'FROZEN' ORDER BY created_at DESC LIMIT 1",
                projectId);

        UUID bundleId;
        UUID mappingRevisionId;
        UUID ruleSetRevisionId;
        UUID snapshotId;
        UUID manifestId;
        String manifestHash;
        UUID rehearsalRunId = null;
        UUID approvalId = null;
        UUID cutoverRunId = null;

        if (!frozenManifests.isEmpty()) {
            Map<String, Object> m = frozenManifests.get(0);
            manifestId = (UUID) m.get("id");
            manifestHash = (String) m.get("manifest_hash");
            bundleId = (UUID) m.get("bundle_id");
            mappingRevisionId = (UUID) m.get("mapping_revision_id");
            ruleSetRevisionId = (UUID) m.get("rule_set_revision_id");
            snapshotId = (UUID) m.get("snapshot_id");

            rehearsalRunId = querySingleId(
                    "SELECT id FROM control.runs WHERE manifest_id = ? AND kind = 'REHEARSAL' ORDER BY created_at DESC LIMIT 1", manifestId);
            approvalId = querySingleId(
                    "SELECT id FROM control.approvals WHERE manifest_id = ? AND decision = 'APPROVED' ORDER BY signed_at DESC LIMIT 1", manifestId);
            cutoverRunId = querySingleId(
                    "SELECT id FROM control.runs WHERE manifest_id = ? AND kind = 'CUTOVER' ORDER BY created_at DESC LIMIT 1", manifestId);
        } else {
            manifestId = null;
            manifestHash = "";
            List<Map<String, Object>> latestSnapshots = jdbc.queryForList(
                    "SELECT id, bundle_id, mapping_revision_id, rule_set_revision_id FROM control.validation_snapshots "
                            + "WHERE project_id = ? ORDER BY (CASE WHEN status = 'COMPLETED' THEN 1 ELSE 2 END), created_at DESC LIMIT 1",
                    projectId);
            if (!latestSnapshots.isEmpty()) {
                Map<String, Object> s = latestSnapshots.get(0);
                snapshotId = (UUID) s.get("id");
                bundleId = (UUID) s.get("bundle_id");
                mappingRevisionId = (UUID) s.get("mapping_revision_id");
                ruleSetRevisionId = (UUID) s.get("rule_set_revision_id");
            } else {
                snapshotId = null;
                bundleId = querySingleId("SELECT id FROM control.dataset_bundles WHERE project_id = ? ORDER BY created_at DESC LIMIT 1", projectId);
                mappingRevisionId = querySingleId("SELECT id FROM control.mapping_revisions WHERE project_id = ? ORDER BY revision_no DESC LIMIT 1", projectId);
                ruleSetRevisionId = querySingleId("SELECT id FROM control.rule_set_revisions WHERE project_id = ? ORDER BY revision_no DESC LIMIT 1", projectId);
            }
        }

        Map<String, Object> flow = new java.util.LinkedHashMap<>();
        flow.put("bundleId", bundleId == null ? "" : bundleId.toString());
        flow.put("mappingRevisionId", mappingRevisionId == null ? "" : mappingRevisionId.toString());
        flow.put("ruleSetRevisionId", ruleSetRevisionId == null ? "" : ruleSetRevisionId.toString());
        flow.put("snapshotId", snapshotId == null ? "" : snapshotId.toString());
        flow.put("manifestId", manifestId == null ? "" : manifestId.toString());
        flow.put("manifestHash", manifestHash == null ? "" : manifestHash);
        flow.put("rehearsalRunId", rehearsalRunId == null ? "" : rehearsalRunId.toString());
        flow.put("approvalId", approvalId == null ? "" : approvalId.toString());
        flow.put("cutoverRunId", cutoverRunId == null ? "" : cutoverRunId.toString());
        return flow;
    }

    private UUID querySingleId(String sql, Object... args) {
        List<UUID> list = jdbc.query(sql, (rs, n) -> rs.getObject(1, UUID.class), args);
        return list.isEmpty() ? null : list.get(0);
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/{projectId}/archive")
    public Map<String, Object> archive(@PathVariable UUID projectId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireProjectRole(actor, projectId, "ENGINEER");
        Long active = jdbc.queryForObject("SELECT count(*) FROM control.runs WHERE project_id = ? AND status IN ('QUEUED','RUNNING')",
                Long.class, projectId);
        if (active != null && active > 0) {
            throw ApiException.conflict("PROJECT_HAS_ACTIVE_WORK", "Project has an active run and cannot be archived");
        }
        jdbc.update("UPDATE control.projects SET status = 'ARCHIVED' WHERE id = ?", projectId);
        audit.record(projectId, actor.id(), "PROJECT_ARCHIVED", "project", projectId, Map.of());
        return get(projectId, auth);
    }

    @GetMapping("/{projectId}/audit-events")
    public Map<String, Object> auditEvents(@PathVariable UUID projectId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, projectId);
        int validPage = PageRequestUtil.validatePage(page);
        int validSize = PageRequestUtil.validateSize(size);
        int offset = PageRequestUtil.safeOffset(validPage, validSize);
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT a.*, u.email AS actor_email FROM control.audit_events a LEFT JOIN control.users u ON u.id = a.actor_id "
                        + "WHERE a.project_id = ? ORDER BY a.occurred_at DESC LIMIT ? OFFSET ?",
                (rs, n) -> Map.<String, Object>of(
                        "id", rs.getObject("id", UUID.class).toString(),
                        "actorEmail", rs.getString("actor_email") == null ? "" : rs.getString("actor_email"),
                        "action", rs.getString("action"),
                        "entityType", rs.getString("entity_type"),
                        "entityId", rs.getObject("entity_id", UUID.class) == null ? "" : rs.getObject("entity_id", UUID.class).toString(),
                        "metadata", rs.getString("metadata_json") == null ? "" : rs.getString("metadata_json"),
                        "occurredAt", String.valueOf(rs.getTimestamp("occurred_at").toInstant())),
                projectId, validSize, offset);
        return Map.of("content", rows, "page", validPage, "size", validSize);
    }

    private Map<String, Object> projectRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return Map.of(
                "id", rs.getObject("id", UUID.class).toString(),
                "name", rs.getString("name"),
                "description", rs.getString("description") == null ? "" : rs.getString("description"),
                "windowNote", rs.getString("window_note") == null ? "" : rs.getString("window_note"),
                "status", rs.getString("status"),
                "createdBy", rs.getObject("created_by", UUID.class).toString(),
                "createdAt", String.valueOf(rs.getTimestamp("created_at").toInstant()));
    }
}
