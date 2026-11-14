package com.migrateflow.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static final RowMapper<AppUser> MAPPER = (rs, n) -> new AppUser(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("role"),
            rs.getBoolean("enabled"));

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.query("SELECT * FROM control.users WHERE email = ?",
                MAPPER, email.toLowerCase()).stream().findFirst();
    }

    public Optional<AppUser> findById(UUID id) {
        return jdbc.query("SELECT * FROM control.users WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<AppUser> findAll() {
        return jdbc.query("SELECT * FROM control.users ORDER BY email", MAPPER);
    }

    public AppUser create(String email, String passwordHash, String displayName, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO control.users (id, email, password_hash, display_name, role) VALUES (?,?,?,?,?)",
                id, email.toLowerCase(), passwordHash, displayName, role);
        return findById(id).orElseThrow();
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM control.users", Long.class);
        return n == null ? 0 : n;
    }
}
