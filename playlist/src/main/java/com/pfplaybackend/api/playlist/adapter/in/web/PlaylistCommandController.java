package com.pfplaybackend.api.playlist.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import com.pfplaybackend.api.playlist.adapter.in.web.payload.request.CreatePlaylistRequest;
import com.pfplaybackend.api.playlist.adapter.in.web.payload.request.DeletePlaylistsRequest;
import com.pfplaybackend.api.playlist.adapter.in.web.payload.request.UpdatePlaylistNameRequest;
import com.pfplaybackend.api.playlist.adapter.in.web.payload.response.CreatePlaylistResponse;
import com.pfplaybackend.api.playlist.application.service.PlaylistCommandService;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.exception.PlaylistException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Playlist API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/playlists")
public class PlaylistCommandController {

    private final PlaylistCommandService playlistCommandService;

    @Operation(summary = "플레이리스트 생성", description = "새로운 플레이리스트를 생성합니다. 회원만 사용할 수 있습니다.")
    @ApiResponse(responseCode = "201", description = "플레이리스트 생성 성공")
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping()
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<ApiCommonResponse<CreatePlaylistResponse>> create(@RequestBody @Valid CreatePlaylistRequest request) {
        PlaylistData playlist = playlistCommandService.createPlaylist(request.getName());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(CreatePlaylistResponse.from(playlist)));
    }

    @Operation(summary = "플레이리스트 삭제", description = "하나 이상의 플레이리스트를 일괄 삭제합니다. 회원만 사용할 수 있습니다.")
    @ApiResponse(responseCode = "204", description = "플레이리스트 삭제 성공")
    @SecurityRequirement(name = "cookieAuth")
    @DeleteMapping()
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<Void> deletePlaylist(@Valid @RequestBody DeletePlaylistsRequest request) {
        playlistCommandService.deletePlaylist(request.getPlaylistIds());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "플레이리스트 이름 변경", description = "지정된 플레이리스트의 이름을 변경합니다. 회원만 사용할 수 있습니다.")
    @ApiResponse(responseCode = "204", description = "이름 변경 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PlaylistException.class})
    @PatchMapping("{playlistId}")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<Void> modifyPlaylistName(
            @Parameter(description = "변경할 플레이리스트 ID") @PathVariable Long playlistId,
            @Valid @RequestBody UpdatePlaylistNameRequest request) {
        playlistCommandService.renamePlaylist(playlistId, request.getName());
        return ResponseEntity.noContent().build();
    }
}
