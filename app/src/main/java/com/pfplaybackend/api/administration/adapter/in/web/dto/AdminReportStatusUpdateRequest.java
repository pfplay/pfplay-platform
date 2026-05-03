package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * C-2 PATCH /admin/reports/{reportId} request body — 신고 상태 전이.
 *
 * <p>{@link ReportStatus} target은 필수. {@code resolutionNote}는 size만 검증
 * (max 2000) — terminal(RESOLVED/DISMISSED) 진입 시 비어있지 않음 검증은
 * {@link com.pfplaybackend.api.administration.application.service.AdminReportCommandService}
 * service-layer guard로 처리(D3.1).
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.4
 */
public record AdminReportStatusUpdateRequest(
        @NotNull ReportStatus status,
        @Size(max = 2000) String resolutionNote
) {}
