package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;

import java.time.LocalDateTime;

/**
 * A-2 detail response — userAccount sub-payload.
 * {@code withdrawnAt} is null for live accounts.
 */
public record UserAccountSummary(
        Long userAccountId,
        String email,
        ProviderType providerType,
        LocalDateTime lastLoginAt,
        LocalDateTime withdrawnAt
) {}
