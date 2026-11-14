package com.migrateflow.identity;

import com.migrateflow.audit.AuditService;
import com.migrateflow.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthenticationManager authManager;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AccessControl access;
    private final AuditService audit;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public AuthController(AuthenticationManager authManager, UserRepository users, PasswordEncoder encoder,
                          AccessControl access, AuditService audit, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.authManager = authManager;
        this.users = users;
        this.encoder = encoder;
        this.access = access;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
    public record CreateUserRequest(@Email @NotBlank String email,
                                    @NotBlank @jakarta.validation.constraints.Size(min = 8, max = 128, message = "Password must be at least 8 characters") String password,
                                    @NotBlank String displayName, @NotBlank String role) {}
    public record MembershipRequest(@jakarta.validation.constraints.NotNull(message = "active is required") Boolean active) {}

    @GetMapping("/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName());
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body, HttpServletRequest req) {
        try {
            Authentication auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(body.email().toLowerCase(), body.password()));
            if (req.getSession(false) != null) {
                req.changeSessionId();
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            HttpSession session = req.getSession(true);
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            AppUser user = users.findByEmail(body.email()).orElseThrow();
            return userView(user);
        } catch (BadCredentialsException e) {
            // Uniform error: never reveal whether the email exists (spec 10.1).
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(Authentication auth) {
        AppUser user = SecurityConfig.requireUser(users, auth);
        return userView(user);
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/admin/users")
    public ResponseEntity<Map<String, Object>> createUser(@Valid @RequestBody CreateUserRequest body, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireRole(actor, "ADMIN");
        if (!List.of("ENGINEER", "REVIEWER", "AUDITOR", "ADMIN").contains(body.role())) {
            throw ApiException.badRequest("INVALID_ROLE", "Unknown role " + body.role());
        }
        if (users.findByEmail(body.email()).isPresent()) {
            throw ApiException.conflict("USER_EXISTS", "A user with this email already exists");
        }
        AppUser created = users.create(body.email(), encoder.encode(body.password()), body.displayName(), body.role());
        audit.record(null, actor.id(), "USER_CREATED", "user", created.id(), Map.of("role", created.role()));
        return ResponseEntity.status(HttpStatus.CREATED).body(userView(created));
    }

    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/projects/{projectId}/members/{userId}")
    public Map<String, Object> setMembership(@PathVariable UUID projectId, @PathVariable UUID userId,
                                             @Valid @RequestBody MembershipRequest body, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireRole(actor, "ADMIN");
        users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        boolean active = body.active();
        jdbc.update("INSERT INTO control.project_memberships (id, project_id, user_id, active) VALUES (?,?,?,?) "
                        + "ON CONFLICT (project_id, user_id) DO UPDATE SET active = EXCLUDED.active",
                UUID.randomUUID(), projectId, userId, active);
        audit.record(projectId, actor.id(), active ? "MEMBERSHIP_GRANTED" : "MEMBERSHIP_REVOKED",
                "project", projectId, Map.of("userId", userId.toString()));
        return Map.of("projectId", projectId.toString(), "userId", userId.toString(), "active", active);
    }

    private Map<String, Object> userView(AppUser user) {
        // MF-A31: Align user project list with effective access control
        String sql = "ADMIN".equals(user.role())
                ? "SELECT p.id, p.name, p.status FROM control.projects p ORDER BY p.created_at"
                : "SELECT p.id, p.name, p.status FROM control.projects p "
                    + "JOIN control.project_memberships m ON m.project_id = p.id AND m.user_id = ? AND m.active = TRUE "
                    + "ORDER BY p.created_at";
        List<Map<String, Object>> projects = "ADMIN".equals(user.role())
                ? jdbc.query(sql, (rs, n) -> Map.<String, Object>of("id", rs.getObject("id", UUID.class).toString(),
                        "name", rs.getString("name"), "status", rs.getString("status")))
                : jdbc.query(sql, (rs, n) -> Map.<String, Object>of("id", rs.getObject("id", UUID.class).toString(),
                        "name", rs.getString("name"), "status", rs.getString("status")), user.id());
        return Map.of("id", user.id().toString(), "email", user.email(), "displayName", user.displayName(),
                "role", user.role(), "projects", projects);
    }
}
