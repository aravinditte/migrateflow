package com.migrateflow.seed;

import com.migrateflow.identity.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Dev-only demo seed (spec 16): creates the four synthetic role accounts
 * only when MIGRATEFLOW_DEMO_SEED_ENABLED=true and no users exist.
 * Passwords come from the environment; the defaults exist solely for a
 * local demo and are printed nowhere. Never enable outside local demos.
 */
@Component
public class DemoSeedRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Environment env;
    private final boolean enabled;

    public DemoSeedRunner(UserRepository users, PasswordEncoder encoder, Environment env,
                          @Value("${migrateflow.demo-seed-enabled}") boolean enabled) {
        this.users = users;
        this.encoder = encoder;
        this.env = env;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        boolean isPublicOrProd = env.matchesProfiles("production", "prod", "public")
                || "production".equalsIgnoreCase(env.getProperty("MIGRATEFLOW_ENV"))
                || "public".equalsIgnoreCase(env.getProperty("MIGRATEFLOW_ENV"));
        if (isPublicOrProd) {
            throw new IllegalStateException("FATAL: MIGRATEFLOW_DEMO_SEED_ENABLED must not be enabled under production/public profiles.");
        }
        if (users.count() > 0) return;
        seed("engineer@example.test", "MIGRATEFLOW_ENGINEER_PASSWORD", "engineer-dev-pw", "Demo Engineer", "ENGINEER");
        seed("reviewer@example.test", "MIGRATEFLOW_REVIEWER_PASSWORD", "reviewer-dev-pw", "Demo Reviewer", "REVIEWER");
        seed("auditor@example.test", "MIGRATEFLOW_AUDITOR_PASSWORD", "auditor-dev-pw", "Demo Auditor", "AUDITOR");
        seed("admin@example.test", "MIGRATEFLOW_ADMIN_PASSWORD", "admin-dev-pw", "Demo Admin", "ADMIN");
        log.warn("Demo seed created 4 synthetic users (dev-only). Disable MIGRATEFLOW_DEMO_SEED_ENABLED outside local demos.");
    }

    private void seed(String email, String envKey, String devDefault, String displayName, String role) {
        String password = env.getProperty(envKey, devDefault);
        users.create(email, encoder.encode(password), displayName, role);
    }
}
