package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
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
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackCommandServiceTest {

    private static final String TEST_PLAYLIST_NAME = "test";
    private static final String SONG_NAME = "song";
    private static final String LINK_ID = "linkId1";
    private static final String DURATION = "03:00";
    private static final String SOURCE_PLAYLIST = "source";
    private static final String TARGET_PLAYLIST = "target";
    private static final String THUMBNAIL = "thumb.jpg";

    @Mock
    private PlaylistAggregatePort aggregatePort;
    @Mock
    private PlaylistQueryPort queryPort;
    @Mock
    private PlaylistQueryService playlistQueryService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TrackCommandService trackCommandService;

    private UserId userId;

    @BeforeEach
    void setUp() {
        userId = new UserId(1L);
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        lenient().when(authContext.getAuthorityTier()).thenReturn(AuthorityTier.FM);
        ThreadLocalContext.setContext(authContext);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    // ========== addTrackInPlaylist ==========

    @Test
    @DisplayName("트랙 추가 성공 — 맨 위(order 1) 삽입 + 기존 전체 shift")
    void addTrackInPlaylistSuccess() {
        // given
        Long playlistId = 1L;
        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        AddTrackCommand command = new AddTrackCommand(SONG_NAME, LINK_ID, DURATION, THUMBNAIL);

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlistData));
        when(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(playlistId), LINK_ID)).thenReturn(Optional.empty());
        when(playlistQueryService.getPlaylist(playlistId))
                .thenReturn(new PlaylistSummaryDto(playlistId, TEST_PLAYLIST_NAME, 0, PlaylistType.PLAYLIST, 3L));
        when(aggregatePort.saveTrack(any())).thenAnswer(invocation -> {
            TrackData track = invocation.getArgument(0);
            TrackData saved = TrackData.builder().playlistId(track.getPlaylistId()).name(track.getName())
                    .linkId(track.getLinkId()).duration(track.getDuration()).orderNumber(track.getOrderNumber())
                    .thumbnailImage(track.getThumbnailImage()).build();
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });

        // when
        trackCommandService.addTrackInPlaylist(playlistId, command);

        // then — 신규 곡은 맨 위(order 1), 기존 전체 +1 shift
        verify(aggregatePort).shiftAllOrdersDown(playlistId);
        verify(aggregatePort, times(1)).saveTrack(argThat(track ->
                track.getOrderNumber() == 1 && LINK_ID.equals(track.getLinkId())
        ));
    }

    @Test
    @DisplayName("insertTrack(TAIL) — 맨 뒤(order=count+1) 추가, shift 없음 (grab 경로)")
    void insertTrack_tail_appendsAtEnd() {
        // given
        Long playlistId = 1L;
        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.GRABLIST).orderNumber(0).build();
        AddTrackCommand command = new AddTrackCommand(SONG_NAME, LINK_ID, DURATION, THUMBNAIL);
        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlistData));
        when(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(playlistId), LINK_ID)).thenReturn(Optional.empty());
        when(playlistQueryService.getPlaylist(playlistId))
                .thenReturn(new PlaylistSummaryDto(playlistId, TEST_PLAYLIST_NAME, 0, PlaylistType.GRABLIST, 3L));
        when(aggregatePort.saveTrack(any())).thenAnswer(inv -> inv.getArgument(0));

        // when
        trackCommandService.insertTrack(playlistId, command, InsertPosition.TAIL);

        // then — 기존 동작: count(3)+1 = 4, shift 없음
        verify(aggregatePort, never()).shiftAllOrdersDown(anyLong());
        verify(aggregatePort).saveTrack(argThat(track -> track.getOrderNumber() == 4));
    }

    @Test
    @DisplayName("트랙 추가 실패 — 플레이리스트가 존재하지 않으면 NotFoundException")
    void addTrackInPlaylistPlaylistNotFound() {
        // given
        Long playlistId = 999L;
        AddTrackCommand command = new AddTrackCommand(SONG_NAME, LINK_ID, DURATION, THUMBNAIL);
        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> trackCommandService.addTrackInPlaylist(playlistId, command))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("트랙 추가 실패 — 중복 트랙이면 ConflictException")
    void addTrackInPlaylistDuplicateTrack() {
        // given
        Long playlistId = 1L;
        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        AddTrackCommand command = new AddTrackCommand(SONG_NAME, LINK_ID, DURATION, THUMBNAIL);
        TrackData existingTrack = TrackData.builder()
                .playlistId(new PlaylistId(playlistData.getId())).name(SONG_NAME).linkId(LINK_ID).duration(Duration.fromString(DURATION)).orderNumber(1).build();

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlistData));
        when(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(playlistId), LINK_ID)).thenReturn(Optional.of(existingTrack));

        // when & then
        assertThatThrownBy(() -> trackCommandService.addTrackInPlaylist(playlistId, command))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("트랙 추가 실패 — 100개 초과 시 ConflictException")
    void addTrackInPlaylistExceededLimit() {
        // given
        Long playlistId = 1L;
        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        AddTrackCommand command = new AddTrackCommand(SONG_NAME, LINK_ID, DURATION, THUMBNAIL);

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlistData));
        when(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(playlistId), LINK_ID)).thenReturn(Optional.empty());
        when(playlistQueryService.getPlaylist(playlistId))
                .thenReturn(new PlaylistSummaryDto(playlistId, TEST_PLAYLIST_NAME, 0, PlaylistType.PLAYLIST, 100L));

        // when & then
        assertThatThrownBy(() -> trackCommandService.addTrackInPlaylist(playlistId, command))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("트랙 추가 성공 — 상한 직전(99개)이면 추가된다")
    void addTrackInPlaylistAtLimitBoundary() {
        // given
        Long playlistId = 1L;
        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        AddTrackCommand command = new AddTrackCommand(SONG_NAME, LINK_ID, DURATION, THUMBNAIL);

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlistData));
        when(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(playlistId), LINK_ID)).thenReturn(Optional.empty());
        when(playlistQueryService.getPlaylist(playlistId))
                .thenReturn(new PlaylistSummaryDto(playlistId, TEST_PLAYLIST_NAME, 0, PlaylistType.PLAYLIST, 99L));
        when(aggregatePort.saveTrack(any())).thenAnswer(invocation -> {
            TrackData track = invocation.getArgument(0);
            TrackData saved = TrackData.builder().playlistId(track.getPlaylistId()).name(track.getName())
                    .linkId(track.getLinkId()).duration(track.getDuration()).orderNumber(track.getOrderNumber())
                    .thumbnailImage(track.getThumbnailImage()).build();
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });

        // when
        trackCommandService.addTrackInPlaylist(playlistId, command);

        // then — musicCount 99 < 100 이므로 추가됨. add-to-head 라 order 1 + 기존 전체 shift.
        verify(aggregatePort).shiftAllOrdersDown(playlistId);
        verify(aggregatePort, times(1)).saveTrack(argThat(track -> track.getOrderNumber() == 1));
    }

    // ========== deleteTrackInPlaylist ==========

    @Test
    @DisplayName("트랙 삭제 성공 — shiftUpTrackOrderByDelete 및 deleteTrack 호출")
    void deleteTrackInPlaylistSuccess() {
        // given
        Long playlistId = 1L;
        Long trackId = 10L;
        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        TrackData trackData = TrackData.builder()
                .playlistId(new PlaylistId(playlistData.getId())).name(SONG_NAME).linkId(LINK_ID).duration(Duration.fromString(DURATION)).orderNumber(3).build();

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlistData));
        when(aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(playlistId))).thenReturn(Optional.of(trackData));

        // when
        trackCommandService.deleteTrackInPlaylist(playlistId, trackId);

        // then
        verify(aggregatePort, times(1)).shiftUpTrackOrderByDelete(playlistId, 3);
        verify(aggregatePort, times(1)).deleteTrack(trackData);
    }

    @Test
    @DisplayName("트랙 삭제 실패 — 트랙이 존재하지 않으면 NotFoundException")
    void deleteTrackInPlaylistTrackNotFound() {
        // given
        Long playlistId = 1L;
        Long trackId = 999L;
        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlistData));
        when(aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(playlistId))).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> trackCommandService.deleteTrackInPlaylist(playlistId, trackId))
                .isInstanceOf(NotFoundException.class);
    }

    // ========== moveTrackToPlaylist ==========

    @Test
    @DisplayName("트랙 이동 성공 — shiftUpTrackOrderByDelete 호출 및 playlistId·orderNumber 변경")
    void moveTrackToPlaylistSuccess() {
        // given
        Long sourcePlaylistId = 1L;
        Long targetPlaylistId = 2L;
        Long trackId = 10L;

        PlaylistData sourcePlaylist = PlaylistData.builder()
                .id(sourcePlaylistId).ownerId(userId).name(SOURCE_PLAYLIST).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        PlaylistData targetPlaylist = PlaylistData.builder()
                .id(targetPlaylistId).ownerId(userId).name(TARGET_PLAYLIST).type(PlaylistType.PLAYLIST).orderNumber(1).build();

        TrackData trackData = TrackData.builder()
                .playlistId(new PlaylistId(sourcePlaylist.getId())).name(SONG_NAME).linkId(LINK_ID).duration(Duration.fromString(DURATION)).orderNumber(2).build();

        MoveTrackCommand command = new MoveTrackCommand(targetPlaylistId);

        when(aggregatePort.findPlaylistByIdAndOwner(sourcePlaylistId, userId)).thenReturn(Optional.of(sourcePlaylist));
        when(aggregatePort.findPlaylistByIdAndOwner(targetPlaylistId, userId)).thenReturn(Optional.of(targetPlaylist));
        when(aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(sourcePlaylistId))).thenReturn(Optional.of(trackData));
        when(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(targetPlaylistId), LINK_ID)).thenReturn(Optional.empty());
        when(playlistQueryService.getPlaylist(targetPlaylistId))
                .thenReturn(new PlaylistSummaryDto(targetPlaylistId, TARGET_PLAYLIST, 1, PlaylistType.PLAYLIST, 5L));

        // when
        trackCommandService.moveTrackToPlaylist(sourcePlaylistId, trackId, command);

        // then
        verify(aggregatePort, times(1)).shiftUpTrackOrderByDelete(sourcePlaylistId, 2);
        verify(aggregatePort, times(1)).saveTrack(argThat(track ->
                track.getPlaylistId().equals(new PlaylistId(targetPlaylist.getId())) && track.getOrderNumber() == 6
        ));
    }

    @Test
    @DisplayName("트랙 이동 실패 — 소스 플레이리스트가 존재하지 않으면 NotFoundException")
    void moveTrackToPlaylistSourceNotFound() {
        // given
        Long sourcePlaylistId = 999L;
        Long trackId = 10L;
        MoveTrackCommand command = new MoveTrackCommand(2L);

        when(aggregatePort.findPlaylistByIdAndOwner(sourcePlaylistId, userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> trackCommandService.moveTrackToPlaylist(sourcePlaylistId, trackId, command))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("트랙 이동 실패 — 타겟 플레이리스트가 존재하지 않으면 NotFoundException")
    void moveTrackToPlaylistTargetNotFound() {
        // given
        Long sourcePlaylistId = 1L;
        Long targetPlaylistId = 999L;
        Long trackId = 10L;

        PlaylistData sourcePlaylist = PlaylistData.builder()
                .id(sourcePlaylistId).ownerId(userId).name(SOURCE_PLAYLIST).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        MoveTrackCommand command = new MoveTrackCommand(targetPlaylistId);

        when(aggregatePort.findPlaylistByIdAndOwner(sourcePlaylistId, userId)).thenReturn(Optional.of(sourcePlaylist));
        when(aggregatePort.findPlaylistByIdAndOwner(targetPlaylistId, userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> trackCommandService.moveTrackToPlaylist(sourcePlaylistId, trackId, command))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("트랙 이동 실패 — 타겟에 중복 트랙이면 ConflictException")
    void moveTrackToPlaylistDuplicateInTarget() {
        // given
        Long sourcePlaylistId = 1L;
        Long targetPlaylistId = 2L;
        Long trackId = 10L;

        PlaylistData sourcePlaylist = PlaylistData.builder()
                .id(sourcePlaylistId).ownerId(userId).name(SOURCE_PLAYLIST).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        PlaylistData targetPlaylist = PlaylistData.builder()
                .id(targetPlaylistId).ownerId(userId).name(TARGET_PLAYLIST).type(PlaylistType.PLAYLIST).orderNumber(1).build();

        TrackData trackData = TrackData.builder()
                .playlistId(new PlaylistId(sourcePlaylist.getId())).name(SONG_NAME).linkId(LINK_ID).duration(Duration.fromString(DURATION)).orderNumber(2).build();
        TrackData duplicateTrack = TrackData.builder()
                .playlistId(new PlaylistId(targetPlaylist.getId())).name(SONG_NAME).linkId(LINK_ID).duration(Duration.fromString(DURATION)).orderNumber(1).build();

        MoveTrackCommand command = new MoveTrackCommand(targetPlaylistId);

        when(aggregatePort.findPlaylistByIdAndOwner(sourcePlaylistId, userId)).thenReturn(Optional.of(sourcePlaylist));
        when(aggregatePort.findPlaylistByIdAndOwner(targetPlaylistId, userId)).thenReturn(Optional.of(targetPlaylist));
        when(aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(sourcePlaylistId))).thenReturn(Optional.of(trackData));
        when(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(targetPlaylistId), LINK_ID)).thenReturn(Optional.of(duplicateTrack));

        // when & then
        assertThatThrownBy(() -> trackCommandService.moveTrackToPlaylist(sourcePlaylistId, trackId, command))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("트랙 이동 실패 — 타겟 100개 초과 시 ConflictException")
    void moveTrackToPlaylistExceededLimit() {
        // given
        Long sourcePlaylistId = 1L;
        Long targetPlaylistId = 2L;
        Long trackId = 10L;

        PlaylistData sourcePlaylist = PlaylistData.builder()
                .id(sourcePlaylistId).ownerId(userId).name(SOURCE_PLAYLIST).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        PlaylistData targetPlaylist = PlaylistData.builder()
                .id(targetPlaylistId).ownerId(userId).name(TARGET_PLAYLIST).type(PlaylistType.PLAYLIST).orderNumber(1).build();

        TrackData trackData = TrackData.builder()
                .playlistId(new PlaylistId(sourcePlaylist.getId())).name(SONG_NAME).linkId(LINK_ID).duration(Duration.fromString(DURATION)).orderNumber(2).build();

        MoveTrackCommand command = new MoveTrackCommand(targetPlaylistId);

        when(aggregatePort.findPlaylistByIdAndOwner(sourcePlaylistId, userId)).thenReturn(Optional.of(sourcePlaylist));
        when(aggregatePort.findPlaylistByIdAndOwner(targetPlaylistId, userId)).thenReturn(Optional.of(targetPlaylist));
        when(aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(sourcePlaylistId))).thenReturn(Optional.of(trackData));
        when(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(targetPlaylistId), LINK_ID)).thenReturn(Optional.empty());
        when(playlistQueryService.getPlaylist(targetPlaylistId))
                .thenReturn(new PlaylistSummaryDto(targetPlaylistId, TARGET_PLAYLIST, 1, PlaylistType.PLAYLIST, 100L));

        // when & then
        assertThatThrownBy(() -> trackCommandService.moveTrackToPlaylist(sourcePlaylistId, trackId, command))
                .isInstanceOf(ConflictException.class);
    }

    // ========== updateTrackOrderInPlaylist ==========

    @Test
    @DisplayName("트랙 순서 변경 성공 — 위에서 아래로 이동 시 shiftUpTrackOrderByDnD 호출")
    void updateTrackOrderInPlaylistMoveDown() {
        // given
        Long playlistId = 1L;
        Long trackId = 10L;
        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        TrackData trackData = TrackData.builder()
                .playlistId(new PlaylistId(playlistData.getId())).name(SONG_NAME).linkId(LINK_ID).duration(Duration.fromString(DURATION)).orderNumber(2).build();

        UpdateTrackOrderCommand command = new UpdateTrackOrderCommand(4);

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlistData));
        when(aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(playlistId))).thenReturn(Optional.of(trackData));
        when(playlistQueryService.getPlaylist(playlistId))
                .thenReturn(new PlaylistSummaryDto(playlistId, TEST_PLAYLIST_NAME, 0, PlaylistType.PLAYLIST, 5L));

        // when
        trackCommandService.updateTrackOrderInPlaylist(playlistId, trackId, command);

        // then
        verify(aggregatePort, times(1)).shiftUpTrackOrderByDnD(playlistId, 2, 4);
        verify(aggregatePort, times(1)).saveTrack(argThat(track -> track.getOrderNumber() == 4));
    }

    @Test
    @DisplayName("트랙 순서 변경 성공 — 아래에서 위로 이동 시 shiftDownTrackOrderByDnD 호출")
    void updateTrackOrderInPlaylistMoveUp() {
        // given
        Long playlistId = 1L;
        Long trackId = 10L;
        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        TrackData trackData = TrackData.builder()
                .playlistId(new PlaylistId(playlistData.getId())).name(SONG_NAME).linkId(LINK_ID).duration(Duration.fromString(DURATION)).orderNumber(4).build();

        UpdateTrackOrderCommand command = new UpdateTrackOrderCommand(2);

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlistData));
        when(aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(playlistId))).thenReturn(Optional.of(trackData));
        when(playlistQueryService.getPlaylist(playlistId))
                .thenReturn(new PlaylistSummaryDto(playlistId, TEST_PLAYLIST_NAME, 0, PlaylistType.PLAYLIST, 5L));

        // when
        trackCommandService.updateTrackOrderInPlaylist(playlistId, trackId, command);

        // then
        verify(aggregatePort, times(1)).shiftDownTrackOrderByDnD(playlistId, 4, 2);
        verify(aggregatePort, times(1)).saveTrack(argThat(track -> track.getOrderNumber() == 2));
    }

    @Test
    @DisplayName("트랙 순서 변경 실패 — 잘못된 순서 값이면 BadRequestException")
    void updateTrackOrderInPlaylistInvalidOrder() {
        // given
        Long playlistId = 1L;
        Long trackId = 10L;
        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        TrackData trackData = TrackData.builder()
                .playlistId(new PlaylistId(playlistData.getId())).name(SONG_NAME).linkId(LINK_ID).duration(Duration.fromString(DURATION)).orderNumber(2).build();

        // nextOrderNumber == prevOrderNumber → invalid
        UpdateTrackOrderCommand command = new UpdateTrackOrderCommand(2);

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlistData));
        when(aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(playlistId))).thenReturn(Optional.of(trackData));
        when(playlistQueryService.getPlaylist(playlistId))
                .thenReturn(new PlaylistSummaryDto(playlistId, TEST_PLAYLIST_NAME, 0, PlaylistType.PLAYLIST, 5L));

        // when & then
        assertThatThrownBy(() -> trackCommandService.updateTrackOrderInPlaylist(playlistId, trackId, command))
                .isInstanceOf(BadRequestException.class);
    }

    // ========== 추가 예외 케이스 ==========

    @Test
    @DisplayName("트랙 추가 성공 — musicCount가 0이면 orderNumber가 1이다")
    void addTrackInPlaylistEmptyPlaylistOrderNumberIsOne() {
        // given
        Long playlistId = 1L;
        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.PLAYLIST).orderNumber(0).build();

        AddTrackCommand command = new AddTrackCommand(SONG_NAME, LINK_ID, DURATION, THUMBNAIL);

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlistData));
        when(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(playlistId), LINK_ID)).thenReturn(Optional.empty());
        when(playlistQueryService.getPlaylist(playlistId))
                .thenReturn(new PlaylistSummaryDto(playlistId, TEST_PLAYLIST_NAME, 0, PlaylistType.PLAYLIST, 0L));
        when(aggregatePort.saveTrack(any())).thenAnswer(invocation -> {
            TrackData track = invocation.getArgument(0);
            TrackData saved = TrackData.builder().playlistId(track.getPlaylistId()).name(track.getName())
                    .linkId(track.getLinkId()).duration(track.getDuration()).orderNumber(track.getOrderNumber())
                    .thumbnailImage(track.getThumbnailImage()).build();
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });

        // when
        trackCommandService.addTrackInPlaylist(playlistId, command);

        // then
        verify(aggregatePort).saveTrack(argThat(track -> track.getOrderNumber() == 1));
    }

    @Test
    @DisplayName("트랙 순서 변경 실패 — 플레이리스트가 존재하지 않으면 NotFoundException")
    void updateTrackOrderInPlaylistPlaylistNotFoundThrows() {
        // given
        Long playlistId = 999L;
        Long trackId = 10L;
        UpdateTrackOrderCommand command = new UpdateTrackOrderCommand(2);

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> trackCommandService.updateTrackOrderInPlaylist(playlistId, trackId, command))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("트랙 순서 변경 실패 — 트랙이 존재하지 않으면 NotFoundException")
    void updateTrackOrderInPlaylistTrackNotFoundThrows() {
        // given
        Long playlistId = 1L;
        Long trackId = 999L;
        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.PLAYLIST).orderNumber(0).build();
        UpdateTrackOrderCommand command = new UpdateTrackOrderCommand(2);

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlistData));
        when(aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(playlistId))).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> trackCommandService.updateTrackOrderInPlaylist(playlistId, trackId, command))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("트랙 이동 실패 — 소스에서 트랙을 찾을 수 없으면 NotFoundException")
    void moveTrackToPlaylistTrackNotFoundInSourceThrows() {
        // given
        Long sourcePlaylistId = 1L;
        Long targetPlaylistId = 2L;
        Long trackId = 999L;

        PlaylistData sourcePlaylist = PlaylistData.builder()
                .id(sourcePlaylistId).ownerId(userId).name(SOURCE_PLAYLIST).type(PlaylistType.PLAYLIST).orderNumber(0).build();
        PlaylistData targetPlaylist = PlaylistData.builder()
                .id(targetPlaylistId).ownerId(userId).name(TARGET_PLAYLIST).type(PlaylistType.PLAYLIST).orderNumber(1).build();

        MoveTrackCommand command = new MoveTrackCommand(targetPlaylistId);

        when(aggregatePort.findPlaylistByIdAndOwner(sourcePlaylistId, userId)).thenReturn(Optional.of(sourcePlaylist));
        when(aggregatePort.findPlaylistByIdAndOwner(targetPlaylistId, userId)).thenReturn(Optional.of(targetPlaylist));
        when(aggregatePort.findTrackByIdAndPlaylist(trackId, new PlaylistId(sourcePlaylistId))).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> trackCommandService.moveTrackToPlaylist(sourcePlaylistId, trackId, command))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("트랙 삭제 실패 — 플레이리스트가 존재하지 않으면 NotFoundException")
    void deleteTrackInPlaylistPlaylistNotFoundThrows() {
        // given
        Long playlistId = 999L;
        Long trackId = 10L;

        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> trackCommandService.deleteTrackInPlaylist(playlistId, trackId))
                .isInstanceOf(NotFoundException.class);
    }

    // ========== peekTracksFromCursor ==========

    private Page<PlaylistTrackDto> pageOf(PlaylistTrackDto... dtos) {
        return new PageImpl<>(List.of(dtos));
    }

    private PlaylistData playlistWithCursor(Long playlistId, Long cursorTrackId) {
        PlaylistData p = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name(TEST_PLAYLIST_NAME).type(PlaylistType.PLAYLIST).orderNumber(0).build();
        ReflectionTestUtils.setField(p, "lastPlayedTrackId", cursorTrackId);
        return p;
    }

    @Test
    @DisplayName("peekTracksFromCursor — 커서 null이면 자연순서(top부터)")
    void peek_nullCursor_naturalOrder() {
        Long playlistId = 1L;
        PlaylistTrackDto a = new PlaylistTrackDto(10L, "a", "A", 1, Duration.fromString("3:00"), THUMBNAIL);
        PlaylistTrackDto b = new PlaylistTrackDto(20L, "b", "B", 2, Duration.fromString("3:00"), THUMBNAIL);
        PlaylistTrackDto c = new PlaylistTrackDto(30L, "c", "C", 3, Duration.fromString("3:00"), THUMBNAIL);
        when(queryPort.getTracksWithPagination(eq(new PlaylistId(playlistId)), any(Pageable.class))).thenReturn(pageOf(a, b, c));
        when(aggregatePort.findPlaylistById(playlistId)).thenReturn(Optional.of(playlistWithCursor(playlistId, null)));

        List<PlaybackTrackDto> result = trackCommandService.peekTracksFromCursor(playlistId);

        assertThat(result).extracting(PlaybackTrackDto::trackId).containsExactly(10L, 20L, 30L);
    }

    @Test
    @DisplayName("peekTracksFromCursor — 커서=B(20) 다음부터 wrap (C,A,B)")
    void peek_wrapsAfterCursor() {
        Long playlistId = 1L;
        PlaylistTrackDto a = new PlaylistTrackDto(10L, "a", "A", 1, Duration.fromString("3:00"), THUMBNAIL);
        PlaylistTrackDto b = new PlaylistTrackDto(20L, "b", "B", 2, Duration.fromString("3:00"), THUMBNAIL);
        PlaylistTrackDto c = new PlaylistTrackDto(30L, "c", "C", 3, Duration.fromString("3:00"), THUMBNAIL);
        when(queryPort.getTracksWithPagination(eq(new PlaylistId(playlistId)), any(Pageable.class))).thenReturn(pageOf(a, b, c));
        when(aggregatePort.findPlaylistById(playlistId)).thenReturn(Optional.of(playlistWithCursor(playlistId, 20L)));

        List<PlaybackTrackDto> result = trackCommandService.peekTracksFromCursor(playlistId);

        assertThat(result).extracting(PlaybackTrackDto::trackId).containsExactly(30L, 10L, 20L);
    }

    @Test
    @DisplayName("peekTracksFromCursor — 커서 트랙이 목록에 없으면(삭제) 자연순서(top부터)")
    void peek_deletedCursor_naturalOrder() {
        Long playlistId = 1L;
        PlaylistTrackDto a = new PlaylistTrackDto(10L, "a", "A", 1, Duration.fromString("3:00"), THUMBNAIL);
        PlaylistTrackDto b = new PlaylistTrackDto(20L, "b", "B", 2, Duration.fromString("3:00"), THUMBNAIL);
        when(queryPort.getTracksWithPagination(eq(new PlaylistId(playlistId)), any(Pageable.class))).thenReturn(pageOf(a, b));
        when(aggregatePort.findPlaylistById(playlistId)).thenReturn(Optional.of(playlistWithCursor(playlistId, 999L)));

        List<PlaybackTrackDto> result = trackCommandService.peekTracksFromCursor(playlistId);

        assertThat(result).extracting(PlaybackTrackDto::trackId).containsExactly(10L, 20L);
    }

    // ========== advancePlaybackCursor ==========

    @Test
    @DisplayName("advancePlaybackCursor — aggregatePort.advancePlaybackCursor에 위임한다")
    void advance_delegates() {
        trackCommandService.advancePlaybackCursor(1L, 30L);
        verify(aggregatePort).advancePlaybackCursor(1L, 30L);
    }
}
