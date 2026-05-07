package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportStatusUpdateRequest;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomReportRepository;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.exception.AdminReportException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-2 PATCH /admin/reports/{reportId} — 신고 상태 전이 service.
 *
 * <p>5 transition (D3 matrix):
 * <ul>
 *     <li>PENDING → REVIEWING (startReview, 첫 검토자 reviewedByAdministratorId 기록)</li>
 *     <li>PENDING → DISMISSED (직접 거절, 첫 검토자 dismiss 시점에 기록)</li>
 *     <li>REVIEWING → RESOLVED (terminal, resolutionNote/resolvedAt 기록)</li>
 *     <li>REVIEWING → DISMISSED (terminal, resolutionNote/resolvedAt 기록)</li>
 *     <li>REVIEWING → PENDING (보류, hold — 첫 검토자 audit 보존)</li>
 * </ul>
 *
 * <p>Matrix 외 전이(terminal-source 또는 PENDING→RESOLVED skip 등)는 도메인
 * {@code guardTransition}이 {@link AdminReportException#INVALID_STATE_TRANSITION} (RPT-002) throw.
 * D3.1 결정에 따라 terminal(RESOLVED/DISMISSED) 진입 시 {@code resolutionNote} 비어있지 않음
 * 검증은 service-layer guard {@link #requireResolutionNote(String)} ({@link AdminReportException#RESOLUTION_NOTE_REQUIRED}
 * RPT-003) — 도메인은 빈 note도 그대로 영속하므로 application layer 책임.
 *
 * <p>응답 shape은 G3 {@link AdminReportQueryService#buildDetailResponse(PartyroomReportData)}
 * 재사용 — list/detail/PATCH 응답 일관성 보장. {@code save(report)} 후 호출하므로 동일 transaction
 * 내에서 dirty-checking flush가 cross-context loose-ref 합성 query 직전에 일어난다.
 *
 * <p>{@link AdminContext}는 PR 12b2 패턴 mirror — service 필드로 inject, controller는 미인지.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.4, §4.1, §12 D3/D3.1/D3.2/D3.3
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminReportCommandService {

    private final PartyroomReportRepository reportRepository;
    private final AdminReportQueryService queryService;
    private final AdminContext adminContext;

    public AdminReportDetailResponse changeStatus(Long reportId, AdminReportStatusUpdateRequest request) {
        PartyroomReportData report = reportRepository.findById(reportId)
                .orElseThrow(() -> ExceptionCreator.create(AdminReportException.REPORT_NOT_FOUND));

        Long byAdministratorId = adminContext.currentAdministratorId();

        // Dispatch on target status. 도메인의 guardTransition이 matrix 위반(RPT-002) 책임.
        switch (request.status()) {
            case REVIEWING -> report.startReview(byAdministratorId);
            case RESOLVED -> {
                requireResolutionNote(request.resolutionNote());
                report.resolve(byAdministratorId, request.resolutionNote());
            }
            case DISMISSED -> {
                requireResolutionNote(request.resolutionNote());
                report.dismiss(byAdministratorId, request.resolutionNote());
            }
            case PENDING -> report.hold(byAdministratorId);
        }

        // save 후 buildDetailResponse — JPA dirty-checking flush가 cross-context loose-ref query 직전에
        // 발생하도록 보장 (동일 transaction 내 일관성).
        reportRepository.save(report);
        return queryService.buildDetailResponse(report);
    }

    /** D3.1: terminal(RESOLVED/DISMISSED) 진입 시 비어있지 않은 resolutionNote 요구. */
    private static void requireResolutionNote(String note) {
        if (note == null || note.isBlank()) {
            throw ExceptionCreator.create(AdminReportException.RESOLUTION_NOTE_REQUIRED);
        }
    }
}
