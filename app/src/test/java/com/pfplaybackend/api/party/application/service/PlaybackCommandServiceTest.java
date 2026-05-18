package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackAggregationRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackRepository;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.ExpirationTaskPort;
import com.pfplaybackend.api.party.application.port.out.PlaylistCommandPort;
import com.pfplaybackend.api.party.application.port.out.UserActivityPort;
import com.pfplaybackend.api.party.domain.entity.data.*;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.event.PlaybackStartedEvent;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.service.PartyroomAggregateService;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.application.dto.PlaybackTrackDto;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaybackCommandServiceTest {

    @Mock PlaybackRepository playbackRepository;
    @Mock PlaybackAggregationRepository playbackAggregationRepository;
    @Mock PlaylistCommandPort playlistCommandPort;
    @Mock UserActivityPort userActivityPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PartyroomAggregatePort aggregatePort;
    @Mock ExpirationTaskPort expirationTaskPort;
    @Mock PartyroomAggregateService partyroomAggregateService;
    @Mock PartyroomQueryService partyroomQueryService;
    @Mock Clock clock;

    @InjectMocks PlaybackCommandService playbackCommandService;

    private final UserId userId = new UserId(1L);
    private final PartyroomId partyroomId = new PartyroomId(10L);

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        lenient().when(clock.millis()).thenReturn(1735689600000L);
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
    @DisplayName("complete — 완료 시 DJ 점수가 1 증가한다")
    void completeUpdatesDjScore() {
        // given — tryProceed에서 DJ가 없으면 deactivate
        PartyroomData partyroom = PartyroomData.builder().id(partyroomId.getId()).partyroomId(partyroomId).build();
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of());

        // when
        playbackCommandService.complete(partyroomId, userId);

        // then
        verify(userActivityPort).updateDjPointScore(userId, 1);
    }

    @Test
    @DisplayName("complete — 대기열에 DJ가 없으면 재생이 비활성화된다")
    void completeNoDjsDeactivates() {
        // given
        PartyroomData partyroom = PartyroomData.builder().id(partyroomId.getId()).partyroomId(partyroomId).build();
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of());

        // when
        playbackCommandService.complete(partyroomId, userId);

        // then
        verify(partyroomAggregateService).deactivatePlayback(partyroomId);
    }

    @Test
    @DisplayName("skipByManager — MODERATOR 이상 등급이면 스킵이 실행된다")
    void skipByManagerModeratorSucceeds() {
        // given
        ActivePartyroomDto activeDto = new ActivePartyroomDto(partyroomId.getId(), false, 1L, true, new PlaybackId(1L), new CrewId(1L));
        CrewData adjuster = CrewData.builder()
                .id(1L).userId(userId).gradeType(GradeType.MODERATOR).build();
        PartyroomData partyroom = PartyroomData.builder().id(partyroomId.getId()).partyroomId(partyroomId).build();

        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeDto));
        when(partyroomQueryService.getCrewOrThrow(new PartyroomId(activeDto.id()), userId)).thenReturn(adjuster);
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of());

        // when
        playbackCommandService.skipByManager(partyroomId);

        // then
        verify(expirationTaskPort).cancelExpiration(String.valueOf(partyroomId.getId()));
        verify(partyroomAggregateService).deactivatePlayback(partyroomId);
    }

    @Test
    @DisplayName("skipByManager — MODERATOR 미만 등급이면 예외가 발생한다")
    void skipByManagerBelowModeratorThrows() {
        // given
        ActivePartyroomDto activeDto = new ActivePartyroomDto(partyroomId.getId(), false, 1L, true, new PlaybackId(1L), new CrewId(1L));
        CrewData adjuster = CrewData.builder()
                .id(1L).userId(userId).gradeType(GradeType.CLUBBER).build();

        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeDto));
        when(partyroomQueryService.getCrewOrThrow(new PartyroomId(activeDto.id()), userId)).thenReturn(adjuster);

        // when & then
        assertThatThrownBy(() -> playbackCommandService.skipByManager(partyroomId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("skipPlayback — 스케줄 태스크를 취소하고 tryProceed를 실행한다")
    void skipPlaybackCancelsTaskAndProceeds() {
        // given
        PartyroomData partyroom = PartyroomData.builder().id(partyroomId.getId()).partyroomId(partyroomId).build();
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of());

        // when
        playbackCommandService.skipPlayback(partyroomId);

        // then
        verify(expirationTaskPort).cancelExpiration(String.valueOf(partyroomId.getId()));
        verify(partyroomAggregateService).deactivatePlayback(partyroomId);
    }

    @Test
    @DisplayName("complete — 대기열에 DJ가 있으면 재생이 시작된다")
    void completeHasDjsStartsPlayback() {
        // given
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId)
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(10))
                .build();
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);

        PlaylistId playlistId = new PlaylistId(100L);
        DjData djData = DjData.builder()
                .id(1L).partyroomId(partyroomId).crewId(new CrewId(1L))
                .playlistId(playlistId).orderNumber(1).build();
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of(djData));
        when(partyroomAggregateService.rotateDjQueue(partyroomId)).thenReturn(List.of(djData));

        CrewData djCrew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        when(aggregatePort.findCrewById(1L)).thenReturn(Optional.of(djCrew));

        PlaybackTrackDto trackDto = new PlaybackTrackDto("linkId", "Song", "thumb.jpg", Duration.fromString("3:30"), 1);
        when(playlistCommandPort.peekOrderedTracks(playlistId)).thenReturn(List.of(trackDto));

        PlaybackData savedPlayback = mock(PlaybackData.class);
        lenient().when(savedPlayback.getId()).thenReturn(1L);
        lenient().when(savedPlayback.getLinkId()).thenReturn("linkId");
        lenient().when(savedPlayback.getName()).thenReturn("Song");
        lenient().when(savedPlayback.getDuration()).thenReturn(Duration.ofSeconds(210));
        lenient().when(savedPlayback.getThumbnailImage()).thenReturn("thumb.jpg");
        lenient().when(savedPlayback.getEndTime()).thenReturn(9999L);
        when(playbackRepository.save(any(PlaybackData.class))).thenReturn(savedPlayback);

        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);

        // when
        playbackCommandService.complete(partyroomId, userId);

        // then
        verify(partyroomAggregateService).rotateDjQueue(partyroomId);
        verify(userActivityPort).updateDjPointScore(userId, 1);
    }

    // ── #222 회귀 잠금: skip(자의/타의) 시 DJ큐 회전 + 본인 플레이리스트 회전이 함께 wiring 되는지 ──
    // DJ큐 산술(#1→맨뒤)은 PartyroomAggregateServiceTest.rotatesCorrectly,
    // 트랙 회전 SQL(played→맨뒤)은 TrackRepositoryReorderIntegrationTest 가 별도로 잠금.
    // 여기서는 "skip 경로가 rotateDjQueue + peekOrderedTracks/rotatePlayed(현재 DJ 플레이리스트)
    // 양쪽을 호출한다"는 수렴 지점의 invariant 를 잠근다.

    private DjData stubDoStartWithQueuedDj(PlaybackTimeLimit timeLimit, Duration trackDuration) {
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId)
                .playbackTimeLimit(timeLimit)
                .build();
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);

        PlaylistId playlistId = new PlaylistId(100L);
        DjData djData = DjData.builder()
                .id(1L).partyroomId(partyroomId).crewId(new CrewId(1L))
                .playlistId(playlistId).orderNumber(1).build();
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of(djData));
        when(partyroomAggregateService.rotateDjQueue(partyroomId)).thenReturn(List.of(djData));

        CrewData djCrew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        when(aggregatePort.findCrewById(1L)).thenReturn(Optional.of(djCrew));

        PlaybackTrackDto trackDto = new PlaybackTrackDto("linkId", "Song", "thumb.jpg", trackDuration, 1);
        when(playlistCommandPort.peekOrderedTracks(playlistId)).thenReturn(List.of(trackDto));
        return djData;
    }

    @Test
    @DisplayName("#222 skipPlayback — 큐에 DJ가 있으면 DJ큐 회전과 현재 DJ 플레이리스트 회전(rotatePlayed)이 함께 호출된다")
    void skipPlaybackWithQueuedDjRotatesQueueAndPlaylist() {
        // given — 트랙 길이가 제한 이내(재생됨)
        DjData dj = stubDoStartWithQueuedDj(PlaybackTimeLimit.ofMinutes(10), Duration.fromString("3:30"));

        PlaybackData savedPlayback = mock(PlaybackData.class);
        lenient().when(savedPlayback.getId()).thenReturn(1L);
        lenient().when(savedPlayback.getDuration()).thenReturn(Duration.ofSeconds(210));
        when(playbackRepository.save(any(PlaybackData.class))).thenReturn(savedPlayback);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(PartyroomPlaybackData.createFor(partyroomId));

        // when — 자의 skip (DELETE /playbacks/current → skipPlayback)
        playbackCommandService.skipPlayback(partyroomId);

        // then — 스케줄 취소 + DJ큐 밀림 + 트랙 회전 양쪽 wiring
        // position-1 트랙(orderNumber=1, size=1)이 제한 이내라 그대로 선택 → rotatePlayed(pl,1,1)
        verify(expirationTaskPort).cancelExpiration(String.valueOf(partyroomId.getId()));
        verify(partyroomAggregateService).rotateDjQueue(partyroomId);
        verify(playlistCommandPort).peekOrderedTracks(dj.getPlaylistId());
        verify(playlistCommandPort).rotatePlayed(dj.getPlaylistId(), 1, 1L);
    }

    @Test
    @DisplayName("#222 skipByManager — MODERATOR 타의 skip 도 DJ큐 회전 + 플레이리스트 회전(rotatePlayed)을 함께 호출한다")
    void skipByManagerWithQueuedDjRotatesQueueAndPlaylist() {
        // given — MODERATOR 권한 컨텍스트
        ActivePartyroomDto activeDto = new ActivePartyroomDto(partyroomId.getId(), false, 1L, true, new PlaybackId(1L), new CrewId(1L));
        CrewData adjuster = CrewData.builder().id(1L).userId(userId).gradeType(GradeType.MODERATOR).build();
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeDto));
        when(partyroomQueryService.getCrewOrThrow(new PartyroomId(activeDto.id()), userId)).thenReturn(adjuster);

        DjData dj = stubDoStartWithQueuedDj(PlaybackTimeLimit.ofMinutes(10), Duration.fromString("3:30"));

        PlaybackData savedPlayback = mock(PlaybackData.class);
        lenient().when(savedPlayback.getId()).thenReturn(1L);
        lenient().when(savedPlayback.getDuration()).thenReturn(Duration.ofSeconds(210));
        when(playbackRepository.save(any(PlaybackData.class))).thenReturn(savedPlayback);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(PartyroomPlaybackData.createFor(partyroomId));

        // when — 타의 skip
        playbackCommandService.skipByManager(partyroomId);

        // then
        verify(expirationTaskPort).cancelExpiration(String.valueOf(partyroomId.getId()));
        verify(partyroomAggregateService).rotateDjQueue(partyroomId);
        verify(playlistCommandPort).peekOrderedTracks(dj.getPlaylistId());
        verify(playlistCommandPort).rotatePlayed(dj.getPlaylistId(), 1, 1L);
    }

    // ── E/#3 트랙단위 스킵 동작 잠금 ──

    @Test
    @DisplayName("E/#3 singleDj_allOverLimit — 단일 DJ의 트랙이 모두 제한 초과면 deactivate, rotatePlayed 미호출")
    void singleDj_allOverLimit_deactivates() {
        // given — 단일 DJ, peek 트랙이 전부 제한(1분=60s) 초과
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId)
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(1))
                .build();
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);

        PlaylistId playlistId = new PlaylistId(100L);
        DjData dj = DjData.builder()
                .id(1L).partyroomId(partyroomId).crewId(new CrewId(1L))
                .playlistId(playlistId).orderNumber(1).build();
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of(dj));
        when(partyroomAggregateService.rotateDjQueue(partyroomId)).thenReturn(List.of(dj));

        CrewData djCrew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        when(aggregatePort.findCrewById(1L)).thenReturn(Optional.of(djCrew));

        List<PlaybackTrackDto> overLimit = List.of(
                new PlaybackTrackDto("l1", "T1", "t1.jpg", Duration.fromString("3:30"), 1),
                new PlaybackTrackDto("l2", "T2", "t2.jpg", Duration.fromString("4:00"), 2));
        when(playlistCommandPort.peekOrderedTracks(playlistId)).thenReturn(overLimit);

        // when — 자의 skip
        playbackCommandService.skipPlayback(partyroomId);

        // then — deactivate, save/rotatePlayed 미호출(peek 부수효과 없음)
        verify(partyroomAggregateService).deactivatePlayback(partyroomId);
        verify(playbackRepository, never()).save(any(PlaybackData.class));
        verify(playlistCommandPort, never()).rotatePlayed(any(PlaylistId.class), anyInt(), anyLong());
    }

    @Test
    @DisplayName("E/#3 singleDj_skipsOverLimit — 제한 초과 트랙은 건너뛰고 첫 재생가능 트랙을 재생한다")
    void singleDj_skipsOverLimit_playsFirstPlayable() {
        // given — 단일 DJ, peek=[t1 초과, t2 이내] (제한 5분=300s)
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId)
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .build();
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);

        PlaylistId playlistId = new PlaylistId(100L);
        DjData dj = DjData.builder()
                .id(1L).partyroomId(partyroomId).crewId(new CrewId(1L))
                .playlistId(playlistId).orderNumber(1).build();
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of(dj));
        when(partyroomAggregateService.rotateDjQueue(partyroomId)).thenReturn(List.of(dj));

        CrewData djCrew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        when(aggregatePort.findCrewById(1L)).thenReturn(Optional.of(djCrew));

        PlaybackTrackDto overLimit = new PlaybackTrackDto("over", "Over", "o.jpg", Duration.fromString("6:00"), 1);
        PlaybackTrackDto playable = new PlaybackTrackDto("ok", "Playable", "p.jpg", Duration.fromString("3:00"), 2);
        when(playlistCommandPort.peekOrderedTracks(playlistId)).thenReturn(List.of(overLimit, playable));

        PlaybackData savedPlayback = mock(PlaybackData.class);
        lenient().when(savedPlayback.getId()).thenReturn(1L);
        lenient().when(savedPlayback.getLinkId()).thenReturn("ok");
        lenient().when(savedPlayback.getName()).thenReturn("Playable");
        lenient().when(savedPlayback.getDuration()).thenReturn(Duration.ofSeconds(180));
        lenient().when(savedPlayback.getThumbnailImage()).thenReturn("p.jpg");
        lenient().when(savedPlayback.getEndTime()).thenReturn(9999L);
        ArgumentCaptor<PlaybackData> playbackCaptor = ArgumentCaptor.forClass(PlaybackData.class);
        when(playbackRepository.save(playbackCaptor.capture())).thenReturn(savedPlayback);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(PartyroomPlaybackData.createFor(partyroomId));

        // when — 자의 skip
        playbackCommandService.skipPlayback(partyroomId);

        // then — t2(orderNumber=2) 재생 + rotatePlayed(pl,2,2), deactivate 미호출
        PlaybackData saved = playbackCaptor.getValue();
        assertThat(saved.getLinkId()).isEqualTo("ok");
        assertThat(saved.getName()).isEqualTo("Playable");
        verify(playlistCommandPort).rotatePlayed(playlistId, 2, 2L);
        verify(eventPublisher).publishEvent(any(PlaybackStartedEvent.class));
        verify(partyroomAggregateService, never()).deactivatePlayback(any(PartyroomId.class));
    }

    @Test
    @DisplayName("E/#3 multiDj_firstDjAllOverLimit — 첫 DJ가 전부 초과면 다음 DJ로 넘어가 재생(rotateDjQueue 1회)")
    void multiDj_firstDjAllOverLimit_playsNextDj() {
        // given — 큐 [dj1(전부 초과), dj2(재생가능)], 제한 5분=300s
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId)
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .build();
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);

        PlaylistId pl1 = new PlaylistId(101L);
        PlaylistId pl2 = new PlaylistId(102L);
        DjData dj1 = DjData.builder()
                .id(1L).partyroomId(partyroomId).crewId(new CrewId(1L))
                .playlistId(pl1).orderNumber(1).build();
        DjData dj2 = DjData.builder()
                .id(2L).partyroomId(partyroomId).crewId(new CrewId(2L))
                .playlistId(pl2).orderNumber(2).build();
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of(dj1, dj2));
        when(partyroomAggregateService.rotateDjQueue(partyroomId)).thenReturn(List.of(dj1, dj2));

        CrewData djCrew1 = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        CrewData djCrew2 = CrewData.builder()
                .id(2L).partyroomId(partyroomId).userId(new UserId(2L)).gradeType(GradeType.CLUBBER).build();
        when(aggregatePort.findCrewById(1L)).thenReturn(Optional.of(djCrew1));
        when(aggregatePort.findCrewById(2L)).thenReturn(Optional.of(djCrew2));

        when(playlistCommandPort.peekOrderedTracks(pl1)).thenReturn(List.of(
                new PlaybackTrackDto("o1", "Over1", "o1.jpg", Duration.fromString("6:00"), 1)));
        when(playlistCommandPort.peekOrderedTracks(pl2)).thenReturn(List.of(
                new PlaybackTrackDto("ok2", "Playable2", "p2.jpg", Duration.fromString("3:00"), 1)));

        PlaybackData savedPlayback = mock(PlaybackData.class);
        lenient().when(savedPlayback.getId()).thenReturn(1L);
        lenient().when(savedPlayback.getLinkId()).thenReturn("ok2");
        lenient().when(savedPlayback.getName()).thenReturn("Playable2");
        lenient().when(savedPlayback.getDuration()).thenReturn(Duration.ofSeconds(180));
        lenient().when(savedPlayback.getThumbnailImage()).thenReturn("p2.jpg");
        lenient().when(savedPlayback.getEndTime()).thenReturn(9999L);
        ArgumentCaptor<PlaybackData> playbackCaptor = ArgumentCaptor.forClass(PlaybackData.class);
        when(playbackRepository.save(playbackCaptor.capture())).thenReturn(savedPlayback);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(PartyroomPlaybackData.createFor(partyroomId));

        // when — 자의 skip
        playbackCommandService.skipPlayback(partyroomId);

        // then — dj2 트랙 재생 + rotateDjQueue 정확히 1회(이중 회전 없음), deactivate 미호출
        PlaybackData saved = playbackCaptor.getValue();
        assertThat(saved.getLinkId()).isEqualTo("ok2");
        verify(playlistCommandPort).rotatePlayed(pl2, 1, 1L);
        verify(playlistCommandPort, never()).rotatePlayed(eq(pl1), anyInt(), anyLong());
        verify(partyroomAggregateService, times(1)).rotateDjQueue(partyroomId);
        verify(partyroomAggregateService, never()).deactivatePlayback(any(PartyroomId.class));
    }

    @Test
    @DisplayName("updatePlaybackAggregation — atomic delta 적용 후 reload된 aggregation 반환")
    void updatePlaybackAggregationUpdatesSuccessfully() {
        // given
        PlaybackAggregationData aggregation = mock(PlaybackAggregationData.class);
        PlaybackId playbackId = new PlaybackId(1L);
        when(playbackAggregationRepository.applyAggregationDelta(playbackId, 1, 0, 1)).thenReturn(1);
        when(playbackAggregationRepository.findById(playbackId)).thenReturn(Optional.of(aggregation));

        // when
        PlaybackAggregationData result = playbackCommandService.updatePlaybackAggregation(playbackId, List.of(1, 0, 1));

        // then
        verify(playbackAggregationRepository).applyAggregationDelta(playbackId, 1, 0, 1);
        verify(playbackAggregationRepository).findById(playbackId);
        assertThat(result).isEqualTo(aggregation);
    }
}
