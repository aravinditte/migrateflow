package com.migrateflow.execution;

import com.migrateflow.audit.AuditService;
import com.migrateflow.common.ApiException;
import com.migrateflow.identity.AppUser;
import com.migrateflow.reconciliation.ReconciliationService;
import com.migrateflow.uat.UatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Run lifecycle (spec sections 7 and 9): rehearsal launch, the atomic
 * cutover preflight against the singleton target slot, same-identity
 * restart, and post-batch reconciliation that alone can mark VERIFIED.
 */
@Service
public class RunService {
    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final JobLauncher jobLauncher;
    private final Job rehearsalJob;
    private final Job cutoverJob;
    private final ReconciliationService reconciliation;
    private final AcceptedRows acceptedRows;
    private final UatService uat;
    private final Executor executor;
    private final TransactionTemplate txTemplate;

    public RunService(JdbcTemplate jdbc, AuditService audit, JobLauncher jobLauncher,
                      @Qualifier("rehearsalJob") Job rehearsalJob, @Qualifier("cutoverJob") Job cutoverJob,
                      ReconciliationService reconciliation, AcceptedRows acceptedRows, UatService uat,
                      @Qualifier("migrateflowTaskExecutor") Executor executor,
                      PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.jobLauncher = jobLauncher;
        this.rehearsalJob = rehearsalJob;
        this.cutoverJob = cutoverJob;
        this.reconciliation = reconciliation;
        this.acceptedRows = acceptedRows;
        this.uat = uat;
        this.executor = executor;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    public UUID launchRehearsal(AppUser actor, UUID manifestId) {
        Map<String, Object> manifest = manifestRow(manifestId);
        UUID projectId = (UUID) manifest.get("project_id");
        UUID snapshotId = (UUID) manifest.get("snapshot_id");
        UUID runId = UUID.randomUUID();
        // The active-run check and the insert are one transaction holding the
        // project row lock, so two concurrent launches cannot both pass.
        txTemplate.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM control.projects WHERE id = ? FOR UPDATE", UUID.class, projectId);
            String manifestStatus = jdbc.queryForObject(
                    "SELECT status FROM control.manifests WHERE id = ?", String.class, manifestId);
            if (!"FROZEN".equals(manifestStatus)) {
                throw ApiException.conflict("PLAN_CHANGED", "Manifest is not the current frozen plan");
            }
            Long active = jdbc.queryForObject(
                    "SELECT count(*) FROM control.runs WHERE project_id = ? AND status IN ('QUEUED','RUNNING')",
                    Long.class, projectId);
            if (active != null && active > 0) {
                throw ApiException.conflict("JOB_ALREADY_RUNNING", "This project already has an active run");
            }
            jdbc.update("INSERT INTO control.runs (id, project_id, manifest_id, kind, status, requested_by, expected_accepted_rows) "
                            + "VALUES (?,?,?,'REHEARSAL','QUEUED',?,?)",
                    runId, projectId, manifestId, actor.id(), acceptedRows.acceptedCount(snapshotId));
        });
        audit.record(projectId, actor.id(), "REHEARSAL_LAUNCHED", "run", runId, Map.of("manifestId", manifestId.toString()));
        executor.execute(() -> executeRun(runId));
        return runId;
    }

    private record CutoverLaunch(UUID runId, boolean created) {}

    /** Atomic preflight + reservation (spec 7.3). Returns the cutover run id. */
    public UUID launchCutover(AppUser actor, UUID manifestId, UUID approvalId, String idempotencyKey) {
        Map<String, Object> manifest = manifestRow(manifestId);
        UUID projectId = (UUID) manifest.get("project_id");
        CutoverLaunch launch = txTemplate.execute(status -> {
            // Lock project and verify no active sibling runs (MF-012)
            jdbc.queryForObject("SELECT id FROM control.projects WHERE id = ? FOR UPDATE", UUID.class, projectId);
            Long siblings = jdbc.queryForObject(
                    "SELECT count(*) FROM control.runs WHERE project_id = ? AND status IN ('QUEUED','RUNNING')",
                    Long.class, projectId);
            if (siblings != null && siblings > 0) {
                throw ApiException.conflict("JOB_ALREADY_RUNNING", "This project already has an active run");
            }

            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                List<Map<String, Object>> existing = jdbc.queryForList(
                        "SELECT id, manifest_id, approval_id FROM control.runs WHERE project_id = ? AND request_key = ?", projectId, idempotencyKey);
                if (!existing.isEmpty()) {
                    if (!manifestId.equals(existing.get(0).get("manifest_id")) || !approvalId.equals(existing.get(0).get("approval_id"))) {
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                                "This Idempotency-Key was already used with a different payload");
                    }
                    // Idempotent replay: return the existing run WITHOUT relaunching it.
                    return new CutoverLaunch((UUID) existing.get(0).get("id"), false);
                }
            }
            Map<String, Object> slot = jdbc.queryForMap(
                    "SELECT status, active_run_id FROM control.target_cutover_slot WHERE slot_id = 1 FOR UPDATE");
            if ("VERIFIED".equals(slot.get("status"))) {
                throw ApiException.conflict("TARGET_NOT_EMPTY", "The final target has already been migrated and verified");
            }
            if ("RESERVED".equals(slot.get("status")) && slot.get("active_run_id") != null) {
                throw ApiException.conflict("JOB_ALREADY_RUNNING", "Another cutover already holds the final target slot");
            }
            if (!"FROZEN".equals(manifest.get("status"))) {
                throw ApiException.conflict("PLAN_CHANGED", "Manifest is not the current frozen plan");
            }
            Map<String, Object> approval = jdbc.queryForMap(
                    "SELECT manifest_id, rehearsal_run_id, reconciliation_report_id, decision, reviewer_id, uat_evidence_hash "
                            + "FROM control.approvals WHERE id = ?", approvalId);
            if (!manifestId.equals(approval.get("manifest_id")) || !"APPROVED".equals(approval.get("decision"))) {
                throw ApiException.unprocessable("INVALID_REFERENCE",
                        "Approval does not approve this manifest");
            }
            // Check MF-010: ensure approval has not been superseded by a later REJECTED decision
            Long laterRejection = jdbc.queryForObject(
                    "SELECT count(*) FROM control.approvals WHERE manifest_id = ? AND decision = 'REJECTED' AND (created_at > (SELECT created_at FROM control.approvals WHERE id = ?) OR signed_at > (SELECT signed_at FROM control.approvals WHERE id = ?))",
                    Long.class, manifestId, approvalId, approvalId);
            if (laterRejection != null && laterRejection > 0) {
                throw ApiException.conflict("APPROVAL_REVOKED", "A later rejection exists for this manifest; approval is revoked");
            }

            UUID reviewerId = (UUID) approval.get("reviewer_id");
            if (reviewerId.equals(actor.id())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                        "The approver cannot launch the cutover they approved");
            }
            UUID rehearsalRunId = (UUID) approval.get("rehearsal_run_id");

            // MF-011: Lock rehearsal run during preflight so UAT cannot mutate concurrently
            jdbc.queryForObject("SELECT id FROM control.runs WHERE id = ? FOR UPDATE", UUID.class, rehearsalRunId);

            String rehearsalStatus = jdbc.queryForObject(
                    "SELECT status FROM control.runs WHERE id = ?", String.class, rehearsalRunId);
            if (!"VERIFIED".equals(rehearsalStatus)) {
                throw ApiException.unprocessable("INVALID_REFERENCE", "The approved rehearsal is not verified");
            }
            String reportStatus = jdbc.queryForObject(
                    "SELECT status FROM control.reconciliation_reports WHERE id = ?", String.class,
                    (UUID) approval.get("reconciliation_report_id"));
            if (!"PASS".equals(reportStatus)) {
                throw ApiException.unprocessable("RECONCILIATION_FAILED", "The approved reconciliation report is not PASS");
            }
            // The approval bound the UAT evidence as it stood when it was
            // signed. Recompute it NOW, inside the preflight transaction: a
            // case re-executed to FAIL, or a blocking defect opened after
            // approval, invalidates the approval instead of reaching cutover.
            String approvedHash = String.valueOf(approval.get("uat_evidence_hash")).trim();
            String currentHash = uat.uatEvidenceHash(rehearsalRunId);
            if (!currentHash.equals(approvedHash)) {
                throw ApiException.unprocessable("UAT_EVIDENCE_STALE",
                        "UAT evidence changed after approval; a fresh approval is required");
            }
            String blocker = uat.approvalBlocker(rehearsalRunId);
            if (blocker != null) {
                throw ApiException.unprocessable("UAT_NOT_PASSED", blocker);
            }
            Long targetRows = jdbc.queryForObject(
                    "SELECT (SELECT count(*) FROM target.customers) + (SELECT count(*) FROM target.orders)", Long.class);
            if (targetRows != null && targetRows > 0) {
                throw ApiException.conflict("TARGET_NOT_EMPTY", "The final target is not empty");
            }
            UUID snapshotId = (UUID) manifest.get("snapshot_id");
            UUID newRunId = UUID.randomUUID();
            jdbc.update("INSERT INTO control.runs (id, project_id, manifest_id, kind, status, requested_by, approval_id, request_key, expected_accepted_rows) "
                            + "VALUES (?,?,?,'CUTOVER','QUEUED',?,?,?,?)",
                    newRunId, projectId, manifestId, actor.id(), approvalId, idempotencyKey,
                    acceptedRows.acceptedCount(snapshotId));
            jdbc.update("UPDATE control.target_cutover_slot SET status = 'RESERVED', active_run_id = ?, updated_at = now() WHERE slot_id = 1",
                    newRunId);
            // MF-014: Record audit inside the transaction with reservation
            audit.record(projectId, actor.id(), "CUTOVER_LAUNCHED", "run", newRunId,
                    Map.of("manifestId", manifestId.toString(), "approvalId", approvalId.toString()));
            return new CutoverLaunch(newRunId, true);
        });
        UUID runId = launch.runId();
        if (launch.created()) {
            try {
                executor.execute(() -> executeRun(runId));
            } catch (Exception ex) {
                log.warn("Executor rejected immediate run execution for {}; will be executed by queue or recovery", runId, ex);
            }
        }
        return runId;
    }
    }

    public void restart(AppUser actor, UUID runId) {
        Map<String, Object> run = runRow(runId);
        UUID projectId = (UUID) run.get("project_id");
        if ("CUTOVER".equals(run.get("kind"))) {
            UUID activeRun = jdbc.queryForObject(
                    "SELECT active_run_id FROM control.target_cutover_slot WHERE slot_id = 1", UUID.class);
            if (!runId.equals(activeRun)) {
                throw ApiException.conflict("JOB_NOT_RESTARTABLE", "The cutover slot no longer belongs to this run");
            }
        }
        // Same single-worker discipline as launch (spec 3.3): under the
        // project row lock, refuse when a sibling run is active, then claim
        // FAILED_RESTARTABLE -> QUEUED with one conditional update so a
        // concurrent restart or launch cannot interleave.
        txTemplate.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM control.projects WHERE id = ? FOR UPDATE", UUID.class, projectId);
            Long siblings = jdbc.queryForObject(
                    "SELECT count(*) FROM control.runs WHERE project_id = ? AND id <> ? AND status IN ('QUEUED','RUNNING')",
                    Long.class, projectId, runId);
            if (siblings != null && siblings > 0) {
                throw ApiException.conflict("JOB_ALREADY_RUNNING", "This project already has an active run");
            }
            int claimed = jdbc.update(
                    "UPDATE control.runs SET status = 'QUEUED', error_message = NULL WHERE id = ? AND status IN ('FAILED_RESTARTABLE', 'BATCH_COMPLETED')",
                    runId);
            if (claimed == 0) {
                throw ApiException.conflict("JOB_NOT_RESTARTABLE", "Only a failed restartable run can be restarted");
            }
        });
        audit.record(projectId, actor.id(), "RUN_RESTARTED", "run", runId, Map.of());
        executor.execute(() -> executeRun(runId));
    }

    void executeRun(UUID runId) {
        // Atomic claim: only the caller that moves QUEUED -> RUNNING owns the
        // execution. Duplicate submissions (executor retry, startup recovery
        // racing a launch) return without launching the job a second time.
        int claimed = jdbc.update(
                "UPDATE control.runs SET status = 'RUNNING', started_at = COALESCE(started_at, now()), finished_at = NULL "
                        + "WHERE id = ? AND status = 'QUEUED'", runId);
        if (claimed == 0) {
            log.info("Run {} was not QUEUED when execution was attempted; skipping duplicate launch", runId);
            return;
        }
        Map<String, Object> run = runRow(runId);
        Long existingBatchExecutionId = (Long) run.get("batch_job_execution_id");
        if (existingBatchExecutionId != null) {
            JobExecution existingExec = jobExplorer.getJobExecution(existingBatchExecutionId);
            if (existingExec != null && existingExec.getStatus() == BatchStatus.COMPLETED) {
                log.info("Run {} already completed batch execution {}; proceeding directly to finalization", runId, existingBatchExecutionId);
                finalizeCompletedRun(runId);
                return;
            }
        }
        String kind = (String) run.get("kind");
        UUID manifestId = (UUID) run.get("manifest_id");
        UUID snapshotId = jdbc.queryForObject("SELECT snapshot_id FROM control.manifests WHERE id = ?", UUID.class, manifestId);
        Job job = "REHEARSAL".equals(kind) ? rehearsalJob : cutoverJob;
        JobParameters params = new JobParametersBuilder()
                .addString("runId", runId.toString(), true)
                .addString("manifestId", manifestId.toString(), false)
                .addString("snapshotId", snapshotId.toString(), false)
                .addString("scope", kind, false)
                .toJobParameters();
        try {
            JobExecution execution = jobLauncher.run(job, params);
            int processed = countApplied(runId);
            jdbc.update("UPDATE control.runs SET batch_job_instance_id = ?, batch_job_execution_id = ?, processed_rows = ? WHERE id = ?",
                    execution.getJobInstance().getInstanceId(), execution.getId(), processed, runId);
            if (execution.getStatus() == BatchStatus.COMPLETED) {
                finalizeCompletedRun(runId);
            } else {
                String exit = execution.getExitStatus() == null ? "" : execution.getExitStatus().getExitDescription();
                jdbc.update("UPDATE control.runs SET status = 'FAILED_RESTARTABLE', finished_at = now(), error_message = ? WHERE id = ?",
                        exit == null ? "" : exit.substring(0, Math.min(900, exit.length())), runId);
            }
        } catch (Exception e) {
            log.error("Run {} failed to execute", runId, e);
            jdbc.update("UPDATE control.runs SET status = 'FAILED_RESTARTABLE', finished_at = now(), error_message = ? WHERE id = ?",
                    String.valueOf(e.getMessage()).substring(0, Math.min(900, String.valueOf(e.getMessage()).length())), runId);
        }
    }

    public void finalizeCompletedRun(UUID runId) {
        Map<String, Object> run = runRow(runId);
        String kind = (String) run.get("kind");
        UUID projectId = (UUID) run.get("project_id");
        UUID requestedBy = (UUID) run.get("requested_by");
        try {
            jdbc.update("UPDATE control.runs SET status = 'BATCH_COMPLETED' WHERE id = ?", runId);
            ReconciliationService.Report report = reconciliation.reconcile(runId);
            if ("PASS".equals(report.status())) {
                txTemplate.executeWithoutResult(status -> {
                    if ("REHEARSAL".equals(kind)) {
                        uat.createDefaultCases(runId);
                    } else {
                        jdbc.update("UPDATE control.target_cutover_slot SET status = 'VERIFIED', verified_run_id = ?, updated_at = now() WHERE slot_id = 1",
                                runId);
                    }
                    jdbc.update("UPDATE control.runs SET status = 'VERIFIED', finished_at = now(), error_message = NULL WHERE id = ?", runId);
                    audit.record(projectId, requestedBy, "RUN_VERIFIED", "run", runId,
                            Map.of("reportId", report.reportId().toString(), "executedBy", "SYSTEM", "requestedBy", requestedBy.toString()));
                });
            } else {
                jdbc.update("UPDATE control.runs SET status = 'VERIFICATION_FAILED', finished_at = now() WHERE id = ?", runId);
            }
        } catch (Exception e) {
            log.error("Run {} failed during finalization", runId, e);
            jdbc.update("UPDATE control.runs SET status = 'FAILED_RESTARTABLE', finished_at = now(), error_message = ? WHERE id = ?",
                    "Finalization failed: " + e.getMessage(), runId);
        }
    }

    private int countApplied(UUID runId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM control.applied_rows WHERE run_id = ?", Integer.class, runId);
        return n == null ? 0 : n;
    }

    public Map<String, Object> manifestRow(UUID manifestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, project_id, snapshot_id, status, created_by FROM control.manifests WHERE id = ?", manifestId);
        if (rows.isEmpty()) throw ApiException.notFound("Manifest");
        return rows.get(0);
    }

    public Map<String, Object> runRow(UUID runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM control.runs WHERE id = ?", runId);
        if (rows.isEmpty()) throw ApiException.notFound("Run");
        return rows.get(0);
    }

    public static Instant instant(Object ts) {
        if (ts instanceof Timestamp t) return t.toInstant();
        return null;
    }
}
