package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;

import java.time.LocalDateTime;

/**
 * Admin partyroom list item (B-1). Composed by
 * {@code AdminPartyroomQueryService.list(...)} from {@code AdminPartyroomListRow}.
 *
 * <p>{@code playbackActivated} is non-null at the response boundary: a missing
 * PARTYROOM_PLAYBACK row is treated as "not activated" (false).
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
        LocalDateTime lastActivityAt
) {}
