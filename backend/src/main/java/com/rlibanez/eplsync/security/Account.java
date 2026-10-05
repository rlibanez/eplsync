package com.rlibanez.eplsync.security;

import java.io.Serializable;
import java.time.Instant;
import java.util.Set;

/** Session projection: never contains a password hash. */
public record Account(String id, String username, String email, String role, String status,
        boolean mustChangePassword, Instant temporaryExpiresAt, long securityVersion,
        Set<Permission> permissions, Instant authenticatedAt) implements Serializable {
    public Account authenticatedNow() {
        return new Account(id, username, email, role, status, mustChangePassword, temporaryExpiresAt,
                securityVersion, permissions, Instant.now());
    }
}
