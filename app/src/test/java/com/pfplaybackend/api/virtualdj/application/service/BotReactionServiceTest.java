package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.CurrentPlaybackView;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.application.service.PlaybackReactionSimulationService;
import com.pfplaybackend.api.party.domain.enums.ReactionType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.application.dto.BotCandidate;
import com.pfplaybackend.api.virtualdj.application.port.Randomizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotReactionServiceTest {

    @Mock PartyroomQueryService partyroomQueryService;
    @Mock BotPoolQueryRepository botPool;
    @Mock PlaybackReactionSimulationService reactionService;
    @Mock VirtualDjReactionConfig config;
    @Mock Randomizer rng;
    @InjectMocks BotReactionService service;

    private final PartyroomId room = new PartyroomId(1L);

    @Test
    @DisplayName("재생 없음 → apply 미호출")
    void noPlayback_skips() {
        when(partyroomQueryService.getCurrentPlaybackState(room)).thenReturn(Optional.empty());

        service.tryReact(room);

        verifyNoInteractions(reactionService);
    }

    @Test
    @DisplayName("확률 미스 → apply 미호출")
    void probabilityMiss_skips() {
        when(partyroomQueryService.getCurrentPlaybackState(room))
                .thenReturn(Optional.of(new CurrentPlaybackView(new PlaybackId(9L), new CrewId(200L))));
        when(config.probabilityPercent()).thenReturn(15);
        when(rng.nextIndex(100)).thenReturn(90); // >= 15 → miss

        service.tryReact(room);

        verifyNoInteractions(reactionService);
    }

    @Test
    @DisplayName("히트 → 현재 DJ 제외한 봇에 LIKE apply")
    void hit_appliesLikeExcludingCurrentDj() {
        PlaybackId pid = new PlaybackId(9L);
        when(partyroomQueryService.getCurrentPlaybackState(room))
                .thenReturn(Optional.of(new CurrentPlaybackView(pid, new CrewId(200L)))); // 현재 DJ crew=200
        when(config.probabilityPercent()).thenReturn(50);
        when(rng.nextIndex(100)).thenReturn(10); // < 50 → hit
        BotCandidate botB = new BotCandidate(10L, 200L, 1L); // 현재 DJ(제외 대상)
        BotCandidate botC = new BotCandidate(11L, 201L, 2L);
        when(botPool.findActivePersonaBotsInRoom(room)).thenReturn(List.of(botB, botC));
        when(rng.nextIndex(1)).thenReturn(0); // 후보 1명(botC)

        service.tryReact(room);

        verify(reactionService).apply(new UserId(11L), new CrewId(201L), pid, room, ReactionType.LIKE, 0);
    }
}
