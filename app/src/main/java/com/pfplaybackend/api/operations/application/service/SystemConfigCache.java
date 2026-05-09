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
 * In-memory snapshot cache for SystemConfig.
 *
 * 30-second TTL. Per-instance — no distributed invalidation in PR 3.
 * Tolerated staleness window matches spec (§9.3 "system_config 캐시 stale: 캐시 TTL 30~60초").
 *
 * PR 6 will add a SystemConfigUpdated domain event + listener that calls invalidate()
 * when admin endpoints toggle maintenance.
 */
@Component
public class SystemConfigCache {

    static final Duration SNAPSHOT_TTL = Duration.ofSeconds(30);
    static final String DEFAULT_MAINTENANCE_MESSAGE = "시스템 점검 중입니다.";
    static final int DEFAULT_DJ_GRACE_SECONDS = 30;
    static final int DEFAULT_LISTENER_GRACE_SECONDS = 10;

    private final SystemConfigRepository repository;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>();

    public SystemConfigCache(SystemConfigRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public boolean isMaintenanceMode() {
        return current().maintenanceEnabled;
    }

    public String getMaintenanceMessage() {
        return current().maintenanceMessage;
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
        boolean enabled = readBool(ConfigKey.MAINTENANCE_ENABLED, false);
        String message = readString(ConfigKey.MAINTENANCE_MESSAGE, DEFAULT_MAINTENANCE_MESSAGE);
        int djGrace = readInt(ConfigKey.PRESENCE_DJ_GRACE_SECONDS, DEFAULT_DJ_GRACE_SECONDS);
        int listenerGrace = readInt(ConfigKey.PRESENCE_LISTENER_GRACE_SECONDS, DEFAULT_LISTENER_GRACE_SECONDS);
        return new Snapshot(enabled, message, djGrace, listenerGrace, now);
    }

    /**
     * Fail-open by design: missing rows or malformed values fall back to {@code fallback}
     * (which is {@code false} for maintenance.enabled). A corrupted seed must NOT brick
     * the platform; operators must explicitly write the literal string "true" to engage
     * maintenance mode. Inverting this would risk locking everyone out from a typo.
     */
    private boolean readBool(ConfigKey key, boolean fallback) {
        Optional<SystemConfigData> row = repository.findByConfigKey(key.value());
        if (row.isEmpty()) return fallback;
        String v = row.get().getConfigValue();
        if ("true".equalsIgnoreCase(v)) return true;
        if ("false".equalsIgnoreCase(v)) return false;
        return fallback;
    }

    private String readString(ConfigKey key, String fallback) {
        return repository.findByConfigKey(key.value())
            .map(SystemConfigData::getConfigValue)
            .filter(s -> !s.isBlank())
            .orElse(fallback);
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

    private record Snapshot(boolean maintenanceEnabled, String maintenanceMessage,
                            int djGraceSeconds, int listenerGraceSeconds,
                            Instant fetchedAt) {}
}
