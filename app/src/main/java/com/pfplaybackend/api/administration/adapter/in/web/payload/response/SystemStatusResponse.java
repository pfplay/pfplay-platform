package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /api/v1/system/status 응답 — anonymous public endpoint.
 *
 * <p>Composition:
 * <ul>
 *     <li>{@code maintenance} — 현재 ACTIVE 점검 1건 (없으면 null).</li>
 *     <li>{@code activeAnnouncements} — EVENT/EMERGENCY 중 expires 미경과 + 미철회.</li>
 *     <li>{@code plannedMaintenance} — MAINTENANCE_NOTICE 중 미시작 + 미철회 + scheduledStart 미래, ASC.</li>
 * </ul>
 *
 * <p>Spec: docs/superpowers/specs/2026-05-03-system-announcement-design.md §4.4
 */
public record SystemStatusResponse(
        MaintenanceInfo maintenance,
        List<AnnouncementSummaryResponse> activeAnnouncements,
        List<MaintenanceInfo> plannedMaintenance
) {
    public record MaintenanceInfo(
            String phase,                  // "PLANNED" | "ACTIVE"
            LocalDateTime startAt,
            LocalDateTime endAt,
            String messageKo,
            String messageEn
    ) {}
}
