package com.pfplaybackend.api.party.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.in.web.payload.response.info.QueryDjQueueResponse;
import com.pfplaybackend.api.party.adapter.in.web.payload.response.info.QueryMyActivePartyroomResponse;
import com.pfplaybackend.api.party.adapter.in.web.payload.response.info.QueryPartyroomListResponse;
import com.pfplaybackend.api.party.adapter.in.web.payload.response.info.QueryPartyroomListResponse.PartyroomElement;
import com.pfplaybackend.api.party.application.dto.partyroom.PartyroomWithCrewDto;
import com.pfplaybackend.api.party.application.dto.result.DjQueueInfoResult;
import com.pfplaybackend.api.party.application.dto.result.PartyroomSummaryResult;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
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

import java.util.List;
import java.util.Map;

@Tag(name = "Partyroom API")
@RequestMapping("/api/v1/partyrooms")
@RestController
@RequiredArgsConstructor
public class PartyroomQueryController {

    private final PartyroomQueryService partyroomQueryService;

    /**
     * 모든 파티룸의 정보를 조회한다.
     * → 대기실에서의 목록 조회 API
     */
    @Operation(summary = "파티룸 목록 조회", description = "활성 상태인 모든 파티룸 목록을 조회합니다. 대기실 화면에서 사용됩니다.")
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping
    public ResponseEntity<ApiCommonResponse<List<PartyroomElement>>> getPartyrooms() {
        List<PartyroomWithCrewDto> partyrooms = partyroomQueryService.getAllPartyrooms();
        Map<UserId, ProfileSettingDto> avatarSettings = partyroomQueryService.getPrimariesAvatarSettings(partyrooms);
        return ResponseEntity.ok().body(ApiCommonResponse.success(QueryPartyroomListResponse.from(partyrooms, avatarSettings)));
    }

    /**
     * 특정 파티룸의 '일반 정보'를 조회한다.
     * → 파티룸 내에서 우측 상단의 '파티 정보' 클릭 시 호출 API
     */
    @Operation(summary = "파티룸 요약 정보 조회", description = "특정 파티룸의 요약 정보(제목, 소개, 재생 시간 제한 등)를 조회합니다. 파티룸 내 '파티 정보' 패널에서 사용됩니다.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PartyroomException.class})
    @GetMapping("/{partyroomId}/summary")
    public ResponseEntity<ApiCommonResponse<PartyroomSummaryResult>> getPartyroomSummaryInfo(
            @Parameter(description = "파티룸 ID") @PathVariable Long partyroomId) {
        return ResponseEntity.ok().body(ApiCommonResponse.success(partyroomQueryService.getSummaryInfo(new PartyroomId(partyroomId))));
    }

    /**
     * 현재 로그인 사용자가 활성 crew로 속한 파티룸을 조회한다 (스냅샷).
     * → 재연결 resync가 기억하던 방을 되훔침(재입장)하는 대신, 서버 권위 스냅샷으로 분기하기 위한 API.
     *   멀티 디바이스 승계(new-session-wins)에서 밀려난 브라우저의 세션 되훔침(핑퐁)을 차단한다. (#477)
     */
    @Operation(summary = "내 활성 파티룸 스냅샷 조회", description = "현재 로그인 사용자가 활성 crew로 속한 파티룸을 조회합니다. 재연결 시 클라이언트는 기억하던 방을 재주장하는 대신 이 스냅샷으로 분기합니다. 활성 방이 있으면 200 + 스냅샷, 없으면 204 No Content.")
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping("/me/active")
    public ResponseEntity<ApiCommonResponse<QueryMyActivePartyroomResponse>> getMyActivePartyroom() {
        // 활성 방 없음을 200 + {data:null} 로 주면 web 인터셉터가 래퍼를 벗기지 못해 오역된다 → 204 로 표현.
        return partyroomQueryService.getMyActivePartyroom()
                .map(dto -> ResponseEntity.ok(ApiCommonResponse.success(QueryMyActivePartyroomResponse.from(dto))))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "DJ 큐 조회", description = "특정 파티룸의 DJ 큐 정보를 조회합니다. 현재 재생 중인 DJ, 대기 중인 DJ 목록, 큐 상태 등을 포함합니다.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PartyroomException.class})
    @GetMapping("/{partyroomId}/dj-queue")
    public ResponseEntity<ApiCommonResponse<QueryDjQueueResponse>> getDjQueueInfo(
            @Parameter(description = "파티룸 ID") @PathVariable Long partyroomId) {
        DjQueueInfoResult result = partyroomQueryService.getDjQueueInfo(new PartyroomId(partyroomId));
        return ResponseEntity.ok().body(ApiCommonResponse.success(
                QueryDjQueueResponse.from(result.playbackActivated(), result.queueStatus(),
                        result.registered(), result.currentPlayback(), result.djs())));
    }
}
