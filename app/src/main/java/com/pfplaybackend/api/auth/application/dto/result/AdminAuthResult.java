package com.pfplaybackend.api.auth.application.dto.result;

import com.pfplaybackend.api.administration.domain.value.AdminRole;

import java.time.LocalDateTime;

public record AdminAuthResult(
        String adminAccessToken,
        String sharedSessionToken,
        AdminRole role,
        long adminAccessTokenTtlMs,
        long sharedSessionTokenTtlMs,
        LocalDateTime issuedAt,
        boolean mustChangePassword
) {}
