package com.pfplaybackend.api.party.adapter.in.web.payload.response.info;

import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "내 활성 파티룸 스냅샷 응답")
public record QueryMyActivePartyroomResponse(
        @Schema(description = "활성 파티룸 ID") Long partyroomId,
        @Schema(description = "해당 파티룸에서의 내 crew ID") Long crewId) {

    public static QueryMyActivePartyroomResponse from(ActivePartyroomDto dto) {
        return new QueryMyActivePartyroomResponse(dto.id(), dto.crewId());
    }
}
