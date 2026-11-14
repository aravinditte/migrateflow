package com.migrateflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end acceptance (spec 13.2 T01-T15, T17-T20 at API level):
 * upload -> validate -> exclude -> freeze -> rehearse -> reconcile ->
 * UAT -> approve -> cutover -> final reconciliation, on real PostgreSQL.
 */
class FullMigrationFlowTest extends AbstractIntegrationTest {

    @Test
    void fullJourneyFromUploadToVerifiedCutover() throws Exception {
        MockHttpSession engineer = engineer();
        MockHttpSession reviewer = reviewer();

        UUID[] ids = prepareValidated(engineer);
        UUID projectId = ids[0], bundleId = ids[1], mappingId = ids[2], snapshotId = ids[3];

        // Validation counts match the fixture contract exactly.
        JsonNode snapshot = awaitSnapshot(engineer, snapshotId);
        JsonNode counts = mapper.readTree(snapshot.get("counts").asText());
        assertEquals(995, counts.get("customers").get("valid").asInt());
        assertEquals(1990, counts.get("orders").get("valid").asInt());
        assertEquals(5, counts.get("customers").get("invalid").asInt());
        assertEquals(10, counts.get("orders").get("invalid").asInt());

        // Freeze is blocked until every invalid row has an approved exclusion (T07).
        mvc.perform(post("/api/v1/projects/" + projectId + "/manifests")
                        .session(engineer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleId\":\"" + bundleId + "\",\"mappingRevisionId\":\"" + mappingId
                                + "\",\"ruleSetRevisionId\":\"" + ruleSetId(projectId)
                                + "\",\"validationSnapshotId\":\"" + snapshotId + "\"}"))
                .andExpect(status().isConflict());

        int excluded = excludeAllInvalid(reviewer, snapshotId);
        assertEquals(15, excluded);

        UUID manifestId = freeze(engineer, projectId, bundleId, mappingId, snapshotId);

        // Rehearsal: isolated, verified by reconciliation, final target untouched (T09, T10).
        MvcResult rehearsalStart = mvc.perform(post("/api/v1/manifests/" + manifestId + "/rehearsals")
                        .session(engineer).with(csrf()))
                .andExpect(status().isAccepted()).andReturn();
        UUID rehearsalId = UUID.fromString(json(rehearsalStart).get("runId").asText());
        JsonNode rehearsal = awaitRun(engineer, rehearsalId);
        assertEquals("VERIFIED", rehearsal.get("status").asText());
        assertEquals(2985, rehearsal.get("processedRows").asInt());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM target.customers", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM target.orders", Integer.class));
        assertEquals(995, jdbc.queryForObject("SELECT count(*) FROM rehearsal.customers WHERE rehearsal_run_id = ?", Integer.class, rehearsalId));
        assertEquals(1990, jdbc.queryForObject("SELECT count(*) FROM rehearsal.orders WHERE rehearsal_run_id = ?", Integer.class, rehearsalId));

        MvcResult reconResult = mvc.perform(get("/api/v1/runs/" + rehearsalId + "/reconciliation").session(engineer))
                .andExpect(status().isOk()).andReturn();
        JsonNode recon = json(reconResult);
        assertEquals("PASS", recon.get("status").asText());
        JsonNode summary = mapper.readTree(recon.get("summary").asText());
        assertEquals(21_998_650L, summary.get("orders").get("writtenAmountYen").asLong());
        UUID reportId = UUID.fromString(recon.get("reportId").asText());

        // A second rehearsal is isolated from the first (T09).
        MvcResult secondStart = mvc.perform(post("/api/v1/manifests/" + manifestId + "/rehearsals")
                        .session(engineer).with(csrf()))
                .andExpect(status().isAccepted()).andReturn();
        UUID secondRehearsalId = UUID.fromString(json(secondStart).get("runId").asText());
        assertEquals("VERIFIED", awaitRun(engineer, secondRehearsalId).get("status").asText());
        assertEquals(995, jdbc.queryForObject("SELECT count(*) FROM rehearsal.customers WHERE rehearsal_run_id = ?", Integer.class, secondRehearsalId));

        // Approval is blocked before UAT passes (T11).
        MvcResult uatResult = mvc.perform(get("/api/v1/rehearsals/" + rehearsalId + "/uat").session(reviewer))
                .andExpect(status().isOk()).andReturn();
        String uatHash = json(uatResult).get("uatEvidenceHash").asText();
        mvc.perform(post("/api/v1/manifests/" + manifestId + "/approvals")
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(approvalJson(rehearsalId, reportId, uatHash)))
                .andExpect(status().isUnprocessableEntity());

        // Reviewer executes all required UAT cases to PASS.
        for (String caseKey : new String[]{"COUNTS_MATCH", "CUSTOMER_SPOT_CHECK", "ORDER_TOTALS", "EXCLUSIONS_ABSENT", "PROVENANCE_TRACE"}) {
            mvc.perform(post("/api/v1/rehearsals/" + rehearsalId + "/uat-executions")
                            .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"caseKey\":\"" + caseKey + "\",\"result\":\"PASS\",\"actualResult\":\"verified\"}"))
                    .andExpect(status().isCreated());
        }
        uatResult = mvc.perform(get("/api/v1/rehearsals/" + rehearsalId + "/uat").session(reviewer))
                .andExpect(status().isOk()).andReturn();
        uatHash = json(uatResult).get("uatEvidenceHash").asText();

        // The engineer cannot approve (role separation, T12).
        mvc.perform(post("/api/v1/manifests/" + manifestId + "/approvals")
                        .session(engineer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(approvalJson(rehearsalId, reportId, uatHash)))
                .andExpect(status().isForbidden());

        MvcResult approvalResult = mvc.perform(post("/api/v1/manifests/" + manifestId + "/approvals")
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(approvalJson(rehearsalId, reportId, uatHash)))
                .andExpect(status().isCreated()).andReturn();
        UUID approvalId = UUID.fromString(json(approvalResult).get("approvalId").asText());

        // The approver cannot launch the cutover (T12 second check is role+identity based).
        mvc.perform(post("/api/v1/manifests/" + manifestId + "/cutovers")
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"" + approvalId + "\"}"))
                .andExpect(status().isForbidden());

        // Cutover with an idempotency key; replay returns the same run (T15).
        String key = UUID.randomUUID().toString();
        MvcResult cutoverStart = mvc.perform(post("/api/v1/manifests/" + manifestId + "/cutovers")
                        .session(engineer).with(csrf()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"" + approvalId + "\"}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID cutoverId = UUID.fromString(json(cutoverStart).get("runId").asText());
        MvcResult replay = mvc.perform(post("/api/v1/manifests/" + manifestId + "/cutovers")
                        .session(engineer).with(csrf()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"" + approvalId + "\"}"))
                .andExpect(status().isAccepted()).andReturn();
        assertEquals(cutoverId.toString(), json(replay).get("runId").asText());

        JsonNode cutover = awaitRun(engineer, cutoverId);
        assertEquals("VERIFIED", cutover.get("status").asText());

        // Final target holds exactly the accepted dataset (T10, T17).
        assertEquals(995, jdbc.queryForObject("SELECT count(*) FROM target.customers", Integer.class));
        assertEquals(1990, jdbc.queryForObject("SELECT count(*) FROM target.orders", Integer.class));
        assertEquals(995, jdbc.queryForObject("SELECT count(DISTINCT external_customer_id) FROM target.customers", Integer.class));
        assertEquals(1990, jdbc.queryForObject("SELECT count(DISTINCT external_order_id) FROM target.orders", Integer.class));
        assertEquals(21_998_650L, jdbc.queryForObject("SELECT COALESCE(SUM(amount_yen),0) FROM target.orders", Long.class));
        assertEquals("VERIFIED", jdbc.queryForObject(
                "SELECT status FROM control.target_cutover_slot WHERE slot_id = 1", String.class));

        // A further cutover is refused: target no longer empty / slot verified.
        mvc.perform(post("/api/v1/manifests/" + manifestId + "/cutovers")
                        .session(engineer).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"" + approvalId + "\"}"))
                .andExpect(status().isConflict());

        // Evidence report downloads as escaped CSV (T20 surface).
        MvcResult report = mvc.perform(get("/api/v1/runs/" + cutoverId + "/report")
                        .param("format", "csv").session(auditor()))
                .andExpect(status().isOk()).andReturn();
        assertTrue(report.getResponse().getContentAsString().contains("VERIFIED"));
    }

    @Test
    void securityAndUploadGuards() throws Exception {
        // Unauthenticated access is refused (T19).
        mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());

        // Uniform invalid-credential error (with a valid CSRF token, like the SPA).
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"engineer@example.test\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        MockHttpSession engineer = engineer();
        UUID projectId = createProject(engineer);

        // Missing CSRF token on a state-changing call is refused.
        mvc.perform(post("/api/v1/projects").session(engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());

        // A non-member cannot see the project (404, not 403: no existence leak).
        MockHttpSession outsider = reviewer();
        mvc.perform(get("/api/v1/projects/" + projectId).session(outsider))
                .andExpect(status().isNotFound());

        // A reviewer cannot create projects (role check).
        mvc.perform(post("/api/v1/projects").session(reviewer()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"nope\"}"))
                .andExpect(status().isForbidden());

        // Malformed CSV (duplicate headers) fails the upload (T02).
        byte[] bad = "Cust_No,Cust_No\nC1,C2\n".getBytes();
        byte[] okOrders = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("..", "fixtures", "orders_2000.csv"));
        mvc.perform(multipart("/api/v1/projects/" + projectId + "/bundles")
                        .file(new MockMultipartFile("customers", "customers.csv", "text/csv", bad))
                        .file(new MockMultipartFile("orders", "orders.csv", "text/csv", okOrders))
                        .session(engineer).with(csrf()))
                .andExpect(status().isBadRequest());

        // Manifest immutability: freezing a revised plan supersedes the first (T08).
        UUID[] ids = prepareValidated(engineer);
        excludeAllInvalid(reviewer(), ids[3]);
        UUID first = freeze(engineer, ids[0], ids[1], ids[2], ids[3]);
        // A fresh validation snapshot of the same inputs is a new plan.
        MvcResult restarted = mvc.perform(post("/api/v1/projects/" + ids[0] + "/validation-snapshots")
                        .session(engineer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleId\":\"" + ids[1] + "\",\"mappingRevisionId\":\"" + ids[2] + "\"}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID secondSnapshot = UUID.fromString(json(restarted).get("snapshotId").asText());
        awaitSnapshot(engineer, secondSnapshot);
        excludeAllInvalid(reviewer(), secondSnapshot);
        UUID second = freeze(engineer, ids[0], ids[1], ids[2], secondSnapshot);
        assertNotEquals(first, second);
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM control.manifests WHERE id = ?", String.class, first));
        assertEquals("FROZEN", jdbc.queryForObject(
                "SELECT status FROM control.manifests WHERE id = ?", String.class, second));
    }
}
