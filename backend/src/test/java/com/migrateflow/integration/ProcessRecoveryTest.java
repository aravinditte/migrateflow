package com.migrateflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.migrateflow.execution.RunRecovery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Startup recovery (spec 9): a run left RUNNING by a terminated process
 * must not wedge forever. RunRecovery marks stale Spring Batch executions
 * FAILED (not ABANDONED, which would permanently prevent restart) and
 * marks the run FAILED_RESTARTABLE so the same-identity restart path can resume it.
 */
class ProcessRecoveryTest extends AbstractIntegrationTest {

    @Autowired
    RunRecovery recovery;

    @Test
    void runningRunLeftByDeadProcessBecomesRestartable() throws Exception {
        MockHttpSession engineer = engineer();
        UUID[] ids = prepareValidated(engineer);
        excludeAllInvalid(reviewer(), ids[3]);
        UUID manifestId = freeze(engineer, ids[0], ids[1], ids[2], ids[3]);

        // Simulate a run whose process died mid-execution before batch execution was registered.
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO control.runs (id, project_id, manifest_id, kind, status, requested_by, expected_accepted_rows, started_at) "
                        + "VALUES (?,?,?,'REHEARSAL','RUNNING',?,2985,now())",
                runId, ids[0], manifestId, userId("engineer@example.test"));

        int recovered = recovery.recoverStaleRuns();
        assertEquals(1, recovered);
        assertEquals("FAILED_RESTARTABLE", jdbc.queryForObject(
                "SELECT status FROM control.runs WHERE id = ?", String.class, runId));

        // Idempotent: a second recovery pass finds nothing stale.
        assertEquals(0, recovery.recoverStaleRuns());
    }

    @Test
    void staleBatchExecutionLeftStartedIsMarkedFailedAndRestartsSuccessfully() throws Exception {
        MockHttpSession engineer = engineer();
        UUID[] ids = prepareValidated(engineer);
        excludeAllInvalid(reviewer(), ids[3]);
        UUID manifestId = freeze(engineer, ids[0], ids[1], ids[2], ids[3]);

        UUID rehearsalId = launchRehearsal(engineer, manifestId);
        JsonNode runNode = awaitRun(engineer, rehearsalId);
        assertEquals("VERIFIED", runNode.get("status").asText());

        Long execId = jdbc.queryForObject("SELECT batch_job_execution_id FROM control.runs WHERE id = ?", Long.class, rehearsalId);

        // Simulate a crash right before completion: DB record left as RUNNING, and batch execution left as STARTED
        jdbc.update("UPDATE control.runs SET status = 'RUNNING', finished_at = NULL WHERE id = ?", rehearsalId);
        jdbc.update("UPDATE control.batch_job_execution SET status = 'STARTED', exit_code = 'UNKNOWN', end_time = NULL WHERE job_execution_id = ?", execId);
        jdbc.update("UPDATE control.batch_step_execution SET status = 'STARTED', exit_code = 'UNKNOWN', end_time = NULL WHERE job_execution_id = ?", execId);

        // Startup recovery runs: it must mark the batch execution FAILED (not ABANDONED)
        int recovered = recovery.recoverStaleRuns();
        assertEquals(1, recovered);

        String batchStatus = jdbc.queryForObject("SELECT status FROM control.batch_job_execution WHERE job_execution_id = ?", String.class, execId);
        assertEquals("FAILED", batchStatus);

        assertEquals("FAILED_RESTARTABLE", jdbc.queryForObject(
                "SELECT status FROM control.runs WHERE id = ?", String.class, rehearsalId));

        // Now verify same-identity restart: because status was set to FAILED instead of ABANDONED,
        // Spring Batch permits the same JobInstance to restart and complete cleanly.
        mvc.perform(post("/api/v1/runs/" + rehearsalId + "/restart").session(engineer).with(csrf()))
                .andExpect(status().isAccepted());

        JsonNode restarted = awaitRun(engineer, rehearsalId);
        assertEquals("VERIFIED", restarted.get("status").asText());
    }
}
