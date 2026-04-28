package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberListQuery;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberDetailRow;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberSummaryRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * Member 어드민 query repository (read-only).
 * QueryDSL 구현 — PR 8 AdminPartyroomQueryRepositoryImpl 패턴 일관.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §6
 */
public interface AdminMemberQueryRepository {

    /**
     * A-2: member + linked userAccount join으로 detail row 1건 조회.
     * recentActivityLog는 별도 UserActivityLogRepository 호출(service orchestration).
     */
    Optional<AdminMemberDetailRow> findDetail(Long memberId);

    /**
     * A-1: filter(email LIKE / tier / 가입일 range) + sort(created_at asc/desc,
     * last_activity_desc) + pagination(size cap 200은 Controller validation).
     *
     * <p>{@code last_activity_desc}: user_activity_log MAX(occurredAt)에 활동이
     * 0건인 member는 fallback으로 user_account.createdAt 사용 — LEFT JOIN +
     * COALESCE 패턴 (spec §6 SQL).
     */
    Page<AdminMemberSummaryRow> search(AdminMemberListQuery query, Pageable pageable);
}
