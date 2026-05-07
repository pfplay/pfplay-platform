package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.common.enums.AuthorityTier;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A-2 GET /admin/members/{memberId} response payload.
 *
 * <p>Composed by {@code AdminMemberQueryService} from two sources:
 *  - {@code AdminMemberQueryRepository.findDetail} — member + linked user_account row.
 *  - {@code UserActivityLogRepository.findTop30...} — recent 30 activity log rows.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §3.2.
 *
 * <p>PR 14g §5: {@code withdrawn} + {@code withdrawnAt}을 root level에 노출 — list response (A-1)와
 * 일관성. 기존 {@code userAccount.withdrawnAt} nested field는 보존 (외부 caller backward compat).
 */
public record AdminMemberDetailResponse(
        Long memberId,
        UserAccountSummary userAccount,
        MemberProfileSummary profile,
        AuthorityTier authorityTier,
        LocalDateTime createdAt,
        boolean withdrawn,
        LocalDateTime withdrawnAt,
        List<RecentActivityLogItem> recentActivityLog
) {}
