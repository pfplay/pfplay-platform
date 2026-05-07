package com.pfplaybackend.api.common.config.security.jwt.dto;

import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.enums.AuthorityTier;

import java.util.List;
import java.util.Objects;

/**
 * Claims request used by JwtService to mint either an AdminAccessToken
 * or a SharedSessionToken. The {@code subject} carries the userAccountId
 * (set on the JWT subject claim, not as a custom claim).
 *
 * @param subject       userAccountId as a string (JWT {@code sub} claim)
 * @param email         user email (custom {@code email} claim)
 * @param accessLevels  one or more granted authorities (custom {@code access_level} claim, JSON array)
 * @param authorityTier optional Member tier (Amplitude integration). Null when no Member exists.
 */
public record TokenClaimsRequest(
        String subject,
        String email,
        List<AccessLevel> accessLevels,
        AuthorityTier authorityTier
) {
    public TokenClaimsRequest {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(accessLevels, "accessLevels must not be null");
        if (accessLevels.isEmpty()) {
            throw new IllegalArgumentException("accessLevels must not be empty");
        }
        accessLevels = List.copyOf(accessLevels);
    }
}
