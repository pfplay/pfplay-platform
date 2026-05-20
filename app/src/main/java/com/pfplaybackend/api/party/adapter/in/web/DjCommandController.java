package com.pfplaybackend.api.party.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.party.adapter.in.web.payload.request.dj.ChangePlaylistRequest;
import com.pfplaybackend.api.party.adapter.in.web.payload.request.dj.RegisterDjRequest;
import com.pfplaybackend.api.party.adapter.in.web.payload.response.CreateDjResponse;
import com.pfplaybackend.api.party.application.service.DjCommandService;
import com.pfplaybackend.api.party.domain.exception.DjException;
import com.pfplaybackend.api.party.domain.exception.GradeException;
import com.pfplaybackend.api.party.domain.value.DjId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 이 클래스는 특정 파티룸 내에서의 DJ 관리에 대한 표현 계층을 담당한다.
 */
@Tag(name = "DJ API")
@RequestMapping("/api/v1/partyrooms")
@RestController
@RequiredArgsConstructor
public class DjCommandController {

    private final DjCommandService djCommandService;

    @Operation(summary = "DJ 등록", description = "파티룸의 DJ 큐에 등록합니다. 플레이리스트를 선택하여 등록하며, MEMBER 권한이 필요합니다.")
    @ApiResponse(responseCode = "201", description = "DJ 등록 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({DjException.class})
    @PostMapping("/{partyroomId}/dj-queue")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<ApiCommonResponse<CreateDjResponse>> enqueueDj(
            @Parameter(description = "파티룸 ID") @PathVariable Long partyroomId,
            @Valid @RequestBody RegisterDjRequest request) {
        Long djId = djCommandService.enqueueDj(new PartyroomId(partyroomId), new PlaylistId(request.getPlaylistId()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(new CreateDjResponse(djId)));
    }

    @Operation(summary = "본인 DJ 해제", description = "본인을 DJ 큐에서 해제합니다. 현재 재생 중인 DJ인 경우 재생이 중단됩니다.")
    @ApiResponse(responseCode = "204", description = "DJ 해제 성공")
    @SecurityRequirement(name = "cookieAuth")
    @DeleteMapping("/{partyroomId}/dj-queue/me")
    public ResponseEntity<Void> dequeueDj(
            @Parameter(description = "파티룸 ID") @PathVariable Long partyroomId) {
        djCommandService.dequeueDj(new PartyroomId(partyroomId));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "타인 DJ 강제 해제", description = "관리자 권한으로 특정 DJ를 큐에서 강제 해제합니다. COMMUNITY_MANAGER 이상의 등급이 필요합니다.")
    @ApiResponse(responseCode = "204", description = "DJ 강제 해제 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({DjException.class, GradeException.class})
    @DeleteMapping("/{partyroomId}/dj-queue/{djId}")
    public ResponseEntity<Void> dequeueDj(
            @Parameter(description = "파티룸 ID") @PathVariable Long partyroomId,
            @Parameter(description = "해제할 DJ ID") @PathVariable Long djId) {
        djCommandService.dequeueDj(new PartyroomId(partyroomId), new DjId(djId));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "본인 DJ 플레이리스트 변경",
            description = "DJ 대기열에 등록된 본인의 디제잉 플레이리스트를 변경합니다. 큐 순서는 보존되며, 재생 중 DJ 는 변경할 수 없습니다.")
    @ApiResponse(responseCode = "204", description = "변경 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({DjException.class})
    @PatchMapping("/{partyroomId}/dj-queue/me")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<Void> changePlaylist(
            @Parameter(description = "파티룸 ID") @PathVariable Long partyroomId,
            @Valid @RequestBody ChangePlaylistRequest request) {
        djCommandService.changePlaylist(new PartyroomId(partyroomId), new PlaylistId(request.getPlaylistId()));
        return ResponseEntity.noContent().build();
    }
}
