package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;

import java.time.LocalDateTime;

/**
 * GET /api/v1/admin/announcements 페이지 요소 / 단건 응답.
 *
 * <p>15 fields — entity의 모든 user-facing 필드(cancelledByAdministratorId 제외).
 *
 * <p>Spec: docs/superpowers/specs/2026-05-03-system-announcement-design.md §4.2
 */
public record AnnouncementSummaryResponse(
        Long id,
        AnnouncementType type,
        AnnouncementSeverity severity,
        String titleKo,
        String titleEn,
        String messageKo,
        String messageEn,
        LocalDateTime scheduledStartAt,
        LocalDateTime scheduledEndAt,
        LocalDateTime expiresAt,
        LocalDateTime sentAt,
        Long sentByAdministratorId,
        LocalDateTime maintenanceStartedAt,
        LocalDateTime cancelledAt,
        LocalDateTime completedAt
) {
    public static AnnouncementSummaryResponse from(SystemAnnouncementData e) {
        return new AnnouncementSummaryResponse(
                e.getId(),
                e.getType(),
                e.getSeverity(),
                e.getTitleKo(),
                e.getTitleEn(),
                e.getMessageKo(),
                e.getMessageEn(),
                e.getScheduledStartAt(),
                e.getScheduledEndAt(),
                e.getExpiresAt(),
                e.getSentAt(),
                e.getSentByAdministratorId(),
                e.getMaintenanceStartedAt(),
                e.getCancelledAt(),
                e.getCompletedAt());
    }
}
