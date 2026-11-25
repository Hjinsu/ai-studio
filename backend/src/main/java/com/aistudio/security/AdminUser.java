package com.aistudio.security;

import java.time.Instant;

/** admin_user 행. password_hash는 응답·로그에 내보내지 않는다. */
public record AdminUser(Long id, String login, String passwordHash, int credentialVersion, String role, boolean enabled,
        Instant passwordChangedAt, Instant lastLoginAt, long version, Instant createdAt, Instant updatedAt) {

    @Override
    public String toString() {
        return "AdminUser[id=" + id + ", login=" + login + ", credentialVersion=" + credentialVersion + ", enabled="
                + enabled + "]";
    }
}
