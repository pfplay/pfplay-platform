package com.pfplaybackend.api.playlist.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import com.pfplaybackend.api.playlist.adapter.in.web.payload.request.AddTrackRequest;
import com.pfplaybackend.api.playlist.adapter.in.web.payload.request.MoveTrackRequest;
import com.pfplaybackend.api.playlist.adapter.in.web.payload.request.UpdateTrackOrderRequest;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import com.pfplaybackend.api.playlist.application.dto.command.MoveTrackCommand;
import com.pfplaybackend.api.playlist.application.dto.command.UpdateTrackOrderCommand;
import com.pfplaybackend.api.playlist.adapter.in.web.payload.response.CreateTrackResponse;
import com.pfplaybackend.api.playlist.application.service.TrackCommandService;
import com.pfplaybackend.api.playlist.domain.exception.PlaylistException;
import com.pfplaybackend.api.playlist.domain.exception.TrackException;
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

@Tag(name = "Playlist API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/playlists")
public class TrackCommandController {

    private final TrackCommandService trackCommandService;

    @Operation(summary = "트랙 추가", description = "지정된 플레이리스트에 새로운 트랙을 추가합니다. 회원만 사용할 수 있습니다.")
    @ApiResponse(responseCode = "201", description = "트랙 추가 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PlaylistException.class, TrackException.class})
    @PostMapping("{playlistId}/tracks")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<ApiCommonResponse<CreateTrackResponse>> addTrack(
            @Parameter(description = "트랙을 추가할 플레이리스트 ID") @PathVariable Long playlistId,
            @Valid @RequestBody AddTrackRequest request) {
        AddTrackCommand command = new AddTrackCommand(request.getName(), request.getLinkId(), request.getDuration(), request.getThumbnailImage());
        Long trackId = trackCommandService.addTrackInPlaylist(playlistId, command);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(new CreateTrackResponse(trackId)));
    }

    @Operation(summary = "트랙 삭제", description = "지정된 플레이리스트에서 특정 트랙을 삭제합니다.")
    @ApiResponse(responseCode = "204", description = "트랙 삭제 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PlaylistException.class, TrackException.class})
    @DeleteMapping("{playlistId}/tracks/{trackId}")
    public ResponseEntity<Void> deleteTrack(
            @Parameter(description = "플레이리스트 ID") @PathVariable Long playlistId,
            @Parameter(description = "삭제할 트랙 ID") @PathVariable Long trackId) {
        trackCommandService.deleteTrackInPlaylist(playlistId, trackId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "트랙 이동", description = "트랙을 현재 플레이리스트에서 다른 플레이리스트로 이동합니다. 회원만 사용할 수 있습니다.")
    @ApiResponse(responseCode = "204", description = "트랙 이동 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PlaylistException.class, TrackException.class})
    @PatchMapping("{playlistId}/tracks/{trackId}/move")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<Void> moveTrack(
            @Parameter(description = "현재 플레이리스트 ID") @PathVariable Long playlistId,
            @Parameter(description = "이동할 트랙 ID") @PathVariable Long trackId,
            @Valid @RequestBody MoveTrackRequest request) {
        MoveTrackCommand command = new MoveTrackCommand(request.getTargetPlaylistId());
        trackCommandService.moveTrackToPlaylist(playlistId, trackId, command);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "트랙 순서 변경", description = "드래그 앤 드롭으로 플레이리스트 내 트랙의 순서를 변경합니다.")
    @ApiResponse(responseCode = "204", description = "트랙 순서 변경 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PlaylistException.class, TrackException.class})
    @PutMapping("{playlistId}/tracks/{trackId}")
    public ResponseEntity<Void> updateMusicOrder(
            @Parameter(description = "플레이리스트 ID") @PathVariable Long playlistId,
            @Parameter(description = "순서를 변경할 트랙 ID") @PathVariable Long trackId,
            @Valid @RequestBody UpdateTrackOrderRequest request) {
        UpdateTrackOrderCommand command = new UpdateTrackOrderCommand(request.getNextOrderNumber());
        trackCommandService.updateTrackOrderInPlaylist(playlistId, trackId, command);
        return ResponseEntity.noContent().build();
    }

}
