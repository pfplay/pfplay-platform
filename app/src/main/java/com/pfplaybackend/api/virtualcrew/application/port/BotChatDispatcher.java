package com.pfplaybackend.api.virtualcrew.application.port;

import com.pfplaybackend.api.party.domain.value.PartyroomId;

/**
 * 게이트를 통과한 봇 채팅 응답을 비동기로 디스패치하는 포트.
 *
 * <p>트리거 게이트({@code BotChatTrigger})는 확률/쿨다운/후보 선정을 끝낸 뒤 이 포트로 위임한다.
 * lock 토큰을 넘기지 않는다 — 방별 게이트 키는 TTL 만료로 자연 해제되며, 명시적 release 가 없다.
 * 구현(후속 chunk 의 LLM 워커)은 즉시 반환하고 실제 응답 생성·송신은 별도 스레드에서 수행한다.
 */
public interface BotChatDispatcher {
    void dispatch(PartyroomId partyroomId, long botCrewId, long botUserId);
}
