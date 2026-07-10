package com.pfplaybackend.api.administration.adapter.out.maintenance;

import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.AnnouncementCancelledEvent;
import com.pfplaybackend.api.administration.domain.event.MaintenanceEndedEvent;
import com.pfplaybackend.api.administration.domain.event.MaintenanceStartedEvent;
import com.pfplaybackend.api.operations.application.port.out.MaintenanceGate;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link MaintenanceGate} 구현 — 현재 ACTIVE 인 system_announcement 점검
 * ({@code maintenanceStartedAt != null && cancelledAt == null && completedAt == null},
 * {@link SystemAnnouncementRepository#findCurrentMaintenance})을 단일 진실원천으로 삼는다.
 * 운영자가 어드민에서 토글하는 바로 그 상태이므로, writer 가 없어 죽어 있던 system_config
 * {@code maintenance.enabled} 키를 대체한다(issue #267).
 *
 * <p>{@code SystemConfigCache} 와 동일한 30s in-memory 스냅샷 캐시 — 필터가 매 요청마다
 * 호출하므로 요청당 DB 조회를 피한다. 토글 후 최대 30s staleness 는 기존 허용 정책과 동일
 * (frontend 점검 화면은 Edge Config 로 즉시 전환되고, 백엔드 게이트는 그 안에서 수렴).
 */
@Component
public class ActiveMaintenanceGate implements MaintenanceGate {

    static final Duration SNAPSHOT_TTL = Duration.ofSeconds(30);
    static final String DEFAULT_MAINTENANCE_MESSAGE = "시스템 점검 중입니다.";

    private final SystemAnnouncementRepository repository;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>();

    public ActiveMaintenanceGate(SystemAnnouncementRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public boolean isUnderMaintenance() {
        return current().underMaintenance;
    }

    @Override
    public String getMaintenanceMessage() {
        return current().message;
    }

    /**
     * 스냅샷 캐시를 즉시 무효화 — 다음 {@link #isUnderMaintenance()} 호출이 DB 를 재조회하게 한다.
     * 점검 상태 전이 이벤트 직후 호출되어, 30s TTL 로 인한 staleness 를 우회한다.
     */
    public void invalidate() {
        snapshotRef.set(null);
    }

    /**
     * 점검 상태 전이 이벤트 → 캐시 무효화.
     *
     * <p><b>{@code @Order(HIGHEST_PRECEDENCE)} 가 핵심:</b> {@code VirtualCrewMaintenanceListener} 의
     * 봇 부활 리스너와 <b>동일 이벤트·동일 AFTER_COMMIT phase</b> 에서 함께 발화한다. 이 evictor 가
     * <b>먼저</b> 실행되어 캐시를 비워야, 뒤이어 부활 리스너가 호출하는 {@link #isUnderMaintenance()} 가
     * 점검 종료 후의 DB 상태(=false)를 신선하게 재조회해 부활이 진행된다. 부활 리스너에는 {@code @Order}
     * 가 없어(=LOWEST_PRECEDENCE) 항상 이 evictor 뒤에 실행된다.
     *
     * <p>세 이벤트 모두에서 무효화하는 것이 옳다: 재조회는 실제 DB 상태를 반영하므로, 겹치는 점검이
     * 아직 ACTIVE 라면 {@code findCurrentMaintenance} 가 그것을 그대로 반환해 게이트가 true 로 유지되어
     * 부활이 올바르게 차단된다. {@code fallbackExecution = true} 로, 주변 트랜잭션이 없어도 발화한다.
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMaintenanceStarted(MaintenanceStartedEvent e) {
        invalidate();
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMaintenanceEnded(MaintenanceEndedEvent e) {
        invalidate();
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAnnouncementCancelled(AnnouncementCancelledEvent e) {
        invalidate();
    }

    private Snapshot current() {
        Snapshot existing = snapshotRef.get();
        Instant now = clock.instant();
        if (existing != null && Duration.between(existing.fetchedAt, now).compareTo(SNAPSHOT_TTL) < 0) {
            return existing;
        }
        Snapshot fresh = fetch(now);
        snapshotRef.compareAndSet(existing, fresh);
        return fresh;
    }

    private Snapshot fetch(Instant now) {
        return repository.findCurrentMaintenance()
            .map(announcement -> new Snapshot(true, messageOf(announcement), now))
            .orElseGet(() -> new Snapshot(false, DEFAULT_MAINTENANCE_MESSAGE, now));
    }

    private String messageOf(SystemAnnouncementData announcement) {
        String message = announcement.getMessageKo();
        return (message == null || message.isBlank()) ? DEFAULT_MAINTENANCE_MESSAGE : message;
    }

    private record Snapshot(boolean underMaintenance, String message, Instant fetchedAt) {}
}
