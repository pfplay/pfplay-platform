package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * C-1 POST /api/v1/partyrooms/{partyroomId}/reports request body.
 *
 * <p>{@code description} is optional (nullable) — reporter can submit without explanatory text.
 * {@code @Size(max = 2000)} mirrors V13 {@code partyroom_report.description TEXT NULL} soft-cap
 * (DDL doesn't enforce length, app validation does — D6 design decision).
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.1
 */
@Schema(description = "파티룸 신고 생성 요청 (C-1)")
public record PartyroomReportCreateRequest(
        @Schema(description = "신고 카테고리", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "SPAM", implementation = ReportCategory.class)
        @NotNull
        ReportCategory category,

        @Schema(description = "신고 사유 (선택, 최대 2000자)",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                example = "광고성 게시 반복")
        @Size(max = 2000)
        String description
) {}
