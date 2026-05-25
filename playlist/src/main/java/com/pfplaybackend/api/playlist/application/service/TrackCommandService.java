package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.playlist.application.dto.PlaybackTrackDto;
import com.pfplaybackend.api.playlist.application.dto.PlaylistSummaryDto;
import com.pfplaybackend.api.playlist.application.dto.PlaylistTrackDto;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import com.pfplaybackend.api.playlist.application.dto.command.MoveTrackCommand;
import com.pfplaybackend.api.playlist.application.dto.command.UpdateTrackOrderCommand;
import com.pfplaybackend.api.playlist.application.port.out.PlaylistQueryPort;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.playlist.domain.enums.InsertPosition;
import com.pfplaybackend.api.playlist.domain.event.TrackAddedEvent;
import com.pfplaybackend.api.playlist.domain.event.TrackRemovedEvent;
import com.pfplaybackend.api.playlist.domain.exception.PlaylistException;
import com.pfplaybackend.api.playlist.domain.exception.TrackException;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TrackCommandService {

    private static final int MAX_PLAYLIST_TRACK_COUNT = 100;

    private final PlaylistAggregatePort aggregatePort;
    private final PlaylistQueryPort queryPort;
    private final PlaylistQueryService playlistQueryService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Long addTrackInPlaylist(Long playlistId, AddTrackCommand command) {
        // 명시적 곡 추가는 맨 위(order 1)로 — 다음 내 차례에 가장 먼저 재생되도록.
        return insertTrack(playlistId, command, InsertPosition.HEAD);
    }

    @Transactional
    public Long insertTrack(Long playlistId, AddTrackCommand command, InsertPosition position) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        // 플레이리스트 접근 권한 검사
        PlaylistData playlistData = aggregatePort.findPlaylistByIdAndOwner(playlistId, authContext.getUserId())
                .orElseThrow(() -> ExceptionCreator.create(PlaylistException.NOT_FOUND_PLAYLIST));
        // 트랙 중복 검사
        Optional<TrackData> optional = aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(playlistData.getId()), command.linkId());
        if (optional.isPresent()) throw ExceptionCreator.create(TrackException.DUPLICATE_TRACK_IN_PLAYLIST);
        // 최대 보유 한계치 초과 검사
        PlaylistSummaryDto playlistSummary = playlistQueryService.getPlaylist(playlistId);
        if (playlistSummary.musicCount() >= MAX_PLAYLIST_TRACK_COUNT) throw ExceptionCreator.create(TrackException.EXCEEDED_TRACK_LIMIT);

        int orderNumber;
        if (position == InsertPosition.HEAD) {
            // 기존 전체를 +1 밀고 신규 곡을 맨 위(1)로.
            aggregatePort.shiftAllOrdersDown(playlistData.getId());
            orderNumber = 1;
        } else {
            // TAIL — 기존 동작 보존 (grab 경로).
            orderNumber = playlistSummary.musicCount() == 0 ? 1 : (int) (playlistSummary.musicCount() + 1);
        }

        TrackData trackData = TrackData.builder()
                .playlistId(new PlaylistId(playlistData.getId()))
                .name(command.name())
                .linkId(command.linkId())
                .duration(Duration.fromString(command.duration()))
                .orderNumber(orderNumber)
                .thumbnailImage(command.thumbnailImage())
                .build();

        TrackData saved = aggregatePort.saveTrack(trackData);
        eventPublisher.publishEvent(new TrackAddedEvent(new PlaylistId(playlistId), command.linkId(), command.name()));
        return saved.getId();
    }

    @Transactional
    public void updateTrackOrderInPlaylist(Long playlistId, Long trackId, UpdateTrackOrderCommand command) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        // 플레이리스트 접근 권한 검사
        aggregatePort.findPlaylistByIdAndOwner(playlistId, authContext.getUserId())
                .orElseThrow(() -> ExceptionCreator.create(PlaylistException.NOT_FOUND_PLAYLIST));

        // 타겟 트랙 존재 여부 검사
        TrackData trackData = aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(playlistId))
                .orElseThrow(() -> ExceptionCreator.create(TrackException.NOT_FOUND_TRACK));

        Integer prevOrderNumber = trackData.getOrderNumber();
        Integer nextOrderNumber = command.nextOrderNumber();
        // 이동할 순서 값에 대한 유효성 검증
        PlaylistSummaryDto playlistSummary = playlistQueryService.getPlaylist(playlistId);
        if (prevOrderNumber < 1 || nextOrderNumber < 1
                || Objects.equals(prevOrderNumber, nextOrderNumber)
                || playlistSummary.musicCount() < nextOrderNumber)
            throw ExceptionCreator.create(TrackException.INVALID_TRACK_ORDER);

        if (prevOrderNumber < nextOrderNumber) {
            // prevOrderNumber < x <= nextOrderNumber 사이에 있는 Track 레코드의 order_number 를 -1씩 변경
            aggregatePort.shiftUpTrackOrderByDnD(playlistId, prevOrderNumber, nextOrderNumber);
        }
        if (prevOrderNumber > nextOrderNumber) {
            // nextOrderNumber <= x < prevOrderNumber  사이에 있는 Track 레코드의 order_number 를 +1씩 변경
            aggregatePort.shiftDownTrackOrderByDnD(playlistId, prevOrderNumber, nextOrderNumber);
        }

        trackData.reorder(nextOrderNumber);
        aggregatePort.saveTrack(trackData);
    }

    @Transactional
    public void moveTrackToPlaylist(Long sourcePlaylistId, Long trackId, MoveTrackCommand command) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId ownerId = authContext.getUserId();
        // 소스 플레이리스트 소유권 검증
        aggregatePort.findPlaylistByIdAndOwner(sourcePlaylistId, ownerId)
                .orElseThrow(() -> ExceptionCreator.create(PlaylistException.NOT_FOUND_PLAYLIST));
        // 타겟 플레이리스트 소유권 검증
        PlaylistData targetPlaylistData = aggregatePort.findPlaylistByIdAndOwner(command.targetPlaylistId(), ownerId)
                .orElseThrow(() -> ExceptionCreator.create(PlaylistException.NOT_FOUND_PLAYLIST));
        // 소스에서 트랙 조회
        TrackData trackData = aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(sourcePlaylistId))
                .orElseThrow(() -> ExceptionCreator.create(TrackException.NOT_FOUND_TRACK));
        // 타겟에 동일 linkId 중복 검사
        Optional<TrackData> duplicate = aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(targetPlaylistData.getId()), trackData.getLinkId());
        if (duplicate.isPresent()) throw ExceptionCreator.create(TrackException.DUPLICATE_TRACK_IN_PLAYLIST);
        // 타겟 트랙 개수 15개 제한 검사
        PlaylistSummaryDto targetSummary = playlistQueryService.getPlaylist(command.targetPlaylistId());
        if (targetSummary.musicCount() >= MAX_PLAYLIST_TRACK_COUNT) throw ExceptionCreator.create(TrackException.EXCEEDED_TRACK_LIMIT);
        // 소스 orderNumber 재정렬
        aggregatePort.shiftUpTrackOrderByDelete(sourcePlaylistId, trackData.getOrderNumber());
        // 트랙을 타겟 플레이리스트로 이동
        int nextOrderNumber = (int) (targetSummary.musicCount() + 1);
        trackData.moveToPlaylist(new PlaylistId(targetPlaylistData.getId()), nextOrderNumber);
        aggregatePort.saveTrack(trackData);
    }

    @Transactional
    public void deleteTrackInPlaylist(Long playlistId, Long trackId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        // 플레이리스트 접근 권한 검사
        aggregatePort.findPlaylistByIdAndOwner(playlistId, authContext.getUserId())
                .orElseThrow(() -> ExceptionCreator.create(PlaylistException.NOT_FOUND_PLAYLIST));

        // 타겟 트랙 존재 여부 검사
        TrackData trackData = aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(playlistId))
                .orElseThrow(() -> ExceptionCreator.create(TrackException.NOT_FOUND_TRACK));

        Integer deleteOrderNumber = trackData.getOrderNumber();
        aggregatePort.shiftUpTrackOrderByDelete(playlistId, deleteOrderNumber);
        aggregatePort.deleteTrack(trackData);
        eventPublisher.publishEvent(new TrackRemovedEvent(new PlaylistId(playlistId), trackId, trackData.getLinkId(), trackData.getName()));
    }

    /**
     * 재생 커서(last_played_track_id) 다음 위치부터 wrap 정렬한 트랙 목록을 반환한다.
     * 커서가 null이거나 가리키던 트랙이 목록에 없으면(삭제) 자연 순서(top부터)로 반환한다.
     * 호출자(party)는 이 목록에서 첫 재생가능 곡을 고르고 {@link #advancePlaybackCursor}로 커서를 갱신한다.
     */
    @Transactional(readOnly = true)
    public List<PlaybackTrackDto> peekTracksFromCursor(Long playlistId) {
        Pageable pageable = PageRequest.of(0, MAX_PLAYLIST_TRACK_COUNT, Sort.by(Sort.Direction.ASC, "orderNumber"));
        List<PlaylistTrackDto> ordered = queryPort.getTracksWithPagination(new PlaylistId(playlistId), pageable).getContent();

        Long cursor = aggregatePort.findPlaylistById(playlistId)
                .map(PlaylistData::getLastPlayedTrackId)
                .orElse(null);

        int start = 0; // 커서 null 또는 미발견 → top(0)부터
        if (cursor != null) {
            for (int i = 0; i < ordered.size(); i++) {
                if (cursor.equals(ordered.get(i).trackId())) {
                    start = i + 1; // 커서 "다음"부터
                    break;
                }
            }
        }

        int n = ordered.size();
        List<PlaybackTrackDto> rotated = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            PlaylistTrackDto dto = ordered.get((start + k) % n);
            rotated.add(new PlaybackTrackDto(dto.trackId(), dto.linkId(), dto.name(),
                    dto.thumbnailImage(), dto.duration(), dto.orderNumber()));
        }
        return rotated;
    }

    @Transactional
    public void advancePlaybackCursor(Long playlistId, Long trackId) {
        aggregatePort.advancePlaybackCursor(playlistId, trackId);
    }
}
