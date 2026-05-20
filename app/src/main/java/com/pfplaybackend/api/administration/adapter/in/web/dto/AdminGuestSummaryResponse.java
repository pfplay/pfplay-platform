package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;

import java.time.LocalDateTime;

/**
 * GET /admin/guests 응답 row payload.
 *
 * <p>Service layer 가 {@link com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestSummaryRow}
 * 를 받아 {@code withdrawn} flag 를 {@code withdrawnAt != null} 로 derive 해서 채운다
 * (Member A-1 패턴과 동일 — Spec §5.3).
 */
public record AdminGuestSummaryResponse(
        Long guestId,
        Long userAccountId,
        String email,
        ProviderType providerType,
        String nickname,
        String agent,
        boolean isProfileUpdated,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        boolean withdrawn,
        LocalDateTime withdrawnAt
) {}
