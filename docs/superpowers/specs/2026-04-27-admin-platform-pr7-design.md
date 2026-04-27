# PR 7: V6 Partyroom 상태 진화 + Atomic Counter 패턴 도입 — Design

> Brainstormed design spec. Implementation plan is generated separately from this document via `superpowers:writing-plans`.

**Date:** 2026-04-27
**Branch:** `feature/admin-auth-iam-schema` (계속, PR 6 HEAD `5bcfc9c9` 위에 빌드)
**Roadmap row:** §9.1 PR 7 — *V6 Partyroom 상태 enum + 카운터 + display_flag + 전체 엔티티 리팩토링* (size: XL)
**Milestone:** M3 (PR 7-9, 파티룸 운영 도구)

---

## 1. Goal

V6 마이그레이션으로 `partyroom` 테이블의 상태 모델을 `is_terminated BOOLEAN`에서 3-상태 ENUM(`ACTIVE`/`SUSPENDED`/`TERMINATED`)으로 진화시키고, denormalized 카운터(`crew_count`)와 활동 시각(`last_activity_at`), 표시 플래그(`display_flag`)를 도입한다. 카운터 무결성 보장을 위해 native atomic UPDATE 패턴을 같이 도입하고, 동일 패턴이 필요한 `PlaybackAggregation`(좋아요/싫어요/그랩)에도 적용한다. 동시 enter/exit으로 인한 application-level race(재입장 toggle)는 조건부 UPDATE로 닫는다.

본 PR이 끝나면:
- Partyroom이 `ACTIVE/SUSPENDED/TERMINATED` 3-상태로 표현 가능 (단, SUSPENDED 진입 경로는 PR 8에서 추가).
- 카운터/활동시각이 multi-instance 환경에서 lost update 없이 정확히 유지.
- `display_flag` 컬럼이 존재하고 GET 가능 (쓰기 경로는 PR 8에서 Administration BC가 도입).
- `playback_aggregation`의 like/dislike/grab 카운터도 동일하게 atomic.

## 2. Scope

### 2.1 In Scope (PR 7)

1. **V6 Flyway 마이그레이션** — `partyroom`에 `status`/`crew_count`/`last_activity_at`/`display_flag` 추가, `is_terminated` 제거, 데이터 이관, 인덱스 2개.
2. **`PartyroomData` 엔티티 리팩토링** — 신규 enum 2개(`PartyroomStatus`, `DisplayFlag`), 필드 추가, 상태 전이 메서드(`suspend`/`restore`/`terminate`), `isActive`/`isSuspended` 신설, `isTerminated` 시그니처 유지.
3. **Atomic counter 패턴** — `PartyroomRepository`에 `incrementCrewCount`/`decrementCrewCount`/`touchLastActivity`; `PlaybackAggregationRepository`에 `applyAggregationDelta`. 기존 read-modify-write 경로 모두 이걸로 교체.
4. **Event listener 신설** — `PartyroomCounterListener` (`@TransactionalEventListener AFTER_COMMIT`), 다음 이벤트 수신:
   - `CrewAccessedEvent(ENTER/EXIT)` → `crew_count` ± + `lastActivityAt` 갱신
   - `PlaybackStartedEvent` → `lastActivityAt` 갱신
   - `PlaybackDeactivatedEvent` → `lastActivityAt` 갱신
5. **Call site migration** — 5개 `isTerminated()` 호출부 의미론적 정정(`!isTerminated()` → `isActive()`), 2개 쿼리 `is_terminated` 참조 제거.
6. **`PartyroomEntrySpecification` 보강** — SUSPENDED 룸 입장 거부.
7. **재입장 race(B-3) 차단** — `crew` 테이블의 `is_active` toggle을 조건부 UPDATE로 전환 (`activateCrew`/`deactivateCrew`), `PartyroomAccessCommandService`에 적용.
8. **테스트** — 단위(상태 전이/specification), 통합(Flyway, atomic UPDATE 동작), 동시성(100스레드 카운터/toggle), multi-instance 시뮬레이션.

### 2.2 Out of Scope (defer)

| 항목 | 이전 PR | 사유 |
|---|---|---|
| `displayFlag` setter / 상태전이 메서드 | PR 8 | Administration B-3 API(`PATCH /admin/partyrooms/{id}/display-flag`)와 함께. 호출자 0건 상태로 setter 추가는 dead code. |
| ArchUnit "Party는 displayFlag 변경 금지" 규칙 | PR 8 | 가드할 메서드(setter)가 PR 8에 추가되므로 같이. |
| `PartyroomSuspendedEvent` / `PartyroomRestoredEvent` publish | PR 8 | listener consumer(어드민 액션 감사)가 PR 8에서 도입. |
| drift 검증 배치 (§4.3.3) | 운영상 trigger 발생 시 | atomic UPDATE 1차 방어로 day-1 drift 위험 0. YAGNI 정직. |
| 분산락 (`DistributedLockExecutor`) | 본 PR scope 아님 | Race A/B/C 분석 결과 모두 application-level / DB-level 매커니즘으로 충분 (§7). |
| `findActiveHostRoom` 메서드 rename | 후속 cosmetic PR | 의미는 본 PR에서 `status <> TERMINATED`로 정확화, 이름 정리는 별도. |

## 3. V6 Migration DDL

**파일:** `app/src/main/resources/db/migration/V6__evolve_partyroom_state.sql`

```sql
-- =====================================================
-- V6: Party context — Partyroom 상태 모델 진화
--
-- - is_terminated BOOLEAN → status ENUM (ACTIVE/SUSPENDED/TERMINATED)
-- - crew_count, last_activity_at denormalized 카운터/시각
-- - display_flag (Operations 관점, 물리적으론 Party 테이블)
-- =====================================================

-- 1. 새 컬럼 추가
ALTER TABLE partyroom
    ADD COLUMN status ENUM('ACTIVE','SUSPENDED','TERMINATED') NOT NULL DEFAULT 'ACTIVE' AFTER is_terminated,
    ADD COLUMN crew_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_activity_at DATETIME NULL,
    ADD COLUMN display_flag ENUM('NORMAL','FEATURED','HIDDEN') NOT NULL DEFAULT 'NORMAL';

-- 2. is_terminated → status 데이터 이관 (ACTIVE는 default라 별도 UPDATE 불필요)
UPDATE partyroom SET status = 'TERMINATED' WHERE is_terminated = 1;

-- 3. crew_count 초기 계산 (활성 crew만)
UPDATE partyroom p
SET crew_count = (
    SELECT COUNT(*) FROM crew c
    WHERE c.partyroom_id = p.partyroom_id AND c.is_active = 1
);

-- 4. last_activity_at 초기값 (방금 추가한 컬럼이라 모두 NULL)
UPDATE partyroom SET last_activity_at = COALESCE(updated_at, created_at);

-- 5. 기존 컬럼 제거
ALTER TABLE partyroom DROP COLUMN is_terminated;

-- 6. 인덱스 (목록 쿼리 최적화)
CREATE INDEX idx_partyroom_status_activity ON partyroom (status, last_activity_at DESC);
CREATE INDEX idx_partyroom_display_flag ON partyroom (display_flag);
```

스펙 §4.3.1 대비 변경:
- 스펙의 `UPDATE partyroom SET status = 'ACTIVE' WHERE is_terminated = 0;` 제거 (default 'ACTIVE'로 redundant).
- 스펙의 `UPDATE ... WHERE last_activity_at IS NULL` 가드 제거 (방금 추가한 컬럼이라 모두 NULL, 가드 redundant).

마이그레이션 검증: 수동 리뷰 + §9.2의 V6 통합 테스트(빈 DB clean apply + V5 직후 데이터 이관 정합성). Flyway 자동 점검 도구는 본 프로젝트에 없음(`fastapi:migrate-check` 스킬은 Alembic 전용).

`crew` 테이블의 `uk_crew_partyroom_user UNIQUE (partyroom_id, user_id)`는 V1:252에 이미 존재. 추가 마이그레이션 불필요.

## 4. Entity Refactor

### 4.1 신규 enum

`app/src/main/java/com/pfplaybackend/api/party/domain/enums/PartyroomStatus.java`
```java
public enum PartyroomStatus { ACTIVE, SUSPENDED, TERMINATED }
```

`app/src/main/java/com/pfplaybackend/api/party/domain/enums/DisplayFlag.java`
```java
public enum DisplayFlag { NORMAL, FEATURED, HIDDEN }
```

### 4.2 `PartyroomData` 변경

**파일:** `app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/PartyroomData.java`

**필드:**
- 제거: `private boolean isTerminated;`
- 추가:
  - `@Enumerated(EnumType.STRING) @Column(name="status", nullable=false, length=16) private PartyroomStatus status;`
  - `@Column(name="crew_count", nullable=false) private int crewCount;`
  - `@Column(name="last_activity_at") private LocalDateTime lastActivityAt;`
  - `@Enumerated(EnumType.STRING) @Column(name="display_flag", nullable=false, length=16) private DisplayFlag displayFlag;`

**메서드:**

| 메서드 | 동작 | 비고 |
|---|---|---|
| `isActive()` | `status == ACTIVE` | 신규 |
| `isSuspended()` | `status == SUSPENDED` | 신규 |
| `isTerminated()` | `status == TERMINATED` | 시그니처 유지 — 모든 기존 호출부 무수정 작동 |
| `suspend()` | ACTIVE → SUSPENDED 전이; 외 상태면 `IllegalPartyroomStateException` | 신규. 호출자는 PR 7에 없음(PR 8에서 진입), 단위 테스트는 PR 7에 포함 |
| `restore()` | SUSPENDED → ACTIVE 전이; 외 상태면 예외 | 동일 |
| `terminate()` | ACTIVE/SUSPENDED → TERMINATED; TERMINATED면 예외 | 시그니처 유지하되 가드 추가 — 이중 terminate 회귀 위험 (§12.1 참조) |
| `validateNotTerminated()` | 기존 동작 유지 | 기존 호출부 무수정 |

상태 전이 매트릭스:

| from \ to | ACTIVE | SUSPENDED | TERMINATED |
|---|---|---|---|
| ACTIVE | — | suspend() ✓ | terminate() ✓ |
| SUSPENDED | restore() ✓ | — | terminate() ✓ |
| TERMINATED | ✗ | ✗ | — (terminal) |

**Visibility 규율:**
- 현재 `PartyroomData`는 클래스 레벨 `@Getter`만 적용되어 있고 `@Setter` 없음 (`PartyroomData.java:21`). 본 PR도 `@Setter` 추가하지 않음.
- `displayFlag`: getter only. setter 미생성 — PR 8에서 Administration BC 진입 시 추가.
- `crewCount`/`lastActivityAt`: 외부 setter 없음. JPA dirty checking 통한 변경 차단 — 모든 갱신은 §5 atomic UPDATE 메서드만.
- `status`: setter 없음. 전이 메서드(`suspend/restore/terminate`)만 통해 변경.
- 기존 entity의 mutator 메서드(`updateBaseInfo` 등)는 그대로 유지.

**`validateNotTerminated()` 시맨틱:**
- 기존 동작 그대로 유지 — `status == TERMINATED`만 체크. SUSPENDED 룸은 통과시킴.
- SUSPENDED 입장 차단은 `PartyroomEntrySpecification`(§8.2)에서 별도 처리 — 명세 layer가 입장 정책의 단일 진입점이고 `validateNotTerminated()`는 단순 termination 가드 역할로 분리.

## 5. Atomic Counter Pattern

### 5.1 원칙

카운터 갱신은 **JPA dirty checking 금지, native `@Modifying @Query`만 허용.** listener/서비스는 entity를 fetch하지 않고 리포지토리 메서드를 직접 호출. 이로써 multi-instance 환경에서도 DB row lock이 직렬화 보장.

### 5.2 `PartyroomRepository` 추가 메서드

**파일:** `app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomRepository.java`

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE PartyroomData p " +
       "SET p.crewCount = p.crewCount + 1, p.lastActivityAt = :now " +
       "WHERE p.id = :id AND p.status <> com.pfplaybackend.api.party.domain.enums.PartyroomStatus.TERMINATED")
int incrementCrewCount(@Param("id") Long id, @Param("now") LocalDateTime now);

@Modifying(clearAutomatically = true)
@Query("UPDATE PartyroomData p " +
       "SET p.crewCount = CASE WHEN p.crewCount > 0 THEN p.crewCount - 1 ELSE 0 END, " +
       "    p.lastActivityAt = :now " +
       "WHERE p.id = :id AND p.status <> com.pfplaybackend.api.party.domain.enums.PartyroomStatus.TERMINATED")
int decrementCrewCount(@Param("id") Long id, @Param("now") LocalDateTime now);

@Modifying(clearAutomatically = true)
@Query("UPDATE PartyroomData p SET p.lastActivityAt = :now " +
       "WHERE p.id = :id AND p.status = com.pfplaybackend.api.party.domain.enums.PartyroomStatus.ACTIVE")
int touchLastActivity(@Param("id") Long id, @Param("now") LocalDateTime now);
```

설계 포인트:
- `crew_count` 음수 방지 (`CASE WHEN`).
- TERMINATED 룸은 카운터 변경 거부.
- `touchLastActivity`는 ACTIVE만 (SUSPENDED/TERMINATED 룸의 lastActivity 갱신은 의미 없음).
- 반환 `int` — 0이면 listener가 WARN 로그.
- `clearAutomatically = true` — 1차 캐시 stale 방지.

### 5.3 기존 쿼리 사이트 변경

`PartyroomRepository.java:14` (JPQL):
```java
// before:
@Query("SELECT p FROM PartyroomData p WHERE p.hostId = :userId AND p.isTerminated = false")
Optional<PartyroomData> findActiveHostRoom(@Param("userId") UserId userId);

// after:
@Query("SELECT p FROM PartyroomData p WHERE p.hostId = :userId " +
       "AND p.status <> com.pfplaybackend.api.party.domain.enums.PartyroomStatus.TERMINATED")
Optional<PartyroomData> findActiveHostRoom(@Param("userId") UserId userId);
```

의미: SUSPENDED 호스트 룸 보유 중에도 신규 룸 생성 차단(우회 행위 방지). 메서드명은 cosmetic mismatch이지만 본 PR scope 외.

`adapter/out/persistence/impl/PartyroomRepositoryImpl.java:110` (QueryDSL):
```java
// before:
.where(qPartyroomData.isTerminated.eq(false))

// after:
.where(qPartyroomData.status.ne(PartyroomStatus.TERMINATED))
```

### 5.4 `PlaybackAggregationRepository` 추가 메서드

**파일:** `app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/PlaybackAggregationRepository.java`

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE PlaybackAggregationData a " +
       "SET a.likeCount = a.likeCount + :deltaLike, " +
       "    a.dislikeCount = a.dislikeCount + :deltaDislike, " +
       "    a.grabCount = a.grabCount + :deltaGrab " +
       "WHERE a.playbackId = :playbackId")
int applyAggregationDelta(
    @Param("playbackId") PlaybackId playbackId,
    @Param("deltaLike") int deltaLike,
    @Param("deltaDislike") int deltaDislike,
    @Param("deltaGrab") int deltaGrab
);
```

음수 가드는 추가하지 않음 — like/dislike는 `PlaybackReactionDomainService`가 history 기준으로 delta 계산, 카운터 < 0 발생 시 history vs counter drift 신호로 WARN 로그가 더 가치 있음 (§12.5 참조).

### 5.5 호출부 변경

**`PlaybackCommandService.java:150-155`** (`updatePlaybackAggregation` 메서드):
```java
// before:
PlaybackAggregationData aggregation = playbackAggregationRepository.findById(playbackId).orElseThrow(...);
aggregation.updateAggregation(deltaRecord.get(0), deltaRecord.get(1), deltaRecord.get(2));
playbackAggregationRepository.save(aggregation);

// after:
int updated = playbackAggregationRepository.applyAggregationDelta(
    playbackId, deltaRecord.get(0), deltaRecord.get(1), deltaRecord.get(2)
);
if (updated == 0) {
    log.warn("playback_aggregation row missing for playbackId={}", playbackId);
}
```

`PlaybackAggregationData.updateAggregation(...)`는 외부 호출자 0건 확인 후 **삭제**. 외부 dirty-checking 경로 모두 제거.

## 6. Event Listener (신규)

### 6.1 `PartyroomCounterListener`

**파일:** `app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/PartyroomCounterListener.java`

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class PartyroomCounterListener {

    private final PartyroomRepository partyroomRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(CrewAccessedEvent event) {
        Long partyroomId = event.getPartyroomId().getId();
        LocalDateTime now = LocalDateTime.now();
        int affected = switch (event.getAccessType()) {
            case ENTER -> partyroomRepository.incrementCrewCount(partyroomId, now);
            case EXIT  -> partyroomRepository.decrementCrewCount(partyroomId, now);
        };
        if (affected == 0) {
            log.warn("crew_count update skipped (room missing or TERMINATED): partyroomId={}, accessType={}",
                     partyroomId, event.getAccessType());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(PlaybackStartedEvent event) {
        touch(event.getPartyroomId().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(PlaybackDeactivatedEvent event) {
        touch(event.getPartyroomId().getId());
    }

    private void touch(Long partyroomId) {
        int affected = partyroomRepository.touchLastActivity(partyroomId, LocalDateTime.now());
        if (affected == 0) {
            log.debug("touchLastActivity skipped (room not ACTIVE): partyroomId={}", partyroomId);
        }
    }
}
```

설계 포인트:
- 위치: `adapter/in/listener` (DB 쓰기 side-effect로 들어가는 inbound). `DomainEventRedisRelay`(`adapter/out/event`)와 분리.
- `Propagation.REQUIRES_NEW` — `AFTER_COMMIT` phase 후 새 트랜잭션 필수 (`@Modifying @Query`는 트랜잭션 컨텍스트 필요).
- `fallbackExecution = true` — 기존 컨벤션.
- `DomainEventRedisRelay`도 `CrewAccessedEvent`를 listen 중이지만 두 listener는 독립 작동 (Spring이 각각 dispatch). 본 listener는 DB 카운터, 그쪽은 Redis fanout.
- `PlaybackStartedEvent`/`PlaybackDeactivatedEvent`가 `getPartyroomId()`를 노출하는지 구현 단계에서 확인 — 미노출 시 이벤트 시그니처 보강.

## 7. Race Analysis & Mitigation

### 7.1 Race A — Counter atomic UPDATE under multi-instance load

다수 인스턴스에서 동시에 enter/exit 발생 시 카운터 정확성.

- 각 인스턴스는 자체 JVM 내에서 발생한 `CrewAccessedEvent`만 listen (Spring `ApplicationEventPublisher`는 intra-JVM).
- 각 listener는 자체 트랜잭션으로 atomic UPDATE 실행.
- DB(InnoDB) row lock이 동시 UPDATE를 직렬화.

**결론:** ✅ 분산락 불필요. atomic UPDATE만으로 multi-instance 안전.

### 7.2 Race B — 같은 사용자 동시 enter (load balancer로 분산)

같은 사용자가 빠른 두 번 클릭으로 인스턴스 A/B 양쪽에서 거의 동시에 `enter()` 진입.

**Race B-first** (첫 입장):
- 두 인스턴스 모두 새 crew row INSERT 시도
- `uk_crew_partyroom_user UNIQUE (partyroom_id, user_id)` (V1:252) 가 두 번째 INSERT 거부 → `DataIntegrityViolationException`
- 두 번째 인스턴스는 이벤트 발행 안 함 → counter +1 (정확)
- ✅ 기존 UNIQUE로 차단됨

**Race B-reentry** (재입장 — 이전에 EXIT한 사용자가 다시 ENTER):
- 현재 코드(`PartyroomAccessCommandService.addOrActivateCrew`, line 92-107):
  - 기존 row가 있고 inactive → `crew.activatePresence()` + `aggregatePort.saveCrew()` (JPA dirty-checking toggle)
- 두 인스턴스 모두 기존 row 발견(`is_active=0`) → 둘 다 `activatePresence()` + saveCrew → JPA dirty-checking은 lost update 방지 못함 → 둘 다 commit 성공 → ENTER 이벤트 2회 → counter +2
- ❌ 추가 차단 필요

**Bonus 이슈: same-room re-entry spurious ENTER 발행** (PartyroomAccessCommandService.java:75-83)
- websocket 재연결 등으로 이미 같은 룸에 active인 사용자가 `tryEnter()` 다시 호출 시:
  - 현재 코드: countryCode 업데이트 후 **무조건 ENTER 이벤트 publish** (line 81)
  - 카운터 listener 도입 후엔 active한 사용자의 재요청이 counter를 또 +1 시켜 inflate
- 본 PR에서 같이 차단해야 카운터 정확성 보장

**대응 (B-3 조건부 toggle UPDATE + spurious 발행 제거):**

EXIT 측 시맨틱 확인: `PartyroomAccessCommandService.exit()` (line 120-140) / `expel()` (142-151)도 `crew.deactivatePresence()` + saveCrew (toggle, NOT delete). is_active row는 영구 보존되어 재입장 시 toggle 경로로 재사용. → activate/deactivate 모두 same-shape 조건부 UPDATE로 대칭 처리 가능.

`CrewRepository`에 추가:
```java
@Modifying
@Query("UPDATE CrewData c SET c.isActive = true " +
       "WHERE c.partyroomId = :partyroomId AND c.userId = :userId AND c.isActive = false")
int activateCrew(@Param("partyroomId") PartyroomId pid, @Param("userId") UserId uid);

@Modifying
@Query("UPDATE CrewData c SET c.isActive = false " +
       "WHERE c.partyroomId = :partyroomId AND c.userId = :userId AND c.isActive = true")
int deactivateCrew(@Param("partyroomId") PartyroomId pid, @Param("userId") UserId uid);
```

`PartyroomAccessCommandService.tryEnter()` 리팩토링 (line 86 `addOrActivateCrew(...)` 호출 지점):
1. 기존 crew 조회
2. row 존재 시:
   - `activateCrew(pid, uid)` 호출
   - 반환 1 → 실제 toggle 발생 → `CrewAccessedEvent(ENTER)` publish + countryCode 업데이트
   - 반환 0 → 이미 active → countryCode만 업데이트, **ENTER publish 안 함** (spurious 발행 차단)
3. row 없음 → 신규 INSERT 시도 (UNIQUE가 동시 INSERT race 방어). `DataIntegrityViolationException` 캐치된 패배자 path는 **ENTER publish 안 함** (`activateCrew == 0`과 동일 원칙: 실제 상태 전이가 발생하지 않은 호출자는 이벤트 발행 금지).

`tryEnter()` line 75-83의 same-room re-entry 분기는 사용자가 이미 같은 룸에 active임이 확정된 path. `activateCrew` 호출 없이 ENTER publish 제거 + countryCode만 update — 카운터 inflate 차단.

`exit()`/`expel()` 대칭: `deactivateCrew` → 반환 1이면 EXIT 이벤트, 0이면 idempotent (이미 inactive 상태에서 EXIT 이중 호출 무시).

### 7.3 Race C — 같은 이벤트가 두 번 처리

`@TransactionalEventListener`는 publisher의 JVM에서 1회만 fire. cross-JVM 전파 없음.

**결론:** ✅ 분산락 불필요. 본질적으로 안전.

### 7.4 분산락 도입 안 함 — 명시적 결정

기존 `DistributedLockExecutor` 사용처(`CrewProfilePreCheckTopicListener`, `PlaybackDurationWaitTopicListener`)는 Redis KeyExpired/topic 기반 listener라 cross-instance 중복 dispatch 가능성이 본질적으로 존재 → 그래서 lock 필요. PR 7이 추가하는 listener는 그 모델이 아님 → lock 도입은 dead complexity.

## 8. Call Site Migration

### 8.1 `isTerminated()` 의미론적 정정

`isTerminated()` 메서드 시그니처는 유지되므로 **호출만 두면 컴파일/동작 모두 OK**. 그러나 `!isTerminated()`는 SUSPENDED 등장 후 의미가 변함:
- before: `!isTerminated()` = ACTIVE only
- after: `!isTerminated()` = ACTIVE OR SUSPENDED

PR 7 시점엔 SUSPENDED row 0건이라 동작 차이 없지만, PR 8 진입 순간 묵시적 회귀. PR 7에서 의미를 명시화 (`isActive()`로 정정).

| # | 위치 | before | after |
|---|---|---|---|
| 1 | `admin/adapter/in/web/payload/response/CreateAdminPartyroomResponse.java:37` | `.isActive(!partyroom.isTerminated())` | `.isActive(partyroom.isActive())` |
| 2 | `admin/application/dto/result/AdminPartyroomResult.java:27` | `!partyroom.isTerminated()` | `partyroom.isActive()` |
| 3 | `admin/application/service/AdminDemoService.java:400` | `.filter(p -> !p.isTerminated() && p.getStageType() == StageType.GENERAL)` | `.filter(p -> p.isActive() && p.getStageType() == StageType.GENERAL)` |
| 4 | `admin/application/service/AdminDemoService.java:411` | `.filter(p -> !p.isTerminated())` | `.filter(p -> p.isActive())` |
| 5 | `party/application/service/PartyroomAccessCommandService.java:58` | log 메시지 내 `partyroom.isTerminated()` | 변경 없음 (단순 로깅) |

### 8.2 `PartyroomEntrySpecification` 보강

기존 명세에 SUSPENDED 거부 가드 추가:
```java
if (partyroom.isSuspended()) {
    throw new IllegalPartyroomStateException("partyroom is suspended");
}
```

PR 7 시점엔 SUSPENDED 진입 경로 없지만 픽스처로 SUSPENDED 룸 직접 생성해 거부 동작 단위 테스트 가능 → PR 7에 포함.

## 9. Testing Strategy

### 9.1 단위 테스트

| 대상 | 케이스 |
|---|---|
| `PartyroomData.suspend/restore/terminate` | 매트릭스(§4.2) 모든 셀 — 허용 전이 성공, 비허용 전이 예외 |
| `PartyroomData.isActive/isSuspended/isTerminated` | 각 상태별 boolean 매트릭스 |
| `PartyroomEntrySpecification` | ACTIVE 입장 허용 / SUSPENDED 거부 / TERMINATED 거부 |
| `PartyroomStatus`, `DisplayFlag` enum | DB ENUM literal 매칭 |

### 9.2 통합 테스트 (Testcontainers + 실제 MySQL)

| 대상 | 케이스 |
|---|---|
| `PartyroomRepository.incrementCrewCount` | (a) 정상 +1, (b) TERMINATED → 0 affected, (c) 존재 X → 0 affected |
| `decrementCrewCount` | (a) 정상 -1, (b) `crew_count=0`에서 호출 → 여전히 0, (c) TERMINATED 거부 |
| `touchLastActivity` | (a) ACTIVE 갱신, (b) SUSPENDED 거부, (c) TERMINATED 거부 |
| `applyAggregationDelta` | 정상 +1/-1, 존재 X → 0 affected |
| `findActiveHostRoom` | TERMINATED 제외, SUSPENDED 제외 |
| V6 마이그레이션 | (a) 빈 DB clean apply, (b) V5 직후(`is_terminated` 데이터 있음) → `status` 정확 이관, `crew_count` = 활성 crew COUNT(*) 일치 |
| `activateCrew`/`deactivateCrew` | 조건부 toggle 동작 — 1/0 반환 정확성 |

### 9.3 동시성 테스트

| 시나리오 | 통과 기준 |
|---|---|
| `incrementCrewCount` 100 스레드 | `crew_count == 100` |
| enter 100 + exit 50 mix | `crew_count == 50` |
| `applyAggregationDelta` like 100 스레드 | `likeCount == 100` |
| `activateCrew` 100 스레드 동시 toggle (같은 partyroom, user) | 정확히 1개만 반환값 1 |
| `deactivateCrew` 100 스레드 동시 toggle | 동일 |
| **`PartyroomAccessCommandService.tryEnter` end-to-end (같은 user 100 스레드)** ★ | **`crew_count == 1`** — §7.2 spurious ENTER + Race B-reentry 두 케이스 모두 차단됐다는 결정적 acceptance test |
| `CrewAccessedEvent` ENTER 50개 동시 publish | DB `crew_count == 50` |

**파일:** `PartyroomRepositoryConcurrencyIT.java`, `PlaybackAggregationConcurrencyIT.java`, `CrewAccessRaceIT.java`. `@SpringBootTest` + `@Testcontainers`.

### 9.4 Multi-instance 시뮬레이션 (stretch)

- **(stretch) 공유 DB + 독립 컨텍스트:** 같은 Testcontainers MySQL을 가리키는 두 개의 EntityManagerFactory(또는 두 SpringBootTest 컨텍스트)를 병렬로 부팅, 각자 enter 시도 → atomic UPDATE / 조건부 toggle이 DB 레벨에서 직렬화되는지 검증. 셋업 비용이 높고 flaky 가능성 존재 — §9.3 단일 컨텍스트 동시성 테스트가 핵심 invariant를 이미 커버하므로, 본 시뮬레이션은 stretch goal로 분류. 시도 후 안정성 확보 어려우면 §9.3로 충분하다고 보고 스킵.
- **Negative test:** UNIQUE 제약 위반 시 `DataIntegrityViolationException` 발생 + 두 번째 호출자에게 적절한 에러 응답 반환 검증.
- **Out of scope (정직):** 진짜 멀티 노드(Redis pub/sub 지연, 네트워크 파티션)는 테스트 레벨에서 재현 불가 — 운영 모니터링(`crew_count` vs `COUNT(*) crew WHERE is_active=1` 일별 alert)으로 대체. drift 배치 도입 시 자연스럽게 흡수.

### 9.5 회귀 테스트

| 대상 | 케이스 |
|---|---|
| 기존 `isTerminated()` 호출부 | 시그니처 유지 → 컴파일/동작 OK, 기존 테스트 통과 |
| `PartyroomCommandService` unused-room termination 잡 | 같은 룸 두 번 terminate 시나리오 — 사전 필터링 확인. 미흡 시 `terminate()` 멱등 가드 추가 |
| `AdminDemoService` 데모 시드 | `isActive()` 변경 후 기존과 동일 결과(SUSPENDED 0건) |

## 10. Atomic Commit Groupings

대부분 task별 독립 commit. 아래만 묶음:

| 그룹 | 묶이는 변경 | 사유 |
|---|---|---|
| **G1: V6 + 엔티티 + enum** | V6 SQL + `PartyroomData` 필드/메서드 + `PartyroomStatus`/`DisplayFlag` enum 신설 | 컬럼만/엔티티만 단독으로는 boot 실패. 단일 commit으로 동시 land. **배포 순서도 동일 — V6 SQL 적용과 새 jar 배포는 분리 불가, 같은 deploy unit으로 진행.** |
| **G2: 쿼리 + atomic UPDATE 메서드** | `PartyroomRepository` 인터페이스(JPQL `findActiveHostRoom` 변경 + atomic UPDATE 3개 추가) + `PartyroomRepositoryImpl` QueryDSL 변경 | 같은 repository 컴포넌트의 의미 단위 — 인터페이스/임플 두 파일이지만 한 commit으로 묶어야 status 시맨틱 일관성 보장 |

기타 단계는 task별 commit (호출부 마이그레이션, listener 신설, PlaybackAggregation 전환, 조건부 toggle, 테스트).

## 11. Migration Order (안전한 종속 순서)

```
1.  V6 SQL 작성 + migrate-check
2.  PartyroomStatus / DisplayFlag enum 신설
3.  PartyroomData 엔티티 변경       ┐ G1 commit
                                     ┘
4.  쿼리 사이트 변경 + atomic UPDATE 메서드 추가  → G2 commit
5.  isTerminated 호출부 5개 마이그레이션 → commit
6.  PartyroomEntrySpecification SUSPENDED 거부 → commit
7.  PartyroomCounterListener 신설 + 단위/통합 테스트 → commit
8.  CrewRepository.activateCrew/deactivateCrew + PartyroomAccessCommandService 적용 + 동시성 테스트 → commit
9.  PlaybackAggregationRepository.applyAggregationDelta + PlaybackCommandService 변경 + PlaybackAggregationData.updateAggregation 정리 → commit
10. 동시성 / multi-instance 통합 테스트 추가 → commit
11. 본 spec 문서 업데이트 ("PR 7 reality" 반영) → commit
```

각 단계 그린 빌드 + 그린 테스트 보장 후 다음.

## 12. Risks & Mitigations

| 위험 | 완화 |
|---|---|
| 12.1 이중 terminate 회귀 — 기존 `terminate()`는 idempotent(`isTerminated=true` 무조건 set)였으나 PR 7 이후 두 번째 호출 예외 | 구현 단계에서 `PartyroomCommandService` unused-room 잡의 사전 필터링 확인. 필요 시 `terminate()`에 `if (status == TERMINATED) return;` 멱등 가드 추가 |
| 12.2 `crew_count` drift (atomic UPDATE 외 경로 발견) | grep으로 `setCrewCount` / `crewCount =` 모든 호출자 0건 확인. setter 미생성으로 컴파일 단계에서 차단 |
| 12.3 ~~EXIT가 row DELETE인지 toggle인지 불확실~~ | **해소됨** — `crew.deactivatePresence()` toggle (is_active 컬럼만 변경, row 보존) 확인. §7.2 B-3 활성/비활성 대칭 설계 그대로 진행. |
| 12.4 ~~`PlaybackStartedEvent`/`PlaybackDeactivatedEvent`에 `partyroomId` 미노출~~ | **해소됨** — 두 이벤트 모두 Lombok `@Getter`로 `getPartyroomId()` 노출 확인. listener 구현에 추가 보강 불필요. |
| 12.5 PlaybackAggregationData 재계산 시 history vs counter drift 발생 가능 | 카운터 < 0 시 WARN 로그. 실제 drift 관측 시 후속 PR에서 history 기반 재계산 유틸 추가 |

## 13. Decisions Taken (브레인스토밍 결과 — implementation에 직접 반영)

1. **PlaybackChangedEvent는 만들지 않음.** 기존 `PlaybackStartedEvent` + `PlaybackDeactivatedEvent` 둘 다 listener에 연결해 `lastActivityAt` 갱신.
2. **`displayFlag` setter / 상태전이 메서드 / ArchUnit 규칙**은 PR 7 scope에서 **제외**, PR 8(Administration B-3 API)에서 도입. PR 7은 컬럼 + 필드 + getter만.
3. **카운터 동시성은 native atomic UPDATE.** `@Version` optimistic locking 도입은 blast radius 폭증으로 거부. JPA dirty checking은 lost update 위험으로 거부.
4. **`PlaybackAggregation` 카운터도 동일 패턴으로 PR 7에 묶음.** 별도 PR 분리하지 않음 — 같은 패턴이 한 번에 일관되게 들어감.
5. **상태 전이 매트릭스(§4.2)는 strict.** TERMINATED는 terminal, 비허용 전이는 모두 예외. `PartyroomSuspendedEvent`/`PartyroomRestoredEvent` publish는 PR 8로 defer (consumer 0건이라 dead publish).
6. **drift 검증 배치는 PR 7에서 제외.** 운영상 실제 drift 관측 시 별 PR로 추가. atomic UPDATE 1차 방어로 day-1 위험 0.
7. **브랜치는 `feature/admin-auth-iam-schema` 계속.** PR 0-6 컨벤션 유지.
8. **Race B 처리는 PR 7에 묶음.** 카운터로 가시화된 race를 같이 닫음. `crew` 테이블의 기존 UNIQUE는 첫 입장 race를 막고, 재입장 race는 `activateCrew`/`deactivateCrew` 조건부 UPDATE로 차단.
9. **분산락은 PR 7에서 도입 안 함.** Race A/B/C 모두 application-level / DB-level 매커니즘으로 충분. 기존 `DistributedLockExecutor`는 cross-JVM 중복 dispatch 위험이 있는 listener 전용.
10. **`findActiveHostRoom`은 의미 정정(`status <> TERMINATED`)만 PR 7에서, 메서드 rename은 별도 cosmetic PR.**
11. **`!isTerminated()` 호출부 5개는 `isActive()`로 의미 정정.** PR 8 SUSPENDED 도입 시 묵시적 회귀 방지.

---

**다음 단계:** 본 spec이 사용자/리뷰어 승인되면 `superpowers:writing-plans` 스킬로 구현 plan을 생성한다 (`docs/superpowers/plans/2026-04-27-admin-platform-pr7.md`). plan은 위 §11 작업 순서를 task 단위(체크박스)로 펼친 형태가 된다.
