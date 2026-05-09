package com.pfplaybackend.api.playlist.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import com.pfplaybackend.api.playlist.adapter.in.web.payload.response.QueryTrackListResponse;
import com.pfplaybackend.api.playlist.application.service.TrackQueryService;
import com.pfplaybackend.api.playlist.domain.exception.PlaylistException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Playlist API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/playlists")
public class TrackQueryController {

    private final TrackQueryService trackQueryService;

    @Operation(summary = "트랙 목록 조회", description = "지정된 플레이리스트의 트랙 목록을 페이지네이션으로 조회합니다. 회원만 사용할 수 있습니다.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PlaylistException.class})
    @GetMapping("{playlistId}/tracks")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<ApiCommonResponse<QueryTrackListResponse>> getAllTracks(
            @Parameter(description = "트랙을 조회할 플레이리스트 ID") @PathVariable Long playlistId,
            @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam int pageNumber,
            @Parameter(description = "페이지 당 트랙 수") @RequestParam int pageSize) {
        return ResponseEntity
                .ok()
                .body(ApiCommonResponse.success(
                        QueryTrackListResponse.from(trackQueryService.getTracks(playlistId, pageNumber, pageSize))
                ));
    }
}
