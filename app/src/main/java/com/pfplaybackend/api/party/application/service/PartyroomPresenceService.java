package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Presence grace state machine.
 *
 * <pre>
 *   ONLINE ──[disconnect]──► PENDING_EXIT ──[grace TTL]──► OFFLINE
 *      ▲                          │
 *      └──[reconnect within grace]┘
 * </pre>
 *
 * <p>DB column {@code crew.pending_exit_at} is the source of truth. Redis key
 * {@code PRESENCE:PENDING:<roomId>:<crewId>} with TTL = grace_seconds drives the
 * timer (event consumed by {@code PresenceExpirationListener}). A reconcile cron
 * provides safety against lost expired events (Issue #195).
 *
 * <p>All transitions other than {@code forceOffline} are silent — no domain events
 * published, no STOMP broadcast. The room is unaware until OFFLINE actually fires.
 *
 * <p>See {@code docs/superpowers/specs/2026-05-09-presence-grace-window-design.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyroomPresenceService {

    private static final String PRESENCE_KEY_PREFIX = "PRESENCE:PENDING:";
    /** Reconcile look-back: max possible grace + buffer for Redis delivery skew. */
    private static final long RECONCILE_BUFFER_SECONDS = 30;

    private final PartyroomAggregatePort aggregatePort;
    private final PartyroomAccessCommandService partyroomAccessCommandService;
    private final SystemConfigCache systemConfigCache;
    private final RedisTemplate<String, Object> redisTemplate;
    private final DistributedLockExecutor distributedLockExecutor;
    private final Clock clock;

    /**
     * ONLINE → PENDING_EXIT. Idempotent: re-firing while already pending is a no-op
     * (original timestamp preserved, grace not extended). Returns silently if the
     * crew row is missing or already inactive.
     */
    @Transactional
    public void markPending(PartyroomId partyroomId, UserId userId) {
        Optional<CrewData> optCrew = aggregatePort.findCrew(partyroomId, userId);
        if (optCrew.isEmpty() || !optCrew.get().isActive()) {
            return;
        }
        CrewData crew = optCrew.get();
        LocalDateTime now = LocalDateTime.now(clock);

        int updated = aggregatePort.markCrewPending(partyroomId, userId, now);
        if (updated == 0) {
            // Already pending or already inactive; do not overwrite Redis TTL.
            return;
        }

        int graceSeconds = pickGraceSeconds(partyroomId, new CrewId(crew.getId()));
        String key = presenceKey(partyroomId, new CrewId(crew.getId()));
        redisTemplate.opsForValue().set(key, "", graceSeconds, TimeUnit.SECONDS);
        log.info("[presence] PENDING_EXIT - userId={}, partyroomId={}, graceSeconds={}",
                userId, partyroomId.getId(), graceSeconds);
    }

    /**
     * PENDING_EXIT → ONLINE. No-op if not currently pending. Returns silently —
     * this transition is invisible to the room.
     */
    @Transactional
    public void clearPending(PartyroomId partyroomId, UserId userId) {
        int cleared = aggregatePort.clearCrewPending(partyroomId, userId);
        if (cleared == 0) {
            return;
        }
        Optional<CrewData> crew = aggregatePort.findCrew(partyroomId, userId);
        crew.ifPresent(c -> redisTemplate.delete(presenceKey(partyroomId, new CrewId(c.getId()))));
        log.info("[presence] ONLINE restored - userId={}, partyroomId={}",
                userId, partyroomId.getId());
    }

    /**
     * PENDING_EXIT → OFFLINE by crewId. Used by the Redis expiration listener
     * (which only has the parsed key) and by the reconcile cron.
     *
     * <p>Re-reads DB to confirm the crew is still PENDING_EXIT — if they reconnected
     * before this handler ran, returns without side effects. Otherwise delegates to
     * {@link PartyroomAccessCommandService#exitInternal} which performs the
     * authoritative deactivation, DJ-queue cleanup, and {@code crew_exited}
     * broadcast.
     */
    @Transactional
    public void forceOffline(PartyroomId partyroomId, CrewId crewId) {
        Optional<CrewData> optCrew = aggregatePort.findCrewById(crewId.getId());
        if (optCrew.isEmpty()) {
            redisTemplate.delete(presenceKey(partyroomId, crewId));
            return;
        }
        CrewData crew = optCrew.get();
        if (!crew.isActive() || !crew.isPendingExit()) {
            // Already exited (race with explicit Exit) or reconnected before this
            // handler arrived — nothing to do beyond cleaning up the Redis key.
            redisTemplate.delete(presenceKey(partyroomId, crewId));
            return;
        }
        log.info("[presence] OFFLINE promotion - userId={}, partyroomId={}, pendingSince={}",
                crew.getUserId(), partyroomId.getId(), crew.getPendingExitAt());

        partyroomAccessCommandService.exitInternal(partyroomId, crew.getUserId());
        redisTemplate.delete(presenceKey(partyroomId, crewId));
    }

    private int pickGraceSeconds(PartyroomId partyroomId, CrewId crewId) {
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroomId);
        boolean isCurrentDj = playbackState != null
                && playbackState.isActivated()
                && playbackState.isCurrentDj(crewId);
        return isCurrentDj
                ? systemConfigCache.getDjGraceSeconds()
                : systemConfigCache.getListenerGraceSeconds();
    }

    static String presenceKey(PartyroomId partyroomId, CrewId crewId) {
        return PRESENCE_KEY_PREFIX + partyroomId.getId() + ":" + crewId.getId();
    }

    /**
     * Parses a Redis presence key back into (partyroomId, crewId). Returns null on
     * malformed input — caller logs and skips.
     */
    public static ParsedPresenceKey parseKey(String key) {
        if (key == null || !key.startsWith(PRESENCE_KEY_PREFIX)) return null;
        String suffix = key.substring(PRESENCE_KEY_PREFIX.length());
        int sep = suffix.indexOf(':');
        if (sep <= 0 || sep == suffix.length() - 1) return null;
        try {
            long partyroomId = Long.parseLong(suffix.substring(0, sep));
            long crewId = Long.parseLong(suffix.substring(sep + 1));
            return new ParsedPresenceKey(new PartyroomId(partyroomId), new CrewId(crewId));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record ParsedPresenceKey(PartyroomId partyroomId, CrewId crewId) {}

    /**
     * Reconciliation safety net for Issue #195: Redis pub/sub does not buffer
     * expired-key events, so any TTL that elapses while the listener is not
     * subscribed (app restart, network blip on the Redis connection) is lost
     * forever. This cron sweeps DB rows whose {@code pending_exit_at} is older
     * than the maximum possible grace and forces them OFFLINE.
     *
     * <p>Doubles as startup recovery — the first tick after boot catches anything
     * stranded during downtime.
     *
     * <p>Idempotent w.r.t. the Redis listener path: both call {@code forceOffline},
     * which re-checks DB and returns early if the row no longer qualifies. The
     * per-crew distributed lock prevents simultaneous publish of the EXIT event.
     */
    @Scheduled(fixedDelay = 60_000)
    public void reconcileStalePending() {
        long maxGrace = Math.max(systemConfigCache.getDjGraceSeconds(),
                                 systemConfigCache.getListenerGraceSeconds());
        LocalDateTime threshold = LocalDateTime.now(clock).minusSeconds(maxGrace + RECONCILE_BUFFER_SECONDS);
        List<CrewData> stale = aggregatePort.findStalePendingCrews(threshold);
        if (stale.isEmpty()) return;

        log.info("[presence] reconcile sweep found {} stale PENDING_EXIT crew(s)", stale.size());
        for (CrewData crew : stale) {
            try {
                String lockKey = "presence:" + crew.getId();
                distributedLockExecutor.performTaskWithLock(lockKey, () -> {
                    forceOffline(crew.getPartyroomId(), new CrewId(crew.getId()));
                    return null;
                });
            } catch (Exception e) {
                log.warn("[presence] reconcile failed for crewId={} : {}", crew.getId(), e.getMessage());
            }
        }
    }
}
