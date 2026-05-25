package com.pfplaybackend.api.playlist.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.application.dto.PlaylistSummaryDto;
import com.pfplaybackend.api.playlist.application.dto.PlaylistTrackDto;
import com.pfplaybackend.api.playlist.application.port.out.PlaylistQueryPort;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PlaylistAggregateAdapter implements PlaylistAggregatePort, PlaylistQueryPort {

    private final PlaylistRepository playlistRepository;
    private final TrackRepository trackRepository;

    // ===== Root: PlaylistData =====

    @Override
    public PlaylistData savePlaylist(PlaylistData playlist) {
        return playlistRepository.save(playlist);
    }

    @Override
    public List<PlaylistData> findAllPlaylistsByOwner(UserId userId) {
        return playlistRepository.findAllByOwnerId(userId);
    }

    @Override
    public List<PlaylistData> findPlaylistsByOwnerAndType(UserId userId, PlaylistType type) {
        return playlistRepository.findByOwnerIdAndTypeOrderByOrderNumberDesc(userId, type);
    }

    @Override
    public PlaylistData findPlaylistByOwnerAndType(UserId userId, PlaylistType type) {
        return playlistRepository.findByOwnerIdAndType(userId, type);
    }

    @Override
    public Optional<PlaylistData> findPlaylistByIdAndOwnerAndType(Long playlistId, UserId userId, PlaylistType type) {
        return playlistRepository.findByIdAndOwnerIdAndType(playlistId, userId, type);
    }

    @Override
    public Optional<PlaylistData> findPlaylistByIdAndOwner(Long playlistId, UserId userId) {
        return playlistRepository.findByIdAndOwnerId(playlistId, userId);
    }

    @Override
    public Optional<PlaylistData> findPlaylistById(Long playlistId) {
        return playlistRepository.findById(playlistId);
    }

    @Override
    public void advancePlaybackCursor(Long playlistId, Long trackId) {
        playlistRepository.updateLastPlayedTrackId(playlistId, trackId);
    }

    @Override
    public Long deletePlaylistsByIds(List<Long> playlistIds) {
        return playlistRepository.deleteByListIds(playlistIds);
    }

    // ===== Child: TrackData =====

    @Override
    public TrackData saveTrack(TrackData track) {
        return trackRepository.save(track);
    }

    @Override
    public void deleteTrack(TrackData track) {
        trackRepository.delete(track);
    }

    @Override
    public Optional<TrackData> findTrackByPlaylistAndLink(PlaylistId playlistId, String linkId) {
        return trackRepository.findByPlaylistIdAndLinkId(playlistId, linkId);
    }

    @Override
    public TrackData findFirstTrackByLink(String linkId) {
        return trackRepository.findFirstByLinkId(linkId);
    }

    @Override
    public Optional<TrackData> findTrackByIdAndPlaylist(Long trackId, PlaylistId playlistId) {
        return trackRepository.findByIdAndPlaylistId(trackId, playlistId);
    }

    @Override
    public boolean hasTracksByPlaylist(PlaylistId playlistId) {
        return trackRepository.existsByPlaylistId(playlistId);
    }

    // ===== Track Reordering =====

    @Override
    public void shiftAllOrdersDown(Long playlistId) {
        trackRepository.shiftAllOrdersDown(playlistId);
    }

    @Override
    public void shiftUpTrackOrderByDelete(Long playlistId, Integer deleteOrderNumber) {
        trackRepository.shiftUpOrderByDelete(playlistId, deleteOrderNumber);
    }

    @Override
    public void shiftUpTrackOrderByDnD(Long playlistId, Integer prevOrderNumber, Integer nextOrderNumber) {
        trackRepository.shiftUpOrderByDnD(playlistId, prevOrderNumber, nextOrderNumber);
    }

    @Override
    public void shiftDownTrackOrderByDnD(Long playlistId, Integer prevOrderNumber, Integer nextOrderNumber) {
        trackRepository.shiftDownOrderByDnD(playlistId, prevOrderNumber, nextOrderNumber);
    }

    // ===== Query Port (DTO-returning methods) =====

    @Override
    public List<PlaylistSummaryDto> findAllByUserId(UserId userId) {
        return playlistRepository.findAllByUserId(userId);
    }

    @Override
    public PlaylistSummaryDto findByIdAndUserId(Long playlistId, UserId userId) {
        return playlistRepository.findByIdAndUserId(playlistId, userId);
    }

    @Override
    public Page<PlaylistTrackDto> getTracksWithPagination(PlaylistId playlistId, Pageable pageable) {
        return trackRepository.getTracksWithPagination(playlistId, pageable);
    }
}
