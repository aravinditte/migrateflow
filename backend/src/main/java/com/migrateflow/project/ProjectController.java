package com.migrateflow.project;

import com.migrateflow.audit.AuditService;
import com.migrateflow.common.ApiException;
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
        List<Map<String, Object>> rows;
        if (actor.isAdmin()) {
            rows = jdbc.query("SELECT * FROM control.projects ORDER BY created_at DESC LIMIT ? OFFSET ?",
                    this::projectRow, size, page * size);
        } else {
            rows = jdbc.query("SELECT p.* FROM control.projects p JOIN control.project_memberships m "
                            + "ON m.project_id = p.id AND m.user_id = ? AND m.active = TRUE "
                            + "ORDER BY p.created_at DESC LIMIT ? OFFSET ?",
                    this::projectRow, actor.id(), size, page * size);
        }
        return Map.of("content", rows, "page", page, "size", size);
    }

    @GetMapping("/{projectId}")
    public Map<String, Object> get(@PathVariable UUID projectId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, projectId);
        return jdbc.query("SELECT * FROM control.projects WHERE id = ?", this::projectRow, projectId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Project"));
    }

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
                projectId, size, page * size);
        return Map.of("content", rows, "page", page, "size", size);
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
