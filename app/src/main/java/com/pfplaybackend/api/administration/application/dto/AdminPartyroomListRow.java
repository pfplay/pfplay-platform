package com.pfplaybackend.api.administration.application.dto;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomListItemResponse.VirtualCrewSummary;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;

import java.time.LocalDateTime;

/**
 * Row projection for the admin partyroom list endpoint (B-1). Cross-BC JOIN
 * result — pulls {@code partyroom} + {@code user_account} + {@code member}
 * (host nickname) into one read-only DTO so Administration BC does not leak
 * Party/User entities to upper layers.
 *
 * {@code playbackActivated} may be null if no PARTYROOM_PLAYBACK row exists yet.
 * {@code hostNickname} may be null if the host's profile is not yet initialized.
 * {@code virtualCrew} (P2 Task 2.2) is null when the room has no
 * {@code partyroom_virtual_crew_config} row.
 */
public record AdminPartyroomListRow(
        Long partyroomId,
        String title,
        StageType stageType,
        Long hostUserAccountId,
        String hostNickname,
        int crewCount,
        long djCount,
        Boolean playbackActivated,
        PartyroomStatus status,
        DisplayFlag displayFlag,
        LocalDateTime createdAt,
        LocalDateTime lastActivityAt,
        VirtualCrewSummary virtualCrew
) {}
