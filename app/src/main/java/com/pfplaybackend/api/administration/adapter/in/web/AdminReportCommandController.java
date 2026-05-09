package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportStatusUpdateRequest;
import com.pfplaybackend.api.administration.application.service.AdminReportCommandService;
import com.pfplaybackend.api.common.ApiCommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-2 PATCH /api/v1/admin/reports/{reportId} — 신고 상태 전이.
 *
 * <p>{@code @Validated} 클래스 + {@code @Min @PathVariable} + {@code @Valid @RequestBody}
 * (PR 12b2 G1 표준 패턴) — Bean Validation 위반 시 {@code GlobalExceptionHandler}가 400.
 * AdminContext 주입은 service-layer ({@link AdminReportCommandService}) — controller는 미인지.
 *
 * <p>{@code @PreAuthorize("@adminAuth.isAdmin()")} 게이팅 — anonymous 401 / non-admin 403.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.4
 */
@Tag(name = "Admin Report Commands API", description = "C-2 PATCH 신고 상태 전이")
@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
@Validated
public class AdminReportCommandController {

    private final AdminReportCommandService adminReportCommandService;

    @Operation(summary = "C-2 신고 상태 전이",
            description = "5 transition matrix(D3): PENDING→REVIEWING/DISMISSED, "
                    + "REVIEWING→RESOLVED/DISMISSED/PENDING(보류). "
                    + "terminal(RESOLVED/DISMISSED) 진입 시 resolutionNote 필수.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PatchMapping("/{reportId}")
    public ResponseEntity<ApiCommonResponse<AdminReportDetailResponse>> changeStatus(
            @PathVariable @Min(1) Long reportId,
            @Valid @RequestBody AdminReportStatusUpdateRequest request) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                adminReportCommandService.changeStatus(reportId, request)));
    }
}
