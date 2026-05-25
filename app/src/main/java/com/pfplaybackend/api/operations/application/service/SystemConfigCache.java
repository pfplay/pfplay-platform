package com.pfplaybackend.api.operations.application.service;

import com.pfplaybackend.api.operations.adapter.out.persistence.SystemConfigRepository;
import com.pfplaybackend.api.operations.domain.entity.data.SystemConfigData;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory snapshot cache for SystemConfig (presence grace seconds).
 *
 * 30-second TTL. Per-instance — no distributed invalidation.
 * Tolerated staleness window matches spec (§9.3 "system_config 캐시 stale: 캐시 TTL 30~60초").
 *
 * <p>점검(maintenance) 게이팅은 {@code MaintenanceGate} 로 분리됨(issue #267) — writer 가 없어
 * 죽어 있던 {@code maintenance.enabled} 키 대신, 진실원천은 ACTIVE 인 system_announcement 점검이다.
 */
@Component
public class SystemConfigCache {

    static final Duration SNAPSHOT_TTL = Duration.ofSeconds(30);
    static final int DEFAULT_DJ_GRACE_SECONDS = 30;
    static final int DEFAULT_LISTENER_GRACE_SECONDS = 10;

    private final SystemConfigRepository repository;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>();

    public SystemConfigCache(SystemConfigRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public int getDjGraceSeconds() {
        return current().djGraceSeconds;
    }

    public int getListenerGraceSeconds() {
        return current().listenerGraceSeconds;
    }

    /** Public so PR 6's event listener can force-invalidate after admin toggle. */
    public void invalidate() {
        snapshotRef.set(null);
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
        int djGrace = readInt(ConfigKey.PRESENCE_DJ_GRACE_SECONDS, DEFAULT_DJ_GRACE_SECONDS);
        int listenerGrace = readInt(ConfigKey.PRESENCE_LISTENER_GRACE_SECONDS, DEFAULT_LISTENER_GRACE_SECONDS);
        return new Snapshot(djGrace, listenerGrace, now);
    }

    /**
     * Fail-open: missing rows, blank values, non-numeric values, or non-positive integers
     * fall back to {@code fallback}. A typo must not brick presence semantics.
     */
    private int readInt(ConfigKey key, int fallback) {
        Optional<SystemConfigData> row = repository.findByConfigKey(key.value());
        if (row.isEmpty()) return fallback;
        String v = row.get().getConfigValue();
        if (v == null || v.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(v.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record Snapshot(int djGraceSeconds, int listenerGraceSeconds, Instant fetchedAt) {}
}
