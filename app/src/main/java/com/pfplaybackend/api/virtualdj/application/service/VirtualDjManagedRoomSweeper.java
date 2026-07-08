package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.operations.application.port.out.MaintenanceGate;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.application.port.VirtualDjOrchestrator;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 전 MANAGED 룸에 대한 봇 라이프사이클 스윕(공유 로직) — 점검 드레인/부활·부팅 부활의 단일 진실원천.
 *
 * <p>드레인/부활은 대칭이다: 점검 시작 시 모든 봇을 드레인(AI 리소스 회수)하되 config 는 MANAGED 로
 * 유지하고, 점검 종료/취소·앱 부팅 시 <b>점검 중이 아닐 때만</b> 목표로 재배치한다. 각 룸 연산은
 * {@link VirtualDjOrchestrator} 파사드(룸별 분산 락 + 멱등 placeToTarget/drainResources)를 그대로
 * 재사용하므로, 이 클래스는 락킹을 재구현하지 않는다.
 *
 * <p>본 클래스는 {@code *Orchestrator*} 명명이 아니며 {@code ..administration..}/{@code ..admin..}
 * 를 import 하지 않는다 — 아키텍처 가드를 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualDjManagedRoomSweeper {

    private final PartyroomVirtualDjConfigRepository configRepository;
    private final VirtualDjOrchestrator orchestrator;
    private final MaintenanceGate maintenanceGate;

    /** 점검 시작: 전 MANAGED 방 드레인(리소스 회수, config MANAGED 유지). */
    public void drainAllManaged() {
        forEachManagedRoom(orchestrator::drainRoom);
    }

    /** 점검 종료/취소/부팅: 점검 중이 아니면 전 MANAGED 방을 목표로 재배치(멱등). */
    public void placeAllManagedIfActive() {
        if (maintenanceGate.isUnderMaintenance()) {
            log.info("[vdj-lifecycle] SKIP_UNDER_MAINTENANCE");
            return;
        }
        forEachManagedRoom(orchestrator::reconcileRoom);
    }

    private void forEachManagedRoom(Consumer<PartyroomId> action) {
        List<PartyroomVirtualDjConfigData> managed = configRepository.findByStatus(VirtualDjStatus.MANAGED);
        for (PartyroomVirtualDjConfigData cfg : managed) {
            try {
                action.accept(new PartyroomId(cfg.getPartyroomId()));
            } catch (Exception e) {
                // 한 룸의 실패가 전체 스윕을 중단시키지 않도록 룸별로 격리한다.
                log.warn("[vdj-lifecycle] room op failed partyroomId={} : {}", cfg.getPartyroomId(), e.getMessage());
            }
        }
    }
}
