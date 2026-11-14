package com.migrateflow.identity;

import com.migrateflow.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Authorization = global role INTERSECT active project membership,
 * checked on every request in the service layer (spec section 2).
 * Missing membership is reported as 404 to avoid leaking project existence.
 */
@Service
public class AccessControl {
    private final JdbcTemplate jdbc;

    public AccessControl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean isMember(UUID projectId, UUID userId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM control.project_memberships WHERE project_id = ? AND user_id = ? AND active = TRUE",
                Long.class, projectId, userId);
        return n != null && n > 0;
    }

    /** Returns the project row's creator or throws 404 when not a member. */
    public void requireMembership(AppUser user, UUID projectId) {
        if (user.isAdmin()) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM control.projects WHERE id = ?", Long.class, projectId);
            if (n == null || n == 0) throw ApiException.notFound("Project");
            return;
        }
        if (!isMember(projectId, user.id())) throw ApiException.notFound("Project");
    }

    public void requireRole(AppUser user, String... roles) {
        for (String r : roles) {
            if (user.hasRole(r)) return;
        }
        throw ApiException.forbidden("Role " + user.role() + " may not perform this action");
    }

    /** Member + one of the given roles. */
    public void requireProjectRole(AppUser user, UUID projectId, String... roles) {
        requireMembership(user, projectId);
        requireRole(user, roles);
    }

    public UUID projectOfBundle(UUID bundleId) {
        return jdbc.query("SELECT project_id FROM control.dataset_bundles WHERE id = ?",
                (rs, n) -> rs.getObject("project_id", UUID.class), bundleId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Bundle"));
    }

    public UUID projectOfSnapshot(UUID snapshotId) {
        return jdbc.query("SELECT project_id FROM control.validation_snapshots WHERE id = ?",
                (rs, n) -> rs.getObject("project_id", UUID.class), snapshotId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Validation snapshot"));
    }

    public UUID projectOfManifest(UUID manifestId) {
        return jdbc.query("SELECT project_id FROM control.manifests WHERE id = ?",
                (rs, n) -> rs.getObject("project_id", UUID.class), manifestId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Manifest"));
    }

    public UUID projectOfRun(UUID runId) {
        return jdbc.query("SELECT project_id FROM control.runs WHERE id = ?",
                (rs, n) -> rs.getObject("project_id", UUID.class), runId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Run"));
    }

    public UUID projectOfFile(UUID fileId) {
        return jdbc.query("SELECT b.project_id FROM control.source_files f JOIN control.dataset_bundles b ON b.id = f.bundle_id WHERE f.id = ?",
                (rs, n) -> rs.getObject("project_id", UUID.class), fileId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("File"));
    }

    public UUID projectOfMappingRevision(UUID revisionId) {
        return jdbc.query("SELECT project_id FROM control.mapping_revisions WHERE id = ?",
                (rs, n) -> rs.getObject("project_id", UUID.class), revisionId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Mapping revision"));
    }
}
