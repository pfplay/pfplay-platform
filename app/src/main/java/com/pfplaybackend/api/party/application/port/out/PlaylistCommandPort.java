package com.pfplaybackend.api.party.application.port.out;

import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.application.dto.PlaybackTrackDto;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;

public interface PlaylistCommandPort {
    AddedTrackInfo grabTrack(UserId userId, String linkId);
    java.util.List<PlaybackTrackDto> peekTracksFromCursor(PlaylistId playlistId);
    void advancePlaybackCursor(PlaylistId playlistId, Long trackId);
    /** Quick-DJ(#331) — per-user TEMP 플리를 리셋 후 선택 곡 1개를 담아 그 id 를 반환한다. */
    Long prepareOneShotPlaylist(UserId userId, AddTrackCommand command);
}
