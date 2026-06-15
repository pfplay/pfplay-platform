package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.common.config.redis.lock.RedisLockService;
import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.party.application.dto.chat.ChatMessageDto;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.in.listener.BotChatTrigger;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.application.dto.BotCandidate;
import com.pfplaybackend.api.virtualdj.application.port.BotChatDispatcher;
import com.pfplaybackend.api.virtualdj.application.port.Randomizer;
import com.pfplaybackend.api.virtualdj.application.service.BotIdentityResolver;
import com.pfplaybackend.api.virtualdj.application.service.ChatContextBuffer;
import com.pfplaybackend.api.virtualdj.application.service.VirtualDjChatConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BotChatTrigger} 단위 테스트 — 채팅 트리거 게이트의 short-circuit 사슬을 검증한다.
 *
 * <p>모든 협력자를 mock 으로 주입하고, {@code Randomizer.nextIndex} 를 결정적으로 stub 해
 * 확률 패스/미스를 통제한다. 게이트 순서: kill switch → loop guard → buffer.append →
 * 확률 → 후보 조회 → SETNX 게이트 → dispatch.
 */
@ExtendWith(MockitoExtension.class)
class BotChatTriggerTest {

    @Mock private VirtualDjChatConfig config;
    @Mock private BotIdentityResolver identityResolver;
    @Mock private ChatContextBuffer buffer;
    @Mock private Randomizer rng;
    @Mock private BotPoolQueryRepository botQuery;
    @Mock private RedisLockService redisLockService;
    @Mock private BotChatDispatcher dispatcher;

    @InjectMocks private BotChatTrigger trigger;

    private static final long ROOM_ID = 555L;
    private static final long HUMAN_CREW_ID = 10L;

    private ChatMessageDto humanMessage() {
        return new ChatMessageDto(
                new PartyroomId(ROOM_ID),
                MessageTopic.CHAT_MESSAGE_SENT,
                "msg-1",
                System.currentTimeMillis(),
                new ChatMessageDto.CrewInfo(HUMAN_CREW_ID),
                new ChatMessageDto.ChatContent("mid-1", "안녕하세요"));
    }

    @Test
    @DisplayName("kill switch off → append 0, dispatch 0")
    void killSwitchOff_doesNothing() {
        when(config.isEnabled()).thenReturn(false);

        trigger.onChatMessage(humanMessage());

        verify(buffer, never()).append(any(), anyString());
        verify(dispatcher, never()).dispatch(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("봇 발화(isBotCrew=true) → loop guard, append 0, dispatch 0")
    void botMessage_loopGuard_skipsAppendAndDispatch() {
        when(config.isEnabled()).thenReturn(true);
        when(identityResolver.isBotCrew(HUMAN_CREW_ID)).thenReturn(true);

        trigger.onChatMessage(humanMessage());

        verify(buffer, never()).append(any(), anyString());
        verify(dispatcher, never()).dispatch(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("사람 메시지 → buffer.append 정확히 1회")
    void humanMessage_appendsOnce() {
        when(config.isEnabled()).thenReturn(true);
        when(identityResolver.isBotCrew(HUMAN_CREW_ID)).thenReturn(false);
        // 확률 미스로 이후 게이트는 차단 — append 만 검증.
        when(rng.nextIndex(100)).thenReturn(99);
        when(config.probabilityPercent()).thenReturn(12);

        trigger.onChatMessage(humanMessage());

        verify(buffer, times(1)).append(eq(new PartyroomId(ROOM_ID)), eq("안녕하세요"));
    }

    @Test
    @DisplayName("확률 미스 → acquireLock 0, dispatch 0")
    void probabilityMiss_noLockNoDispatch() {
        when(config.isEnabled()).thenReturn(true);
        when(identityResolver.isBotCrew(HUMAN_CREW_ID)).thenReturn(false);
        when(rng.nextIndex(100)).thenReturn(12);   // >= 12 → 미스
        when(config.probabilityPercent()).thenReturn(12);

        trigger.onChatMessage(humanMessage());

        verify(buffer, times(1)).append(any(), anyString());
        verify(redisLockService, never()).acquireLock(anyString(), anyString(), anyLong(), any());
        verify(dispatcher, never()).dispatch(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("확률 패스 + 페르소나 봇 없음 → acquireLock 0, dispatch 0")
    void probabilityPass_noPersonaBots_noLockNoDispatch() {
        when(config.isEnabled()).thenReturn(true);
        when(identityResolver.isBotCrew(HUMAN_CREW_ID)).thenReturn(false);
        when(rng.nextIndex(100)).thenReturn(0);    // < 12 → 패스
        when(config.probabilityPercent()).thenReturn(12);
        when(botQuery.findActivePersonaBotsInRoom(new PartyroomId(ROOM_ID))).thenReturn(List.of());

        trigger.onChatMessage(humanMessage());

        verify(redisLockService, never()).acquireLock(anyString(), anyString(), anyLong(), any());
        verify(dispatcher, never()).dispatch(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("확률 패스 + 후보 있음 + acquireLock=false → dispatch 0")
    void lockNotAcquired_noDispatch() {
        when(config.isEnabled()).thenReturn(true);
        when(identityResolver.isBotCrew(HUMAN_CREW_ID)).thenReturn(false);
        when(rng.nextIndex(100)).thenReturn(0);
        when(config.probabilityPercent()).thenReturn(12);
        when(config.cooldownSeconds()).thenReturn(30);
        when(botQuery.findActivePersonaBotsInRoom(new PartyroomId(ROOM_ID)))
                .thenReturn(List.of(new BotCandidate(7001L, 42L, 9L)));
        when(redisLockService.acquireLock(
                eq("vdj:chat:gate:" + ROOM_ID), eq("1"), eq(30L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        trigger.onChatMessage(humanMessage());

        verify(dispatcher, never()).dispatch(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("확률 패스 + 후보 있음 + acquireLock=true → dispatch 1회, 선택 봇 crewId/userId 전달")
    void lockAcquired_dispatchesChosenBot() {
        when(config.isEnabled()).thenReturn(true);
        when(identityResolver.isBotCrew(HUMAN_CREW_ID)).thenReturn(false);
        when(config.probabilityPercent()).thenReturn(12);
        when(config.cooldownSeconds()).thenReturn(30);
        BotCandidate bot0 = new BotCandidate(7001L, 42L, 9L);
        BotCandidate bot1 = new BotCandidate(7002L, 43L, 9L);
        when(botQuery.findActivePersonaBotsInRoom(new PartyroomId(ROOM_ID)))
                .thenReturn(List.of(bot0, bot1));
        // 첫 nextIndex(100)=확률(패스), 두번째 nextIndex(2)=후보 인덱스 1 선택.
        when(rng.nextIndex(100)).thenReturn(0);
        when(rng.nextIndex(2)).thenReturn(1);
        when(redisLockService.acquireLock(
                eq("vdj:chat:gate:" + ROOM_ID), eq("1"), eq(30L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        trigger.onChatMessage(humanMessage());

        verify(dispatcher, times(1))
                .dispatch(eq(new PartyroomId(ROOM_ID)), eq(43L), eq(7002L));
    }
}
