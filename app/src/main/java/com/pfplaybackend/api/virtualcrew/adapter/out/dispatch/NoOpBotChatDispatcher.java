package com.pfplaybackend.api.virtualcrew.adapter.out.dispatch;

import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.application.port.BotChatDispatcher;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link BotChatDispatcher} 의 기본 no-op 구현 (Chunk 4).
 *
 * <p>채팅 트리거 게이트({@code BotChatTrigger})는 완성됐지만 실제 응답 생성·송신을 담당하는
 * LLM 비동기 워커는 Chunk 5 에서 도입된다. 그 전까지는 게이트가 후보를 골라 dispatch 를 호출해도
 * 아무 응답도 나가지 않는다 — 단, 컨텍스트 적재·확률·쿨다운 게이트는 정상 동작한다.
 *
 * <p>이 클래스는 {@code @Component} 를 갖지 않는다. 빈 등록은 {@link BotChatDispatcherConfig} 의
 * {@code @Bean @ConditionalOnMissingBean} 팩토리 메서드가 담당하며, 이 방식이 컴포넌트 스캔 순서
 * 비결정성 문제 없이 조건부 등록을 안전하게 보장한다.
 *
 * <p>디스패치가 NO-OP 임을 디버깅에서 식별하기 위해 debug 로그를 남긴다.
 */
@Slf4j
public class NoOpBotChatDispatcher implements BotChatDispatcher {

    @Override
    public void dispatch(PartyroomId partyroomId, long botCrewId, long botUserId) {
        log.debug("[vcrew.chat] dispatch no-op (LLM 워커 미도입, Chunk 5 예정) — roomId={}, botCrewId={}, botUserId={}",
                partyroomId.getId(), botCrewId, botUserId);
    }
}
