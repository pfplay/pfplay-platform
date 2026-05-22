package com.pfplaybackend.api.administration.adapter.in.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /admin/guests/{guestId} response payload.
 *
 * <p>Composed by AdminGuestQueryService from two sources:
 *  - AdminGuestQueryRepository.findDetail — guest + linked user_account row.
 *  - UserActivityLogRepository.findTop30... — recent 30 activity log rows (Member 와 재사용).
 *
 * <p>{@code withdrawn} flag 는 Member detail 과 동일하게 root level 에 노출 (list/detail 일관성).
 */
public record AdminGuestDetailResponse(
        Long guestId,
        UserAccountSummary userAccount,
        GuestProfileSummary profile,
        String agent,
        boolean isProfileUpdated,
        LocalDateTime createdAt,
        boolean withdrawn,
        LocalDateTime withdrawnAt,
        List<RecentActivityLogItem> recentActivityLog
) {}
