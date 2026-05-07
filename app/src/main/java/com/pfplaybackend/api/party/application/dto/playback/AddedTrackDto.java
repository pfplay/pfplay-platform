package com.pfplaybackend.api.party.application.dto.playback;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "GRAB 리액션으로 사용자 GRABLIST에 추가된 트랙 정보")
public record AddedTrackDto(
        @Schema(description = "추가된 트랙 ID", example = "12345") Long trackId,
        @Schema(description = "추가된 GRABLIST 플레이리스트 ID", example = "67") Long playlistId
) {}
