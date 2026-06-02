package com.pfplaybackend.api.virtualdj.adapter.out.dispatch;

import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.application.port.BotChatDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * {@link BotChatDispatcher} 의 기본 no-op 구현 (Chunk 4).
 *
 * <p>채팅 트리거 게이트({@code BotChatTrigger})는 완성됐지만 실제 응답 생성·송신을 담당하는
 * LLM 비동기 워커는 Chunk 5 에서 도입된다. 그 전까지는 게이트가 후보를 골라 dispatch 를 호출해도
 * 아무 응답도 나가지 않는다 — 단, 컨텍스트 적재·확률·쿨다운 게이트는 정상 동작한다.
 *
 * <p>{@link ConditionalOnMissingBean} 로 두어, Chunk 5 의 실제 디스패처 빈이 등록되면 자동으로
 * 대체된다. 디스패치가 NO-OP 임을 디버깅에서 식별하기 위해 debug 로그를 남긴다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = BotChatDispatcher.class, ignored = NoOpBotChatDispatcher.class)
public class NoOpBotChatDispatcher implements BotChatDispatcher {

    @Override
    public void dispatch(PartyroomId partyroomId, long botCrewId, long botUserId) {
        log.debug("[vdj.chat] dispatch no-op (LLM 워커 미도입, Chunk 5 예정) — roomId={}, botCrewId={}, botUserId={}",
                partyroomId.getId(), botCrewId, botUserId);
    }
}
