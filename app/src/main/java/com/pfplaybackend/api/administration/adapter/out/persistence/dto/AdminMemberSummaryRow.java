package com.pfplaybackend.api.administration.adapter.out.persistence.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.enums.AuthorityTier;

import java.time.LocalDateTime;

/**
 * AdminMemberQueryRepository.search Projection — member + linked user_account 합본.
 *
 * <p>Service에서 {@code withdrawn} flag를 {@code withdrawnAt != null}로 derive해
 * {@code AdminMemberSummaryResponse}로 매핑한다.
 */
public record AdminMemberSummaryRow(
        Long memberId,
        Long userAccountId,
        String email,
        ProviderType providerType,
        String nickname,
        AuthorityTier authorityTier,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime withdrawnAt
) {}
