package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.playlist.application.dto.GrabbedTrackDto;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.exception.TrackException;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GrabTrackService {

    private final PlaylistAggregatePort aggregatePort;
    private final TrackCommandService trackCommandService;

    @Transactional
    public GrabbedTrackDto grabTrack(UserId userId, String linkId) {
        TrackData targetTrackData = aggregatePort.findFirstTrackByLink(linkId);
        if (targetTrackData == null) throw ExceptionCreator.create(TrackException.NOT_FOUND_TRACK);

        PlaylistData playlistData = aggregatePort.findPlaylistByOwnerAndType(userId, PlaylistType.GRABLIST);
        // LinkId cannot be duplicated.
        Optional<TrackData> optional = aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(playlistData.getId()), linkId);
        if (optional.isPresent()) throw ExceptionCreator.create(TrackException.DUPLICATE_TRACK_IN_PLAYLIST);

        AddTrackCommand command = new AddTrackCommand(
                targetTrackData.getName(),
                targetTrackData.getLinkId(),
                targetTrackData.getDuration().toDisplayString(),
                targetTrackData.getThumbnailImage()
        );
        Long trackId = trackCommandService.addTrackInPlaylist(playlistData.getId(), command);
        return new GrabbedTrackDto(trackId, playlistData.getId());
    }
}
