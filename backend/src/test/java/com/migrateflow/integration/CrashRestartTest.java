package com.migrateflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Crash/restart acceptance (spec 13.2 T16): with test-only fault injection,
 * a run dies after committed chunks, stays restartable under the SAME
 * identity, and its restart finishes with exact counts - no duplicates,
 * no missing rows. Verified for both rehearsal and cutover.
 */
@TestPropertySource(properties = {
        "migrateflow.batch-chunk-size=50",
        "migrateflow.test-fail-after-chunks=5"
})
class CrashRestartTest extends AbstractIntegrationTest {

    @Test
    void crashAfterCommittedChunksRestartsToExactCounts() throws Exception {
        MockHttpSession engineer = engineer();
        MockHttpSession reviewer = reviewer();
        UUID[] ids = prepareValidated(engineer);
        excludeAllInvalid(reviewer, ids[3]);
        UUID manifestId = freeze(engineer, ids[0], ids[1], ids[2], ids[3]);

        // Rehearsal crashes after 5 committed chunks of 50 (250 customers).
        MvcResult start = mvc.perform(post("/api/v1/manifests/" + manifestId + "/rehearsals")
                        .session(engineer).with(csrf()))
                .andExpect(status().isAccepted()).andReturn();
        UUID rehearsalId = UUID.fromString(json(start).get("runId").asText());
        JsonNode failed = awaitRun(engineer, rehearsalId);
        assertEquals("FAILED_RESTARTABLE", failed.get("status").asText());
        int partial = jdbc.queryForObject(
                "SELECT count(*) FROM rehearsal.customers WHERE rehearsal_run_id = ?", Integer.class, rehearsalId);
        assertTrue(partial >= 250 && partial < 995, "expected a partially committed rehearsal, got " + partial);

        // Restart keeps the same run identity and completes exactly.
        mvc.perform(post("/api/v1/runs/" + rehearsalId + "/restart").session(engineer).with(csrf()))
                .andExpect(status().isAccepted());
        JsonNode restarted = awaitRun(engineer, rehearsalId);
        assertEquals("VERIFIED", restarted.get("status").asText());
        assertEquals(995, jdbc.queryForObject(
                "SELECT count(*) FROM rehearsal.customers WHERE rehearsal_run_id = ?", Integer.class, rehearsalId));
        assertEquals(1990, jdbc.queryForObject(
                "SELECT count(*) FROM rehearsal.orders WHERE rehearsal_run_id = ?", Integer.class, rehearsalId));
        assertEquals(995, jdbc.queryForObject(
                "SELECT count(DISTINCT external_customer_id) FROM rehearsal.customers WHERE rehearsal_run_id = ?", Integer.class, rehearsalId));

        // UAT + approval, then the same crash/restart story for cutover.
        MvcResult recon = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/runs/" + rehearsalId + "/reconciliation").session(engineer))
                .andReturn();
        UUID reportId = UUID.fromString(json(recon).get("reportId").asText());
        for (String caseKey : new String[]{"COUNTS_MATCH", "CUSTOMER_SPOT_CHECK", "ORDER_TOTALS", "EXCLUSIONS_ABSENT", "PROVENANCE_TRACE"}) {
            mvc.perform(post("/api/v1/rehearsals/" + rehearsalId + "/uat-executions")
                            .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"caseKey\":\"" + caseKey + "\",\"result\":\"PASS\",\"actualResult\":\"verified\"}"))
                    .andExpect(status().isCreated());
        }
        MvcResult uat = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/rehearsals/" + rehearsalId + "/uat").session(reviewer))
                .andReturn();
        String uatHash = json(uat).get("uatEvidenceHash").asText();
        MvcResult approval = mvc.perform(post("/api/v1/manifests/" + manifestId + "/approvals")
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"rehearsalRunId\":\"" + rehearsalId
                                + "\",\"reconciliationReportId\":\"" + reportId + "\",\"uatEvidenceHash\":\"" + uatHash + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID approvalId = UUID.fromString(json(approval).get("approvalId").asText());

        MvcResult cutoverStart = mvc.perform(post("/api/v1/manifests/" + manifestId + "/cutovers")
                        .session(engineer).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"" + approvalId + "\"}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID cutoverId = UUID.fromString(json(cutoverStart).get("runId").asText());
        JsonNode cutoverFailed = awaitRun(engineer, cutoverId);
        assertEquals("FAILED_RESTARTABLE", cutoverFailed.get("status").asText());
        int partialTarget = jdbc.queryForObject("SELECT count(*) FROM target.customers", Integer.class);
        assertTrue(partialTarget >= 250 && partialTarget < 995,
                "expected a partially committed target, got " + partialTarget);
        // The slot still belongs to the failed run while it awaits restart.
        assertEquals("RESERVED", jdbc.queryForObject(
                "SELECT status FROM control.target_cutover_slot WHERE slot_id = 1", String.class));

        mvc.perform(post("/api/v1/runs/" + cutoverId + "/restart").session(engineer).with(csrf()))
                .andExpect(status().isAccepted());
        JsonNode cutoverDone = awaitRun(engineer, cutoverId);
        assertEquals("VERIFIED", cutoverDone.get("status").asText());
        assertEquals(995, jdbc.queryForObject("SELECT count(*) FROM target.customers", Integer.class));
        assertEquals(1990, jdbc.queryForObject("SELECT count(*) FROM target.orders", Integer.class));
        assertEquals(21_998_650L, jdbc.queryForObject("SELECT COALESCE(SUM(amount_yen),0) FROM target.orders", Long.class));
    }
}
