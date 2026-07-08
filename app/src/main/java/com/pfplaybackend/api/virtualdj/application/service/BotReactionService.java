package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.CurrentPlaybackView;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.application.service.PlaybackReactionSimulationService;
import com.pfplaybackend.api.party.domain.enums.ReactionType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.application.dto.BotCandidate;
import com.pfplaybackend.api.virtualdj.application.port.Randomizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 봇이 현재 재생 곡에 확률적으로 LIKE — 방 생동감(ambient). 스케줄러가 설정 게이트로 호출. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotReactionService {

    private final PartyroomQueryService partyroomQueryService;
    private final BotPoolQueryRepository botPool;
    private final PlaybackReactionSimulationService reactionService;
    private final VirtualDjReactionConfig config;
    private final Randomizer rng;

    public void tryReact(PartyroomId partyroomId) {
        Optional<CurrentPlaybackView> viewOpt = partyroomQueryService.getCurrentPlaybackState(partyroomId);
        if (viewOpt.isEmpty()) return;                                   // 재생 중 아님
        CurrentPlaybackView view = viewOpt.get();

        if (rng.nextIndex(100) >= config.probabilityPercent()) return;   // 확률 롤

        long djCrewId = (view.currentDjCrewId() == null) ? -1L : view.currentDjCrewId().getId();
        List<BotCandidate> candidates = botPool.findActivePersonaBotsInRoom(partyroomId).stream()
                .filter(b -> b.crewId() != djCrewId)                     // 현재 DJ 제외(crewId 기준)
                .toList();
        if (candidates.isEmpty()) return;

        BotCandidate bot = candidates.get(rng.nextIndex(candidates.size()));
        reactionService.apply(new UserId(bot.botUserId()), new CrewId(bot.crewId()),
                view.playbackId(), partyroomId, ReactionType.LIKE, 0);
        log.debug("[vdj-reaction] bot LIKE applied: room={}, botUserId={}", partyroomId.getId(), bot.botUserId());
    }
}
