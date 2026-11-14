package com.migrateflow.uat;

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
@RequestMapping("/api/v1")
public class UatController {
    private final UatService uat;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AccessControl access;
    private final AuditService audit;

    public UatController(UatService uat, JdbcTemplate jdbc, UserRepository users, AccessControl access, AuditService audit) {
        this.uat = uat;
        this.jdbc = jdbc;
        this.users = users;
        this.access = access;
        this.audit = audit;
    }

    public record ExecutionRequest(@NotBlank String caseKey, @NotBlank String result, String actualResult) {}
    public record DefectRequest(String caseKey, @NotBlank String severity, @NotBlank String description) {}
    public record DefectPatchRequest(@NotBlank String status, String resolutionNote) {}

    @GetMapping("/rehearsals/{runId}/uat")
    public Map<String, Object> getUat(@PathVariable UUID runId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfRun(runId));
        List<Map<String, Object>> cases = uat.cases(runId).stream().map(c -> Map.<String, Object>of(
                "caseId", c.caseId().toString(), "caseKey", c.caseKey(), "title", c.title(),
                "expectedResult", c.expectedResult(), "required", c.required(), "latestResult", c.latestResult())).toList();
        return Map.of("cases", cases, "defects", uat.defects(runId),
                "uatEvidenceHash", uat.uatEvidenceHash(runId));
    }

    @PostMapping("/rehearsals/{runId}/uat-executions")
    public ResponseEntity<Map<String, Object>> execute(@PathVariable UUID runId,
                                                       @Valid @RequestBody ExecutionRequest body, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        UUID projectId = access.projectOfRun(runId);
        access.requireProjectRole(actor, projectId, "REVIEWER");
        if (!List.of("PASS", "FAIL", "BLOCKED").contains(body.result())) {
            throw ApiException.badRequest("INVALID_RESULT", "result must be PASS, FAIL or BLOCKED");
        }
        UUID caseId = jdbc.query("SELECT id FROM control.uat_cases WHERE rehearsal_run_id = ? AND case_key = ?",
                (rs, n) -> rs.getObject("id", UUID.class), runId, body.caseKey()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("UAT case"));
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO control.uat_executions (id, case_id, executed_by, result, actual_result) VALUES (?,?,?,?,?)",
                id, caseId, actor.id(), body.result(), body.actualResult());
        audit.record(projectId, actor.id(), "UAT_EXECUTED", "uat_case", caseId,
                Map.of("runId", runId.toString(), "result", body.result()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "executionId", id.toString(), "caseKey", body.caseKey(), "result", body.result()));
    }

    @PostMapping("/rehearsals/{runId}/defects")
    public ResponseEntity<Map<String, Object>> openDefect(@PathVariable UUID runId,
                                                          @Valid @RequestBody DefectRequest body, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        UUID projectId = access.projectOfRun(runId);
        access.requireProjectRole(actor, projectId, "REVIEWER");
        if (!List.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR").contains(body.severity())) {
            throw ApiException.badRequest("INVALID_SEVERITY", "Unknown severity " + body.severity());
        }
        UUID caseId = null;
        if (body.caseKey() != null && !body.caseKey().isBlank()) {
            caseId = jdbc.query("SELECT id FROM control.uat_cases WHERE rehearsal_run_id = ? AND case_key = ?",
                    (rs, n) -> rs.getObject("id", UUID.class), runId, body.caseKey()).stream().findFirst()
                    .orElseThrow(() -> ApiException.notFound("UAT case"));
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO control.defects (id, rehearsal_run_id, uat_case_id, severity, description, created_by) VALUES (?,?,?,?,?,?)",
                id, runId, caseId, body.severity(), body.description(), actor.id());
        audit.record(projectId, actor.id(), "DEFECT_OPENED", "defect", id, Map.of("severity", body.severity()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "defectId", id.toString(), "status", "OPEN", "severity", body.severity()));
    }

    @PatchMapping("/defects/{defectId}")
    public Map<String, Object> patchDefect(@PathVariable UUID defectId,
                                           @Valid @RequestBody DefectPatchRequest body, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        Map<String, Object> defect = jdbc.queryForMap(
                "SELECT rehearsal_run_id, uat_case_id, status, resolved_at FROM control.defects WHERE id = ?", defectId);
        UUID runId = (UUID) defect.get("rehearsal_run_id");
        UUID projectId = access.projectOfRun(runId);
        access.requireProjectRole(actor, projectId, "REVIEWER");
        String target = body.status();
        if (!List.of("RESOLVED", "CLOSED").contains(target)) {
            throw ApiException.badRequest("INVALID_STATUS", "Defect can only move to RESOLVED or CLOSED");
        }
        if ("RESOLVED".equals(target)) {
            if (body.resolutionNote() == null || body.resolutionNote().isBlank()) {
                throw ApiException.unprocessable("RESOLUTION_NOTE_REQUIRED", "A resolution note is required");
            }
            jdbc.update("UPDATE control.defects SET status = 'RESOLVED', resolution_note = ?, resolved_by = ?, resolved_at = now() WHERE id = ?",
                    body.resolutionNote(), actor.id(), defectId);
        } else {
            // CLOSED requires a passing retest of the defect's case after resolution (spec 9).
            if (!"RESOLVED".equals(defect.get("status"))) {
                throw ApiException.conflict("DEFECT_NOT_RESOLVED", "Resolve the defect before closing it");
            }
            UUID caseId = (UUID) defect.get("uat_case_id");
            if (caseId != null) {
                Long passes = jdbc.queryForObject(
                        "SELECT count(*) FROM control.uat_executions WHERE case_id = ? AND result = 'PASS' AND executed_at >= ?",
                        Long.class, caseId, defect.get("resolved_at"));
                if (passes == null || passes == 0) {
                    throw ApiException.conflict("RETEST_REQUIRED",
                            "A passing retest after resolution is required before closing this defect");
                }
            }
            jdbc.update("UPDATE control.defects SET status = 'CLOSED' WHERE id = ?", defectId);
        }
        audit.record(projectId, actor.id(), "DEFECT_" + target, "defect", defectId, Map.of());
        return Map.of("defectId", defectId.toString(), "status", target);
    }
}
