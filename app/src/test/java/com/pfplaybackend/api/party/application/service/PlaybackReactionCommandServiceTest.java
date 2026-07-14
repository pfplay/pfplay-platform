package com.pfplaybackend.api.party.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackReactionHistoryRepository;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.AddedTrackInfo;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.history.PlaybackReactionHistoryData;
import com.pfplaybackend.api.party.domain.enums.ReactionType;
import com.pfplaybackend.api.party.domain.model.ReactionPostProcessResult;
import com.pfplaybackend.api.party.domain.model.ReactionState;
import com.pfplaybackend.api.party.domain.service.PlaybackReactionDomainService;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.application.dto.playback.ReactionHistoryDto;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaybackReactionCommandServiceTest {

    @Mock PlaybackReactionHistoryRepository playbackReactionHistoryRepository;
    @Mock PlaybackReactionDomainService playbackReactionDomainService;
    @Mock PartyroomQueryService partyroomQueryService;
    @Mock PlaybackReactionPostProcessCommandService playbackReactionPostProcessCommandService;

    @InjectMocks PlaybackReactionCommandService playbackReactionCommandService;

    private final UserId userId = new UserId(1L);
    private final PartyroomId partyroomId = new PartyroomId(10L);
    private final PlaybackId playbackId = new PlaybackId(100L);

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        lenient().when(authContext.getAuthorityTier()).thenReturn(AuthorityTier.FM);
        ThreadLocalContext.setContext(authContext);

        logger = (Logger) LoggerFactory.getLogger(PlaybackReactionCommandService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("LIKE 반응 시 정상적으로 후처리가 호출되고 addedTrack=null로 결과가 반환된다")
    void reactToCurrentPlaybackLikeSuccess() {
        // given
        ActivePartyroomDto activePartyroom = new ActivePartyroomDto(
                partyroomId.getId(), false, 5L, true, playbackId, new CrewId(5L));
        when(partyroomQueryService.getMyActivePartyroom()).thenReturn(Optional.of(activePartyroom));

        PlaybackReactionHistoryData historyData = new PlaybackReactionHistoryData(userId, playbackId);
        when(playbackReactionHistoryRepository.findByPlaybackIdAndUserIdForUpdate(playbackId, userId))
                .thenReturn(Optional.empty());
        when(playbackReactionHistoryRepository.saveAndFlush(any())).thenReturn(historyData);

        ReactionState baseState = ReactionState.createBaseState();
        ReactionState targetState = new ReactionState(true, false, false);
        when(playbackReactionDomainService.getTargetReactionState(baseState, ReactionType.LIKE))
                .thenReturn(targetState);

        ReactionPostProcessResult postProcessResult = new ReactionPostProcessResult(false, false, false, false, null, 0, null);
        when(playbackReactionDomainService.determinePostProcessing(baseState, targetState))
                .thenReturn(postProcessResult);
        when(playbackReactionHistoryRepository.save(any())).thenReturn(historyData);

        CrewData crew = CrewData.builder().id(5L).userId(userId).build();
        when(partyroomQueryService.getCrewByUserId(partyroomId, userId)).thenReturn(Optional.of(crew));

        when(playbackReactionPostProcessCommandService.postProcess(any(), any(), any(), any(), any()))
                .thenReturn(null);

        // when
        ReactionHistoryDto result = playbackReactionCommandService.reactToCurrentPlayback(partyroomId, ReactionType.LIKE);

        // then
        assertThat(result.isLiked()).isTrue();
        assertThat(result.isDisliked()).isFalse();
        assertThat(result.isGrabbed()).isFalse();
        assertThat(result.addedTrack()).isNull();
        verify(playbackReactionPostProcessCommandService).postProcess(
                postProcessResult, ReactionType.LIKE, partyroomId, playbackId, new CrewId(5L));
    }

    @Test
    @DisplayName("GRAB 반응 시 응답에 addedTrack(trackId, playlistId)이 포함된다")
    void reactToCurrentPlaybackGrabSuccessIncludesAddedTrack() {
        // given
        ActivePartyroomDto activePartyroom = new ActivePartyroomDto(
                partyroomId.getId(), false, 5L, true, playbackId, new CrewId(5L));
        when(partyroomQueryService.getMyActivePartyroom()).thenReturn(Optional.of(activePartyroom));

        PlaybackReactionHistoryData historyData = new PlaybackReactionHistoryData(userId, playbackId);
        when(playbackReactionHistoryRepository.findByPlaybackIdAndUserIdForUpdate(playbackId, userId))
                .thenReturn(Optional.empty());
        when(playbackReactionHistoryRepository.saveAndFlush(any())).thenReturn(historyData);

        ReactionState baseState = ReactionState.createBaseState();
        ReactionState targetState = new ReactionState(true, false, true);
        when(playbackReactionDomainService.getTargetReactionState(baseState, ReactionType.GRAB))
                .thenReturn(targetState);

        ReactionPostProcessResult postProcessResult = new ReactionPostProcessResult(true, true, true, true, null, 3, null);
        when(playbackReactionDomainService.determinePostProcessing(baseState, targetState))
                .thenReturn(postProcessResult);
        when(playbackReactionHistoryRepository.save(any())).thenReturn(historyData);

        CrewData crew = CrewData.builder().id(5L).userId(userId).build();
        when(partyroomQueryService.getCrewByUserId(partyroomId, userId)).thenReturn(Optional.of(crew));

        AddedTrackInfo addedTrackInfo = new AddedTrackInfo(12345L, 67L);
        when(playbackReactionPostProcessCommandService.postProcess(
                postProcessResult, ReactionType.GRAB, partyroomId, playbackId, new CrewId(5L)))
                .thenReturn(addedTrackInfo);

        // when
        ReactionHistoryDto result = playbackReactionCommandService.reactToCurrentPlayback(partyroomId, ReactionType.GRAB);

        // then
        assertThat(result.isLiked()).isTrue();
        assertThat(result.isGrabbed()).isTrue();
        assertThat(result.addedTrack()).isNotNull();
        assertThat(result.addedTrack().trackId()).isEqualTo(12345L);
        assertThat(result.addedTrack().playlistId()).isEqualTo(67L);
    }

    @Test
    @DisplayName("GRAB 토글 off (이미 grab 상태에서 다시 GRAB → un-grab) 시 addedTrack=null")
    void reactToCurrentPlaybackGrabToggleOffReturnsNullAddedTrack() {
        // given
        ActivePartyroomDto activePartyroom = new ActivePartyroomDto(
                partyroomId.getId(), false, 5L, true, playbackId, new CrewId(5L));
        when(partyroomQueryService.getMyActivePartyroom()).thenReturn(Optional.of(activePartyroom));

        PlaybackReactionHistoryData historyData = mock(PlaybackReactionHistoryData.class);
        when(historyData.getId()).thenReturn(99L);
        when(historyData.applyReactionState(any())).thenReturn(historyData);
        when(playbackReactionHistoryRepository.findByPlaybackIdAndUserIdForUpdate(playbackId, userId))
                .thenReturn(Optional.of(historyData));

        // already-grabbed → GRAB toggle leaves grab false (per ReactionStateResolver: (true,false,true) -GRAB-> (true,false,true);
        // we still verify the contract: when post-process reports no grab status change, addedTrack must be null.
        ReactionState existingState = new ReactionState(true, false, true);
        when(playbackReactionDomainService.getReactionStateByHistory(historyData)).thenReturn(existingState);

        ReactionState targetState = new ReactionState(true, false, true);
        when(playbackReactionDomainService.getTargetReactionState(existingState, ReactionType.GRAB))
                .thenReturn(targetState);

        ReactionPostProcessResult postProcessResult = new ReactionPostProcessResult(false, false, false, false, null, 0, null);
        when(playbackReactionDomainService.determinePostProcessing(existingState, targetState))
                .thenReturn(postProcessResult);
        when(playbackReactionHistoryRepository.save(any())).thenReturn(historyData);

        CrewData crew = CrewData.builder().id(5L).userId(userId).build();
        when(partyroomQueryService.getCrewByUserId(partyroomId, userId)).thenReturn(Optional.of(crew));

        when(playbackReactionPostProcessCommandService.postProcess(any(), any(), any(), any(), any()))
                .thenReturn(null);

        // when
        ReactionHistoryDto result = playbackReactionCommandService.reactToCurrentPlayback(partyroomId, ReactionType.GRAB);

        // then
        assertThat(result.addedTrack()).isNull();
    }

    @Test
    @DisplayName("게스트 사용자가 GRAB 반응 시 예외가 발생한다")
    void reactToCurrentPlaybackGuestGrabThrowsException() {
        // given
        AuthContext guestContext = mock(AuthContext.class);
        lenient().when(guestContext.getUserId()).thenReturn(userId);
        when(guestContext.getAuthorityTier()).thenReturn(AuthorityTier.GT);
        ThreadLocalContext.setContext(guestContext);

        // when & then
        assertThatThrownBy(() -> playbackReactionCommandService.reactToCurrentPlayback(partyroomId, ReactionType.GRAB))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("활성 파티룸이 없으면(소프트 재연결 룸 desync) raw 'No value present' 대신 NOT_FOUND(CRW-001) 도메인 예외로 매핑된다")
    void reactToCurrentPlaybackNoActivePartyroomMapsToNotFound() {
        // given — FM(비게스트) + LIKE 이지만 백엔드에 활성 파티룸 없음 (재연결 stale 윈도우 재현)
        when(partyroomQueryService.getMyActivePartyroom()).thenReturn(Optional.empty());

        // when & then — NoSuchElementException("No value present") 누출이 아니라 도메인 NOT_FOUND
        assertThatThrownBy(() ->
                playbackReactionCommandService.reactToCurrentPlayback(partyroomId, ReactionType.LIKE))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("참여 중인 파티룸을 찾을 수 없습니다");

        // 히스토리 락/후처리까지 진행되지 않는다 (멤버십 없으면 즉시 차단)
        verify(playbackReactionHistoryRepository, never()).findByPlaybackIdAndUserIdForUpdate(any(), any());
        verify(playbackReactionPostProcessCommandService, never())
                .postProcess(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("활성 파티룸은 있으나 해당 룸의 크루가 아니면 NOT_FOUND(CRW-003) 도메인 예외로 매핑된다")
    void reactToCurrentPlaybackCrewNotFoundMapsToNotFound() {
        // given — 활성 파티룸 있음, 히스토리 처리 통과하지만 getCrewByUserId 빈값(부분 desync)
        ActivePartyroomDto activePartyroom = new ActivePartyroomDto(
                partyroomId.getId(), false, 5L, true, playbackId, new CrewId(5L));
        when(partyroomQueryService.getMyActivePartyroom()).thenReturn(Optional.of(activePartyroom));

        PlaybackReactionHistoryData historyData = new PlaybackReactionHistoryData(userId, playbackId);
        when(playbackReactionHistoryRepository.findByPlaybackIdAndUserIdForUpdate(playbackId, userId))
                .thenReturn(Optional.empty());
        when(playbackReactionHistoryRepository.saveAndFlush(any())).thenReturn(historyData);

        ReactionState baseState = ReactionState.createBaseState();
        ReactionState targetState = new ReactionState(true, false, false);
        when(playbackReactionDomainService.getTargetReactionState(baseState, ReactionType.LIKE))
                .thenReturn(targetState);
        ReactionPostProcessResult postProcessResult = new ReactionPostProcessResult(false, false, false, false, null, 0, null);
        when(playbackReactionDomainService.determinePostProcessing(baseState, targetState))
                .thenReturn(postProcessResult);
        when(playbackReactionHistoryRepository.save(any())).thenReturn(historyData);

        when(partyroomQueryService.getCrewByUserId(partyroomId, userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                playbackReactionCommandService.reactToCurrentPlayback(partyroomId, ReactionType.LIKE))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("파티룸의 크루가 아닙니다");
    }

    @Test
    @DisplayName("기존 반응 이력이 있으면 해당 이력을 기반으로 상태를 결정한다")
    void reactToCurrentPlaybackWithExistingHistory() {
        // given
        ActivePartyroomDto activePartyroom = new ActivePartyroomDto(
                partyroomId.getId(), false, 5L, true, playbackId, new CrewId(5L));
        when(partyroomQueryService.getMyActivePartyroom()).thenReturn(Optional.of(activePartyroom));

        PlaybackReactionHistoryData historyData = mock(PlaybackReactionHistoryData.class);
        when(historyData.getId()).thenReturn(99L);
        when(historyData.applyReactionState(any())).thenReturn(historyData);
        when(playbackReactionHistoryRepository.findByPlaybackIdAndUserIdForUpdate(playbackId, userId))
                .thenReturn(Optional.of(historyData));

        ReactionState existingState = new ReactionState(true, false, false);
        when(playbackReactionDomainService.getReactionStateByHistory(historyData))
                .thenReturn(existingState);

        ReactionState targetState = new ReactionState(false, false, false);
        when(playbackReactionDomainService.getTargetReactionState(existingState, ReactionType.LIKE))
                .thenReturn(targetState);

        ReactionPostProcessResult postProcessResult = new ReactionPostProcessResult(false, false, false, false, null, 0, null);
        when(playbackReactionDomainService.determinePostProcessing(existingState, targetState))
                .thenReturn(postProcessResult);
        when(playbackReactionHistoryRepository.save(any())).thenReturn(historyData);

        CrewData crew = CrewData.builder().id(5L).userId(userId).build();
        when(partyroomQueryService.getCrewByUserId(partyroomId, userId)).thenReturn(Optional.of(crew));

        // when
        ReactionHistoryDto result = playbackReactionCommandService.reactToCurrentPlayback(partyroomId, ReactionType.LIKE);

        // then
        assertThat(result.isLiked()).isFalse();
        verify(playbackReactionDomainService).getReactionStateByHistory(historyData);
    }

    @Test
    @DisplayName("동시 첫-INSERT 패자(saveAndFlush unique 위반) — 409 ConflictException 으로 매핑되고 WARN 이 emit 된다")
    void concurrentFirstInsertLoserMapsToConflictAndWarns() {
        // given — FOR-UPDATE 가 행을 못 찾는 첫 반응 경로에서 saveAndFlush 가 unique 제약 위반
        ActivePartyroomDto activePartyroom = new ActivePartyroomDto(
                partyroomId.getId(), false, 5L, true, playbackId, new CrewId(5L));
        when(partyroomQueryService.getMyActivePartyroom()).thenReturn(Optional.of(activePartyroom));

        when(playbackReactionHistoryRepository.findByPlaybackIdAndUserIdForUpdate(playbackId, userId))
                .thenReturn(Optional.empty());
        when(playbackReactionHistoryRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "could not execute statement; constraint [uk_reaction_user_playback]"));

        // when & then — 원시 500/SQL 누출이 아니라 도메인 409 ConflictException 으로 변환
        assertThatThrownBy(() ->
                playbackReactionCommandService.reactToCurrentPlayback(partyroomId, ReactionType.LIKE))
                .isInstanceOf(ConflictException.class);

        // delta 적용(save)·후처리는 일어나지 않는다 — 패자 반응은 드롭
        verify(playbackReactionHistoryRepository, never()).save(any());
        verify(playbackReactionPostProcessCommandService, never())
                .postProcess(any(), any(), any(), any(), any());

        // WARN 한 줄이 playbackId/userId + 재시도 안내와 함께 관측 가능하게 emit
        List<ILoggingEvent> events = appender.list;
        ILoggingEvent warn = events.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("CONCURRENT_FIRST_INSERT_LOST"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "CONCURRENT_FIRST_INSERT_LOST WARN not emitted. Captured: " +
                                events.stream().map(ILoggingEvent::getFormattedMessage).toList()));
        assertThat(warn.getFormattedMessage())
                .contains("playbackId=" + playbackId)
                .contains("userId=" + userId)
                .contains("client should retry");
    }
}
