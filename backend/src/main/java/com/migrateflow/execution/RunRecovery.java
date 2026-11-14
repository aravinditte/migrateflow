package com.migrateflow.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Startup recovery for process termination (spec 9): MigrateFlow runs as a
 * single instance, so at startup any run still marked RUNNING belongs to a
 * process that no longer exists. Its stale Spring Batch executions are
 * marked FAILED in the job repository (so Spring Batch allows the same
 * JobInstance to be restarted from the last committed checkpoint, whereas
 * ABANDONED would refuse restart), and the run is marked FAILED_RESTARTABLE
 * so the ordinary same-identity restart path resumes it - the applied-rows
 * ledger makes the resumed execution exact. Runs still QUEUED (the JVM
 * died between the launch insert and the executor picking the task up) are
 * simply executed; executeRun's atomic claim keeps that race-safe.
 */
@Component
public class RunRecovery implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RunRecovery.class);
    private static final List<String> JOB_NAMES = List.of("rehearsalJob", "cutoverJob");

    private final JdbcTemplate jdbc;
    private final JobExplorer jobExplorer;
    private final JobRepository jobRepository;
    private final RunService runs;

    public RunRecovery(JdbcTemplate jdbc, JobExplorer jobExplorer, JobRepository jobRepository, RunService runs) {
        this.jdbc = jdbc;
        this.jobExplorer = jobExplorer;
        this.jobRepository = jobRepository;
        this.runs = runs;
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = recoverStaleRuns();
        if (recovered > 0) {
            log.warn("Startup recovery processed {} stale run(s)", recovered);
        }
    }

    /** Returns how many stale runs were recovered. Callable from tests. */
    public int recoverStaleRuns() {
        int recovered = 0;
        for (Map<String, Object> run : jdbc.queryForList(
                "SELECT id FROM control.runs WHERE status = 'RUNNING'")) {
            UUID runId = (UUID) run.get("id");
            failStaleExecutions(runId);
            int updated = jdbc.update(
                    "UPDATE control.runs SET status = 'FAILED_RESTARTABLE', finished_at = now(), "
                            + "error_message = 'Recovered at startup: the previous process terminated during execution' "
                            + "WHERE id = ? AND status = 'RUNNING'", runId);
            if (updated > 0) {
                recovered++;
                log.warn("Run {} was RUNNING at startup; marked FAILED_RESTARTABLE for same-identity restart", runId);
            }
        }
        for (Map<String, Object> run : jdbc.queryForList(
                "SELECT id FROM control.runs WHERE status = 'QUEUED'")) {
            UUID runId = (UUID) run.get("id");
            log.warn("Run {} was QUEUED at startup; executing now", runId);
            runs.executeRun(runId);
            recovered++;
        }
        return recovered;
    }

    private void failStaleExecutions(UUID runId) {
        for (String jobName : JOB_NAMES) {
            for (JobExecution execution : jobExplorer.findRunningJobExecutions(jobName)) {
                if (!runId.toString().equals(execution.getJobParameters().getString("runId"))) {
                    continue;
                }
                execution.getStepExecutions().forEach(step -> {
                    step.setStatus(BatchStatus.FAILED);
                    step.setExitStatus(ExitStatus.FAILED);
                    step.setEndTime(LocalDateTime.now());
                    jobRepository.update(step);
                });
                execution.setStatus(BatchStatus.FAILED);
                execution.setExitStatus(ExitStatus.FAILED);
                execution.setEndTime(LocalDateTime.now());
                jobRepository.update(execution);
                log.warn("Marked stale batch execution {} as FAILED for run {} to enable safe restart", execution.getId(), runId);
            }
        }
    }
}
