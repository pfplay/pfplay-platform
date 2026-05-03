package com.pfplaybackend.api.party.application.port.out;

import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.application.dto.PlaybackTrackDto;

public interface PlaylistCommandPort {
    AddedTrackInfo grabTrack(UserId userId, String linkId);
    PlaybackTrackDto getFirstTrack(PlaylistId playlistId);
}
