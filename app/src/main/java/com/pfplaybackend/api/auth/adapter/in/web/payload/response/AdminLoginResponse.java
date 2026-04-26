package com.pfplaybackend.api.auth.adapter.in.web.payload.response;

import com.pfplaybackend.api.administration.domain.value.AdminRole;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record AdminLoginResponse(
        String tokenType,
        long expiresIn,
        LocalDateTime issuedAt,
        AdminRole role
) {}
