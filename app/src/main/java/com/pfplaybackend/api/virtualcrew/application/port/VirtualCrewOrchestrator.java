package com.pfplaybackend.api.virtualcrew.application.port;

import com.pfplaybackend.api.party.domain.value.PartyroomId;

/**
 * 한 룸의 봇 DJ 수를 설정 목표에 수렴시키는 오케스트레이터 (Chunk 4).
 *
 * <p>봇 신원으로 실유저와 동일한 명령 서비스(tryEnter/enqueueDj/exit)를 호출하여 투입/제거한다.
 * 도메인 가드를 한 줄도 우회하지 않는다. Chunk 5 의 이벤트 반응·anti-flap·안전망 cron 이 이
 * {@link #reconcileRoom} 을 트리거한다.
 */
public interface VirtualCrewOrchestrator {

    /**
     * 룸의 봇 DJ 수를 설정({@code partyroom_virtual_crew_config}) 목표에 수렴시킨다.
     * 분산 락으로 직렬화되며, 목표와 현재가 같으면 아무 것도 하지 않는다(멱등).
     */
    void reconcileRoom(PartyroomId partyroomId);

    /**
     * 어드민의 비우기(drain) — 룸의 <b>모든</b> 봇 DJ 를 즉시 제거한다.
     *
     * <p>reconcile 과 달리 anti-flap dwell/​debounce 를 건너뛰는 어드민 의도적 액션이지만, 제거 자체는
     * 봇 신원으로 동일한 {@code exit} 명령 경로(path A)를 거치므로 도메인 가드/캐스케이드는 우회하지 않는다.
     * config 상태 전환(OFF)은 호출자(어드민 서비스)가 담당하고, 본 메서드는 봇 제거만 한다.
     * 분산 락으로 reconcile 과 직렬화된다.
     */
    void drainRoom(PartyroomId partyroomId);
}
