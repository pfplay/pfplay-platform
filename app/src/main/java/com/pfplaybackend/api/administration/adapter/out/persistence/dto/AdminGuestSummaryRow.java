package com.pfplaybackend.api.administration.adapter.out.persistence.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;

import java.time.LocalDateTime;

/**
 * AdminGuestQueryRepository.search 의 Projection — guest + linked user_account 합본.
 * Service 가 {@code withdrawn} flag 를 {@code withdrawnAt != null} 로 derive 해서
 * {@code AdminGuestSummaryResponse} 로 매핑한다.
 */
public record AdminGuestSummaryRow(
        Long guestId,
        Long userAccountId,
        String email,
        ProviderType providerType,
        String nickname,
        String agent,
        boolean isProfileUpdated,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime withdrawnAt
) {}
