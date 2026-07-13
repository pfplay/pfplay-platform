package com.pfplaybackend.api.bootstrap.adapter;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.application.service.PlaylistCommandService;
import com.pfplaybackend.api.user.application.port.out.PlaylistSetupPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlaylistSetupAdapter implements PlaylistSetupPort {

    private final PlaylistCommandService playlistCommandService;

    @Override
    public void createDefaultPlaylist(UserId userId) {
        playlistCommandService.createDefaultPlaylist(userId);
    }

    @Override
    public void createDefaultDjPlaylist(UserId userId) {
        playlistCommandService.createDefaultDjPlaylist(userId);
    }
}
