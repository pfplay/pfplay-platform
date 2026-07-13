package com.pfplaybackend.api.virtualcrew.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 앱 부팅 시 MANAGED 룸 봇 부활(점검 중이 아닐 때만).
 *
 * <p>점검 종료/취소와 대칭인 부활 경로로, 재기동 후 드레인 상태로 남은 봇을 목표로 재배치한다.
 *
 * <p><b>클러스터 중복 스윕 가드 없음(의도):</b> 정합성은 이미 룸별 분산 락
 * ({@code orchestrator.reconcileRoom} 내부) + placeToTarget 멱등성으로 보장된다 — 여러 인스턴스가
 * 동시에 스윕해도 동일한 수렴 상태로 수렴하며 이중 투입이 발생하지 않는다. 파킹된 Redis-NX/ShedLock
 * 멀티인스턴스 가드는 현재 트리에 없고, 새 인프라를 세우지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VirtualCrewBootReviver {

    private final VirtualCrewManagedRoomSweeper sweeper;

    @EventListener(ApplicationReadyEvent.class)
    public void reviveOnBoot() {
        log.info("[vcrew-boot] ApplicationReady → revive managed rooms (if not under maintenance)");
        sweeper.placeAllManagedIfActive();
    }
}
