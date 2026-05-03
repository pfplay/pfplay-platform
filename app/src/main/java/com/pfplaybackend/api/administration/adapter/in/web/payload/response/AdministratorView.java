package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import com.pfplaybackend.api.administration.domain.value.AdminRole;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record AdministratorView(
        Long administratorId,
        AdminRole role,
        LocalDateTime grantedAt,
        Long grantedByAdministratorId,
        LocalDateTime revokedAt,
        Long userAccountId,
        String email,
        LocalDateTime lastLoginAt,
        boolean mustChangePassword,
        Long memberId,
        String nickname
) {}
