package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * party 도메인 잔존-오염 자가치유 cron (Cluster G, #308).
 * presence {@code reconcileStalePending} 미러 — 룸별 분산락 + 룸별 @Transactional heal + 락+txn 내 멱등 재검.
 *
 * <pre>
 *   A. orphan DJ  : dj.crew 가 inactive (회전 유령, #304)  → 비활성 dj 제거 + (현재 DJ면) skipPlayback
 *   B. stuck      : is_activated=1 ∧ end_time<now-BUFFER (정지 유령, #299/#195) → skipPlayback
 * </pre>
 *
 * <p>안전: BUFFER(90s) + 락+txn 내 재검이 정상-path 레이스를 방어한다(정상 playback 커맨드는 분산락 미사용
 * → 이 락은 멀티 인스턴스 cron 중복만 차단). 락 획득 실패는 WARN 후 다음 tick 재시도(잡 자체 멱등).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyroomPlaybackReconcileService {

    static final long STUCK_BUFFER_MS = 90_000L;

    private final PartyroomAggregatePort aggregatePort;
    private final PlaybackReconcileHealer healer;
    private final DistributedLockExecutor lock;
    private final Clock clock;

    @Scheduled(fixedDelay = 60_000)
    public void reconcile() {
        sweepOrphanDjs();
        sweepStuckPlayback();
    }

    void sweepOrphanDjs() {
        for (PartyroomId room : aggregatePort.findPartyroomIdsWithInactiveCrewDj()) {
            try {
                lock.performTaskWithLock("playback-reconcile:" + room.getId(), () -> {
                    healer.healOrphan(room);
                    return null;
                });
            } catch (Exception e) {
                log.warn("[reconcile] orphan heal failed - room={}, {}", room.getId(), e.getMessage());
            }
        }
    }

    void sweepStuckPlayback() {
        long threshold = clock.millis() - STUCK_BUFFER_MS;
        for (PartyroomId room : aggregatePort.findStuckActivatedPartyroomIds(threshold)) {
            try {
                lock.performTaskWithLock("playback-reconcile:" + room.getId(), () -> {
                    healer.healStuck(room, threshold);
                    return null;
                });
            } catch (Exception e) {
                log.warn("[reconcile] stuck heal failed - room={}, {}", room.getId(), e.getMessage());
            }
        }
    }
}

/**
 * 별 빈 — self-invocation 회피로 {@code @Transactional} AOP 가 적용되어 재검 + mutation 이 한 트랜잭션.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PlaybackReconcileHealer {

    private final PartyroomAggregatePort aggregatePort;
    private final PlaybackControlPort playbackControlPort;
    private final PlaybackQueryService playbackQueryService;

    @Transactional
    public void healOrphan(PartyroomId room) {
        List<DjData> djs = aggregatePort.findDjsOrdered(room);
        if (djs.isEmpty()) {
            return;
        }
        Map<Long, Boolean> activeByCrewId = aggregatePort
                .findCrewsByIds(djs.stream().map(d -> d.getCrewId().getId()).toList())
                .stream()
                .collect(Collectors.toMap(CrewData::getId, CrewData::isActive));
        List<DjData> orphans = djs.stream()
                .filter(d -> !Boolean.TRUE.equals(activeByCrewId.get(d.getCrewId().getId())))
                .toList();
        if (orphans.isEmpty()) {
            return; // 멱등 재검 — 탐지~락 사이 정상 경로가 이미 정리
        }
        PartyroomPlaybackData pp = aggregatePort.findPlaybackState(room);
        boolean orphanWasCurrent = pp.isActivated()
                && orphans.stream().anyMatch(d -> pp.isCurrentDj(d.getCrewId()));
        log.info("[reconcile] orphan sweep - room={}, removing {} dj(s), wasCurrent={}",
                room.getId(), orphans.size(), orphanWasCurrent);
        aggregatePort.removeDjs(orphans);
        if (orphanWasCurrent) {
            playbackControlPort.skipPlayback(room);
        }
    }

    @Transactional
    public void healStuck(PartyroomId room, long threshold) {
        PartyroomPlaybackData pp = aggregatePort.findPlaybackState(room);
        if (!pp.isActivated()) {
            return; // 이미 치유됨
        }
        Long endTime = pp.getCurrentPlaybackId() == null
                ? null
                : playbackQueryService.getPlaybackById(pp.getCurrentPlaybackId()).getEndTime();
        if (endTime != null && endTime >= threshold) {
            return; // 새 트랙이 시작됨 → 멱등 no-op
        }
        log.info("[reconcile] stuck sweep - room={}, endTime={}, threshold={}",
                room.getId(), endTime, threshold);
        playbackControlPort.skipPlayback(room);
    }
}
