package com.migrateflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Showcase spec acceptance section B (guest security): the public API is
 * anonymous GET-only, answers NOT_READY honestly before any bootstrap,
 * never opens the private workbench API, and the internal register
 * endpoint stays behind session + token even when bootstrap is enabled.
 */
@TestPropertySource(properties = {
        "migrateflow.showcase.bootstrap-enabled=true",
        "migrateflow.showcase.bootstrap-token=test-bootstrap-token"
})
class ShowcaseSecurityTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void anonymousOverviewAnswersNotReadyWithoutFabricatedData() throws Exception {
        mvc.perform(get("/api/v1/showcase/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupState").value("NOT_READY"))
                .andExpect(jsonPath("$.dataClassification").value("SYNTHETIC"))
                .andExpect(jsonPath("$.validation").doesNotExist());
    }

    @Test
    void anonymousDataEndpointsAnswer503BeforeBootstrap() throws Exception {
        mvc.perform(get("/api/v1/showcase/workflow")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/showcase/uat")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/showcase/records?kind=customers")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void anonymousMutationsAreDeniedEverywhere() throws Exception {
        mvc.perform(post("/api/v1/showcase/overview").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/showcase/overview").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"description\":\"y\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
    }

    @Test
    void registerRequiresAdminSessionAndBootstrapToken() throws Exception {
        String body = "{\"slug\":\"hinata-trading\",\"setupState\":\"PREPARING\",\"setupStep\":\"project\","
                + "\"bootstrapVersion\":\"test\"}";
        // No session at all.
        mvc.perform(post("/api/v1/internal/showcase/register").with(csrf())
                        .header("X-Showcase-Bootstrap-Token", "test-bootstrap-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        // Session but no token.
        mvc.perform(post("/api/v1/internal/showcase/register").session(engineer()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        // Session but wrong token.
        mvc.perform(post("/api/v1/internal/showcase/register").session(engineer()).with(csrf())
                        .header("X-Showcase-Bootstrap-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        // A non-admin session is not a bootstrap principal, even with the token.
        mvc.perform(post("/api/v1/internal/showcase/register").session(engineer()).with(csrf())
                        .header("X-Showcase-Bootstrap-Token", "test-bootstrap-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        // ADMIN session + token works (progress registration, not READY).
        mvc.perform(post("/api/v1/internal/showcase/register").session(admin()).with(csrf())
                        .header("X-Showcase-Bootstrap-Token", "test-bootstrap-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupState").value("PREPARING"));
    }

    @Test
    void detailEndpointsRequireAReadyScenario() throws Exception {
        // A PREPARING scenario exposes only its overview state (S-15).
        String body = "{\"slug\":\"hinata-trading\",\"setupState\":\"PREPARING\",\"setupStep\":\"intake\","
                + "\"bootstrapVersion\":\"test\"}";
        mvc.perform(post("/api/v1/internal/showcase/register").session(admin()).with(csrf())
                        .header("X-Showcase-Bootstrap-Token", "test-bootstrap-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/showcase/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupState").value("PREPARING"));
        mvc.perform(get("/api/v1/showcase/workflow")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/showcase/records")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/showcase/uat")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/showcase/evidence")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void disabledAccountSessionStopsWorking() throws Exception {
        // Review S-20: disabling an account must cut off its live session,
        // not just future logins.
        MockHttpSession session = auditor();
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
        jdbc.update("UPDATE control.users SET enabled = FALSE WHERE email = 'auditor@example.test'");
        try {
            mvc.perform(get("/api/v1/auth/me").session(session))
                    .andExpect(status().isUnauthorized());
        } finally {
            // Users are seeded once per context, not reset between tests:
            // restore the flag so other test classes keep their auditor.
            jdbc.update("UPDATE control.users SET enabled = TRUE WHERE email = 'auditor@example.test'");
        }
    }
}
