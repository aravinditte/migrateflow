package com.migrateflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Showcase spec acceptance section A (authenticity): a real end-to-end
 * journey (the same HTTP operations the bootstrap script performs) can be
 * registered as the public showcase; the anonymous overview then serves
 * the genuine persisted figures with lineage integrity OK; re-registering
 * is idempotent and writes no additional target rows; a READY claim with
 * broken lineage is refused and recorded FAILED.
 */
@TestPropertySource(properties = {
        "migrateflow.showcase.bootstrap-enabled=true",
        "migrateflow.showcase.bootstrap-token=test-bootstrap-token"
})
class ShowcaseBootstrapTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private static final String TOKEN_HEADER = "X-Showcase-Bootstrap-Token";
    private static final String TOKEN = "test-bootstrap-token";

    private MvcResult register(String body, int expectedStatus) throws Exception {
        var request = post("/api/v1/internal/showcase/register")
                .session(admin()).with(csrf())
                .header(TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }

    private String registerBody(String slug, UUID projectId, Map<String, Object> manifest,
                                UUID rehearsalId, UUID approvalId, UUID cutoverId, String state) {
        return "{"
                + "\"slug\":\"" + slug + "\","
                + "\"projectId\":\"" + projectId + "\","
                + "\"bundleId\":\"" + manifest.get("bundle_id") + "\","
                + "\"mappingRevisionId\":\"" + manifest.get("mapping_revision_id") + "\","
                + "\"ruleSetRevisionId\":\"" + manifest.get("rule_set_revision_id") + "\","
                + "\"snapshotId\":\"" + manifest.get("snapshot_id") + "\","
                + "\"manifestId\":\"" + manifest.get("id") + "\","
                + "\"rehearsalRunId\":\"" + rehearsalId + "\","
                + "\"approvalId\":\"" + approvalId + "\","
                + "\"cutoverRunId\":\"" + cutoverId + "\","
                + "\"setupState\":\"" + state + "\",\"setupStep\":\"publish\","
                + "\"bootstrapVersion\":\"test\"}";
    }

    @Test
    void realJourneyRegistersReadyOverviewServesGenuineFiguresAndReregistrationIsIdempotent() throws Exception {
        var engineer = engineer();
        var reviewer = reviewer();
        UUID[] journey = prepareApproved(engineer, reviewer);
        UUID projectId = journey[0];
        UUID manifestId = journey[1];
        UUID rehearsalId = journey[2];
        UUID approvalId = journey[4];

        // Real cutover over HTTP, exactly as the bootstrap script does.
        MvcResult cutoverStart = mvc.perform(post("/api/v1/manifests/" + manifestId + "/cutovers")
                        .session(engineer).with(csrf())
                        .header("Idempotency-Key", "showcase-test-v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"" + approvalId + "\"}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID cutoverId = UUID.fromString(json(cutoverStart).get("runId").asText());
        assertEquals("VERIFIED", awaitRun(engineer, cutoverId).get("status").asText());

        Map<String, Object> manifest = jdbc.queryForMap(
                "SELECT id, bundle_id, mapping_revision_id, rule_set_revision_id, snapshot_id "
                        + "FROM control.manifests WHERE id = ?", manifestId);
        String body = registerBody("hinata-trading", projectId, manifest, rehearsalId, approvalId, cutoverId, "READY");

        register(body, 200);

        // Anonymous public overview: genuine figures, lineage intact.
        MvcResult overview = mvc.perform(get("/api/v1/showcase/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupState").value("READY"))
                .andExpect(jsonPath("$.integrity").value("OK"))
                .andExpect(jsonPath("$.validation.acceptedCustomers").value(995))
                .andExpect(jsonPath("$.validation.acceptedOrders").value(1990))
                .andExpect(jsonPath("$.money.writtenTotalYen").value(21998650))
                .andExpect(jsonPath("$.cutover.status").value("VERIFIED"))
                .andReturn();
        JsonNode ov = json(overview);
        assertEquals(64, ov.get("verification").get("reportEvidenceHash").asText().length());

        MvcResult workflow = mvc.perform(get("/api/v1/showcase/workflow"))
                .andExpect(status().isOk()).andReturn();
        JsonNode stages = json(workflow).get("stages");
        assertEquals(11, stages.size());
        for (JsonNode stage : stages) {
            assertEquals("COMPLETED", stage.get("status").asText(),
                    "stage " + stage.get("key").asText() + " must be COMPLETED");
        }

        mvc.perform(get("/api/v1/showcase/records?kind=customers&size=3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].sourceFile").value("customers_1000.csv"));

        // Idempotent re-registration: no new rows anywhere.
        register(body, 200);
        assertEquals(995, jdbc.queryForObject("SELECT count(*) FROM target.customers", Integer.class).intValue());
        assertEquals(1990, jdbc.queryForObject("SELECT count(*) FROM target.orders", Integer.class).intValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM control.showcase_scenarios WHERE slug = 'hinata-trading'", Integer.class).intValue());

        // Broken lineage (rehearsal pointer aimed at the cutover run) is
        // refused with 422 and persisted as FAILED, never READY.
        String bogus = registerBody("bogus-scenario", projectId, manifest, cutoverId, approvalId, cutoverId, "READY");
        MvcResult refused = register(bogus, 422);
        JsonNode problems = json(refused).get("problems");
        org.junit.jupiter.api.Assertions.assertTrue(problems != null && problems.size() > 0,
                "lineage problems must be reported");
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT setup_state FROM control.showcase_scenarios WHERE slug = 'bogus-scenario'", String.class));
        // The genuine scenario is untouched.
        mvc.perform(get("/api/v1/showcase/overview"))
                .andExpect(jsonPath("$.setupState").value("READY"));
    }
}
