package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;

import java.time.LocalDateTime;

/**
 * Admin partyroom list item (B-1). Composed by
 * {@code AdminPartyroomQueryService.list(...)} from {@code AdminPartyroomListRow}.
 *
 * <p>{@code playbackActivated} is non-null at the response boundary: a missing
 * PARTYROOM_PLAYBACK row is treated as "not activated" (false).
 *
 * <p>{@code virtualCrew} (P2 Task 2.2) is nullable — null when the room has no
 * {@code partyroom_virtual_crew_config} row. When present it carries the configured
 * {@code status}/{@code targetCount} plus the live {@code botDjCount} (dummy DJs
 * currently queued) so the admin UI can render a "봇 x/T · 상태" column without N+1.
 * This summary is administration-module-only and is NOT surfaced on the
 * user-facing party module ({@code /api/v1/partyrooms}).
 */
public record AdminPartyroomListItemResponse(
        Long partyroomId,
        String title,
        StageType stageType,
        Long hostUserAccountId,
        String hostNickname,
        int crewCount,
        long djCount,
        boolean playbackActivated,
        PartyroomStatus status,
        DisplayFlag displayFlag,
        LocalDateTime createdAt,
        LocalDateTime lastActivityAt,
        VirtualCrewSummary virtualCrew
) {
    /**
     * 가상 DJ 요약 — config row 가 있는 룸에 한해 채워진다.
     * {@code botDjCount} = 현재 큐에 들어가 있는 dummy DJ 수(라이브 카운트).
     */
    public record VirtualCrewSummary(
            VirtualCrewStatus status,
            Integer targetCount,
            long botDjCount
    ) {}
}
