package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.party.application.port.out.LivenessSweepQueryPort;
import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
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
    // #356 in-process 실연결 진실원천(STOMP Principal name = uid 문자열). Redis 기반
    // UserSessionRegistry 는 disconnect 이벤트 유실 시 스테일 세션이 남아 "살아있음"으로
    // 거짓말할 수 있으므로 liveness 판정에는 쓰지 않는다.
    private final SimpUserRegistry simpUserRegistry;
    // #356 후보 조회는 봇 판별(user_account.is_dummy) cross-BC 조인이 필요해 external 어댑터 포트로 분리.
    private final LivenessSweepQueryPort livenessSweepQueryPort;

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
    /**
     * #356 presence liveness 스윕 — "떠남 신호가 유실된" 고아 활성 crew(유령) 자가 치유.
     *
     * <p>{@code is_active=1 AND pending_exit_at IS NULL} 인 crew 는 disconnect 이벤트가 유실되면
     * (WS silent death, markPending 중 Redis 실패 롤백, 배포/크래시 시점 유실 등) grace/Redis/
     * reconcile 어디에도 잡히지 않아 본인이 재입장할 때까지 불멸이다 — #241 이 prod 에서 실증됨
     * (실유저 2명이 수일간 유령 잔존). 이 크론이 그 사각을 메운다.
     *
     * <p>판정·조치 설계:
     * <ul>
     *   <li><b>판정 = {@link SimpUserRegistry}</b>(in-process 실연결). 단일 인스턴스 전제 —
     *       스케일아웃 시 각 인스턴스가 자기 세션만 아는 문제가 생기므로 그때는 인스턴스별
     *       스윕 or Redis 세션 union 으로 승격 필요(#356 참조).</li>
     *   <li><b>조치 = {@link #markPending}(즉시 exit 아님)</b> — 기존 상태머신 재사용. 만에 하나
     *       오탐이어도 grace 안 reconnect 의 clearPending 이 취소하고, 진짜 유령이면 grace 만료로
     *       정상 exit(DJ큐 정리 + EXIT 이벤트) 된다.</li>
     *   <li><b>오탐 가드</b>: 후보 쿼리가 봇(is_dummy)과 최근 입장(연결 수립 전) 을 제외하고,
     *       {@code initialDelay} 가 부팅 직후 재연결 창(레지스트리 재수화)을 건너뛴다.</li>
     * </ul>
     *
     * <p>{@code @Transactional}: markPending 은 self-invocation 이라 프록시를 타지 않으므로
     * 스윕 자체를 트랜잭션 경계로 삼아 내부 @Modifying UPDATE 가 tx 안에서 실행되게 한다.
     */
    @Scheduled(fixedDelay = LIVENESS_SWEEP_INTERVAL_MS, initialDelay = LIVENESS_SWEEP_BOOT_DELAY_MS)
    @Transactional
    public void sweepOrphanActiveCrews() {
        LocalDateTime enteredBefore = LocalDateTime.now(clock).minusSeconds(LIVENESS_RECENT_ENTER_GRACE_SECONDS);
        List<CrewData> candidates = livenessSweepQueryPort.findLivenessSweepCandidates(enteredBefore);
        if (candidates.isEmpty()) return;

        int swept = 0;
        for (CrewData crew : candidates) {
            // STOMP Principal name = String.valueOf(uid) — ConnectionEventListener 의 register 와 동일 표현.
            if (simpUserRegistry.getUser(crew.getUserId().toString()) != null) {
                continue; // 살아있는 세션 존재 — 정상 접속자
            }
            markPending(crew.getPartyroomId(), crew.getUserId());
            swept++;
        }
        if (swept > 0) {
            log.info("[presence] liveness sweep - {} orphan-active crew(s) → PENDING_EXIT (candidates={})",
                    swept, candidates.size());
        }
    }

    /** 스윕 주기. 유령은 수일 단위로 잔존하던 클래스라 저빈도(5분)로 충분 — DB 부하 최소화. */
    private static final long LIVENESS_SWEEP_INTERVAL_MS = 300_000;
    /** 부팅 직후 재연결 창 유예 — in-process 레지스트리가 비어 전원 유령으로 보이는 오탐 방지. */
    private static final long LIVENESS_SWEEP_BOOT_DELAY_MS = 300_000;
    /** 입장 직후 WS 연결 수립 전 오탐 방지용 최근-입장 제외 창. */
    private static final long LIVENESS_RECENT_ENTER_GRACE_SECONDS = 600;

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
