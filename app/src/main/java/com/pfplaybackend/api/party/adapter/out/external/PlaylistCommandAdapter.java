package com.pfplaybackend.api.party.adapter.out.external;

import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.port.out.AddedTrackInfo;
import com.pfplaybackend.api.party.application.port.out.PlaylistCommandPort;
import com.pfplaybackend.api.playlist.application.dto.GrabbedTrackDto;
import com.pfplaybackend.api.playlist.application.dto.PlaybackTrackDto;
import com.pfplaybackend.api.playlist.application.service.GrabTrackService;
import com.pfplaybackend.api.playlist.application.service.TrackCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PlaylistCommandAdapter implements PlaylistCommandPort {

    private final GrabTrackService grabTrackService;
    private final TrackCommandService trackCommandService;

    @Override
    public AddedTrackInfo grabTrack(UserId userId, String linkId) {
        GrabbedTrackDto grabbed = grabTrackService.grabTrack(userId, linkId);
        return new AddedTrackInfo(grabbed.trackId(), grabbed.playlistId());
    }

    @Override
    public List<PlaybackTrackDto> peekOrderedTracks(PlaylistId playlistId) {
        return trackCommandService.peekOrderedTracks(playlistId.getId());
    }

    @Override
    public void rotatePlayed(PlaylistId playlistId, int playedOrderNumber, long totalCount) {
        trackCommandService.rotatePlayed(playlistId.getId(), playedOrderNumber, totalCount);
    }
}
