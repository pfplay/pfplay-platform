package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestListQuery;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestDetailRow;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestSummaryRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * Guest 어드민 query repository (read-only).
 * QueryDSL 구현 — AdminMemberQueryRepository 패턴 동형.
 *
 * <p>Cross-BC: implementation 만 user BC entity ({@code GuestData}, {@code UserAccountData}) 참조
 * (administration BC 내 ArchUnit 룰: repository impl 만 entity import 허용).
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-d8-admin-guest-readonly-design.md §6.
 */
public interface AdminGuestQueryRepository {

    /**
     * guest + linked userAccount join 으로 detail row 1건 조회.
     * recentActivityLog 는 별도 UserActivityLogRepository 호출 (service orchestration).
     */
    Optional<AdminGuestDetailRow> findDetail(Long guestId);

    /**
     * filter(email LIKE / 가입일 range) + sort(created_at asc/desc, last_activity_desc) +
     * pagination. tier filter 부재 (guest 는 항상 GT).
     *
     * <p>{@code last_activity_desc}: user_activity_log MAX(occurredAt) 에 활동 0건인 guest 는
     * fallback 으로 user_account.createdAt 사용 — LEFT JOIN + COALESCE 패턴
     * (AdminMemberQueryRepositoryImpl 와 동형).
     */
    Page<AdminGuestSummaryRow> search(AdminGuestListQuery query, Pageable pageable);
}
