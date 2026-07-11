package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.application.port.out.PlaylistQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.*;
import com.pfplaybackend.api.party.domain.enums.DjKind;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.service.PartyroomAggregateService;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;

import com.pfplaybackend.api.common.exception.http.NotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DjCommandServiceTest {

    @Mock PartyroomAggregatePort aggregatePort;
    @Mock PlaybackControlPort playbackControlPort;
    @Mock PlaylistQueryPort playlistQueryPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PartyroomAggregateService partyroomAggregateService;
    @Mock PartyroomQueryService partyroomQueryService;

    @InjectMocks DjCommandService djCommandService;

    private final UserId userId = new UserId(1L);
    private final PartyroomId partyroomId = new PartyroomId(10L);
    private final PlaylistId playlistId = new PlaylistId(100L);

    @BeforeEach
    void setUp() {
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        lenient().when(authContext.getAuthorityTier()).thenReturn(AuthorityTier.FM);
        ThreadLocalContext.setContext(authContext);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("enqueueDj — 큐가 닫혀있으면 예외가 발생한다")
    void enqueueDjQueueClosedThrows() {
        // given
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);
        djQueue.close();

        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);

        // when & then
        assertThatThrownBy(() -> djCommandService.enqueueDj(partyroomId, playlistId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enqueueDj — 이미 등록된 DJ이면 예외가 발생한다")
    void enqueueDjAlreadyRegisteredThrows() {
        // given
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);

        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.isDjRegistered(partyroomId, new CrewId(1L))).thenReturn(true);
        when(playlistQueryPort.isOwnedBy(playlistId.getId(), userId.getUid())).thenReturn(true);
        when(playlistQueryPort.isEmptyPlaylist(playlistId.getId())).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> djCommandService.enqueueDj(partyroomId, playlistId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("enqueueDj — 빈 플레이리스트이면 예외가 발생한다")
    void enqueueDjEmptyPlaylistThrows() {
        // given
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);

        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.isDjRegistered(partyroomId, new CrewId(1L))).thenReturn(false);
        when(playlistQueryPort.isOwnedBy(playlistId.getId(), userId.getUid())).thenReturn(true);
        when(playlistQueryPort.isEmptyPlaylist(playlistId.getId())).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> djCommandService.enqueueDj(partyroomId, playlistId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enqueueDj — 첫 번째 DJ 등록 시 재생이 시작된다")
    void enqueueDjFirstDjStartsPlayback() {
        // given
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);

        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.isDjRegistered(partyroomId, new CrewId(1L))).thenReturn(false);
        when(playlistQueryPort.isOwnedBy(playlistId.getId(), userId.getUid())).thenReturn(true);
        when(playlistQueryPort.isEmptyPlaylist(playlistId.getId())).thenReturn(false);
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(Collections.emptyList());
        when(aggregatePort.saveDj(any(DjData.class))).thenReturn(mock(DjData.class));

        // when
        djCommandService.enqueueDj(partyroomId, playlistId);

        // then
        verify(playbackControlPort).startPlayback(partyroom);
    }

    @Test
    @DisplayName("enqueueDj(kind) — ONE_SHOT 지정 시 kind 가 저장된다")
    void enqueueDjPersistsOneShotKind() {
        // given — 기존 enqueueDj happy 테스트와 동일 스텁 구성
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.isDjRegistered(partyroomId, new CrewId(1L))).thenReturn(false);
        when(playlistQueryPort.isOwnedBy(playlistId.getId(), userId.getUid())).thenReturn(true);
        when(playlistQueryPort.isEmptyPlaylist(playlistId.getId())).thenReturn(false);
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(Collections.emptyList());
        when(aggregatePort.saveDj(any(DjData.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        djCommandService.enqueueDj(partyroomId, playlistId, DjKind.ONE_SHOT);

        // then
        ArgumentCaptor<DjData> captor = ArgumentCaptor.forClass(DjData.class);
        verify(aggregatePort).saveDj(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo(DjKind.ONE_SHOT);
    }

    @Test
    @DisplayName("enqueueDj(2-arg) — 기존 경로는 NORMAL 유지(회귀)")
    void enqueueDjDefaultsToNormalKind() {
        // given — 동일 스텁
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.isDjRegistered(partyroomId, new CrewId(1L))).thenReturn(false);
        when(playlistQueryPort.isOwnedBy(playlistId.getId(), userId.getUid())).thenReturn(true);
        when(playlistQueryPort.isEmptyPlaylist(playlistId.getId())).thenReturn(false);
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(Collections.emptyList());
        when(aggregatePort.saveDj(any(DjData.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        djCommandService.enqueueDj(partyroomId, playlistId);

        // then
        ArgumentCaptor<DjData> captor = ArgumentCaptor.forClass(DjData.class);
        verify(aggregatePort).saveDj(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo(DjKind.NORMAL);
    }

    @Test
    @DisplayName("enqueueDj — 타인 소유 playlist 면 NOT_OWNED_PLAYLIST (Task 4/10 회귀 가드)")
    void enqueueDjNotOwnedThrows() {
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.isDjRegistered(partyroomId, new CrewId(1L))).thenReturn(false);
        when(playlistQueryPort.isOwnedBy(playlistId.getId(), userId.getUid())).thenReturn(false);

        assertThatThrownBy(() -> djCommandService.enqueueDj(partyroomId, playlistId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("changePlaylist — 큐에 없으면 NOT_FOUND_DJ")
    void changePlaylistNotInQueueThrows() {
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> djCommandService.changePlaylist(partyroomId, new PlaylistId(200L)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("changePlaylist — 재생 중 DJ 면 CURRENT_DJ_CANNOT_CHANGE_PLAYLIST")
    void changePlaylistCurrentDjThrows() {
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        playbackState.activate(null, new CrewId(1L));
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        DjData me = DjData.create(partyroomId, playlistId, new CrewId(1L), 1);

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.of(me));

        assertThatThrownBy(() -> djCommandService.changePlaylist(partyroomId, new PlaylistId(200L)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("changePlaylist — 타인 소유 playlist 면 NOT_OWNED_PLAYLIST")
    void changePlaylistNotOwnedThrows() {
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        DjData me = DjData.create(partyroomId, playlistId, new CrewId(1L), 1);

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.of(me));
        when(playlistQueryPort.isOwnedBy(200L, userId.getUid())).thenReturn(false);

        assertThatThrownBy(() -> djCommandService.changePlaylist(partyroomId, new PlaylistId(200L)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("changePlaylist — 미존재 playlistId 도 isOwnedBy=false 로 NOT_OWNED_PLAYLIST (security boundary)")
    void changePlaylistNonExistentPlaylistThrows() {
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        DjData me = DjData.create(partyroomId, playlistId, new CrewId(1L), 1);

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.of(me));
        when(playlistQueryPort.isOwnedBy(999_999L, userId.getUid())).thenReturn(false);

        assertThatThrownBy(() -> djCommandService.changePlaylist(partyroomId, new PlaylistId(999_999L)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("changePlaylist — 빈 playlist 면 EMPTY_PLAYLIST")
    void changePlaylistEmptyThrows() {
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        DjData me = DjData.create(partyroomId, playlistId, new CrewId(1L), 1);

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.of(me));
        when(playlistQueryPort.isOwnedBy(200L, userId.getUid())).thenReturn(true);
        when(playlistQueryPort.isEmptyPlaylist(200L)).thenReturn(true);

        assertThatThrownBy(() -> djCommandService.changePlaylist(partyroomId, new PlaylistId(200L)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("changePlaylist — happy path: me.playlistId 갱신 + 이벤트 발행 0회")
    void changePlaylistHappy() {
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        DjData me = DjData.create(partyroomId, playlistId, new CrewId(1L), 1);

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.of(me));
        when(playlistQueryPort.isOwnedBy(200L, userId.getUid())).thenReturn(true);
        when(playlistQueryPort.isEmptyPlaylist(200L)).thenReturn(false);

        djCommandService.changePlaylist(partyroomId, new PlaylistId(200L));

        assertThat(me.getPlaylistId()).isEqualTo(new PlaylistId(200L));
        assertThat(me.getOrderNumber()).isEqualTo(1);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("changePlaylist — idempotent: 같은 playlistId 입력 예외 없음")
    void changePlaylistIdempotent() {
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        DjData me = DjData.create(partyroomId, playlistId, new CrewId(1L), 1);

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.of(me));
        when(playlistQueryPort.isOwnedBy(playlistId.getId(), userId.getUid())).thenReturn(true);
        when(playlistQueryPort.isEmptyPlaylist(playlistId.getId())).thenReturn(false);

        djCommandService.changePlaylist(partyroomId, playlistId);

        assertThat(me.getPlaylistId()).isEqualTo(playlistId);
    }

    @Test
    @DisplayName("dequeueDj — 현재 DJ가 아니면 skipBySystem이 호출되지 않는다")
    void dequeueDjNotCurrentDjNoSkip() {
        // given
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();

        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        playbackState.activate(null, new CrewId(99L)); // different DJ

        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);

        // when
        djCommandService.dequeueDj(partyroomId);

        // then
        verify(partyroomAggregateService).removeDjFromQueue(partyroomId, new CrewId(1L));
        verify(playbackControlPort, never()).skipPlayback(any());
    }
}
