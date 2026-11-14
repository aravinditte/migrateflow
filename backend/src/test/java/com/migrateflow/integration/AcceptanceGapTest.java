package com.migrateflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.migrateflow.reconciliation.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The acceptance-matrix rows the first suite left thin (spec 13.2):
 * T11 defect gate in full, T14 racing cutovers, T18 post-cutover tamper
 * detection, T20 audit-trail completeness.
 */
class AcceptanceGapTest extends AbstractIntegrationTest {

    @Autowired
    ReconciliationService reconciliation;

    /** T11: an unresolved BLOCKER defect blocks approval; closing requires
     *  a passing retest recorded after resolution. */
    @Test
    void defectGateBlocksApprovalAndCloseRequiresRetest() throws Exception {
        MockHttpSession engineer = engineer();
        MockHttpSession reviewer = reviewer();
        UUID[] ids = prepareValidated(engineer);
        excludeAllInvalid(reviewer, ids[3]);
        UUID manifestId = freeze(engineer, ids[0], ids[1], ids[2], ids[3]);
        UUID rehearsalId = launchRehearsal(engineer, manifestId);
        awaitRun(engineer, rehearsalId);
        UUID reportId = reconciliationReportId(engineer, rehearsalId);
        passAllUat(reviewer, rehearsalId);

        // Open a BLOCKER defect against a passed case.
        MvcResult defectResult = mvc.perform(post("/api/v1/rehearsals/" + rehearsalId + "/defects")
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseKey\":\"COUNTS_MATCH\",\"severity\":\"BLOCKER\",\"description\":\"Totals disagree with the source ledger\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID defectId = UUID.fromString(json(defectResult).get("defectId").asText());

        // Approval is refused while the defect is OPEN.
        mvc.perform(post("/api/v1/manifests/" + manifestId + "/approvals")
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(approvalJson(rehearsalId, reportId, currentUatHash(reviewer, rehearsalId))))
                .andExpect(status().isUnprocessableEntity());

        // Resolving alone does not unblock approval (defect not closed).
        mvc.perform(patch("/api/v1/defects/" + defectId)
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"resolutionNote\":\"Rounding rule clarified with the business\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/manifests/" + manifestId + "/approvals")
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(approvalJson(rehearsalId, reportId, currentUatHash(reviewer, rehearsalId))))
                .andExpect(status().isUnprocessableEntity());

        // Closing without a passing retest after resolution is refused.
        mvc.perform(patch("/api/v1/defects/" + defectId)
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isConflict());

        // Retest: re-execute the defect's case to PASS, then close succeeds.
        mvc.perform(post("/api/v1/rehearsals/" + rehearsalId + "/uat-executions")
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseKey\":\"COUNTS_MATCH\",\"result\":\"PASS\",\"actualResult\":\"retest after fix\"}"))
                .andExpect(status().isCreated());
        mvc.perform(patch("/api/v1/defects/" + defectId)
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk());

        // With the defect closed, approval succeeds against the fresh hash.
        mvc.perform(post("/api/v1/manifests/" + manifestId + "/approvals")
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(approvalJson(rehearsalId, reportId, currentUatHash(reviewer, rehearsalId))))
                .andExpect(status().isCreated());
    }

    /** T14: two projects race for the single final target; exactly one
     *  cutover is accepted, the other conflicts. */
    @Test
    void racingCutoversAcrossProjectsExactlyOneWins() throws Exception {
        UUID[] a = prepareApproved(engineer(), reviewer());
        UUID[] b = prepareApproved(engineer(), reviewer());
        MockHttpSession engineerA = engineer();
        MockHttpSession engineerB = engineer();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Integer> first = pool.submit(() -> cutoverAttempt(engineerA, a[1], a[4], gate));
            Future<Integer> second = pool.submit(() -> cutoverAttempt(engineerB, b[1], b[4], gate));
            gate.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS)));
            Collections.sort(statuses);
            assertEquals(List.of(202, 409), statuses,
                    "exactly one racing cutover may reserve the target slot");
        } finally {
            pool.shutdownNow();
        }

        // The winner completes and verifies; the slot names it as verified
        // (verified_run_id is stamped when the run finishes, so poll).
        UUID winningRun = null;
        for (int i = 0; i < 120 && winningRun == null; i++) {
            winningRun = jdbc.queryForObject(
                    "SELECT verified_run_id FROM control.target_cutover_slot WHERE slot_id = 1", UUID.class);
            if (winningRun == null) Thread.sleep(500);
        }
        assertNotNull(winningRun, "one cutover must reach VERIFIED and own the slot");
        assertEquals("VERIFIED", awaitRun(engineerA, winningRun).get("status").asText());
        assertEquals(995, jdbc.queryForObject("SELECT count(*) FROM target.customers", Integer.class));
        assertEquals(1990, jdbc.queryForObject("SELECT count(*) FROM target.orders", Integer.class));
    }

    private int cutoverAttempt(MockHttpSession session, UUID manifestId, UUID approvalId, CountDownLatch gate) throws Exception {
        gate.await(10, TimeUnit.SECONDS);
        MvcResult result = mvc.perform(post("/api/v1/manifests/" + manifestId + "/cutovers")
                        .session(session).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"" + approvalId + "\"}"))
                .andReturn();
        return result.getResponse().getStatus();
    }

    /** T18: a target row altered after a verified cutover is detected by
     *  reconciliation - the new report FAILs instead of passing silently. */
    @Test
    void tamperedTargetRowFailsReconciliation() throws Exception {
        MockHttpSession engineer = engineer();
        UUID[] ids = prepareApproved(engineer, reviewer());
        MvcResult start = mvc.perform(post("/api/v1/manifests/" + ids[1] + "/cutovers")
                        .session(engineer).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"" + ids[4] + "\"}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID cutoverId = UUID.fromString(json(start).get("runId").asText());
        assertEquals("VERIFIED", awaitRun(engineer, cutoverId).get("status").asText());

        // Rogue change: one order's amount no longer matches the accepted row.
        jdbc.update("UPDATE target.orders SET amount_yen = amount_yen + 1 "
                + "WHERE external_order_id = (SELECT external_order_id FROM target.orders ORDER BY external_order_id LIMIT 1)");

        ReconciliationService.Report report = reconciliation.reconcile(cutoverId);
        assertEquals("FAIL", report.status());
        assertTrue(report.summaryJson().contains("difference") || report.summaryJson().contains("mismatch")
                        || report.summaryJson().contains("amount"),
                "FAIL report should name the discrepancy: " + report.summaryJson());

        // The API surface a reviewer reads now reports the failure too.
        MvcResult latest = mvc.perform(get("/api/v1/runs/" + cutoverId + "/reconciliation").session(engineer))
                .andExpect(status().isOk()).andReturn();
        assertEquals("FAIL", json(latest).get("status").asText());
    }

    /** T20: the audit trail for a full journey is complete, attributed and
     *  in chronological order. */
    @Test
    void auditTrailCoversTheWholeJourneyInOrder() throws Exception {
        MockHttpSession engineer = engineer();
        UUID[] ids = prepareApproved(engineer, reviewer());
        MvcResult start = mvc.perform(post("/api/v1/manifests/" + ids[1] + "/cutovers")
                        .session(engineer).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"" + ids[4] + "\"}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID cutoverId = UUID.fromString(json(start).get("runId").asText());
        assertEquals("VERIFIED", awaitRun(engineer, cutoverId).get("status").asText());

        MvcResult result = mvc.perform(get("/api/v1/projects/" + ids[0] + "/audit-events?size=100")
                        .session(auditor()))
                .andExpect(status().isOk()).andReturn();
        JsonNode content = json(result).get("content");
        assertTrue(content.size() >= 25, "journey should leave a rich trail, got " + content.size());

        List<String> actionsDesc = new ArrayList<>();
        List<java.time.Instant> timesDesc = new ArrayList<>();
        for (JsonNode event : content) {
            actionsDesc.add(event.get("action").asText());
            timesDesc.add(java.time.Instant.parse(event.get("occurredAt").asText()));
            assertFalse(event.get("actorEmail").asText().isBlank(), "every event is attributed");
        }
        // Endpoint returns newest first: timestamps must be non-increasing.
        for (int i = 1; i < timesDesc.size(); i++) {
            assertFalse(timesDesc.get(i).isAfter(timesDesc.get(i - 1)),
                    "audit events out of order at " + i);
        }
        // Chronological (oldest-first) milestone order for the journey.
        List<String> actions = new ArrayList<>(actionsDesc);
        Collections.reverse(actions);
        List<String> milestones = List.of("PROJECT_CREATED", "BUNDLE_UPLOADED", "MAPPING_PUBLISHED",
                "VALIDATION_STARTED", "MANIFEST_FROZEN", "REHEARSAL_LAUNCHED", "APPROVAL_APPROVE", "CUTOVER_LAUNCHED");
        int cursor = -1;
        for (String milestone : milestones) {
            int idx = actions.indexOf(milestone);
            assertTrue(idx > cursor, milestone + " missing or out of order in " + actions);
            cursor = idx;
        }
        assertEquals(15, actions.stream().filter("EXCLUSION_DECIDED"::equals).count());
        assertTrue(actions.stream().filter("UAT_EXECUTED"::equals).count() >= 5);
    }
}
