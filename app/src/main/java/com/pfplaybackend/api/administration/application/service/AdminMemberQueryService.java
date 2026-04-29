package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberListQuery;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberSummaryResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.MemberProfileSummary;
import com.pfplaybackend.api.administration.adapter.in.web.dto.RecentActivityLogItem;
import com.pfplaybackend.api.administration.adapter.in.web.dto.UserAccountSummary;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminMemberQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberDetailRow;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.exception.AdminMemberException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only query service for the admin Member views (A-1 list, A-2 detail).
 *
 * <p>A-2 ({@link #getDetail(Long)}) composes two read sources:
 *  - {@link AdminMemberQueryRepository#findDetail} — single QueryDSL row of member + userAccount.
 *  - {@link UserActivityLogRepository#findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc} —
 *    most-recent 30 audit rows for the linked user_account_id.
 *
 * <p>Cross-BC entity reference (User) is confined to {@code AdminMemberQueryRepositoryImpl};
 * this service operates exclusively on Administration BC DTOs.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §3.2, §6
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMemberQueryService {

    /** Spec §3.2 — recentActivityLog 응답 상한. */
    static final int RECENT_ACTIVITY_LIMIT = 30;

    private final AdminMemberQueryRepository memberRepository;
    private final UserActivityLogRepository userActivityLogRepository;

    public AdminMemberDetailResponse getDetail(Long memberId) {
        AdminMemberDetailRow row = memberRepository.findDetail(memberId)
                .orElseThrow(() -> ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND));

        List<UserActivityLogData> logs = row.userAccountId() == null
                ? List.of()
                : userActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(
                        row.userAccountId());

        List<RecentActivityLogItem> activityItems = logs.stream()
                .map(d -> new RecentActivityLogItem(
                        d.getEventType(), d.getPartyroomId(), d.getMetadata(), d.getOccurredAt()))
                .toList();

        return new AdminMemberDetailResponse(
                row.memberId(),
                new UserAccountSummary(row.userAccountId(), row.email(), row.providerType(),
                        row.lastLoginAt(), row.withdrawnAt()),
                new MemberProfileSummary(row.nickname(), row.introduction()),
                row.authorityTier(),
                row.createdAt(),
                row.withdrawnAt() != null,
                row.withdrawnAt(),
                activityItems);
    }

    /**
     * A-1: paginated list of admin members. Filter/sort/pagination을 repository에 위임하고,
     * Page&lt;Row&gt;를 Page&lt;Response&gt;로 매핑하면서 {@code withdrawn}을 {@code withdrawnAt != null}로
     * derive한다 (spec §11 #11 — frontend convenience flag).
     */
    public Page<AdminMemberSummaryResponse> getList(AdminMemberListQuery query, Pageable pageable) {
        return memberRepository.search(query, pageable)
                .map(r -> new AdminMemberSummaryResponse(
                        r.memberId(),
                        r.userAccountId(),
                        r.email(),
                        r.providerType(),
                        r.nickname(),
                        r.authorityTier(),
                        r.lastLoginAt(),
                        r.createdAt(),
                        r.withdrawnAt() != null,
                        r.withdrawnAt()));
    }
}
