package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.application.dto.PlaylistSummaryDto;
import com.pfplaybackend.api.playlist.application.port.out.PlaylistQueryPort;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 플레이리스트 CRUD
 */
@Service
@RequiredArgsConstructor
public class PlaylistQueryService {

    private final PlaylistQueryPort queryPort;
    private final PlaylistAggregatePort aggregatePort;

    @Transactional(readOnly = true)
    public List<PlaylistSummaryDto> getPlaylists() {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        return queryPort.findAllByUserId(authContext.getUserId());
    }

    @Transactional(readOnly = true)
    public PlaylistSummaryDto getPlaylist(Long playlistId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        return queryPort.findByIdAndUserId(playlistId, authContext.getUserId());
    }

    /**
     * 소유권 검증 — 조회 숨김(TEMP 제외, V36)과는 별개 관심사라 view 쿼리가 아닌
     * aggregate 포트의 원시 조회를 쓴다. Quick-DJ(#331)의 TEMP 플리도 본인 소유면 true —
     * view 쿼리에 얹으면 ONE_SHOT enqueue 의 ownership 검증이 NOT_OWNED 로 오탐한다.
     */
    @Transactional(readOnly = true)
    public boolean isOwnedBy(Long playlistId, UserId userId) {
        return aggregatePort.findPlaylistByIdAndOwner(playlistId, userId).isPresent();
    }
}