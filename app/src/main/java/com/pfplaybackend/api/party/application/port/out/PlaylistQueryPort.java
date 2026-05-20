package com.pfplaybackend.api.party.application.port.out;

public interface PlaylistQueryPort {
    boolean isEmptyPlaylist(Long playlistId);
    boolean isOwnedBy(Long playlistId, Long userId);
}
