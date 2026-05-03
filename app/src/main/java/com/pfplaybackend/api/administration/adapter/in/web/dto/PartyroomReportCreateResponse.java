package com.pfplaybackend.api.administration.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * C-1 POST /api/v1/partyrooms/{partyroomId}/reports response body.
 *
 * <p>Returns the persisted {@code reportId} (DB-assigned IDENTITY) so the client
 * can correlate / surface the receipt without a follow-up GET.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.1
 */
@Schema(description = "파티룸 신고 생성 응답 (C-1)")
public record PartyroomReportCreateResponse(
        @Schema(description = "생성된 신고 ID", example = "1234")
        Long reportId
) {}
