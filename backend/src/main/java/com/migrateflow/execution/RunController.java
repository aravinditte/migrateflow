package com.migrateflow.execution;

import com.migrateflow.common.ApiException;
import com.migrateflow.common.CsvEscaping;
import com.migrateflow.common.PageRequestUtil;
import com.migrateflow.identity.AccessControl;
import com.migrateflow.identity.AppUser;
import com.migrateflow.identity.SecurityConfig;
import com.migrateflow.identity.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class RunController {
    private final RunService runs;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AccessControl access;

    public RunController(RunService runs, JdbcTemplate jdbc, UserRepository users, AccessControl access) {
        this.runs = runs;
        this.jdbc = jdbc;
        this.users = users;
        this.access = access;
    }

    public record CutoverRequest(@NotNull UUID approvalId) {}

    @PostMapping("/manifests/{manifestId}/rehearsals")
    public ResponseEntity<Map<String, Object>> launchRehearsal(@PathVariable UUID manifestId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        UUID projectId = access.projectOfManifest(manifestId);
        access.requireProjectRole(actor, projectId, "ENGINEER");
        access.requireActiveProject(projectId);
        UUID runId = runs.launchRehearsal(actor, manifestId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "runId", runId.toString(), "kind", "REHEARSAL", "status", "QUEUED"));
    }

    @PostMapping("/manifests/{manifestId}/cutovers")
    public ResponseEntity<Map<String, Object>> launchCutover(@PathVariable UUID manifestId,
                                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                             @Valid @RequestBody CutoverRequest body, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        UUID projectId = access.projectOfManifest(manifestId);
        access.requireProjectRole(actor, projectId, "ENGINEER");
        access.requireActiveProject(projectId);
        UUID runId = runs.launchCutover(actor, manifestId, body.approvalId(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "runId", runId.toString(), "kind", "CUTOVER", "status", "QUEUED"));
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> getRun(@PathVariable UUID runId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfRun(runId));
        return runView(runs.runRow(runId));
    }

    @PostMapping("/runs/{runId}/restart")
    public ResponseEntity<Map<String, Object>> restart(@PathVariable UUID runId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        UUID projectId = access.projectOfRun(runId);
        access.requireProjectRole(actor, projectId, "ENGINEER");
        access.requireActiveProject(projectId);
        runs.restart(actor, runId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "runId", runId.toString(), "status", "QUEUED"));
    }

    @GetMapping("/runs/{runId}/reconciliation")
    public Map<String, Object> reconciliation(@PathVariable UUID runId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfRun(runId));
        return jdbc.query("SELECT id, status, summary_json::text AS summary, evidence_hash, completed_at "
                        + "FROM control.reconciliation_reports WHERE run_id = ? ORDER BY completed_at DESC LIMIT 1",
                (rs, n) -> Map.<String, Object>of(
                        "reportId", rs.getObject("id", UUID.class).toString(),
                        "runId", runId.toString(),
                        "status", rs.getString("status"),
                        "summary", rs.getString("summary"),
                        "evidenceHash", rs.getString("evidence_hash"),
                        "completedAt", String.valueOf(rs.getTimestamp("completed_at").toInstant())),
                runId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Reconciliation report"));
    }

    @GetMapping("/rehearsals/{runId}/customers")
    public Map<String, Object> rehearsalCustomers(@PathVariable UUID runId,
                                                  @RequestParam(required = false) String q,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfRun(runId));
        int validPage = PageRequestUtil.clampPage(page);
        int validSize = PageRequestUtil.clampSize(size);
        long offset = PageRequestUtil.safeOffset(validPage, validSize);
        String sql = "SELECT id, external_customer_id, full_name, email, joined_on, source_row_number "
                + "FROM rehearsal.customers WHERE rehearsal_run_id = ?"
                + (q == null || q.isBlank() ? "" : " AND external_customer_id LIKE ?")
                + " ORDER BY external_customer_id LIMIT ? OFFSET ?";
        Object[] args = q == null || q.isBlank()
                ? new Object[]{runId, validSize, offset}
                : new Object[]{runId, "%" + q.toUpperCase() + "%", validSize, offset};
        List<Map<String, Object>> rows = jdbc.query(sql,
                (rs, n) -> Map.<String, Object>of(
                        "id", rs.getObject("id", UUID.class).toString(),
                        "externalCustomerId", rs.getString("external_customer_id"),
                        "fullName", rs.getString("full_name"),
                        "email", rs.getString("email") == null ? "" : rs.getString("email"),
                        "joinedOn", rs.getDate("joined_on") == null ? "" : rs.getDate("joined_on").toString(),
                        "sourceRowNumber", rs.getInt("source_row_number")),
                args);
        return Map.of("content", rows, "page", validPage, "size", validSize);
    }

    @GetMapping("/rehearsals/{runId}/orders")
    public Map<String, Object> rehearsalOrders(@PathVariable UUID runId,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfRun(runId));
        int validPage = PageRequestUtil.clampPage(page);
        int validSize = PageRequestUtil.clampSize(size);
        long offset = PageRequestUtil.safeOffset(validPage, validSize);
        String sql = "SELECT o.id, o.external_order_id, c.external_customer_id, o.order_date, o.amount_yen, o.status, o.source_row_number "
                + "FROM rehearsal.orders o JOIN rehearsal.customers c "
                + "ON c.rehearsal_run_id = o.rehearsal_run_id AND c.id = o.customer_id WHERE o.rehearsal_run_id = ?"
                + (q == null || q.isBlank() ? "" : " AND o.external_order_id LIKE ?")
                + " ORDER BY o.external_order_id LIMIT ? OFFSET ?";
        Object[] args = q == null || q.isBlank()
                ? new Object[]{runId, validSize, offset}
                : new Object[]{runId, "%" + q.toUpperCase() + "%", validSize, offset};
        List<Map<String, Object>> rows = jdbc.query(sql,
                (rs, n) -> Map.<String, Object>of(
                        "id", rs.getObject("id", UUID.class).toString(),
                        "externalOrderId", rs.getString("external_order_id"),
                        "externalCustomerId", rs.getString("external_customer_id"),
                        "orderDate", rs.getDate("order_date").toString(),
                        "amountYen", rs.getLong("amount_yen"),
                        "status", rs.getString("status"),
                        "sourceRowNumber", rs.getInt("source_row_number")),
                args);
        return Map.of("content", rows, "page", validPage, "size", validSize);
    }

    /** Downloadable evidence: JSON by default, escaped CSV with format=csv. */
    @GetMapping("/runs/{runId}/report")
    public ResponseEntity<?> report(@PathVariable UUID runId,
                                    @RequestParam(defaultValue = "json") String format, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfRun(runId));
        Map<String, Object> run = runs.runRow(runId);
        Map<String, Object> recon = null;
        try {
            recon = reconciliation(runId, auth);
        } catch (ApiException e) {
            // A run without a report still yields run evidence.
        }
        Map<String, Object> manifest = jdbc.queryForMap(
                "SELECT manifest_hash, exclusion_hash, target_contract_hash FROM control.manifests WHERE id = ?",
                run.get("manifest_id"));
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("runId", runId.toString());
        evidence.put("kind", run.get("kind"));
        evidence.put("status", run.get("status"));
        evidence.put("manifestId", String.valueOf(run.get("manifest_id")));
        evidence.put("manifestHash", manifest.get("manifest_hash"));
        evidence.put("exclusionHash", manifest.get("exclusion_hash"));
        evidence.put("targetContractHash", manifest.get("target_contract_hash"));
        evidence.put("processedRows", run.get("processed_rows"));
        evidence.put("expectedAcceptedRows", run.get("expected_accepted_rows"));
        evidence.put("reconciliation", recon);
        if ("csv".equalsIgnoreCase(format)) {
            StringBuilder csv = new StringBuilder("section,field,value\n");
            evidence.forEach((k, v) -> {
                if (v != null && !(v instanceof Map)) {
                    csv.append("run,").append(CsvEscaping.escapeCell(k)).append(',')
                            .append(CsvEscaping.escapeCell(String.valueOf(v))).append('\n');
                }
            });
            if (recon != null) {
                csv.append("reconciliation,status,").append(CsvEscaping.escapeCell(String.valueOf(recon.get("status")))).append('\n');
                csv.append("reconciliation,evidenceHash,").append(CsvEscaping.escapeCell(String.valueOf(recon.get("evidenceHash")))).append('\n');
                csv.append("reconciliation,summary,").append(CsvEscaping.escapeCell(String.valueOf(recon.get("summary")))).append('\n');
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"run-" + runId + "-report.csv\"")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(csv.toString());
        }
        return ResponseEntity.ok(evidence);
    }

    private Map<String, Object> runView(Map<String, Object> run) {
        // current_step is stamped live by the step listener while the job
        // runs; after completion it holds the last executed step.
        String currentStep = run.get("current_step") == null ? "" : String.valueOf(run.get("current_step"));
        Object execId = run.get("batch_job_execution_id");
        if (execId != null) {
            List<String> steps = jdbc.query(
                    "SELECT step_name FROM control.batch_step_execution WHERE job_execution_id = ? ORDER BY step_execution_id DESC LIMIT 1",
                    (rs, n) -> rs.getString(1), ((Number) execId).longValue());
            if (!steps.isEmpty()) currentStep = steps.get(0);
        }
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("runId", String.valueOf(run.get("id")));
        view.put("kind", run.get("kind"));
        view.put("status", run.get("status"));
        view.put("manifestId", String.valueOf(run.get("manifest_id")));
        view.put("projectId", String.valueOf(run.get("project_id")));
        view.put("processedRows", run.get("processed_rows"));
        view.put("expectedAcceptedRows", run.get("expected_accepted_rows"));
        view.put("currentStep", currentStep);
        view.put("restartEligible", "FAILED_RESTARTABLE".equals(run.get("status")));
        view.put("errorMessage", run.get("error_message") == null ? "" : run.get("error_message"));
        return view;
    }
}
