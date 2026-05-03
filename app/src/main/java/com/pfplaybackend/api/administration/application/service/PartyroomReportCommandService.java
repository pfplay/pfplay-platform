package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.PartyroomReportCreateRequest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.PartyroomReportCreateResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomReportRepository;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.exception.AdminReportException;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * C-1 유저 신고 접수 service.
 *
 * <p>Validation order (D6 → D2 → D1) — exception precedence is locked in here:
 * <ol>
 *     <li>Guest 차단 (D1.5) — Member-only endpoint, FORBIDDEN before any DB I/O.</li>
 *     <li>Partyroom 존재 검증 (D6) — {@code PartyroomException.NOT_FOUND_ROOM} 404.</li>
 *     <li>Partyroom 신고 가능 상태 검증 (D6) — {@code AdminReportException.PARTYROOM_NOT_REPORTABLE} 400.</li>
 *     <li>Self-report 차단 (D2) — {@code AdminReportException.SELF_REPORT_FORBIDDEN} 400.</li>
 *     <li>24h 동일 카테고리 중복 차단 (D1) — {@code AdminReportException.DUPLICATE_REPORT} 400.</li>
 * </ol>
 *
 * <p>{@code reporterUserAccountId} maps to {@code partyroom_report.reporter_user_account_id}
 * (the {@link com.pfplaybackend.api.user.domain.entity.data.UserAccountData} PK / {@code MemberData.userAccountId}).
 * Caller (controller) extracts it from JWT — service never touches SecurityContext directly so it stays
 * trivially unit-testable.
 *
 * <p>Cross-context loose-ref read goes through {@link PartyroomAggregatePort} (party BC's hexagonal
 * boundary), matching the established pattern across {@code AdminPartyroomCommandService},
 * {@code AdminPartyroomQueryService}, {@code AdminCrewPenaltyCommandService}.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.1
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PartyroomReportCommandService {

    private static final long DEDUP_WINDOW_HOURS = 24L;

    private final PartyroomReportRepository reportRepository;
    private final PartyroomAggregatePort partyroomAggregatePort;

    public PartyroomReportCreateResponse create(Long partyroomId,
                                                PartyroomReportCreateRequest request,
                                                AuthorityTier reporterTier,
                                                Long reporterUserAccountId) {
        // 1. Guest 차단 (D1.5) — Member-only.
        guardMemberOnly(reporterTier);

        // 2. partyroom 존재 검증 (D6).
        PartyroomData partyroom = partyroomAggregatePort.findPartyroomById(partyroomId)
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));

        // 3. partyroom 신고 가능 상태 검증 (D6).
        if (!partyroom.isReportable()) {
            throw ExceptionCreator.create(AdminReportException.PARTYROOM_NOT_REPORTABLE);
        }

        // 4. self-report 차단 (D2).
        if (isSelfReport(partyroom, reporterUserAccountId)) {
            throw ExceptionCreator.create(AdminReportException.SELF_REPORT_FORBIDDEN);
        }

        // 5. 24h 동일 카테고리 중복 차단 (D1) — 앱 검증 (D1: DB unique 미사용).
        LocalDateTime since = LocalDateTime.now().minusHours(DEDUP_WINDOW_HOURS);
        if (reportRepository.existsByReporterUserAccountIdAndPartyroomIdAndCategoryAndCreatedAtAfter(
                reporterUserAccountId, partyroomId, request.category(), since)) {
            throw ExceptionCreator.create(AdminReportException.DUPLICATE_REPORT);
        }

        // 6. 영속화 — IDENTITY 컬럼이 reportId 채움.
        PartyroomReportData saved = reportRepository.save(
                PartyroomReportData.create(
                        partyroomId, reporterUserAccountId, request.category(), request.description()));

        return new PartyroomReportCreateResponse(saved.getReportId());
    }

    private void guardMemberOnly(AuthorityTier tier) {
        // Allow-list: Member 등급(FM/AM)만 신고 가능. 새 tier 추가 시 명시적으로 opt-in해야 통과.
        // (정책 — Guest는 partyroom 작업 일반 차단 — partyroom BC 기존 RESTRICTED_AUTHORITY 코드 재사용.)
        if (tier != AuthorityTier.AM && tier != AuthorityTier.FM) {
            throw ExceptionCreator.create(PartyroomException.RESTRICTED_AUTHORITY);
        }
    }

    private static boolean isSelfReport(PartyroomData partyroom, Long reporterUserAccountId) {
        return partyroom.getHostId() != null
                && partyroom.getHostId().getUid() != null
                && partyroom.getHostId().getUid().equals(reporterUserAccountId);
    }
}
