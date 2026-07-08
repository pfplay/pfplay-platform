package com.pfplaybackend.api.party.application.dto.partyroom;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.crew.CrewDto;
import com.pfplaybackend.api.party.application.dto.playback.PlaybackDto;
import com.pfplaybackend.api.party.domain.enums.StageType;

import java.time.LocalDateTime;
import java.util.List;

public record PartyroomWithCrewDto(
        long partyroomId,
        StageType stageType,
        UserId hostId,
        String title,
        String introduction,
        boolean playbackActivated,
        boolean queueClosed,
        Long crewCount,
        LocalDateTime createdAt,
        PlaybackDto playbackDto,
        List<CrewDto> crews
) {
    public static PartyroomWithCrewDto from(PartyroomWithCrewDto dto, List<CrewDto> members) {
        return new PartyroomWithCrewDto(
                dto.partyroomId(), dto.stageType(), dto.hostId(), dto.title(), dto.introduction(),
                dto.playbackActivated(), dto.queueClosed(), dto.crewCount(), dto.createdAt(), dto.playbackDto(), members);
    }
}
