package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberDetailRow;

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

    // A-1 search 메서드는 Task 17(G4)에서 추가.
}
