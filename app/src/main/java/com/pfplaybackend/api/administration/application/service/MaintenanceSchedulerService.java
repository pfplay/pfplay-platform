package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.MaintenanceStartedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 매분 0초 cron 으로 PLANNED → ACTIVE phase 전이를 책임지는 scheduler.
 *
 * <p>{@link SystemAnnouncementRepository#findDueForMaintenanceActivation} 가
 * scheduledStart ≤ now &lt; scheduledEnd, started=null, cancelled=null 인
 * MAINTENANCE_NOTICE 만 가져옴. 각 entity 는 도메인 메서드
 * {@link SystemAnnouncementData#markMaintenanceStarted} 로 mark 하고
 * {@link MaintenanceStartedEvent} 를 발행 — listener 가 WS broadcast + Edge Config write.
 *
 * <p>단일 attempt: Edge Config write 실패 시 listener 가 ERROR log 만 — 다음 분 tick 에서
 * 자연 재시도 (idx_active_maintenance 기반 query 가 다시 동일 row 반환 안 함, since
 * markMaintenanceStarted 가 maintenanceStartedAt 을 설정해 결과 집합에서 제외됨).
 *
 * <p>Cron: {@code "0 * * * * *"} (Spring 6 syntax — second minute hour day month dow).
 *
 * <p>Spec: docs/superpowers/specs/2026-05-03-system-announcement-design.md §5.4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceSchedulerService {

    private final SystemAnnouncementRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void promoteScheduledMaintenance() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<SystemAnnouncementData> due = repository.findDueForMaintenanceActivation(now);
        if (due.isEmpty()) {
            return;
        }
        for (SystemAnnouncementData entity : due) {
            entity.markMaintenanceStarted(clock);
            eventPublisher.publishEvent(new MaintenanceStartedEvent(entity));
        }
    }
}
