package com.pfplaybackend.api.administration.adapter.out.persistence.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.enums.AuthorityTier;

import java.time.LocalDateTime;

/**
 * AdminMemberQueryRepository.findDetail Projection — member + userAccount 합본.
 * service에서 AdminMemberDetailResponse + recentActivityLog로 합성.
 */
public record AdminMemberDetailRow(
        Long memberId,
        Long userAccountId,
        String email,
        ProviderType providerType,
        LocalDateTime lastLoginAt,
        LocalDateTime withdrawnAt,
        String nickname,
        String introduction,
        AuthorityTier authorityTier,
        LocalDateTime createdAt
) {}
