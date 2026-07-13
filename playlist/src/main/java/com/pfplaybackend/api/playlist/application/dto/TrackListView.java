package com.pfplaybackend.api.playlist.application.dto;

import org.springframework.data.domain.Page;

/**
 * 트랙 목록 조회 결과 + 재생 커서.
 * lastPlayedTrackId(커서)는 CurrentDJ에겐 NOW(지금 재생 중) 트랙이다.
 * NEXT(다음 재생 곡)는 커서 + 현재 트랙 순서로부터 클라이언트가 파생한다
 * (낙관적 재정렬에도 refetch 없이 정확하도록).
 */
public record TrackListView(
        Page<PlaylistTrackDto> page,
        Long lastPlayedTrackId
) {}
