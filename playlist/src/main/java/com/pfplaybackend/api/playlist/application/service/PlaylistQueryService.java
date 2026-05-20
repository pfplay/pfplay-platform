package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.application.dto.PlaylistSummaryDto;
import com.pfplaybackend.api.playlist.application.port.out.PlaylistQueryPort;
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

    @Transactional(readOnly = true)
    public boolean isOwnedBy(Long playlistId, UserId userId) {
        return queryPort.findByIdAndUserId(playlistId, userId) != null;
    }
}