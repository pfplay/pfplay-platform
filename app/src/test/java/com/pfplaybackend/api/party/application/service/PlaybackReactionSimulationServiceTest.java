package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackReactionHistoryRepository;
import com.pfplaybackend.api.party.application.port.out.UserActivityPort;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.history.PlaybackReactionHistoryData;
import com.pfplaybackend.api.party.domain.enums.MotionType;
import com.pfplaybackend.api.party.domain.enums.ReactionType;
import com.pfplaybackend.api.party.domain.model.ReactionPostProcessResult;
import com.pfplaybackend.api.party.domain.model.ReactionState;
import com.pfplaybackend.api.party.domain.service.PlaybackReactionDomainService;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaybackReactionSimulationServiceTest {

    @Mock
    private PlaybackReactionHistoryRepository historyRepository;

    @Mock
    private PlaybackQueryService playbackQueryService;

    @Mock
    private PlaybackCommandService playbackCommandService;

    @Mock
    private PlaybackReactionDomainService reactionDomainService;

    @Mock
    private PlaybackReactionPostProcessCommandService postProcessCommandService;

    @Mock
    private UserActivityPort userActivityPort;

    @InjectMocks
    private PlaybackReactionSimulationService service;

    private void setupCommonMocks(PlaybackId playbackId, UserId userId, ReactionPostProcessResult postProcess) {
        // 반응 히스토리 없음 -> 새 히스토리 생성
        when(historyRepository.findByPlaybackIdAndUserId(eq(playbackId), eq(userId)))
                .thenReturn(Optional.empty());

        // 도메인 서비스: base state -> target state
        ReactionState baseState = ReactionState.createBaseState();
        ReactionState targetState = new ReactionState(true, false, false);
        when(reactionDomainService.getTargetReactionState(baseState, ReactionType.LIKE))
                .thenReturn(targetState);
        when(reactionDomainService.determinePostProcessing(baseState, targetState))
                .thenReturn(postProcess);

        // 히스토리 저장
        when(historyRepository.save(any(PlaybackReactionHistoryData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // 후처리용 playback (lenient: 모든 브랜치를 타지 않음)
        PlaybackData playback = mock(PlaybackData.class);
        lenient().when(playback.getUserId()).thenReturn(new UserId(99L));
        lenient().when(playback.getId()).thenReturn(playbackId.getId());
        when(playbackQueryService.getPlaybackById(playbackId)).thenReturn(playback);
    }

    @Test
    @DisplayName("apply — 새 반응 히스토리 생성 시 저장이 호출된다")
    void applyNewHistorySavesCalled() {
        // given
        UserId userId = new UserId(1L);
        CrewId crewId = new CrewId(10L);
        PlaybackId playbackId = new PlaybackId(100L);
        PartyroomId partyroomId = new PartyroomId(1000L);

        ReactionPostProcessResult postProcess = new ReactionPostProcessResult(
                false, true, false, false,
                List.of(0, 0, 0), 0, MotionType.DANCE_TYPE_1
        );
        setupCommonMocks(playbackId, userId, postProcess);

        // when
        service.apply(userId, crewId, playbackId, partyroomId, ReactionType.LIKE, 0);

        // then
        verify(historyRepository).save(any(PlaybackReactionHistoryData.class));
        verify(postProcessCommandService).publishMotionChangedEvent(
                eq(partyroomId), eq(ReactionType.LIKE), any(), eq(crewId));
    }

    @Test
    @DisplayName("apply — DJ 점수 변경 시 userActivityPort가 호출된다")
    void applyDjScoreChangedUpdatesActivity() {
        // given
        UserId userId = new UserId(2L);
        CrewId crewId = new CrewId(20L);
        PlaybackId playbackId = new PlaybackId(200L);
        PartyroomId partyroomId = new PartyroomId(2000L);

        ReactionPostProcessResult postProcess = new ReactionPostProcessResult(
                false, true, true, false,
                List.of(0, 0, 0), 1, MotionType.DANCE_TYPE_1
        );
        setupCommonMocks(playbackId, userId, postProcess);

        // when
        service.apply(userId, crewId, playbackId, partyroomId, ReactionType.LIKE, 0);

        // then
        verify(userActivityPort).updateDjPointScore(new UserId(99L), 1);
    }

    @Test
    @DisplayName("apply — 집계 변경 시 updatePlaybackAggregation + 이벤트 발행이 호출된다")
    void applyAggregationChangedUpdatesAggregationAndPublishes() {
        // given
        UserId userId = new UserId(3L);
        CrewId crewId = new CrewId(30L);
        PlaybackId playbackId = new PlaybackId(300L);
        PartyroomId partyroomId = new PartyroomId(3000L);

        List<Integer> deltaRecord = List.of(1, 0, 0);
        ReactionPostProcessResult postProcess = new ReactionPostProcessResult(
                true, true, false, false,
                deltaRecord, 0, MotionType.DANCE_TYPE_1
        );
        setupCommonMocks(playbackId, userId, postProcess);

        PlaybackAggregationData aggregation = mock(PlaybackAggregationData.class);
        when(playbackCommandService.updatePlaybackAggregation(new PlaybackId(playbackId.getId()), deltaRecord))
                .thenReturn(aggregation);

        // when
        service.apply(userId, crewId, playbackId, partyroomId, ReactionType.LIKE, 0);

        // then
        verify(playbackCommandService).updatePlaybackAggregation(new PlaybackId(playbackId.getId()), deltaRecord);
        verify(postProcessCommandService).publishAggregationChangedEvent(partyroomId, aggregation);
    }
}
