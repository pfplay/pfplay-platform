package com.pfplaybackend.api.admin.application.dto.result;

import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;

import java.time.LocalDateTime;

public record AdminPartyroomResult(
        Long partyroomId,
        String hostUserId,
        String title,
        String introduction,
        String linkDomain,
        Integer playbackTimeLimit,
        String stageType,
        Boolean isActive,
        LocalDateTime createdAt
) {
    public static AdminPartyroomResult from(PartyroomData partyroom, String hostUserId) {
        return new AdminPartyroomResult(
                partyroom.getPartyroomId().getId(),
                hostUserId,
                partyroom.getTitle(),
                partyroom.getIntroduction(),
                partyroom.getLinkDomain().getValue(),
                partyroom.getPlaybackTimeLimit().getMinutes(),
                partyroom.getStageType().name(),
                partyroom.isActive(),
                partyroom.getCreatedAt()
        );
    }
}
