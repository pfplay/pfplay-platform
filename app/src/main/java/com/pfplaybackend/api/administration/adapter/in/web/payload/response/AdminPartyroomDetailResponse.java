package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.StageType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin partyroom detail (B-2). Composite view assembled by
 * {@code AdminPartyroomQueryService.detail(...)} from 5–6 sub-queries (no single
 * cross-BC JOIN — see plan §5 for rationale).
 *
 * <p>PR 9 scope:
 * <ul>
 *   <li>{@code recentPenalties} is populated from V8 {@code punisher_type} column
 *       (top 5 by penalty_date desc; CREW + ADMIN both surfaced).</li>
 *   <li>{@code recentReports} is always empty — PR 13 implements the report system.</li>
 *   <li>{@link PlaybackSummary#currentTrackName} and
 *       {@link DjSummary#playlistName} are null — Playlist module's query port is
 *       out of scope for PR 8.</li>
 * </ul>
 */
public record AdminPartyroomDetailResponse(
        Long partyroomId,
        String title,
        String introduction,
        PartyroomStatus status,
        DisplayFlag displayFlag,
        Long hostUserAccountId,
        String hostNickname,
        String hostEmail,
        int crewCount,
        LocalDateTime lastActivityAt,
        StageType stageType,
        Integer playbackTimeLimit,
        PlaybackSummary playback,
        List<CrewSummary> crews,
        List<DjSummary> djQueue,
        List<PenaltySummary> recentPenalties,
        List<ReportSummary> recentReports,
        List<AdminActionSummary> recentAdminActions
) {
    public record PlaybackSummary(
            boolean activated,
            String currentTrackName,
            Long currentDjCrewId
    ) {}

    public record CrewSummary(
            Long crewId,
            Long memberId,
            GradeType gradeType,
            String nickname,
            LocalDateTime enteredAt
    ) {}

    public record DjSummary(
            Long djId,
            Long crewId,
            String playlistName,
            int orderNumber
    ) {}

    public record PenaltySummary(
            Long id,
            Long crewId,
            PenaltyType penaltyType,
            String punisherType,
            String reason,
            LocalDateTime date
    ) {}

    public record ReportSummary(
            Long id,
            String category,
            String status,
            Long reporterUserAccountId,
            LocalDateTime createdAt
    ) {}

    public record AdminActionSummary(
            Long actionId,
            PartyroomAdminActionType actionType,
            Long administratorId,
            LocalDateTime occurredAt
    ) {}
}
