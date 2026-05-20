package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestListQuery;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestSummaryResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.GuestProfileSummary;
import com.pfplaybackend.api.administration.adapter.in.web.dto.RecentActivityLogItem;
import com.pfplaybackend.api.administration.adapter.in.web.dto.UserAccountSummary;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminGuestQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestDetailRow;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.exception.AdminGuestException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only query service for the admin Guest views.
 *
 * <p>getDetail composes two read sources:
 *  - AdminGuestQueryRepository.findDetail — single QueryDSL row of guest + userAccount.
 *  - UserActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc —
 *    recent 30 audit rows for the linked user_account_id (Member 와 재사용).
 *
 * <p>Cross-BC entity reference (User) is confined to AdminGuestQueryRepositoryImpl;
 * this service operates exclusively on administration BC DTOs.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-d8-admin-guest-readonly-design.md §7.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminGuestQueryService {

    /** Spec — recentActivityLog 응답 상한. AdminMemberQueryService 와 동일값. */
    static final int RECENT_ACTIVITY_LIMIT = 30;

    private final AdminGuestQueryRepository guestRepository;
    private final UserActivityLogRepository userActivityLogRepository;

    public AdminGuestDetailResponse getDetail(Long guestId) {
        AdminGuestDetailRow row = guestRepository.findDetail(guestId)
                .orElseThrow(() -> ExceptionCreator.create(AdminGuestException.GUEST_NOT_FOUND));

        List<UserActivityLogData> logs = row.userAccountId() == null
                ? List.of()
                : userActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(
                        row.userAccountId());

        List<RecentActivityLogItem> activityItems = logs.stream()
                .map(d -> new RecentActivityLogItem(
                        d.getEventType(), d.getPartyroomId(), d.getMetadata(), d.getOccurredAt()))
                .toList();

        return new AdminGuestDetailResponse(
                row.guestId(),
                new UserAccountSummary(row.userAccountId(), row.email(), row.providerType(),
                        row.lastLoginAt(), row.withdrawnAt()),
                new GuestProfileSummary(row.nickname(), row.introduction()),
                row.agent(),
                row.isProfileUpdated(),
                row.createdAt(),
                row.withdrawnAt() != null,
                row.withdrawnAt(),
                activityItems);
    }

    public Page<AdminGuestSummaryResponse> getList(AdminGuestListQuery query, Pageable pageable) {
        return guestRepository.search(query, pageable)
                .map(r -> new AdminGuestSummaryResponse(
                        r.guestId(),
                        r.userAccountId(),
                        r.email(),
                        r.providerType(),
                        r.nickname(),
                        r.agent(),
                        r.isProfileUpdated(),
                        r.lastLoginAt(),
                        r.createdAt(),
                        r.withdrawnAt() != null,
                        r.withdrawnAt()));
    }
}
