package com.migrateflow.identity;

import java.util.UUID;

public record AppUser(UUID id, String email, String passwordHash, String displayName, String role, boolean enabled) {
    public boolean hasRole(String r) { return role.equals(r); }
    public boolean isAdmin() { return "ADMIN".equals(role); }
}
