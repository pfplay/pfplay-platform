package com.pfplaybackend.api.virtualcrew.adapter.in.event;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.AnnouncementCancelledEvent;
import com.pfplaybackend.api.administration.domain.event.MaintenanceEndedEvent;
import com.pfplaybackend.api.administration.domain.event.MaintenanceStartedEvent;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewManagedRoomSweeper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 점검 라이프사이클 이벤트 → 봇 드레인/부활 트리거(얇은 리스너).
 *
 * <p>{@link VirtualCrewManagedRoomSweeper} 로만 위임하며 락/멱등/점검게이트는 sweeper·orchestrator
 * 파사드가 담당한다. 본 클래스는 {@code ..administration..} 이벤트를 import 하지만
 * {@code *Orchestrator*} 명명이 아니므로 아키텍처 규칙 A(오케스트레이터의 admin 역참조 금지)에
 * 저촉되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VirtualCrewMaintenanceListener {

    private final VirtualCrewManagedRoomSweeper sweeper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMaintenanceStarted(MaintenanceStartedEvent e) {
        sweeper.drainAllManaged();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMaintenanceEnded(MaintenanceEndedEvent e) {
        sweeper.placeAllManagedIfActive();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAnnouncementCancelled(AnnouncementCancelledEvent e) {
        // 점검 공지 취소는, 그 점검이 실제로 ACTIVE(maintenanceStartedAt != null)였을 때만 부활 트리거.
        // 비-점검 공지 취소나 아직 시작 안 된 점검 취소는 무시(드레인된 봇이 없으므로).
        SystemAnnouncementData a = e.entity();
        if (a.getType() == AnnouncementType.MAINTENANCE_NOTICE && a.getMaintenanceStartedAt() != null) {
            sweeper.placeAllManagedIfActive();
        }
    }
}
