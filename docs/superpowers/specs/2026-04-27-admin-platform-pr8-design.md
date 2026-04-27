# PR 8: V7 partyroom_admin_action + Admin Partyroom Management API — Design

> Brainstormed design spec. Implementation plan is generated separately via `superpowers:writing-plans`.

**Date:** 2026-04-27
**Branch:** `feature/admin-auth-iam-schema` (계속, PR 7 HEAD `8d3f4b5a` 위에 빌드)
**Roadmap row:** §9.1 PR 8 — *V7 partyroom_admin_action + admin partyroom management API (B-2~B-6, B-8)* (size: L)
**Milestone:** M3 (PR 7-9, 파티룸 운영 도구)

---

## 1. Goal

V7 마이그레이션(`partyroom_admin_action` 테이블)을 도입하고, 어드민의 파티룸 운영 도구 7개 endpoint를 구현한다. PR 7에서 만들어두고 호출자 0건이었던 도메인 메서드(`suspend`/`restore`/`terminate`/`displayFlag setter`)에 어드민 진입 경로를 연결한다. 모든 어드민 액션은 `partyroom_admin_action`에 동기 listener로 atomic 감사 기록.

본 PR이 끝나면:
- 어드민이 파티룸 목록(필터/정렬/페이징)·상세·상태전이(suspend/restore/terminate)·메타수정·display flag 변경·일괄 액션을 수행 가능.
- 모든 어드민 액션이 `partyroom_admin_action`에 atomic 기록.
- Cross-BC 어드민 read는 `Administration → 다른 BC` 단방향 ArchUnit 가드로 명시화.
- PR 7에서 introduce된 도메인 모델(`PartyroomStatus`, `DisplayFlag`)이 실제 사용 단계 진입.

## 2. Scope

### 2.1 In Scope (PR 8)

1. **V7 Flyway 마이그레이션** — `partyroom_admin_action` 테이블 (Administration BC 소유, FK는 `administrator(administrator_id)`만, cross-BC ID는 loose ref).
2. **Endpoint 7개** (모두 `/api/v1/admin/partyrooms/...`, `@PreAuthorize("@adminAuth.isAdmin()")`):
   - B-1: `GET /admin/partyrooms` — 페이징/필터/정렬 list (cross-BC JOIN)
   - B-2: `GET /admin/partyrooms/{id}` — composite detail
   - B-3: `POST /admin/partyrooms/{id}/terminate` — bulk crew deactivate + status TERMINATED + audit
   - B-4: `POST /admin/partyrooms/{id}/suspend` — status SUSPENDED + audit (입장만 거부)
   - B-4: `POST /admin/partyrooms/{id}/restore` — status ACTIVE + audit
   - B-5: `PATCH /admin/partyrooms/{id}` — title/introduction/playbackTimeLimit 부분 수정 + audit
   - B-6: `PATCH /admin/partyrooms/{id}/display-flag` — displayFlag 변경 + audit
   - B-8: `POST /admin/partyrooms/bulk-action` — TERMINATE/SUSPEND/SET_HIDDEN 일괄, per-item TX
3. **Party 도메인 보강** — `PartyroomData.setDisplayFlagFeatured/Hidden/Normal()` 신설, 신규 도메인 이벤트 5종 (`PartyroomTerminatedEvent`/`PartyroomSuspendedEvent`/`PartyroomRestoredEvent`/`PartyroomMetaUpdatedEvent`/`PartyroomDisplayFlagChangedEvent`, administratorId + reason 페이로드).
4. **Crew bulk deactivate** — `CrewRepository.bulkDeactivateByPartyroomId(partyroomId, now)` native UPDATE.
5. **Counter listener 확장** — `PartyroomCounterListener`에 `on(PartyroomTerminatedEvent)` + `on(PartyroomClosedEvent)` 추가 → `resetCrewCount(0)`. 신규 atomic UPDATE 메서드 `PartyroomRepository.resetCrewCount`.
6. **Audit listener 신설** — `PartyroomAdminActionListener` (synchronous `@EventListener`, same TX) → 7개 action_type INSERT.
7. **Cross-BC admin-read repository** — `AdminPartyroomQueryRepository(Impl)` + QueryDSL JOIN (`partyroom + user_account + member`), B-1/B-2 read path.
8. **ArchUnit 가드** — 단방향 cross-BC 규칙 4종 (Administration → 다른 BC 허용, 역방향 차단).
9. **Redis fanout 확장** — `DomainEventRedisRelay`에 `on(PartyroomTerminatedEvent/SuspendedEvent/RestoredEvent)` 추가 → 신규 `MessageTopic` 3종.
10. **테스트** — 단위 + 통합 + WebMvc + ArchUnit + 동시성 race 회귀.

### 2.2 Out of Scope (defer)

| 항목 | 이전 PR | 사유 |
|---|---|---|
| B-7 PENALIZE_CREW listener | PR 9 | V8 `punisher_type` 컬럼과 같이 묶음. roadmap 의도 유지. PR 8 audit listener는 7 action_type만 처리 |
| `recentReports` 실데이터 | PR 13 | C-1 신고 테이블이 PR 13. response shape는 PR 8에서 잡되 빈 배열 hardcoded |
| `recentPenalties.punisherType` 필드 | PR 9 | V8 컬럼이 PR 9. 일단 항상 `"CREW"` hardcoded |
| C-3 banned word, D-* 공지/푸시 | future | spec future scope |
| WebSocket realtime 룸 알림 | future | MVP는 Redis fanout + 클라이언트 polling/구독 |
| RESTORE/SET_FEATURED/SET_NORMAL/UPDATE_META의 일괄 액션 | scope 외 | 일괄 의미 약함, B-8 MVP는 TERMINATE/SUSPEND/SET_HIDDEN만 |
| Use-case port 패턴 도입 (PR 11에서 검토) | PR 11 | PR 8은 7개 endpoint 모두 1-aggregate 1-호출이라 use-case port 정당화 안 됨 (Q1 결정) |

## 3. V7 Migration DDL

**파일:** `app/src/main/resources/db/migration/V7__create_partyroom_admin_action.sql`

```sql
-- =====================================================
-- V7: Administration context — AdminAction aggregate
--
-- 어드민의 시스템 액션 감사 로그. Append-only.
-- 교차 컨텍스트 참조(partyroom_id, target_id)는 FK 없이 값 저장.
-- =====================================================

CREATE TABLE partyroom_admin_action (
    action_id          BIGINT       NOT NULL AUTO_INCREMENT,
    administrator_id   BIGINT       NOT NULL,
    action_type        VARCHAR(32)  NOT NULL,                  -- SUSPEND_PARTYROOM | RESTORE_PARTYROOM
                                                               -- | TERMINATE_PARTYROOM | SET_FEATURED
                                                               -- | SET_HIDDEN | SET_NORMAL
                                                               -- | UPDATE_PARTYROOM_META
                                                               -- (PENALIZE_CREW는 PR 9, MEMBER 관련은 PR 12)
    target_type        VARCHAR(16)  NOT NULL,                  -- PR 8에선 'PARTYROOM'만 사용
    target_id          BIGINT       NOT NULL,
    partyroom_id       BIGINT       NULL,                      -- 룸 관련 액션의 룸 ID (denormalized for query)
    reason             TEXT         NULL,
    metadata           JSON         NULL,
    occurred_at        DATETIME     NOT NULL,
    PRIMARY KEY (action_id),
    CONSTRAINT fk_paa_administrator
        FOREIGN KEY (administrator_id)
        REFERENCES administrator(administrator_id),
    INDEX idx_paa_partyroom_time (partyroom_id, occurred_at DESC),
    INDEX idx_paa_administrator_time (administrator_id, occurred_at DESC),
    INDEX idx_paa_target (target_type, target_id, occurred_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

미세 조정 vs spec §4.4.1:
- `action_type`/`target_type`은 VARCHAR로 향후 확장 시 마이그레이션 불필요.
- PR 8 시점 사용 enum 값만 7개 (PENALIZE_CREW, CHANGE_MEMBER_TIER, WITHDRAW_MEMBER 제외).

V slot: V6(PR 7) → V7(PR 8). V8/V9/V10/V11/V12 future PR 예약.

## 4. Endpoint Specification

### 4.1 B-1. `GET /api/v1/admin/partyrooms`

**Query params:**
- `status` (default: 미지정 → `<> TERMINATED` — Risk #6 결정 따라 ACTIVE+SUSPENDED 모두 노출)
- `stageType` (MAIN/GENERAL, optional)
- `createdFrom`, `createdTo` (ISO date, optional)
- `host` (partial match on email OR member nickname, optional)
- `page` (default 0)
- `size` (default 50, max 200)
- `sort` (default `createdAt,desc`; whitelist: `createdAt`, `lastActivityAt`, `crewCount`, `title`, `hostNickname`)

**Response:** `Page<AdminPartyroomListItemResponse>`

```json
{
  "content": [{
    "partyroomId": 1, "title": "...", "stageType": "GENERAL",
    "hostUserAccountId": 100, "hostNickname": "...",
    "crewCount": 42, "djCount": 3, "playbackActivated": true,
    "status": "ACTIVE", "displayFlag": "FEATURED",
    "createdAt": "...", "lastActivityAt": "..."
  }],
  "page": { "number": 0, "size": 50, "totalElements": 123, "totalPages": 3 }
}
```

(`hostEmail`은 list response에 노출 안 함 — privacy. detail에선 노출.)

### 4.2 B-2. `GET /api/v1/admin/partyrooms/{partyroomId}`

**Response:** spec §6.B-2 형태 — composite detail. 5-6 sub-query 조립 (§7).

`recentReports` 빈 배열 hardcoded (PR 13). `recentPenalties.punisherType` 항상 `"CREW"` (PR 9에서 V8 컬럼 도입 후 진짜 값).

### 4.3 B-3. `POST /api/v1/admin/partyrooms/{partyroomId}/terminate`

**Request:** `{ "reason": "string, max 500" }` (required).
**Response:** `204 No Content`.

Service flow (§5/§6 상세):
1. Load partyroom
2. Bulk crew deactivate (atomic single SQL)
3. `partyroom.terminate()` (PR 7 strict guard)
4. Save partyroom
5. Publish `PartyroomTerminatedEvent(partyroomId, administratorId, reason, now)`
6. AuditListener (sync, same TX) → INSERT `TERMINATE_PARTYROOM`
7. CounterListener (AFTER_COMMIT, REQUIRES_NEW) → `resetCrewCount(0)`
8. RedisRelay (AFTER_COMMIT) → `ROOM_TERMINATED` topic

### 4.4 B-4. `POST /api/v1/admin/partyrooms/{partyroomId}/suspend` / `/restore`

**suspend** request: `{ "reason": "string, max 500" }` (required).
**restore** request: `{}` (body 없음).
**Response:** `204 No Content`.

`partyroom.suspend()` / `restore()` (PR 7 strict guards) → save → 이벤트 publish → AuditListener INSERT (`SUSPEND_PARTYROOM` / `RESTORE_PARTYROOM`) → RedisRelay (`ROOM_SUSPENDED` / `ROOM_RESTORED`).

⚠️ Suspend는 bulk crew deactivate **하지 않음** — Q4 결정 (입장만 거부).

### 4.5 B-5. `PATCH /api/v1/admin/partyrooms/{partyroomId}`

**Request:** `{ "title"?: "...", "introduction"?: "...", "playbackTimeLimit"?: 30 }` (모두 optional, 최소 1개 필요).
**Response:** `204 No Content`.

Service:
1. Load partyroom
2. **Compute diff BEFORE mutation** — 변경 후보 필드를 현재 entity 값과 비교해 변경된 것만 `{old, new}` pair로 캡쳐 (mutation 전에 해야 old 값 보존)
3. `partyroom.updateBaseInfo(...)` (linkDomain은 admin이 변경 안 하므로 기존 값 그대로)
4. Save + publish `PartyroomMetaUpdatedEvent(partyroomId, administratorId, diff)`
5. AuditListener → `UPDATE_PARTYROOM_META` with `metadata = {"changes": {"title": {"old": "A", "new": "B"}, ...}}`

Redis fanout 안 함 (메타 변경은 즉시 broadcast 불필요).

### 4.6 B-6. `PATCH /api/v1/admin/partyrooms/{partyroomId}/display-flag`

**Request:** `{ "flag": "FEATURED" | "HIDDEN" | "NORMAL" }` (required).
**Response:** `204 No Content`.

Service:
1. Load partyroom
2. `partyroom.setDisplayFlagFeatured() | Hidden() | Normal()` (3개 도메인 메서드, 의미 명료화)
3. Save + publish `PartyroomDisplayFlagChangedEvent(partyroomId, administratorId, oldFlag, newFlag)`
4. AuditListener → action_type 분기 (`SET_FEATURED` / `SET_HIDDEN` / `SET_NORMAL`) + `metadata = {"old_flag": ..., "new_flag": ...}`

### 4.7 B-8. `POST /api/v1/admin/partyrooms/bulk-action`

**Request:**
```json
{
  "partyroomIds": [1, 2, 3],
  "action": "TERMINATE" | "SUSPEND" | "SET_HIDDEN",
  "reason": "...",
  "skipErrors": true
}
```

**Response:**
```json
{
  "results": [
    { "partyroomId": 1, "success": true },
    { "partyroomId": 2, "success": false, "error": "..." },
    { "partyroomId": 3, "success": true }
  ]
}
```

Per-item TX (Q6) — 각 항목 별 TX, `skipErrors=true` 시 실패 후 진행, `false` 시 첫 실패에서 break (이전 성공은 commit 유지).

제약:
- `partyroomIds` length: 1-100
- 모든 항목 같은 `action` (혼합 금지)

성공 항목마다 audit listener가 개별 INSERT (일괄 N건).

## 5. Architecture

### 5.1 신규 컴포넌트 layout (Administration BC)

**패키지:** `com.pfplaybackend.api.administration` (PR 5/6 컨벤션 — `com.pfplaybackend.api.admin`은 별 legacy area).

```
app/src/main/java/com/pfplaybackend/api/administration/
├── adapter/
│   ├── in/
│   │   ├── listener/
│   │   │   └── PartyroomAdminActionListener.java   ← @EventListener (sync, same TX) 5 method
│   │   └── web/
│   │       ├── AdminPartyroomCommandController.java   ← B-3/B-4/B-5/B-6/B-8
│   │       ├── AdminPartyroomQueryController.java     ← B-1/B-2
│   │       └── payload/
│   │           ├── request/  (5 request DTOs)
│   │           └── response/ (3 response DTOs)
│   └── out/persistence/
│       ├── PartyroomAdminActionRepository.java   ← simple JpaRepository
│       ├── AdminPartyroomQueryRepository.java    ← cross-BC JOIN interface
│       └── impl/
│           └── AdminPartyroomQueryRepositoryImpl.java
├── application/
│   └── service/
│       ├── AdminPartyroomCommandService.java          ← B-3/B-4/B-5/B-6 단건
│       ├── AdminPartyroomQueryService.java            ← B-1/B-2 read 조립
│       ├── AdminBulkPartyroomActionService.java       ← B-8 outer (non-tx)
│       └── AdminPartyroomTransactionalUnit.java       ← B-8 inner (단일 항목 @Transactional, 별 bean)
└── domain/
    ├── entity/
    │   └── PartyroomAdminActionData.java   ← V7 entity
    └── enums/
        ├── PartyroomAdminActionType.java
        └── AdminActionTargetType.java
```

### 5.2 신규 컴포넌트 layout (Party 모듈)

```
app/src/main/java/com/pfplaybackend/api/party/
├── adapter/
│   ├── in/listener/
│   │   └── PartyroomCounterListener.java   ← 기존 (PR 7) + on(PartyroomTerminatedEvent) + on(PartyroomClosedEvent) 추가
│   └── out/
│       ├── event/
│       │   └── DomainEventRedisRelay.java   ← 기존 + 3 신규 listener
│       └── persistence/
│           ├── CrewRepository.java   ← 기존 + bulkDeactivateByPartyroomId
│           └── PartyroomRepository.java   ← 기존 + resetCrewCount
└── domain/
    ├── entity/data/
    │   └── PartyroomData.java   ← 기존 + setDisplayFlagFeatured/Hidden/Normal
    └── event/
        ├── PartyroomTerminatedEvent.java       ← 신규
        ├── PartyroomSuspendedEvent.java        ← 신규
        ├── PartyroomRestoredEvent.java         ← 신규
        ├── PartyroomMetaUpdatedEvent.java      ← 신규
        └── PartyroomDisplayFlagChangedEvent.java   ← 신규
```

### 5.3 `PartyroomClosedEvent` (기존) vs `PartyroomTerminatedEvent` (신규)

- `PartyroomClosedEvent` (PR 7 기존): `PartyroomData.terminate()` 도메인 메서드가 등록. host 자발 종료/기타 자동 종료 경로에서 발생. admin info 없음.
- `PartyroomTerminatedEvent` (PR 8 신규): admin 경로(`AdminPartyroomCommandService.terminate`)에서 명시적으로 publish. `(partyroomId, administratorId, reason, occurredAt)` 페이로드.

분기:
- AuditListener는 `PartyroomTerminatedEvent`만 listen (admin 경로만 audit)
- CounterListener는 `PartyroomTerminatedEvent` + `PartyroomClosedEvent` 둘 다 listen → counter reset 일관성 (Risk #7 결정)
- RedisRelay에 3개 신규 이벤트(Terminated/Suspended/Restored) listener 추가. 기존 `PartyroomClosedEvent` Redis fanout(`PARTYROOM_CLOSED` topic)은 PR 8 이전부터 이미 존재 — 그대로 유지. admin 경로는 별 topic(`ROOM_TERMINATED`)으로 발행 → 클라이언트는 두 topic 모두 구독해 동일하게 disconnect 처리.

### 5.4 권한

전 endpoint `@PreAuthorize("@adminAuth.isAdmin()")` (PR 5 SpEL bean). ADMIN/SUPER_ADMIN 모두 통과.

## 6. AdminAction Audit Listener (Atomic Pattern)

### 6.1 `PartyroomAdminActionListener`

위치: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/PartyroomAdminActionListener.java`

핵심 시맨틱:
- `@EventListener` (NOT `@TransactionalEventListener`) — synchronous + same TX
- No `@Transactional` annotation on method — caller TX에 자연 참여 (REQUIRED 기본)
- INSERT 실패 시 ERROR 로그 + rethrow → caller TX rollback (atomic 보장)
- `administratorId`는 이벤트 페이로드에서 읽음 (SecurityContext 의존 0)
- `Clock` 주입 (PR 7 컨벤션)

5개 listener 메서드 (각 도메인 이벤트당 1개) — 모두 `PartyroomAdminActionRepository.save(...)` 호출. `PartyroomDisplayFlagChangedEvent`만 `newFlag`에 따라 action_type 분기 (FEATURED/HIDDEN/NORMAL).

### 6.2 PR 7 listener와의 phase 차이 (의도된 분기)

| 항목 | PR 7 PartyroomCounterListener | PR 8 PartyroomAdminActionListener |
|---|---|---|
| Phase | `@TransactionalEventListener(AFTER_COMMIT)` | `@EventListener` (synchronous) |
| Tx propagation | `REQUIRES_NEW` | `REQUIRED` (caller tx 참여) |
| Failure 정책 | WARN/DEBUG 로그 + swallow | ERROR 로그 + rethrow → caller rollback |
| 의도 | side-effect (commit 후 카운터 갱신, 손실 허용) | parallel record (atomic 필수, audit gap 허용 안 됨) |

같은 코드베이스에 두 패턴 공존 — javadoc + 본 spec §6.2로 명시 구분.

### 6.3 Entity `PartyroomAdminActionData`

- `@Entity @Table(name = "PARTYROOM_ADMIN_ACTION")`
- `@DynamicInsert` (NULL 컬럼 안 보냄)
- No setters — append-only audit table
- `metadata` 컬럼: `JsonMetadata` VO + JPA `@Convert` (Map<String, Object> wrapper, Jackson 직렬화) — **신규 infrastructure 1점**, 단순 `String → JSON` Converter. null/empty Map 안전 처리. plan §10 commit groupings에 별도 task로 분리.
- 팩토리 `of(...)` 메서드 — 모든 필드 명시적 입력

## 7. B-1 List Cross-BC Query

### 7.1 인터페이스 + impl 위치

- `AdminPartyroomQueryRepository` (interface): Administration BC adapter/out
- `AdminPartyroomQueryRepositoryImpl`: QueryDSL JPAQueryFactory 사용
- DTO projection (`AdminPartyroomListRow` record) — entity 직접 노출 안 함

### 7.2 QueryDSL JOIN

**SQL-level conceptual:**
```
partyroom p
  LEFT JOIN user_account ua ON ua.user_id = p.host_id
  LEFT JOIN member m ON m.user_account_id = ua.user_id
  LEFT JOIN partyroom_playback pb ON pb.partyroom_id = p.partyroom_id
WHERE [filters]
ORDER BY [whitelisted sort]
```

**Entity-path notes (중요 — embedded VO 경로):**
- `partyroom.host_id` → `qPartyroomData.hostId.uid` (host_id는 `UserId` embedded VO, column `uid`)
- `user_account.user_id` (PK) → `qUserAccountData.userId.uid` (`@EmbeddedId UserId userId`)
- `member.user_account_id` → `qMemberData.userAccountId` (plain `Long` column, FK 없음)
- `member.profileData.bio.nickname.value` → 닉네임 실제 경로 (Member → ProfileData(@OneToOne) → Bio(@Embedded) → Nickname(@Embedded VO with `value` String). **NOT `m.profileData.nickname`.**

**JOIN 표현식 예 (QueryDSL):**
```java
.from(p)
.leftJoin(ua).on(ua.userId.uid.eq(p.hostId.uid))
.leftJoin(m).on(m.userAccountId.eq(ua.userId.uid))
.leftJoin(pb).on(pb.partyroomId.id.eq(p.id))
```

**Host nickname filter/sort 예:**
```java
// filter
String like = "%" + filter.hostQuery() + "%";
b.and(ua.email.like(like).or(m.profileData.bio.nickname.value.like(like)));

// sort by nickname
query.orderBy(m.profileData.bio.nickname.value.asc());
```

DJ count는 subquery (`SELECT count(*) FROM dj WHERE dj.partyroom_id = p.id`).

### 7.3 ArchUnit 가드 (단방향 cross-BC)

`CrossContextDependencyTest` 4 rules:
- `party_must_not_reference_user_or_admin_schema`
- `user_module_must_not_reference_party_or_admin`
- `auth_module_must_not_reference_party_or_admin`
- `admin_must_not_directly_mutate_party_entities` (setter 호출 금지, 도메인 메서드만)

### 7.4 Default status 시맨틱

`status` query param 미지정 시:
- ❌ NOT `status = ACTIVE` (spec §7.2 어휘 그대로 해석 시)
- ✅ `status <> TERMINATED` — ACTIVE + SUSPENDED 모두 노출 (Risk #6 결정 — 어드민 사용성 우선)

## 8. Bulk Crew Deactivate + Counter Reset

### 8.1 `CrewRepository.bulkDeactivateByPartyroomId`

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE CrewData c SET c.isActive = false, c.exitedAt = :now " +
       "WHERE c.partyroomId = :partyroomId AND c.isActive = true")
int bulkDeactivateByPartyroomId(@Param("partyroomId") PartyroomId partyroomId,
                                @Param("now") LocalDateTime now);
```

### 8.2 `PartyroomRepository.resetCrewCount`

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE PartyroomData p SET p.crewCount = 0 WHERE p.id = :id")
int resetCrewCount(@Param("id") Long id);
```

(status 가드 없음 — TERMINATED 룸 reset이 본 use case)

### 8.3 `PartyroomCounterListener` 확장

```java
@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
@Transactional(propagation = REQUIRES_NEW)
public void on(PartyroomTerminatedEvent event) {
    int affected = partyroomRepository.resetCrewCount(event.getPartyroomId().getId());
    log.info("[PartyroomCounterListener] crew_count reset for terminated partyroomId={}, affected={}",
             event.getPartyroomId().getId(), affected);
}

@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
@Transactional(propagation = REQUIRES_NEW)
public void on(PartyroomClosedEvent event) {
    int affected = partyroomRepository.resetCrewCount(event.getPartyroomId().getId());
    log.info("[PartyroomCounterListener] crew_count reset for closed partyroomId={}, affected={}",
             event.getPartyroomId().getId(), affected);
}
```

### 8.4 `DomainEventRedisRelay` 확장

3개 신규 `@TransactionalEventListener(AFTER_COMMIT)`:
- `on(PartyroomTerminatedEvent)` → `MessageTopic.ROOM_TERMINATED`
- `on(PartyroomSuspendedEvent)` → `MessageTopic.ROOM_SUSPENDED`
- `on(PartyroomRestoredEvent)` → `MessageTopic.ROOM_RESTORED`

각 message DTO 신설 (`RoomTerminatedMessage`/`RoomSuspendedMessage`/`RoomRestoredMessage`).

## 9. B-8 Bulk Action — Per-Item TX

### 9.1 Outer (non-tx)

`AdminBulkPartyroomActionService.execute(...)` — partyroomIds loop, 각각 `txUnit.executeOne(...)` 호출. try/catch로 결과 누적.

### 9.2 Inner (per-item @Transactional, 별 bean)

`AdminPartyroomTransactionalUnit.executeOne(...)` — `@Transactional` 적용. 별 bean이라 outer가 호출 시 Spring proxy 통과 → 정상 TX 시작.

`@Transactional` 같은 클래스 내부 호출 함정 회피 (PR 7 `findCrewInNewTransaction` 사례 답습).

### 9.3 결과 모델

- `skipErrors=true` (default): 각 항목 별 TX, 실패 무시 + 다음 진행
- `skipErrors=false`: 첫 실패에서 break (이전 성공은 commit 유지, 후속 시도 안 함)
- 어떤 경우에도 "이미 commit된 것을 rollback"은 없음

## 10. Race Analysis

### 10.1 Concurrent terminate vs enter

- T0: Admin terminate 시작 (TX A)
- T1: User enter 시작 (TX B, partyroom load → status=ACTIVE 본 상태)
- T2: TX A bulk deactivate + status=TERMINATED + commit
- T3: TX B의 `PartyroomEntrySpecification.validate(stale partyroom)` — 통과 (entity stale)
- T4: TX B `aggregatePort.activateCrew(...)` 실행 → atomic UPDATE WHERE is_active=false
- T5: TX B의 `CrewAccessedEvent(ENTER)` publish + commit
- T6: AFTER_COMMIT listener `incrementCrewCount(partyroomId, now)` → `WHERE p.status <> TERMINATED` 가드로 **0 affected**, WARN 로그

**결과:**
- crew row stale active 1건 (terminated 룸에) — 다음 cleanup batch나 사용자 다음 행위에서 자연 해소
- counter는 정확 (PR 7 가드로 reject)

수용 가능. **추가 완화 옵션 (defer):** `tryEnter`가 트랜잭션 안에서 partyroom status double-check — 비용 SELECT 1회. 운영 관측 후 결정.

### 10.2 Concurrent admin actions on same partyroom

두 admin이 동시에 같은 룸 suspend 시도:
- 둘 다 partyroom load → status=ACTIVE
- 둘 다 `partyroom.suspend()` 호출 (1번째 PR 7 가드 통과, 2번째도 entity가 stale이라 통과)
- 둘 다 save → 1번째 commit → 2번째 commit (status는 SUSPENDED → SUSPENDED, 멱등)
- 두 audit row 모두 INSERT — **부정확** (1번째만 의미 있음, 2번째는 noop)

수용 가능. audit 중복은 시간순으로 정렬되어 노이즈 수준. 완화 옵션 (defer): `@Version` optimistic locking — 2번째가 OptimisticLockException → admin UI에 "다른 어드민이 이미 처리함" 안내. 본 PR scope 외.

## 11. Testing Strategy

### 11.1 단위 테스트
- `PartyroomData.setDisplayFlagFeatured/Hidden/Normal` — 각 메서드별 변경 + TERMINATED 룸에서 ILLEGAL_STATE_TRANSITION 거부
- `PartyroomAdminActionData.of(...)` — 모든 필드 매핑, BaseEntity timestamps
- `AdminPartyroomCommandService` 각 메서드 — mock dependencies, 흐름 검증
- `AdminBulkPartyroomActionService.execute` — skipErrors 분기, length 검증
- `PartyroomAdminActionListener` 5 메서드 — event payload → entity 인자 매핑

### 11.2 통합 테스트 (Testcontainers)
- V7 마이그레이션 — clean DB, V6 직후 적용
- `CrewRepository.bulkDeactivateByPartyroomId` — 정상/없음/이미 inactive
- `PartyroomRepository.resetCrewCount` — 정상/이미 0
- `PartyroomAdminActionListener` end-to-end — atomic 보장 (listener throw 시 caller rollback) 검증
- `AdminPartyroomQueryRepositoryImpl.findAdminList` — status filter, hostQuery email/nickname, sort 분기
- `AdminPartyroomQueryRepositoryImpl.findDetailById` — composite 조립

### 11.3 Service / Listener 통합 (`@SpringBootTest`)
- terminate end-to-end — 49 active crew → bulk deactivate → status TERMINATED → admin_action 1건(per-crew EXIT 미발생) → counter=0 → Redis publish 1회
- suspend → enter 거부 (PR 7 가드 회귀)
- restore → enter 허용
- meta diff metadata JSON 정확 직렬화
- display-flag 분기 → 다른 action_type INSERT
- audit listener atomic 보장

### 11.4 Web (`@WebMvcTest`)
- 7 endpoint × (정상 / 권한 없음 401/403 / validation 400 / 도메인 예외 매핑 404/409/403)
- BulkPartyroomActionRequest 검증 (empty, 101개, action 미지정, reason 누락)

### 11.5 ArchUnit
`CrossContextDependencyTest` 4 rules (§7.3)

### 11.6 동시성 race 회귀
- Concurrent terminate vs enter (§10.1) — stale active crew 발생 + counter 정확 검증

### 11.7 Out of scope (test)
- Multi-instance simulation (PR 7 stretch와 동일 reasoning)
- `recentReports` 동작 (PR 13)
- Frontend integration (별 repo)

## 12. Atomic commit groupings

| 그룹 | 묶이는 변경 | 사유 |
|---|---|---|
| **G1: V7 + entity + enums + repository** | V7 SQL + `PartyroomAdminActionData` + 2 enums + `PartyroomAdminActionRepository` | 컬럼/엔티티/enum boot-or-die. 단일 commit. **배포 순서 V7 SQL ↔ 새 jar 분리 불가.** |
| **G2: 신규 도메인 이벤트 5종 + Party 도메인 메서드 (setDisplayFlag*)** | 5 events + 3 setDisplayFlag methods | publisher-consumer 쌍. 같이 commit. |

기타 task별 독립 commit. 총 ~16 commits (G1, G2 + 14 task commits).

## 13. Risks & Mitigations

| # | 위험 | 완화 |
|---|---|---|
| 1 | Audit listener 실패 시 caller TX rollback (정상 admin action 거부) | 의도 시맨틱 (Q2 결정). 운영 모니터링: `partyroom_admin_action` INSERT 실패 ERROR 로그를 alert 대상으로 |
| 2 | Concurrent terminate vs enter — terminated 룸에 stale active crew row | §10.1 — counter는 정확, crew row stale은 다음 cleanup batch에서 해소. PR 8 scope 외 |
| 3 | Cross-BC JOIN — schema 변경 시 Administration도 깨짐 | ArchUnit 가드는 의존성 방향만 강제. schema 변경 시 Q-class 재생성 단계에서 컴파일 에러로 발견 |
| 4 | Bulk action 부분 commit | per-item TX 모델의 자연스러운 결과. spec example 일치 |
| 5 | `PartyroomClosedEvent` vs `PartyroomTerminatedEvent` 분기 | §5.3 명시 — audit listener는 후자만, counter listener는 둘 다 처리 |
| 6 | Concurrent admin actions on same partyroom — audit 중복 INSERT | §10.2 — 노이즈 수준 수용. 완화 (Optimistic lock)는 PR scope 외 |
| 7 | B-1 default status — `<> TERMINATED` (어드민 사용성) vs spec §7.2 `ACTIVE` | §7.4 결정 — 어드민 사용성 우선 (Risk #6 답) |

## 14. Decisions Taken (브레인스토밍 결과 8건)

1. **Q1 — Administration → `aggregatePort` 직접** (use-case port는 PR 11에서 재검토)
2. **Q2 — Synchronous `@EventListener` (same TX)** for audit. Atomic 보장 우선
3. **Q3 — Bulk crew deactivate + 단일 `PartyroomTerminatedEvent`** (not per-crew EXIT)
4. **Q4 — SUSPEND = 입장만 거부** (PR 7 가드로 충분)
5. **Q5 — Cross-BC JOIN in Administration BC's admin-read repository**, ArchUnit 단방향 가드
6. **Q6 — Per-item TX, skipErrors=true default**
7. **Q7 — PENALIZE_CREW는 PR 9로 분리** (PR 8 audit listener는 7 action_type만)
8. **Q8 — Service-level 5-6 sub-query 조립** (no single big JOIN)

추가 결정 (Section 10):
- **Risk #6 — B-1 default status `<> TERMINATED`** (ACTIVE+SUSPENDED 모두 노출)
- **Risk #7 — CounterListener는 `PartyroomTerminatedEvent` + `PartyroomClosedEvent` 둘 다 listen** (counter consistency)

---

**다음 단계:** 본 spec이 reviewer + 사용자 승인되면 `superpowers:writing-plans`로 구현 plan 생성 (`docs/superpowers/plans/2026-04-27-admin-platform-pr8.md`).
