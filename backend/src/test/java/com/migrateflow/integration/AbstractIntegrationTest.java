package com.migrateflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for PostgreSQL-backed integration tests. Requires a reachable
 * PostgreSQL (default jdbc:postgresql://localhost:5432/migrateflow_test,
 * override with MIGRATEFLOW_TEST_DATASOURCE_URL). In CI a postgres service
 * provides it. All non-user state is truncated between tests; the seeded
 * demo users are kept.
 */
@SpringBootTest(properties = {
        "migrateflow.demo-seed-enabled=true",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres"
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    protected final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault(
                "MIGRATEFLOW_TEST_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/migrateflow_test"));
        registry.add("migrateflow.upload-dir", () -> {
            try {
                return Files.createTempDirectory("migrateflow-test-uploads").toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE control.showcase_scenarios, control.audit_events, control.approvals, control.defects, control.uat_executions, "
                + "control.uat_cases, control.reconciliation_reports, control.applied_rows, control.runs, "
                + "control.manifests, control.exclusion_decisions, control.validation_issues, control.validation_snapshots, "
                + "control.rule_set_revisions, control.mapping_revisions, control.source_files, control.dataset_bundles, "
                + "control.project_memberships, control.projects, stage.customer_input_rows, stage.order_input_rows, "
                + "stage.customer_validated_rows, stage.order_validated_rows, rehearsal.customers, rehearsal.orders, "
                + "target.customers, target.orders, control.batch_job_execution_params, control.batch_job_execution_context, "
                + "control.batch_step_execution_context, control.batch_step_execution, control.batch_job_execution, "
                + "control.batch_job_instance RESTART IDENTITY CASCADE");
        // TRUNCATE ... CASCADE above also empties target_cutover_slot (it
        // references control.runs), so the singleton row must be restored
        // explicitly - an UPDATE alone would touch zero rows.
        jdbc.update("INSERT INTO control.target_cutover_slot (slot_id, status, active_run_id, verified_run_id) "
                + "VALUES (1, 'FREE', NULL, NULL) "
                + "ON CONFLICT (slot_id) DO UPDATE SET status = 'FREE', active_run_id = NULL, verified_run_id = NULL, updated_at = now()");
    }

    protected MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession();
    }

    protected MockHttpSession engineer() throws Exception { return login("engineer@example.test", "engineer-dev-pw"); }
    protected MockHttpSession reviewer() throws Exception { return login("reviewer@example.test", "reviewer-dev-pw"); }
    protected MockHttpSession auditor() throws Exception { return login("auditor@example.test", "auditor-dev-pw"); }
    protected MockHttpSession admin() throws Exception { return login("admin@example.test", "admin-dev-pw"); }

    protected UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM control.users WHERE email = ?", UUID.class, email);
    }

    protected void grantMembership(UUID projectId, String email) {
        jdbc.update("INSERT INTO control.project_memberships (id, project_id, user_id, active) VALUES (?,?,?,TRUE) "
                + "ON CONFLICT (project_id, user_id) DO UPDATE SET active = TRUE",
                UUID.randomUUID(), projectId, userId(email));
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    /** Creates a project as the engineer and returns its id. */
    protected UUID createProject(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/projects").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hinata migration\",\"description\":\"test\",\"windowNote\":\"window\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(result).get("id").asText());
    }

    /** Uploads the checked-in fixtures and returns the bundle id. */
    protected UUID uploadFixtures(MockHttpSession session, UUID projectId) throws Exception {
        byte[] customers = Files.readAllBytes(Path.of("..", "fixtures", "customers_1000.csv"));
        byte[] orders = Files.readAllBytes(Path.of("..", "fixtures", "orders_2000.csv"));
        MvcResult result = mvc.perform(multipart("/api/v1/projects/" + projectId + "/bundles")
                        .file(new MockMultipartFile("customers", "customers_1000.csv", "text/csv", customers))
                        .file(new MockMultipartFile("orders", "orders_2000.csv", "text/csv", orders))
                        .param("exportedAt", "2025-03-01T00:00:00Z")
                        .session(session).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(result).get("bundleId").asText());
    }

    protected UUID publishDefaultMapping(MockHttpSession session, UUID projectId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/projects/" + projectId + "/mapping-revisions")
                        .session(session).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = json(result);
        return UUID.fromString(node.get("revisionId").asText());
    }

    protected UUID ruleSetId(UUID projectId) {
        return jdbc.queryForObject("SELECT id FROM control.rule_set_revisions WHERE project_id = ? ORDER BY revision_no LIMIT 1",
                UUID.class, projectId);
    }

    protected JsonNode awaitSnapshot(MockHttpSession session, UUID snapshotId) throws Exception {
        for (int i = 0; i < 120; i++) {
            MvcResult result = mvc.perform(get("/api/v1/validation-snapshots/" + snapshotId).session(session))
                    .andExpect(status().isOk()).andReturn();
            JsonNode node = json(result);
            String status = node.get("status").asText();
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) return node;
            Thread.sleep(500);
        }
        throw new AssertionError("Validation snapshot did not complete in time");
    }

    protected JsonNode awaitRun(MockHttpSession session, UUID runId) throws Exception {
        for (int i = 0; i < 240; i++) {
            MvcResult result = mvc.perform(get("/api/v1/runs/" + runId).session(session))
                    .andExpect(status().isOk()).andReturn();
            JsonNode node = json(result);
            String status = node.get("status").asText();
            if ("VERIFIED".equals(status) || "VERIFICATION_FAILED".equals(status)
                    || "FAILED_RESTARTABLE".equals(status) || "FAILED_TERMINAL".equals(status)) {
                return node;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Run did not reach a terminal state in time");
    }

    /** Full happy-path preparation: project + fixtures + mapping + validation. Returns [projectId, bundleId, mappingId, snapshotId]. */
    protected UUID[] prepareValidated(MockHttpSession engineer) throws Exception {
        UUID projectId = createProject(engineer);
        grantMembership(projectId, "reviewer@example.test");
        grantMembership(projectId, "auditor@example.test");
        UUID bundleId = uploadFixtures(engineer, projectId);
        UUID mappingId = publishDefaultMapping(engineer, projectId);
        MvcResult started = mvc.perform(post("/api/v1/projects/" + projectId + "/validation-snapshots")
                        .session(engineer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleId\":\"" + bundleId + "\",\"mappingRevisionId\":\"" + mappingId + "\"}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID snapshotId = UUID.fromString(json(started).get("snapshotId").asText());
        awaitSnapshot(engineer, snapshotId);
        return new UUID[]{projectId, bundleId, mappingId, snapshotId};
    }

    /** Reviewer approves exclusions for every invalid row of the snapshot. Returns the invalid-row count. */
    protected int excludeAllInvalid(MockHttpSession reviewer, UUID snapshotId) throws Exception {
        var rows = jdbc.queryForList(
                "SELECT kind, source_row_id FROM ("
                        + " SELECT 'CUSTOMERS' AS kind, input_row_id AS source_row_id FROM stage.customer_validated_rows WHERE snapshot_id = ? AND is_valid = FALSE"
                        + " UNION ALL"
                        + " SELECT 'ORDERS', input_row_id FROM stage.order_validated_rows WHERE snapshot_id = ? AND is_valid = FALSE"
                        + ") t", snapshotId, snapshotId);
        for (var row : rows) {
            mvc.perform(post("/api/v1/validation-snapshots/" + snapshotId + "/exclusions")
                            .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dataset\":\"" + row.get("kind") + "\",\"sourceRowId\":\"" + row.get("source_row_id")
                                    + "\",\"decision\":\"EXCLUDE\",\"reason\":\"Confirmed against source export\"}"))
                    .andExpect(status().isCreated());
        }
        return rows.size();
    }

    protected static final String[] UAT_CASE_KEYS = {
            "COUNTS_MATCH", "CUSTOMER_SPOT_CHECK", "ORDER_TOTALS", "EXCLUSIONS_ABSENT", "PROVENANCE_TRACE"};

    protected String approvalJson(UUID rehearsalId, UUID reportId, String uatHash) {
        return "{\"decision\":\"APPROVE\",\"rehearsalRunId\":\"" + rehearsalId
                + "\",\"reconciliationReportId\":\"" + reportId + "\",\"uatEvidenceHash\":\"" + uatHash
                + "\",\"comment\":\"verified\"}";
    }

    protected UUID launchRehearsal(MockHttpSession engineer, UUID manifestId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/manifests/" + manifestId + "/rehearsals")
                        .session(engineer).with(csrf()))
                .andExpect(status().isAccepted()).andReturn();
        return UUID.fromString(json(result).get("runId").asText());
    }

    protected void passAllUat(MockHttpSession reviewer, UUID rehearsalId) throws Exception {
        for (String caseKey : UAT_CASE_KEYS) {
            mvc.perform(post("/api/v1/rehearsals/" + rehearsalId + "/uat-executions")
                            .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"caseKey\":\"" + caseKey + "\",\"result\":\"PASS\",\"actualResult\":\"verified\"}"))
                    .andExpect(status().isCreated());
        }
    }

    protected String currentUatHash(MockHttpSession session, UUID rehearsalId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/rehearsals/" + rehearsalId + "/uat").session(session))
                .andExpect(status().isOk()).andReturn();
        return json(result).get("uatEvidenceHash").asText();
    }

    protected UUID reconciliationReportId(MockHttpSession session, UUID runId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/runs/" + runId + "/reconciliation").session(session))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(json(result).get("reportId").asText());
    }

    /**
     * Full journey to a signed approval.
     * Returns [projectId, manifestId, rehearsalRunId, reportId, approvalId].
     */
    protected UUID[] prepareApproved(MockHttpSession engineer, MockHttpSession reviewer) throws Exception {
        UUID[] ids = prepareValidated(engineer);
        excludeAllInvalid(reviewer, ids[3]);
        UUID manifestId = freeze(engineer, ids[0], ids[1], ids[2], ids[3]);
        UUID rehearsalId = launchRehearsal(engineer, manifestId);
        awaitRun(engineer, rehearsalId);
        UUID reportId = reconciliationReportId(engineer, rehearsalId);
        passAllUat(reviewer, rehearsalId);
        String uatHash = currentUatHash(reviewer, rehearsalId);
        MvcResult approval = mvc.perform(post("/api/v1/manifests/" + manifestId + "/approvals")
                        .session(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(approvalJson(rehearsalId, reportId, uatHash)))
                .andExpect(status().isCreated()).andReturn();
        UUID approvalId = UUID.fromString(json(approval).get("approvalId").asText());
        return new UUID[]{ids[0], manifestId, rehearsalId, reportId, approvalId};
    }

    protected UUID freeze(MockHttpSession engineer, UUID projectId, UUID bundleId, UUID mappingId, UUID snapshotId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/projects/" + projectId + "/manifests")
                        .session(engineer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleId\":\"" + bundleId + "\",\"mappingRevisionId\":\"" + mappingId
                                + "\",\"ruleSetRevisionId\":\"" + ruleSetId(projectId)
                                + "\",\"validationSnapshotId\":\"" + snapshotId + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(result).get("manifestId").asText());
    }
}
