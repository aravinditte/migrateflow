package com.migrateflow.approval;

import com.migrateflow.audit.AuditService;
import com.migrateflow.common.ApiException;
import com.migrateflow.identity.AccessControl;
import com.migrateflow.identity.AppUser;
import com.migrateflow.identity.SecurityConfig;
import com.migrateflow.identity.UserRepository;
import com.migrateflow.uat.UatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Signed approval binding the manifest, the verified rehearsal, the PASS
 * reconciliation report and the exact UAT evidence digest (spec 6).
 * Separation of duties is enforced here and again at cutover launch.
 */
@RestController
@RequestMapping("/api/v1")
public class ApprovalController {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AccessControl access;
    private final AuditService audit;
    private final UatService uat;

    public ApprovalController(JdbcTemplate jdbc, UserRepository users, AccessControl access,
                              AuditService audit, UatService uat) {
        this.jdbc = jdbc;
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.uat = uat;
    }

    public record ApprovalRequest(@NotBlank String decision, @NotNull UUID rehearsalRunId,
                                  @NotNull UUID reconciliationReportId, @NotBlank String uatEvidenceHash,
                                  String comment) {}

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/manifests/{manifestId}/approvals")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable UUID manifestId,
                                                       @Valid @RequestBody ApprovalRequest body, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        UUID projectId = access.projectOfManifest(manifestId);
        access.requireProjectRole(actor, projectId, "REVIEWER");
        access.requireActiveProject(projectId);
        if (!java.util.List.of("APPROVE", "REJECT").contains(body.decision())) {
            throw ApiException.badRequest("INVALID_DECISION", "decision must be APPROVE or REJECT");
        }
        Map<String, Object> manifest = jdbc.queryForMap(
                "SELECT status, created_by FROM control.manifests WHERE id = ?", manifestId);
        if (!"FROZEN".equals(manifest.get("status"))) {
            throw ApiException.conflict("PLAN_CHANGED", "Manifest is superseded and can no longer be approved");
        }
        if (actor.id().equals(manifest.get("created_by"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                    "The engineer who froze this manifest cannot approve it");
        }
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT manifest_id, kind, status FROM control.runs WHERE id = ?", body.rehearsalRunId());
        if (!manifestId.equals(run.get("manifest_id")) || !"REHEARSAL".equals(run.get("kind"))) {
            throw ApiException.unprocessable("INVALID_REFERENCE", "Rehearsal does not belong to this manifest");
        }
        if ("APPROVE".equals(body.decision())) {
            if (!"VERIFIED".equals(run.get("status"))) {
                throw ApiException.unprocessable("INVALID_REFERENCE", "Rehearsal is not verified");
            }
            Map<String, Object> report = jdbc.queryForMap(
                    "SELECT run_id, status FROM control.reconciliation_reports WHERE id = ?", body.reconciliationReportId());
            if (!body.rehearsalRunId().equals(report.get("run_id")) || !"PASS".equals(report.get("status"))) {
                throw ApiException.unprocessable("RECONCILIATION_FAILED",
                        "The referenced reconciliation report is not a PASS report for this rehearsal");
            }
            String currentUatHash = uat.uatEvidenceHash(body.rehearsalRunId());
            if (!currentUatHash.equals(body.uatEvidenceHash())) {
                throw ApiException.unprocessable("INVALID_REFERENCE",
                        "UAT evidence has changed since the supplied digest was computed");
            }
            String blocker = uat.approvalBlocker(body.rehearsalRunId());
            if (blocker != null) {
                throw ApiException.unprocessable("UAT_INCOMPLETE", blocker);
            }
        }
        UUID id = UUID.randomUUID();
        String decision = "APPROVE".equals(body.decision()) ? "APPROVED" : "REJECTED";
        jdbc.update("INSERT INTO control.approvals (id, manifest_id, rehearsal_run_id, reconciliation_report_id, "
                        + "uat_evidence_hash, decision, comment, reviewer_id) VALUES (?,?,?,?,?,?,?,?)",
                id, manifestId, body.rehearsalRunId(), body.reconciliationReportId(),
                body.uatEvidenceHash(), decision, body.comment(), actor.id());
        audit.record(projectId, actor.id(), "APPROVAL_" + body.decision(), "manifest", manifestId,
                Map.of("approvalId", id.toString(), "rehearsalRunId", body.rehearsalRunId().toString()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "approvalId", id.toString(), "decision", decision, "manifestId", manifestId.toString(),
                "rehearsalRunId", body.rehearsalRunId().toString()));
    }
}
