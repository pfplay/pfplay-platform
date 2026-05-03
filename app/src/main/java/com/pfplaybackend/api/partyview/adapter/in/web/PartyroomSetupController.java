package com.pfplaybackend.api.partyview.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.partyview.adapter.in.web.payload.response.QueryPartyroomSetupResponse;
import com.pfplaybackend.api.partyview.application.dto.result.PartyroomSetupResult;
import com.pfplaybackend.api.partyview.application.service.PartyroomSetupQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Partyroom API")
@RequestMapping("/api/v1/partyrooms")
@RestController
@RequiredArgsConstructor
public class PartyroomSetupController {

    private final PartyroomSetupQueryService partyroomSetupQueryService;

    @Operation(summary = "파티룸 셋업 정보 조회", description = "파티룸 입장 후 초기화에 필요한 셋업 정보를 조회합니다. 크루 목록, 현재 재생 정보 등 파티룸 화면 구성에 필요한 전체 데이터를 반환합니다.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PartyroomException.class})
    @GetMapping("/{partyroomId}/setup")
    public ResponseEntity<ApiCommonResponse<QueryPartyroomSetupResponse>> getSetupInfo(
            @Parameter(description = "파티룸 ID") @PathVariable Long partyroomId) {
        PartyroomSetupResult result = partyroomSetupQueryService.getSetupInfo(new PartyroomId(partyroomId));
        return ResponseEntity.ok().body(
                ApiCommonResponse.success(QueryPartyroomSetupResponse.from(result.stageType(), result.crews(), result.display())));
    }
}
