package com.pfplaybackend.api.administration.adapter.out.persistence.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;

import java.time.LocalDateTime;

/**
 * AdminGuestQueryRepository.findDetail 의 Projection.
 * recentActivityLog 는 service 가 UserActivityLogRepository 호출로 별도 합성.
 */
public record AdminGuestDetailRow(
        Long guestId,
        Long userAccountId,
        String email,
        ProviderType providerType,
        LocalDateTime lastLoginAt,
        LocalDateTime withdrawnAt,
        String nickname,
        String introduction,
        String agent,
        boolean isProfileUpdated,
        LocalDateTime createdAt
) {}
