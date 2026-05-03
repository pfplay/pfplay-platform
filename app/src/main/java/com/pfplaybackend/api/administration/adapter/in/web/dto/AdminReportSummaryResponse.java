package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;

import java.time.LocalDateTime;

/**
 * C-2 list 응답 row.
 *
 * <p>List view는 cross-context join 미수행(D7) — partyroom_report 테이블 단독 컬럼만 노출.
 * 상세 정보(reporter nickname, partyroom title 등)는 detail endpoint에서 join.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.2
 */
public record AdminReportSummaryResponse(
        Long reportId,
        Long partyroomId,
        Long reporterUserAccountId,
        ReportCategory category,
        ReportStatus status,
        LocalDateTime createdAt,
        Long reviewedByAdministratorId,
        LocalDateTime resolvedAt
) {
    public static AdminReportSummaryResponse from(PartyroomReportData report) {
        return new AdminReportSummaryResponse(
                report.getReportId(),
                report.getPartyroomId(),
                report.getReporterUserAccountId(),
                report.getCategory(),
                report.getStatus(),
                report.getCreatedAt(),
                report.getReviewedByAdministratorId(),
                report.getResolvedAt());
    }
}
