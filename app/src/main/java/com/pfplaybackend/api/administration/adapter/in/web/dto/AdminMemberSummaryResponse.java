package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.enums.AuthorityTier;

import java.time.LocalDateTime;

/**
 * A-1 GET /admin/members 응답 row payload.
 *
 * <p>Service layer는 {@link AdminMemberSummaryRow}(persistence projection)를 받아
 * {@code withdrawn} boolean을 {@code withdrawnAt != null}로 derive해서 채운다 — 프론트
 * convenience flag(spec §11 #11). {@code withdrawnAt} 자체도 함께 노출한다.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §3.1, §11 #11
 */
public record AdminMemberSummaryResponse(
        Long memberId,
        Long userAccountId,
        String email,
        ProviderType providerType,
        String nickname,
        AuthorityTier authorityTier,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        boolean withdrawn,
        LocalDateTime withdrawnAt
) {}
