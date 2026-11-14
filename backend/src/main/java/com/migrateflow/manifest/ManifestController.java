package com.migrateflow.manifest;

import com.migrateflow.common.ApiException;
import com.migrateflow.common.CanonicalHash;
import com.migrateflow.identity.AccessControl;
import com.migrateflow.identity.AppUser;
import com.migrateflow.identity.SecurityConfig;
import com.migrateflow.identity.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ManifestController {
    private final ManifestService manifests;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AccessControl access;

    public ManifestController(ManifestService manifests, JdbcTemplate jdbc, UserRepository users, AccessControl access) {
        this.manifests = manifests;
        this.jdbc = jdbc;
        this.users = users;
        this.access = access;
    }

    public record FreezeRequest(@NotNull UUID bundleId, @NotNull UUID mappingRevisionId,
                                @NotNull UUID ruleSetRevisionId, @NotNull UUID validationSnapshotId) {}

    @PostMapping("/projects/{projectId}/manifests")
    public ResponseEntity<Map<String, Object>> freeze(@PathVariable UUID projectId,
                                                      @Valid @RequestBody FreezeRequest body, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireProjectRole(actor, projectId, "ENGINEER");
        ManifestService.FreezeResult result = manifests.freeze(actor, projectId, body.bundleId(),
                body.mappingRevisionId(), body.ruleSetRevisionId(), body.validationSnapshotId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "manifestId", result.manifestId().toString(),
                "manifestHash", result.manifestHash(),
                "fingerprint", CanonicalHash.shortFingerprint(result.manifestHash()),
                "exclusionHash", result.exclusionHash(),
                "acceptedCustomers", result.acceptedCustomers(),
                "acceptedOrders", result.acceptedOrders(),
                "status", "FROZEN"));
    }

    @GetMapping("/manifests/{manifestId}")
    public Map<String, Object> get(@PathVariable UUID manifestId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfManifest(manifestId));
        return jdbc.query("SELECT * FROM control.manifests WHERE id = ?",
                (rs, n) -> Map.<String, Object>ofEntries(
                        Map.entry("manifestId", rs.getObject("id", UUID.class).toString()),
                        Map.entry("projectId", rs.getObject("project_id", UUID.class).toString()),
                        Map.entry("bundleId", rs.getObject("bundle_id", UUID.class).toString()),
                        Map.entry("mappingRevisionId", rs.getObject("mapping_revision_id", UUID.class).toString()),
                        Map.entry("ruleSetRevisionId", rs.getObject("rule_set_revision_id", UUID.class).toString()),
                        Map.entry("snapshotId", rs.getObject("snapshot_id", UUID.class).toString()),
                        Map.entry("exclusionHash", rs.getString("exclusion_hash")),
                        Map.entry("targetContractHash", rs.getString("target_contract_hash")),
                        Map.entry("manifestHash", rs.getString("manifest_hash")),
                        Map.entry("fingerprint", CanonicalHash.shortFingerprint(rs.getString("manifest_hash"))),
                        Map.entry("status", rs.getString("status")),
                        Map.entry("createdBy", rs.getObject("created_by", UUID.class).toString()),
                        Map.entry("createdAt", String.valueOf(rs.getTimestamp("created_at").toInstant()))),
                manifestId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Manifest"));
    }
}
