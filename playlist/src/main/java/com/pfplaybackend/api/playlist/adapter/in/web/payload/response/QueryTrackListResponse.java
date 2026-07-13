package com.pfplaybackend.api.playlist.adapter.in.web.payload.response;

import com.pfplaybackend.api.common.dto.PaginationDto;
import com.pfplaybackend.api.playlist.application.dto.PlaylistTrackDto;
import com.pfplaybackend.api.playlist.application.dto.TrackListView;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;

@Getter
@Builder
public class QueryTrackListResponse {
    private List<PlaylistTrackDto> content;
    // 재생 커서 — CurrentDJ에겐 NOW(지금 재생 중) 트랙. 커서 미설정 시 null.
    // NEXT(다음 재생 곡)는 이 커서 + 현재 트랙 순서로부터 클라이언트가 파생한다.
    private Long lastPlayedTrackId;
    private PaginationDto pagination;

    public static QueryTrackListResponse from(TrackListView view) {
        Page<PlaylistTrackDto> page = view.page();
        return QueryTrackListResponse.builder()
                .content(page.getContent())
                .lastPlayedTrackId(view.lastPlayedTrackId())
                .pagination(new PaginationDto(
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalPages(),
                        page.getTotalElements(),
                        page.hasNext()
                ))
                .build();
    }
}