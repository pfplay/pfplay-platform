# playback/dj reconcile cron Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 60초 cron으로 두 잔존-오염 클래스(orphan DJ, stuck playback)를 자가치유하되 건강한 룸은 손상시키지 않는다.

**Architecture:** 신규 `PartyroomPlaybackReconcileService`가 `@Scheduled(60s)`로 2 sweep 실행. presence reconcile 미러 — 룸별 분산락 + 룸별 `@Transactional` heal + 락+txn 내 멱등 재검. 치유는 기존 `skipPlayback`/`removeDjs` 재사용.

**Tech Stack:** Spring Boot, JPA(JPQL), JUnit5/Mockito. 스펙: `docs/superpowers/specs/2026-06-19-playback-reconcile-cron-design.md`.

---

## 파일 구조
- Create `app/.../party/application/service/PartyroomPlaybackReconcileService.java` — cron + sweepOrphanDjs + sweepStuckPlayback + 락(no @Transactional).
- Create `app/.../party/application/service/PlaybackReconcileHealer.java` — `@Transactional healOrphan/healStuck`(**별 빈** — self-invocation AOP 미적용 회피, re-check+mutation 1 txn 보장).
- Modify `app/.../party/adapter/out/persistence/DjRepository.java` — `findPartyroomIdsWithInactiveCrewDj()`.
- Modify `app/.../party/adapter/out/persistence/PartyroomPlaybackRepository.java` — `findStuckActivatedPartyroomIds(threshold)`.
- Modify `app/.../party/domain/port/PartyroomAggregatePort.java` + `adapter/out/persistence/PartyroomAggregateAdapter.java` — 두 쿼리 노출.
- Test `app/.../party/application/service/PartyroomPlaybackReconcileServiceTest.java` (unit, mock).
- Test `app/.../party/adapter/out/persistence/PlaybackReconcileQueryIT.java` (쿼리 IT, 실 DB) — 선택, 기존 IT 패턴 따름.

---

## Chunk 1: 탐지 쿼리

### Task 1: 두 신규 쿼리 + 포트 노출

**Files:** `DjRepository.java`, `PartyroomPlaybackRepository.java`, `PartyroomAggregatePort.java`, `PartyroomAggregateAdapter.java`; Test `PlaybackReconcileQueryIT.java`

- [ ] **Step 1: 쿼리 IT 작성** (기존 `@DataJpaTest`/IT 패턴 확인 후) — orphan dj 룸 선별(active crew dj 제외), stuck 룸 선별(BUFFER 내·is_activated=false·TERMINATED 제외, activated+null-playback 포함). 실패 확인.

- [ ] **Step 2: 구현** — `DjRepository`:
```java
@Query("SELECT DISTINCT d.partyroomId FROM DjData d, CrewData c, PartyroomData pr " +
       "WHERE c.id = d.crewId.id AND c.isActive = false " +
       "AND pr.partyroomId = d.partyroomId AND pr.status = com.pfplaybackend.api.party.domain.enums.PartyroomStatus.ACTIVE")
List<PartyroomId> findPartyroomIdsWithInactiveCrewDj();
```
`PartyroomPlaybackRepository`:
```java
@Query("SELECT pp.partyroomId FROM PartyroomPlaybackData pp, PartyroomData pr " +
       "LEFT JOIN PlaybackData p ON p.id = pp.currentPlaybackId.id " +
       "WHERE pr.partyroomId = pp.partyroomId " +
       "AND pr.status = com.pfplaybackend.api.party.domain.enums.PartyroomStatus.ACTIVE " +
       "AND pp.isActivated = true AND (p IS NULL OR p.endTime < :threshold)")
List<PartyroomId> findStuckActivatedPartyroomIds(@Param("threshold") long threshold);
```
⚠️ 실제 임베디드 경로(`d.crewId.id`, `pp.currentPlaybackId.id`)·엔티티 필드명은 구현 시 `DjData`/`PartyroomPlaybackData`/`PlaybackData`/`CrewData` 실 매핑으로 검증(JPQL 바인딩). 연관 없으면 cross-join 형태 유지.
- 포트(`PartyroomAggregatePort`)에 두 메서드 시그니처 + `PartyroomAggregateAdapter`에서 repo 위임.

- [ ] **Step 3: IT 통과 확인.** Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*PlaybackReconcileQueryIT"`
- [ ] **Step 4: 커밋** — `git commit -m "feat(party): orphan-dj/stuck-playback 탐지 쿼리 (#308)"`

---

## Chunk 2: reconcile 서비스

### Task 2: PartyroomPlaybackReconcileService (cron + 2 sweep)

**Files:** Create `PartyroomPlaybackReconcileService.java`; Test `PartyroomPlaybackReconcileServiceTest.java`

- [ ] **Step 1: 실패 단위 테스트** (mock aggregatePort/playbackControlPort/distributedLockExecutor/clock; `performTaskWithLock`는 supplier 실행하도록 stub)
```java
// 핵심 케이스:
// A1) orphan=현재DJ → removeDjs + skipPlayback
// A2) orphan=비현재DJ → removeDjs, skipPlayback 미호출
// A3) orphan 없음(재검 no-op) → removeDjs/skip 미호출
// B1) 여전히 stuck(activated & endTime<threshold 또는 null playback) → skipPlayback
// B2) 재검 결과 not stuck(activated=false 또는 새 트랙) → skipPlayback 미호출(멱등)
```
예시(A1):
```java
when(distributedLockExecutor.performTaskWithLock(anyString(), any()))
    .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
when(aggregatePort.findPartyroomIdsWithInactiveCrewDj()).thenReturn(List.of(roomId));
// findDjsOrdered → [dj(crew 67)], findCrewsByIds → crew67 inactive
// playbackState.isActivated()=true, currentDjCrewId=67
service.reconcile();
verify(aggregatePort).removeDjs(argThat(djs -> /* contains dj crew67 */));
verify(playbackControlPort).skipPlayback(roomId);
```

- [ ] **Step 2: 실패 확인.** Run: `… --tests "*PartyroomPlaybackReconcileServiceTest"` · Expected: FAIL(클래스 없음).

- [ ] **Step 3: 구현**
```java
// ── 빈 1: cron + sweep + 락 (no @Transactional) ──
@Slf4j @Service @RequiredArgsConstructor
public class PartyroomPlaybackReconcileService {
    private static final long STUCK_BUFFER_MS = 90_000L;
    private final PartyroomAggregatePort aggregatePort;
    private final PlaybackReconcileHealer healer; // 별 빈 → @Transactional AOP 적용
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
                lock.performTaskWithLock("playback-reconcile:" + room.getId(), () -> { healer.healOrphan(room); return null; });
            } catch (Exception e) { log.warn("[reconcile] orphan heal failed room={} : {}", room.getId(), e.getMessage()); }
        }
    }

    void sweepStuckPlayback() {
        long threshold = clock.millis() - STUCK_BUFFER_MS;
        for (PartyroomId room : aggregatePort.findStuckActivatedPartyroomIds(threshold)) {
            try {
                lock.performTaskWithLock("playback-reconcile:" + room.getId(), () -> { healer.healStuck(room, threshold); return null; });
            } catch (Exception e) { log.warn("[reconcile] stuck heal failed room={} : {}", room.getId(), e.getMessage()); }
        }
    }
}

// ── 빈 2: @Transactional heal (별 빈) ──
@Slf4j @Service @RequiredArgsConstructor
class PlaybackReconcileHealer {
    private final PartyroomAggregatePort aggregatePort;
    private final PlaybackControlPort playbackControlPort;
    private final PlaybackQueryService playbackQueryService; // getPlaybackById (endTime 재검)

    @Transactional
    public void healOrphan(PartyroomId room) {
        List<DjData> djs = aggregatePort.findDjsOrdered(room);
        if (djs.isEmpty()) return;
        Map<Long, Boolean> active = aggregatePort.findCrewsByIds(djs.stream().map(d -> d.getCrewId().getId()).toList())
                .stream().collect(Collectors.toMap(CrewData::getId, CrewData::isActive));
        List<DjData> orphans = djs.stream().filter(d -> !Boolean.TRUE.equals(active.get(d.getCrewId().getId()))).toList();
        if (orphans.isEmpty()) return; // 멱등 재검
        PartyroomPlaybackData pp = aggregatePort.findPlaybackState(room);
        boolean orphanWasCurrent = pp.isActivated() && pp.getCurrentDjCrewId() != null
                && orphans.stream().anyMatch(d -> d.getCrewId().equals(pp.getCurrentDjCrewId()));
        log.info("[reconcile] orphan sweep room={}, removing {} dj(s), wasCurrent={}", room.getId(), orphans.size(), orphanWasCurrent);
        aggregatePort.removeDjs(orphans);
        if (orphanWasCurrent) playbackControlPort.skipPlayback(room);
    }

    @Transactional
    public void healStuck(PartyroomId room, long threshold) {
        PartyroomPlaybackData pp = aggregatePort.findPlaybackState(room);
        if (!pp.isActivated()) return; // 이미 치유됨
        Long endTime = pp.getCurrentPlaybackId() == null ? null
                : playbackQueryService.getPlaybackById(pp.getCurrentPlaybackId()).getEndTime();
        if (endTime != null && endTime >= threshold) return; // 새 트랙 시작됨 → 멱등 no-op
        log.info("[reconcile] stuck sweep room={}, endTime={}, threshold={}", room.getId(), endTime, threshold);
        playbackControlPort.skipPlayback(room);
    }
}
```
⚠️ 구현 시 검증: `PartyroomPlaybackData.getCurrentDjCrewId()`/`getCurrentPlaybackId()` 접근자명, `aggregatePort.findCrewsByIds`/`findPlaybackState` 시그니처, `PlaybackQueryService.getPlaybackById` 존재(없으면 port/메서드 추가). **heal은 별 빈(`PlaybackReconcileHealer`)이라 `@Transactional` AOP 정상 적용** → re-check + removeDjs/skip가 한 txn. CrewId equals(`d.getCrewId().equals(pp.getCurrentDjCrewId())`)는 `CrewId` VO의 equals 동작 확인.

- [ ] **Step 4: 통과 확인.** Run: `… --tests "*PartyroomPlaybackReconcileServiceTest"` · Expected: PASS.
- [ ] **Step 5: 회귀.** Run: `… :app:test` · Expected: 풀 GREEN.
- [ ] **Step 6: 커밋** — `git commit -m "feat(party): playback/dj reconcile cron — orphan/stuck 자가치유 (#308)"`

---

## 검증
- `:app:test` 풀 GREEN.
- (머지 전, 게이트) 로컬 풀스택: orphan dj/stuck playback 행을 SQL로 심고 cron 1tick 후 정리되는지 + BUFFER 내 정상 룸 무손상 스모크.

## 범위 밖
refresh(#306-①), WS 만료검증(#306-②). prod 잔존은 배포 후 첫 sweep이 자동 정리(로그 확인).
