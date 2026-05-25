package com.pfplaybackend.api.administration.adapter.out.maintenance;

import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.operations.application.port.out.MaintenanceGate;
import org.springframework.stereotype.Component;

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
