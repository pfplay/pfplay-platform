package com.pfplaybackend.api.playlist.adapter.in.web.search;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.playlist.adapter.in.web.payload.request.MusicSearchRequest;
import com.pfplaybackend.api.playlist.adapter.in.web.payload.response.QueryMusicSearchResponse;
import com.pfplaybackend.api.playlist.application.service.search.MusicSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Playlist API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/music-search")
public class MusicSearchController {

    private final MusicSearchService musicSearchService;

    @Operation(summary = "음악 검색", description = "외부 플랫폼에서 음악을 검색합니다. 검색어(q)는 필수이며, 플랫폼을 선택적으로 지정할 수 있습니다. 회원만 사용할 수 있습니다.")
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping()
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<ApiCommonResponse<QueryMusicSearchResponse>> getSearchList(@ModelAttribute @Valid MusicSearchRequest request) {
        QueryMusicSearchResponse searchMusicResponse = QueryMusicSearchResponse.from(musicSearchService.getSearchList(request.getQ()));
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiCommonResponse.success(searchMusicResponse));
    }
}
