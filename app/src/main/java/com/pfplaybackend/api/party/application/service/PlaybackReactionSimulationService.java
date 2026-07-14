package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackReactionHistoryRepository;
import com.pfplaybackend.api.party.application.port.out.UserActivityPort;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.history.PlaybackReactionHistoryData;
import com.pfplaybackend.api.party.domain.enums.ReactionType;
import com.pfplaybackend.api.party.domain.model.ReactionPostProcessResult;
import com.pfplaybackend.api.party.domain.model.ReactionState;
import com.pfplaybackend.api.party.domain.service.PlaybackReactionDomainService;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반응(LIKE/GRAB)을 명시적 신원으로 적용하는 프리미티브. WS·ThreadLocalContext 비의존.
 * admin 시뮬레이션과 virtualcrew 봇 반응이 공유한다(admin ReactionSimulationService에서 이관).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackReactionSimulationService {

    private final PlaybackReactionHistoryRepository historyRepository;
    private final PlaybackQueryService playbackQueryService;
    private final PlaybackCommandService playbackCommandService;
    private final PlaybackReactionDomainService reactionDomainService;
    private final PlaybackReactionPostProcessCommandService postProcessCommandService;
    private final UserActivityPort userActivityPort;

    /**
     * 반응을 명시적 신원으로 적용한다(ThreadLocalContext 비의존).
     *
     * @param userId       반응을 수행하는 사용자
     * @param crewId       크루 ID
     * @param playbackId   현재 재생 ID
     * @param partyroomId  파티룸 ID
     * @param reactionType 반응 유형(LIKE/GRAB)
     * @param delayMs      반응 전 지연(ms) — 로깅용, 지연 자체는 호출부 책임
     */
    @Transactional
    public void apply(UserId userId, CrewId crewId, PlaybackId playbackId,
                      PartyroomId partyroomId, ReactionType reactionType, int delayMs) {
        // 1. 반응 히스토리 조회 또는 생성
        PlaybackReactionHistoryData historyData = historyRepository
                .findByPlaybackIdAndUserId(playbackId, userId)
                .orElse(new PlaybackReactionHistoryData(userId, playbackId));

        // 2. 기존 상태 계산
        ReactionState existingState = historyData.getId() != null
                ? reactionDomainService.getReactionStateByHistory(historyData)
                : ReactionState.createBaseState();

        // 3. 타깃 상태 계산
        ReactionState targetState = reactionDomainService.getTargetReactionState(existingState, reactionType);

        // 4. 히스토리 저장
        historyRepository.save(historyData.applyReactionState(targetState));

        // 5. 후처리 결정
        ReactionPostProcessResult postProcessDto = reactionDomainService
                .determinePostProcessing(existingState, targetState);

        // 6. 후처리 실행(ThreadLocalContext 비의존 — grab music 조작은 시뮬레이션 대상 아님)
        PlaybackData playback = playbackQueryService.getPlaybackById(playbackId);

        if (postProcessDto.djActivityScoreChanged()) {
            userActivityPort.updateDjPointScore(playback.getUserId(), postProcessDto.deltaScore());
        }

        if (postProcessDto.aggregationChanged()) {
            PlaybackAggregationData aggregation = playbackCommandService
                    .updatePlaybackAggregation(new PlaybackId(playback.getId()), postProcessDto.deltaRecord());
            postProcessCommandService.publishAggregationChangedEvent(partyroomId, aggregation);
        }

        postProcessCommandService.publishMotionChangedEvent(
                partyroomId, reactionType, postProcessDto.determinedMotionType(), crewId);

        log.debug("Applied {} reaction after {}ms delay: userId={}, playbackId={}",
                reactionType, delayMs, userId.getUid(), playbackId.getId());
    }
}
