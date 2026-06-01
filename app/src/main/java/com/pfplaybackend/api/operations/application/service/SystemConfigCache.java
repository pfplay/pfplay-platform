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
    static final int DEFAULT_BOT_YIELD_DEBOUNCE_MS = 5_000;
    static final int DEFAULT_BOT_MIN_DWELL_MS = 10_000;
    static final boolean DEFAULT_VIRTUALDJ_DISTINGUISHABLE = false;

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

    /** 봇 양보(제거) debounce — 제거 의도가 이 시간 이상 지속돼야 실제 제거. Chunk 5 anti-flap. */
    public int getBotYieldDebounceMs() {
        return current().botYieldDebounceMs;
    }

    /** 봇 최소 체류시간 — 투입 후 이 시간 이내 봇은 제거 보호. Chunk 5 anti-flap. */
    public int getBotMinDwellMs() {
        return current().botMinDwellMs;
    }

    /**
     * 가상 DJ 식별 가능 여부 토글 — pre-seed 전용(이번 chunk 에서는 접근자만 제공, DTO 노출은 Plan B).
     * fail-open false.
     */
    public boolean isVirtualDjDistinguishable() {
        return current().virtualDjDistinguishable;
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
        int botYieldDebounce = readInt(ConfigKey.VIRTUALDJ_BOT_YIELD_DEBOUNCE_MS, DEFAULT_BOT_YIELD_DEBOUNCE_MS);
        int botMinDwell = readInt(ConfigKey.VIRTUALDJ_BOT_MIN_DWELL_MS, DEFAULT_BOT_MIN_DWELL_MS);
        boolean distinguishable = readBoolean(ConfigKey.VIRTUALDJ_DISTINGUISHABLE, DEFAULT_VIRTUALDJ_DISTINGUISHABLE);
        return new Snapshot(djGrace, listenerGrace, botYieldDebounce, botMinDwell, distinguishable, now);
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

    /**
     * Fail-open: missing/blank rows fall back to {@code fallback}. Accepts {@code true}/{@code false}
     * (case-insensitive, trimmed); any other value falls back. A typo must not flip a feature toggle.
     */
    private boolean readBoolean(ConfigKey key, boolean fallback) {
        Optional<SystemConfigData> row = repository.findByConfigKey(key.value());
        if (row.isEmpty()) return fallback;
        String v = row.get().getConfigValue();
        if (v == null || v.isBlank()) return fallback;
        String trimmed = v.trim();
        if (trimmed.equalsIgnoreCase("true")) return true;
        if (trimmed.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    private record Snapshot(int djGraceSeconds, int listenerGraceSeconds,
                            int botYieldDebounceMs, int botMinDwellMs,
                            boolean virtualDjDistinguishable, Instant fetchedAt) {}
}
