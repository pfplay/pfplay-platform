package com.pfplaybackend.api.partyview.adapter.in.web.payload.response;

import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.partyview.application.dto.CrewSetupDto;
import com.pfplaybackend.api.partyview.application.dto.DisplayDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Builder
@Data
public class QueryPartyroomSetupResponse {

    // Amplitude L1: 클라이언트가 lobby 캐시에 의존하지 않고 입장 시점에서
    // canonical stage_type 값을 받을 수 있도록 응답 최상위에 노출.
    @Schema(description = "파티룸 스테이지 타입 (Amplitude `Partyroom Entered`의 stage_type 프로퍼티 source)",
            example = "MAIN")
    private StageType stageType;

    private List<CrewSetupDto> crews;
    private DisplayDto display;

    public static QueryPartyroomSetupResponse from(StageType stageType, List<CrewSetupDto> crews, DisplayDto display) {
        return new QueryPartyroomSetupResponse(stageType, crews, display);
    }
}
