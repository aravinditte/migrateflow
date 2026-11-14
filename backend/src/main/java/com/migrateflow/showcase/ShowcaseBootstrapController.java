package com.migrateflow.showcase;

import com.migrateflow.common.ApiException;
import com.migrateflow.identity.SecurityConfig;
import com.migrateflow.identity.UserRepository;
import com.migrateflow.showcase.ShowcaseRegistryService.Scenario;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal bootstrap registry endpoint (spec 4.1/4.2, 5.1). This is NOT
 * part of the public showcase API: it requires an authenticated session
 * (the bootstrap script signs in as the demo ENGINEER), the
 * MIGRATEFLOW_SHOWCASE_BOOTSTRAP_ENABLED=true flag, and a shared
 * X-Showcase-Bootstrap-Token header. It only records scenario pointers
 * and progress; all business artifacts are produced by the normal
 * authenticated workflow endpoints. A READY write is refused (and the
 * registry marked FAILED) unless lineage verification passes.
 */
@RestController
@RequestMapping("/api/v1/internal/showcase")
public class ShowcaseBootstrapController {

    public record RegisterRequest(
            String slug,
            UUID projectId, UUID bundleId, UUID mappingRevisionId, UUID ruleSetRevisionId,
            UUID snapshotId, UUID manifestId, UUID rehearsalRunId, UUID approvalId, UUID cutoverRunId,
            String setupState, String setupStep, String bootstrapVersion, String failureMessage) {}

    private final ShowcaseRegistryService registry;
    private final ShowcaseQueryService query;
    private final UserRepository users;
    private final boolean enabled;
    private final String token;

    public ShowcaseBootstrapController(ShowcaseRegistryService registry, ShowcaseQueryService query,
                                       UserRepository users,
                                       @Value("${migrateflow.showcase.bootstrap-enabled:false}") boolean enabled,
                                       @Value("${migrateflow.showcase.bootstrap-token:}") String token) {
        this.registry = registry;
        this.query = query;
        this.users = users;
        this.enabled = enabled;
        this.token = token;
    }

    private void guard(Authentication auth, String providedToken) {
        if (!enabled) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Showcase bootstrap is not enabled");
        }
        // The bootstrap principal is the ADMIN service identity (review
        // S-24): an arbitrary authenticated account plus the token is not
        // enough to register scenario pointers.
        com.migrateflow.identity.AppUser actor = SecurityConfig.requireUser(users, auth);
        if (!"ADMIN".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BOOTSTRAP_ADMIN_REQUIRED",
                    "Showcase registration requires the ADMIN bootstrap principal");
        }
        if (token == null || token.isBlank() || !token.equals(providedToken)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BOOTSTRAP_TOKEN_INVALID",
                    "A valid showcase bootstrap token is required");
        }
    }

    @GetMapping("/scenario")
    public Scenario scenario(Authentication auth,
                             @RequestHeader(value = "X-Showcase-Bootstrap-Token", required = false) String providedToken,
                             @RequestParam String slug) {
        guard(auth, providedToken);
        return registry.findBySlug(slug).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "SHOWCASE_NOT_FOUND", "No showcase scenario is registered for slug " + slug));
    }

    @PostMapping("/register")
    public Object register(Authentication auth,
                           @RequestHeader(value = "X-Showcase-Bootstrap-Token", required = false) String providedToken,
                           @RequestBody RegisterRequest req) {
        guard(auth, providedToken);
        Scenario candidate = new Scenario(null, req.slug(),
                req.projectId(), req.bundleId(), req.mappingRevisionId(), req.ruleSetRevisionId(),
                req.snapshotId(), req.manifestId(), req.rehearsalRunId(), req.approvalId(), req.cutoverRunId(),
                req.setupState(), req.setupStep(), req.bootstrapVersion(), req.failureMessage(), null, null);
        if ("READY".equals(req.setupState())) {
            List<String> problems = query.verifyLineage(candidate);
            if (!problems.isEmpty()) {
                registry.upsert(new Scenario(null, req.slug(),
                        req.projectId(), req.bundleId(), req.mappingRevisionId(), req.ruleSetRevisionId(),
                        req.snapshotId(), req.manifestId(), req.rehearsalRunId(), req.approvalId(), req.cutoverRunId(),
                        "FAILED", req.setupStep(), req.bootstrapVersion(),
                        "Lineage verification failed: " + String.join("; ", problems), null, null));
                return new org.springframework.http.ResponseEntity<>(
                        Map.of("status", 422, "code", "LINEAGE_INVALID", "problems", problems),
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
        return registry.upsert(candidate);
    }
}
