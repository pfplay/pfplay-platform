package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.operations.application.port.out.MaintenanceGate;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.PartyroomVirtualCrewConfigRepository;
import com.pfplaybackend.api.virtualcrew.application.port.VirtualCrewOrchestrator;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.PartyroomVirtualCrewConfigData;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Consumer;

/**
 * 전 MANAGED 룸에 대한 봇 라이프사이클 스윕(공유 로직) — 점검 드레인/부활·부팅 부활의 단일 진실원천.
 *
 * <p>드레인/부활은 대칭이다: 점검 시작 시 모든 봇을 드레인(AI 리소스 회수)하되 config 는 MANAGED 로
 * 유지하고, 점검 종료/취소·앱 부팅 시 <b>점검 중이 아닐 때만</b> 목표로 재배치한다. 각 룸 연산은
 * {@link VirtualCrewOrchestrator} 파사드(룸별 분산 락 + 멱등 placeToTarget/drainResources)를 그대로
 * 재사용하므로, 이 클래스는 락킹을 재구현하지 않는다.
 *
 * <p>본 클래스는 {@code *Orchestrator*} 명명이 아니며 {@code ..administration..}/{@code ..admin..}
 * 를 import 하지 않는다 — 아키텍처 가드를 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualCrewManagedRoomSweeper {

    private final PartyroomVirtualCrewConfigRepository configRepository;
    private final VirtualCrewOrchestrator orchestrator;
    private final MaintenanceGate maintenanceGate;

    /**
     * 룸별 {@code REQUIRES_NEW} 경계를 프록시로 통과하기 위한 self 참조.
     * {@code @Lazy} + setter 주입으로 순환 의존성을 피하면서 Spring AOP 프록시를 경유해
     * {@link #runRoomOpInNewTransaction} 의 {@code @Transactional(REQUIRES_NEW)} 가 적용되게 한다.
     */
    @Setter(onMethod_ = {@Autowired, @Lazy})
    private VirtualCrewManagedRoomSweeper self;

    /** 점검 시작: 전 MANAGED 방 드레인(리소스 회수, config MANAGED 유지). */
    public void drainAllManaged() {
        forEachManagedRoom(orchestrator::drainRoom);
    }

    /** 점검 종료/취소/부팅: 점검 중이 아니면 전 MANAGED 방을 목표로 재배치(멱등). */
    public void placeAllManagedIfActive() {
        if (maintenanceGate.isUnderMaintenance()) {
            log.info("[vcrew-lifecycle] SKIP_UNDER_MAINTENANCE");
            return;
        }
        forEachManagedRoom(orchestrator::reconcileRoom);
    }

    private void forEachManagedRoom(Consumer<PartyroomId> action) {
        List<PartyroomVirtualCrewConfigData> managed = configRepository.findByStatus(VirtualCrewStatus.MANAGED);
        for (PartyroomVirtualCrewConfigData cfg : managed) {
            try {
                // self 프록시로 룸별 REQUIRES_NEW 경계를 통과시킨다(아래 주석 참조).
                self.runRoomOpInNewTransaction(action, new PartyroomId(cfg.getPartyroomId()));
            } catch (Exception e) {
                // 한 룸의 실패가 전체 스윕을 중단시키지 않도록 룸별로 격리한다.
                log.warn("[vcrew-lifecycle] room op failed partyroomId={} : {}", cfg.getPartyroomId(), e.getMessage());
            }
        }
    }

    /**
     * 단일 룸 봇 연산을 <b>독립 트랜잭션</b>(REQUIRES_NEW)으로 실행한다.
     *
     * <p><b>왜 REQUIRES_NEW 인가:</b> 이 sweeper 의 진입점(점검 리스너)은
     * {@code @TransactionalEventListener(AFTER_COMMIT)} 에서 호출된다. 그 phase 에서는 커밋을
     * 트리거한 트랜잭션의 리소스가 스레드에 <b>바인딩된 채 남아</b> 있어, 하위
     * {@code @Transactional(REQUIRED)} 명령들이 새 트랜잭션을 열지 못하고 이미 완료된 트랜잭션에
     * 편승 → "no transaction is in progress"/"Executing an update/delete query" 로 실패하고
     * per-bot try/catch 가 이를 삼켜 <b>무음 no-op</b> 이 된다. REQUIRES_NEW 는 완료된 트랜잭션을
     * suspend 하고 진짜 새 트랜잭션을 열어 하위 쓰기(orchestrator REQUIRED 가 join)를 커밋시킨다.
     * 부팅 경로(주변 tx 없음)에서도 새 tx 를 열어 동일하게 동작한다.
     *
     * <p><b>룸별 경계인 이유:</b> 스윕 전체를 한 tx 로 묶으면 한 룸의 실패가 rollback-only 를 유발해
     * 다른 룸까지 롤백된다. 룸마다 독립 tx + 호출부 try/catch 로 룸 단위 격리를 유지한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runRoomOpInNewTransaction(Consumer<PartyroomId> action, PartyroomId partyroomId) {
        action.accept(partyroomId);
    }
}
