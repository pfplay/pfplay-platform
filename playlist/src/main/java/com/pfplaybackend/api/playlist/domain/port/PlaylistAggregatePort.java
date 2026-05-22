package com.pfplaybackend.api.playlist.domain.port;

import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;

import java.util.List;
import java.util.Optional;

public interface PlaylistAggregatePort {

    // ===== Root: PlaylistData =====
    PlaylistData savePlaylist(PlaylistData playlist);
    List<PlaylistData> findAllPlaylistsByOwner(UserId userId);
    List<PlaylistData> findPlaylistsByOwnerAndType(UserId userId, PlaylistType type);
    PlaylistData findPlaylistByOwnerAndType(UserId userId, PlaylistType type);
    Optional<PlaylistData> findPlaylistByIdAndOwnerAndType(Long playlistId, UserId userId, PlaylistType type);
    Optional<PlaylistData> findPlaylistByIdAndOwner(Long playlistId, UserId userId);
    Optional<PlaylistData> findPlaylistById(Long playlistId);
    void advancePlaybackCursor(Long playlistId, Long trackId);
    Long deletePlaylistsByIds(List<Long> playlistIds);

    // ===== Child: TrackData =====
    TrackData saveTrack(TrackData track);
    void deleteTrack(TrackData track);
    Optional<TrackData> findTrackByPlaylistAndLink(PlaylistId playlistId, String linkId);
    TrackData findFirstTrackByLink(String linkId);
    Optional<TrackData> findTrackByIdAndPlaylist(Long trackId, PlaylistId playlistId);
    boolean hasTracksByPlaylist(PlaylistId playlistId);

    // ===== Track Reordering (batch operations) =====
    void shiftAllOrdersDown(Long playlistId);
    void shiftUpTrackOrderByDelete(Long playlistId, Integer deleteOrderNumber);
    void shiftUpTrackOrderByDnD(Long playlistId, Integer prevOrderNumber, Integer nextOrderNumber);
    void shiftDownTrackOrderByDnD(Long playlistId, Integer prevOrderNumber, Integer nextOrderNumber);
}
