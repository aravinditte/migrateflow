package com.migrateflow.integration;

import com.migrateflow.execution.RunRecovery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Startup recovery (review finding, spec 9): a run left RUNNING by a
 * terminated process must not wedge forever - recovery marks it
 * FAILED_RESTARTABLE so the same-identity restart path can resume it.
 * The stale batch-execution abandonment is exercised implicitly: this
 * seeded run has no live batch execution in this JVM, exactly like a
 * fresh process inspecting a dead predecessor's database.
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

        // Simulate a run whose process died mid-execution.
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
}
