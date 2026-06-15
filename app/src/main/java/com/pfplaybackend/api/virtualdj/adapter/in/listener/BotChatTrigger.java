package com.pfplaybackend.api.virtualdj.adapter.in.listener;

import com.pfplaybackend.api.common.config.redis.lock.RedisLockService;
import com.pfplaybackend.api.party.application.dto.chat.ChatMessageDto;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.application.dto.BotCandidate;
import com.pfplaybackend.api.virtualdj.application.port.BotChatDispatcher;
import com.pfplaybackend.api.virtualdj.application.port.Randomizer;
import com.pfplaybackend.api.virtualdj.application.service.BotIdentityResolver;
import com.pfplaybackend.api.virtualdj.application.service.ChatContextBuffer;
import com.pfplaybackend.api.virtualdj.application.service.VirtualDjChatConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 채팅 관찰 파이프라인의 트리거 게이트 (Chunk 4).
 *
 * <p>{@code chat_message_sent} 메시지 1건마다 호출돼, 사람 메시지를 컨텍스트 버퍼에 적재하고
 * 확률·쿨다운·후보 게이트를 통과하면 봇 응답을 디스패치한다. 봇 발화는 loop guard 로 무시한다.
 *
 * <p>동시성/쿨다운은 방별 단일 게이트 키({@code vdj:chat:gate:<roomId>})의 SETNX+TTL 로 처리한다.
 * 명시적 release 가 없다 — TTL({@code cooldownSeconds}) 만료가 곧 다음 응답 허용 시점이다.
 */
@Component
@RequiredArgsConstructor
public class BotChatTrigger {

    private final VirtualDjChatConfig config;
    private final BotIdentityResolver identityResolver;
    private final ChatContextBuffer buffer;
    private final Randomizer rng;
    private final BotPoolQueryRepository botQuery;
    private final RedisLockService redisLockService;
    private final BotChatDispatcher dispatcher;

    public void onChatMessage(ChatMessageDto msg) {
        if (!config.isEnabled()) return;                                  // kill switch (append 전)
        long crewId = msg.crew().crewId();
        if (identityResolver.isBotCrew(crewId)) return;                   // loop guard: 봇 발화 무시
        buffer.append(msg.partyroomId(), msg.message().content());        // 사람 메시지만 적재
        if (rng.nextIndex(100) >= config.probabilityPercent()) return;    // 확률 미스 → 락 미취득
        List<BotCandidate> bots = botQuery.findActivePersonaBotsInRoom(msg.partyroomId());
        if (bots.isEmpty()) return;
        String gateKey = "vdj:chat:gate:" + msg.partyroomId().getId();
        boolean acquired = redisLockService.acquireLock(
                gateKey, "1", config.cooldownSeconds(), TimeUnit.SECONDS);
        if (!acquired) return;                                            // 쿨다운/inflight: 누군가 보유 중
        BotCandidate chosen = bots.get(rng.nextIndex(bots.size()));
        // 비동기; lock 토큰 없음(TTL 만료로 해제).
        dispatcher.dispatch(msg.partyroomId(), chosen.crewId(), chosen.botUserId());
    }
}
