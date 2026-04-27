# PR 9: V8 punisher_type + Admin Crew Penalty API — Design

> Brainstormed design spec. Implementation plan is generated separately via `superpowers:writing-plans`.

**Date:** 2026-04-28
**Branch:** `feature/admin-auth-iam-schema` (계속, PR 8 HEAD `76d7b2c1` 위에 빌드)
**Roadmap row:** §9.1 PR 9 — *V8 penalty history punisher_type + 어드민 페널티 경로 (B-7)* (size: M)
**Milestone:** M3 (PR 7-9, 파티룸 운영 도구) — PR 9가 M3 마지막

---

## 1. Goal

V8 마이그레이션(`crew_penalty_history.punisher_type`)을 도입하고, 어드민의 크루-레벨 페널티 운영 도구 2 endpoint(부과 / 해제)를 구현한다. PR 8에서 hardcoded `"CREW"`였던 detail 응답의 `recentPenalties.punisherType`을 V8 컬럼 값으로 채운다. 모든 어드민 페널티 액션은 PR 8의 `partyroom_admin_action`에 동기 listener로 atomic 감사 기록.

본 PR이 끝나면:
- 어드민이 어떤 룸의 어떤 crew에게도 ONE_TIME_EXPULSION / PERMANENT_EXPULSION을 부과 가능 (grade hierarchy 무시).
- 어드민이 부과한 PERMANENT_EXPULSION을 어드민이 해제 가능. crew는 admin-applied 페널티 해제 불가 (403).
- 모든 어드민 페널티 액션이 `partyroom_admin_action`에 atomic 기록 (action_type ∈ {PENALIZE_CREW, RELEASE_CREW_PENALTY}).
- detail 응답의 `recentPenalties.punisherType`이 실제 컬럼 값 반영.

---

## 2. Scope

### 2.1 In Scope (PR 9)

1. **V8 Flyway 마이그레이션** — `crew_penalty_history.punisher_type ENUM('CREW','ADMIN') NOT NULL DEFAULT 'CREW'` 컬럼 추가. `crew_block_history`는 손대지 않음 (Q5).
2. **Endpoint 2개** (모두 `/api/v1/admin/partyrooms/...`, `@PreAuthorize("@adminAuth.isAdmin()")`):
   - `POST /api/v1/admin/partyrooms/{partyroomId}/penalties` — admin penalty 부과 (B-7, Q1=B)
   - `DELETE /api/v1/admin/partyrooms/{partyroomId}/penalties/{penaltyId}` — admin이 부과한 페널티 해제 (Q6=B)
3. **기존 crew DELETE 가드** — `DELETE /api/v1/partyrooms/{partyroomId}/penalties/{penaltyId}` 호출 시 history.punisher_type='ADMIN'이면 403 (`ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE`).
4. **Party 도메인 보강**:
   - `CrewPenaltyHistoryData`에 `punisherType` 필드 + `releaseByAdmin(now)` 메서드 추가. 기존 `release(CrewId, now)` 그대로 (crew path).
   - 신규 enum `PunisherType { CREW, ADMIN }` (party domain).
   - 신규 도메인 이벤트 2종: `AdminCrewPenalizedEvent` / `AdminCrewPenaltyReleasedEvent` (Q4=A).
   - 기존 `CrewPenaltyCommandService.addPenalty` builder에 `.punisherType(CREW)` 한 줄 추가 (명시성).
5. **Cross-BC port 확장** — `PartyroomAggregatePort`에 `applyAdminPenalty` / `releaseAdminPenalty` 2개 메서드 추가 (Q3=A, PR 8 패턴).
6. **Administration BC 신설**:
   - `AdminCrewPenaltyCommandController` (administration adapter)
   - `AdminCrewPenaltyCommandService` (administration application, port 위임만)
   - `AdminApplyPenaltyRequest` DTO + `AdminPenaltyType` enum (Q8.3)
7. **Audit listener 확장** — PR 8의 `PartyroomAdminActionListener`에 `@EventListener` 메서드 2개 추가 (`PENALIZE_CREW`, `RELEASE_CREW_PENALTY`).
8. **Admin enum 확장**:
   - `PartyroomAdminActionType` += `PENALIZE_CREW`, `RELEASE_CREW_PENALTY`
   - `AdminActionTargetType` += `CREW`
9. **Read-side wiring** — `AdminPartyroomQueryRepository(Impl)`/`AdminPartyroomQueryService` detail projection의 `recentPenalties.punisherType`을 V8 컬럼에서 채움 (PR 8의 hardcoded `"CREW"` / `[]` 정리).
10. **Spec 동기화** — `2026-04-19-admin-platform-schema.md` §4.5.1에서 `crew_block_history` ALTER 라인 제거 + 사유 주석.
11. **테스트** — 단위/서비스/IT/WebMvc/concurrency/회귀 (§7).

### 2.2 Out of Scope (defer)

| 항목 | 이전/이후 PR | 사유 |
|---|---|---|
| `CHAT_MESSAGE_REMOVAL` / `CHAT_BAN_30_SECONDS` admin 부과 | future | Q2=B. 어드민 expulsion 2종만 |
| `crew_block_history.punisher_type` 컬럼 | 영구 제외 | Q5. user-to-user 의미라 admin 무관 |
| Admin이 crew-applied penalty 해제 | future | Q7=α. release 권한도 부과 권한 따라감 |
| Bulk action에 PENALIZE_CREW | future | Q8.6. spec §2.2 OOS 유지 |
| `released_by_type` 컬럼 (V8 추가 안 함) | future | Q8.9 (i). audit 책임은 admin_action에 집중 |
| 어드민 페널티 자체 rate limit | future | Q8.7. abuse 시나리오 부재 |
| Member-level penalty (계정 정지 등) | PR 12+ | spec future scope |
| WebSocket realtime 룸 알림 | future | MVP는 Redis fanout(필요 시) + 폴링 |

---

## 3. V8 Migration DDL

**파일:** `app/src/main/resources/db/migration/V8__add_punisher_type_to_penalty_history.sql`

```sql
-- =====================================================
-- V8: Party context — crew_penalty_history에 punisher_type 추가
-- Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.5
-- Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §3
-- Plan: docs/superpowers/plans/2026-04-28-admin-platform-pr9.md Task 1
--
-- 어드민이 부과한 페널티를 구분.
-- 어드민 정체(administrator_id)는 partyroom_admin_action에 별도 기록.
-- correlation은 partyroom_admin_action.metadata.crew_penalty_history_id.
-- punisher_crew_id는 V1부터 nullable이라 ALTER 불필요 — admin 부과 시 NULL.
-- crew_block_history는 user-to-user 차단 의미라 본 PR 범위에서 제외 (admin 무관).
-- =====================================================

ALTER TABLE crew_penalty_history
    ADD COLUMN punisher_type ENUM('CREW','ADMIN') NOT NULL DEFAULT 'CREW' AFTER punisher_crew_id;
```

**미세 결정:**
- **`ENUM` vs VARCHAR**: PR 4/§5에서 `provider_type`/`role`은 VARCHAR로 갔지만, 본 테이블의 기존 `penalty_type`이 V1부터 ENUM(CHAT_MESSAGE_REMOVAL/CHAT_BAN_30_SECONDS/ONE_TIME_EXPULSION/PERMANENT_EXPULSION). 같은 테이블 내 컬럼 일관성 유지하는 ENUM 선택. (CREW, ADMIN) 두 값은 도메인상 영구 고정 가능성 높음 — 향후 SYSTEM 같은 값 추가가 발생하면 ALTER ENUM 1회로 처리.
- **`DEFAULT 'CREW'`**: 기존 row 전부 'CREW'로 backfill. NOT NULL 안전.
- **`AFTER punisher_crew_id`**: read-time 가독성. 컬럼 순서 안 깸.
- **인덱스 추가 안 함**: punisher_type 단독 조회 use case 없음. 향후 어드민 페널티 통계 쿼리 발생 시 `(punisher_type, penalty_date)` 추가 검토.
- **MySQL 8 ALGORITHM**: `ADD COLUMN ... NOT NULL DEFAULT '...'`는 MySQL 8.0.12+에서 `ALGORITHM=INSTANT` (metadata-only, table rebuild 없음). DDL에 명시적 `ALGORITHM=` 절은 추가하지 않음 — MySQL이 자동 선택 (INSTANT 실패 시 INPLACE/COPY로 fallback).
- **기존 nullable 컬럼 재확인**: V1 DDL에서 `punisher_crew_id bigint`(nullable), `released_by_crew_id bigint`(nullable), `punished_crew_id bigint`(nullable)로 모두 NULL 허용. PR 9는 기존 nullability 의존(admin 부과/해제 시 `punisher_crew_id=NULL`, `released_by_crew_id=NULL`)이라 추가 ALTER 불필요. 도메인 invariant: `punisher_type='ADMIN' ⇒ punisher_crew_id IS NULL`, `released=true ∧ released_by_crew_id IS NULL ⇒ admin이 release한 것` (Q8.9(i) 따라 별도 컬럼 없이 이 invariant로 식별).

V slot: V1~V7 사용. V8 신규. V9~V13 future PR 예약.

---

## 4. Endpoint Specification

### 4.1 `POST /api/v1/admin/partyrooms/{partyroomId}/penalties` — Admin Penalty 부과

| 항목 | 값 |
|---|---|
| Auth | `@PreAuthorize("@adminAuth.isAdmin()")` (PR 5 SpEL bean) |
| Cookie | `AdminAccessToken` (PR 4 admin cookie chain) |
| Path | `partyroomId` (Long) |
| Body | `AdminApplyPenaltyRequest` |
| Response | `201 Created`, `{ "penaltyId": Long | null }` |

**`AdminApplyPenaltyRequest`** (administration adapter):
```java
@Getter
public class AdminApplyPenaltyRequest {
    @NotNull Long crewId;
    @NotNull AdminPenaltyType penaltyType;          // ONE_TIME_EXPULSION | PERMANENT_EXPULSION
    @NotBlank @Size(min = 1, max = 255) String reason;
}
```
- `penaltyId`는 `PERMANENT_EXPULSION`일 때만 non-null (history INSERT 발생). `ONE_TIME_EXPULSION`은 null (기존 party 동작 유지).

**Validation:**
1. SecurityContext → `administratorId` (PR 5 admin principal, `AdminContext.currentAdministratorId()`)
2. partyroom 존재 + status ∈ {ACTIVE, SUSPENDED}. TERMINATED 거부 (이미 종료된 룸은 의미 없음 → 409)
3. punished crew 존재 + `crew.partyroomId == path partyroomId` (다른 룸 crew 차단 → 404)
4. crew.isActive 무관 (이미 inactive라도 PERMANENT는 `enforceBan` 멱등)
5. grade hierarchy 검증 **없음** (Q8.1)
6. **Idempotency note**: 이미 PERMANENT_EXPULSION으로 ban된 crew에게 admin이 다시 PERMANENT를 부과해도 거부하지 않는다. `expel` 멱등 + history row가 새로 INSERT됨(audit 완전성). 따라서 동일 crew에 대해 unreleased PERMANENT history row가 둘 이상 존재 가능 (§6.1 race도 동일 결과). admin이 중복 의도하지 않게 하려면 클라이언트(어드민 UI)가 사전 표시.

**Response wrapper:** 모든 endpoint는 PR 0~8 패턴대로 `ApiCommonResponse<T>`로 감싸진다. 즉 on-the-wire JSON은 `{ "data": { "penaltyId": ... }, "meta": {...} }` 형태. 본 문서의 "Response: ... `{ "penaltyId": ... }`" 표기는 inner data 한정.

**Errors:**
| HTTP | 사유 |
|---|---|
| 400 | DTO validation (reason 누락/빈 문자/길이 초과, crewId 누락, penaltyType invalid 또는 허용 외 값) |
| 401 | 어드민 미인증 |
| 403 | `@adminAuth.isAdmin()` 실패 |
| 404 | partyroom 없음 또는 crew 없음 또는 crew가 다른 룸 |
| 409 | partyroom status TERMINATED |

### 4.2 `DELETE /api/v1/admin/partyrooms/{partyroomId}/penalties/{penaltyId}` — Admin Release

| 항목 | 값 |
|---|---|
| Auth | `@PreAuthorize("@adminAuth.isAdmin()")` |
| Path | `partyroomId`, `penaltyId` |
| Body | 없음 |
| Response | `204 No Content` |

**Validation:**
1. partyroom 존재 (status TERMINATED여도 release 허용 — termination이 기존 페널티 row를 무효화하지 않음. cleanup이 종료 후 발생할 수 있어야 함)
2. penalty history 존재 + `partyroomId` 일치 + `released == false`
3. `punisher_type == ADMIN` (Q7α). crew-applied면 403 (`CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE`)

**Side effect note:** Release는 `crew.releaseBan()` 플래그만 클리어. crew를 자동으로 룸에 재입장시키지 않음 (released crew가 다시 들어오려면 본인이 enter API 호출). 이는 기존 crew release 경로와 동일한 시맨틱.

**Errors:**
| HTTP | 사유 |
|---|---|
| 401 | 어드민 미인증 |
| 403 | `@adminAuth.isAdmin()` 실패 또는 punisher_type ≠ ADMIN |
| 404 | partyroom 또는 penalty history 없음 또는 이미 released |

### 4.3 기존 `DELETE /api/v1/partyrooms/{partyroomId}/penalties/{penaltyId}` — Crew Release 가드

기존 `CrewPenaltyCommandService.releaseCrewPenalty`에 한 줄 추가:

```java
public void releaseCrewPenalty(PartyroomId partyroomId, Long penaltyId) {
    AuthContext authContext = ThreadLocalContext.getAuthContext();
    partyroomQueryService.getPartyroomById(partyroomId);

    CrewData releaserCrewForValidation = partyroomQueryService.getCrewOrThrow(partyroomId, authContext.getUserId());
    if (releaserCrewForValidation.isBelowGrade(GradeType.MODERATOR))
        throw ExceptionCreator.create(GradeException.MANAGER_GRADE_REQUIRED);

    CrewPenaltyHistoryData historyData = crewPenaltyHistoryRepository
        .findByIdAndPartyroomIdAndReleasedIsFalse(penaltyId, partyroomId)
        .orElseThrow(() -> ExceptionCreator.create(PenaltyException.PENALTY_HISTORY_NOT_FOUND));

    // [PR 9 추가] admin-applied는 admin endpoint를 통해서만 release 가능
    if (historyData.getPunisherType() == PunisherType.ADMIN)
        throw ExceptionCreator.create(PenaltyException.ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE);

    // 기존 release 진행 ...
}
```

**신규 Exception 코드 (둘 다 HTTP 403):**
- `PenaltyException.ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE`
- `PenaltyException.CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE`

**가드 평가 순서:** §3.4 (existing) `findByIdAndPartyroomIdAndReleasedIsFalse`가 먼저 → 이미 released된 row면 404(`PENALTY_HISTORY_NOT_FOUND`) 반환. punisher_type 가드는 unreleased row에서만 트리거. 즉 released admin row를 crew가 DELETE 시도해도 403이 아닌 404가 응답됨(기존 시맨틱 유지, admin-applied 정보 leak 방지).

### 4.4 PR 8 detail endpoint 갱신

`GET /api/v1/admin/partyrooms/{id}` 응답의 `recentPenalties[].punisherType`:
- PR 8: hardcoded `"CREW"` 또는 `[]` (Section 15 catch-up 참조)
- PR 9: V8 컬럼 값으로 매핑 + history row 실데이터 projection. PR 8 IT의 hardcoded 검증을 V8 컬럼 검증으로 갱신.

응답 shape 동일 (string field), 값만 바뀜 — 클라이언트 호환성 영향 0.

---

## 5. Domain Model + Service Architecture

### 5.1 Party BC — Entity / Enum / Events

**`PunisherType` (party domain enum, 신규):**
```java
package com.pfplaybackend.api.party.domain.enums;
public enum PunisherType { CREW, ADMIN }
```

**`CrewPenaltyHistoryData` 변경:**
```java
@Enumerated(EnumType.STRING)
@Column(name = "punisher_type", nullable = false)
private PunisherType punisherType;

public void releaseByAdmin(LocalDateTime now) {
    this.released = true;
    // admin-released signal: released_by_crew_id IS NULL (Q8.9(i)에서 별도 released_by_type 컬럼 미도입).
    // V1 스키마에서 released_by_crew_id는 nullable이라 추가 마이그 불필요.
    // admin 정체는 partyroom_admin_action.administrator_id 경로로 식별 (correlation: metadata.crew_penalty_history_id).
    this.releasedByCrewId = null;
    this.releaseDate = now;
}
```
기존 `release(CrewId, LocalDateTime)`은 그대로 (crew path). `BaseEntity` 수정 불필요.

**Domain events (party domain, 신규):**

PR 8의 `PartyroomTerminatedEvent` 등과 동일하게 `DomainEvent` 추상 클래스(`common.domain.event`) 상속 — `eventId`/`occurredAt`/`eventType`이 자동 설정되며 listener는 `event.getOccurredAt()`을 사용. `administratorId`를 party-domain event에 포함하는 것은 PR 8 `PartyroomTerminatedEvent`/`PartyroomSuspendedEvent` 등이 이미 잡은 선례 — admin id는 party domain에서 loose ref(integer)로 다룸.

```java
@Getter
public class AdminCrewPenalizedEvent extends DomainEvent {
    private final Long administratorId;
    private final PartyroomId partyroomId;
    private final CrewId punishedCrewId;
    private final PenaltyType penaltyType;
    private final Long crewPenaltyHistoryId;   // PERMANENT_EXPULSION일 때만 non-null, ONE_TIME은 null
    private final String reason;

    public AdminCrewPenalizedEvent(Long administratorId, PartyroomId partyroomId,
                                   CrewId punishedCrewId, PenaltyType penaltyType,
                                   Long crewPenaltyHistoryId, String reason) {
        super();
        this.administratorId = administratorId;
        this.partyroomId = partyroomId;
        this.punishedCrewId = punishedCrewId;
        this.penaltyType = penaltyType;
        this.crewPenaltyHistoryId = crewPenaltyHistoryId;
        this.reason = reason;
    }

    @Override
    public String getAggregateId() { return String.valueOf(partyroomId.getId()); }
}

@Getter
public class AdminCrewPenaltyReleasedEvent extends DomainEvent {
    private final Long administratorId;
    private final PartyroomId partyroomId;
    private final CrewId releasedCrewId;
    private final Long crewPenaltyHistoryId;

    public AdminCrewPenaltyReleasedEvent(Long administratorId, PartyroomId partyroomId,
                                         CrewId releasedCrewId, Long crewPenaltyHistoryId) {
        super();
        this.administratorId = administratorId;
        this.partyroomId = partyroomId;
        this.releasedCrewId = releasedCrewId;
        this.crewPenaltyHistoryId = crewPenaltyHistoryId;
    }

    @Override
    public String getAggregateId() { return String.valueOf(partyroomId.getId()); }
}
```

기존 `CrewPenalizedEvent` 안 건드림 (Q4=A) — 회귀 위험 0.

### 5.2 Cross-BC 의존성 (port는 변경 없음)

**중요:** `PartyroomAggregatePort` / `PartyroomAggregateAdapter`에 새 메서드 **추가하지 않음**. `PartyroomAggregateAdapter`는 PR 8 시점부터 thin CRUD pass-through(repository wrapper)이며, orchestration은 administration application service가 담당하는 것이 PR 8의 확립된 패턴. (`AdminPartyroomCommandService.terminate/suspend/restore/setDisplayFlag/updateMeta` 모두 admin service 안에서 load → mutate → save → publishEvent.)

PR 9의 `AdminCrewPenaltyCommandService`도 동일 패턴으로 다음 collaborator를 직접 주입:
- `PartyroomAggregatePort` (party domain) — partyroom/crew 조회, crew save
- `PartyroomAccessCommandService` (party application) — 기존 `expel(partyroom, crew, isPermanent)` 재사용 (PR 8 76d7b2c1의 atomic toggle 포함)
- `CrewPenaltyHistoryRepository` (party adapter) — history INSERT/조회. PR 8 `AdminPartyroomCommandService`가 `CrewRepository`를 직접 주입한 선례와 일관
- `ApplicationEventPublisher` — 신규 이벤트 publish
- `Clock` — `LocalDateTime.now(clock)`
- `AdminContext` — administratorId 추출

**ArchUnit cross-BC:** PR 8이 잡은 administration → party 단방향 가드 안에서 모두 합법. 신규 port/use-case port 도입 안 함 (PR 11에서 재검토).

### 5.3 Administration BC — Service / Controller

**`AdminCrewPenaltyCommandController`** (administration adapter):
```java
@Tag(name = "Admin Partyroom Penalty API")
@RequestMapping("/api/v1/admin/partyrooms/{partyroomId}/penalties")
@RestController
@RequiredArgsConstructor
public class AdminCrewPenaltyCommandController {
    private final AdminCrewPenaltyCommandService service;

    @PostMapping
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<AdminApplyPenaltyResponse>> apply(
            @PathVariable Long partyroomId,
            @Valid @RequestBody AdminApplyPenaltyRequest req) {
        Long penaltyId = service.apply(partyroomId, req.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(new AdminApplyPenaltyResponse(penaltyId)));
    }

    @DeleteMapping("/{penaltyId}")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<Void> release(
            @PathVariable Long partyroomId,
            @PathVariable Long penaltyId) {
        service.release(partyroomId, penaltyId);
        return ResponseEntity.noContent().build();
    }
}
```

**`AdminCrewPenaltyCommandService`** (administration application) — orchestration 담당. PR 8 `AdminPartyroomCommandService`와 동일 패턴 (load → validate → mutate → save → publish).

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminCrewPenaltyCommandService {
    private final PartyroomAggregatePort aggregatePort;
    private final PartyroomAccessCommandService partyroomAccessCommandService;
    private final CrewPenaltyHistoryRepository crewPenaltyHistoryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AdminContext adminContext;
    private final Clock clock;

    @Transactional
    public Long apply(Long partyroomId, AdminApplyPenaltyCommand cmd) {
        Long administratorId = adminContext.currentAdministratorId();
        PartyroomId pid = new PartyroomId(partyroomId);

        PartyroomData partyroom = aggregatePort.findPartyroomById(partyroomId)
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
        if (partyroom.isTerminated()) {
            throw ExceptionCreator.create(PartyroomException.ALREADY_TERMINATED);
        }

        CrewData crew = aggregatePort.findCrewById(cmd.crewId())
                .orElseThrow(() -> ExceptionCreator.create(CrewException.NOT_FOUND_ROOM));
        if (!crew.getPartyroomId().getId().equals(partyroomId)) {
            throw ExceptionCreator.create(CrewException.NOT_FOUND_ROOM);
        }

        PenaltyType partyEnum = cmd.penaltyType().toPartyEnum();
        boolean isPermanent = partyEnum == PenaltyType.PERMANENT_EXPULSION;

        // 기존 expel 재사용 — atomic toggle (PR 8 76d7b2c1) + isPermanent 시 enforceBan + saveCrew
        partyroomAccessCommandService.expel(partyroom, crew, isPermanent);

        // PERMANENT_EXPULSION만 history row를 남긴다 (기존 CrewPenaltyCommandService 동작과 대칭).
        // 이미 ban된 crew에 다시 PERMANENT를 부과해도 멱등 + 새 history row 생성 (audit 완전성).
        Long historyId = null;
        if (isPermanent) {
            CrewPenaltyHistoryData saved = crewPenaltyHistoryRepository.save(
                    CrewPenaltyHistoryData.builder()
                            .partyroomId(pid)
                            .punishedCrewId(new CrewId(crew.getId()))
                            .punisherCrewId(null)                       // admin은 crew 아님 (V1 nullable)
                            .punisherType(PunisherType.ADMIN)
                            .penaltyReason(cmd.reason())
                            .penaltyDate(LocalDateTime.now(clock))
                            .penaltyType(partyEnum)
                            .released(false)
                            .build());
            historyId = saved.getId();
        }

        eventPublisher.publishEvent(new AdminCrewPenalizedEvent(
                administratorId, pid, new CrewId(crew.getId()),
                partyEnum, historyId, cmd.reason()));

        log.info("[AdminCrewPenalty.apply] partyroomId={} crewId={} type={} historyId={} by adminId={}",
                partyroomId, crew.getId(), partyEnum, historyId, administratorId);
        return historyId;
    }

    @Transactional
    public void release(Long partyroomId, Long penaltyId) {
        Long administratorId = adminContext.currentAdministratorId();
        PartyroomId pid = new PartyroomId(partyroomId);

        // partyroom 존재 검증만 (status TERMINATED여도 release 허용 — cleanup이 종료 시점 이후 발생할 수 있음).
        aggregatePort.findPartyroomById(partyroomId)
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));

        CrewPenaltyHistoryData history = crewPenaltyHistoryRepository
                .findByIdAndPartyroomIdAndReleasedIsFalse(penaltyId, pid)
                .orElseThrow(() -> ExceptionCreator.create(PenaltyException.PENALTY_HISTORY_NOT_FOUND));

        if (history.getPunisherType() != PunisherType.ADMIN) {
            throw ExceptionCreator.create(PenaltyException.CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE);
        }

        // 1. crew의 ban 해제 — 자동 재입장 없음(release는 ban 플래그만 클리어).
        CrewData crew = aggregatePort.findCrewById(history.getPunishedCrewId().getId())
                .orElseThrow();
        crew.releaseBan();
        aggregatePort.saveCrew(crew);

        // 2. history row release 마킹 (releasedByCrewId=null, Q8.9(i))
        history.releaseByAdmin(LocalDateTime.now(clock));
        crewPenaltyHistoryRepository.save(history);

        eventPublisher.publishEvent(new AdminCrewPenaltyReleasedEvent(
                administratorId, pid, new CrewId(crew.getId()), penaltyId));

        log.info("[AdminCrewPenalty.release] partyroomId={} crewId={} penaltyId={} by adminId={}",
                partyroomId, crew.getId(), penaltyId, administratorId);
    }
}
```

서비스가 두꺼운 이유: PR 8과 동일하게 hexagonal layering 유지 — adapter는 thin CRUD, application service가 use case orchestration. (use-case port는 PR 11에서 재검토.)

**`AdminPenaltyType`** (administration adapter enum):
```java
public enum AdminPenaltyType {
    ONE_TIME_EXPULSION(PenaltyType.ONE_TIME_EXPULSION),
    PERMANENT_EXPULSION(PenaltyType.PERMANENT_EXPULSION);
    private final PenaltyType partyEnum;
    public PenaltyType toPartyEnum() { return partyEnum; }
}
```

administration BC가 party의 4종 enum 전체를 노출하지 않게 하는 anti-corruption layer 역할. `PenaltyType` import는 service/command 레이어로 한정.

### 5.4 Audit Listener (PR 8 클래스 확장)

`PartyroomAdminActionListener` (PR 8 administration BC)에 핸들러 2개 추가. PR 8 5개 기존 핸들러와 동일하게 **bare `@EventListener`** 사용 (`@Transactional(propagation=MANDATORY)` 사용 안 함 — 클래스 Javadoc에 "synchronous + same TX, NOT @TransactionalEventListener" 명시되어 있고, 5개 기존 핸들러 모두 bare이므로 일관성 유지). `clock` 주입 없이 `event.getOccurredAt()` 사용 (DomainEvent 기반 자동 설정 값) — PR 8 핸들러 5개와 동일.

```java
@EventListener
public void on(AdminCrewPenalizedEvent event) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("penalty_type", event.getPenaltyType().name());
    if (event.getCrewPenaltyHistoryId() != null) {
        meta.put("crew_penalty_history_id", event.getCrewPenaltyHistoryId());
    }
    save(PartyroomAdminActionData.of(
            event.getAdministratorId(),
            PartyroomAdminActionType.PENALIZE_CREW,
            AdminActionTargetType.CREW,
            event.getPunishedCrewId().getId(),       // target_id = 페널티 받은 crew id
            event.getPartyroomId().getId(),          // partyroom_id 컬럼 = 부모 룸 id (참조 보존)
            event.getReason(),
            JsonMetadata.of(meta),
            event.getOccurredAt()));
}

@EventListener
public void on(AdminCrewPenaltyReleasedEvent event) {
    save(PartyroomAdminActionData.of(
            event.getAdministratorId(),
            PartyroomAdminActionType.RELEASE_CREW_PENALTY,
            AdminActionTargetType.CREW,
            event.getReleasedCrewId().getId(),       // target_id = release 대상 crew id
            event.getPartyroomId().getId(),          // partyroom_id 컬럼 = 부모 룸 id
            null,                                    // release는 unstructured reason 받지 않음
            JsonMetadata.of(Map.of("crew_penalty_history_id", event.getCrewPenaltyHistoryId())),
            event.getOccurredAt()));
}
```

기존 `private save(PartyroomAdminActionData)` 헬퍼(PR 8) 재사용 — INSERT 실패 시 ERROR + rethrow → caller TX rollback (atomic).

**`AdminActionTargetType.CREW` 컨벤션:** `target_id`는 crew의 id, `partyroom_id` 컬럼은 부모 룸의 id. PR 8의 `PARTYROOM` target_type에서는 `target_id == partyroom_id`였지만, CREW target에서는 두 값이 다름 — 같은 admin_action 테이블에서 cross-target 조회 가능하도록 일관 유지.

### 5.4.1 배포 순서 — enum widening + listener handler atomicity

새 enum 값(`PENALIZE_CREW`, `RELEASE_CREW_PENALTY`, `CREW`)과 새 listener 핸들러는 같은 jar로 배포. 롤링 deploy 중 stale 인스턴스가 신규 이벤트를 받으면 핸들러 부재로 admin TX rollback. 따라서:
- §8 G1/G2/listener 변경은 **단일 PR 머지 + 단일 release** 단위로 이동.
- 신규 endpoint(controller)는 동일 jar에 포함되어 있으므로 endpoint가 라이브 = 코드가 라이브 (이벤트 발행 가능 시점 = 핸들러 가용 시점).
- pre-launch + 단일 인스턴스 환경이라 본질적 위험은 없으나 다중 인스턴스 운영 진입 시 본 deploy 원칙 유지.

**Enum 확장:**
- `PartyroomAdminActionType` += `PENALIZE_CREW`, `RELEASE_CREW_PENALTY`
- `AdminActionTargetType` += `CREW`

### 5.5 Read-side wiring

`AdminPartyroomQueryRepository(Impl)` / `AdminPartyroomQueryService` detail projection 갱신:
- `recentPenalties` projection의 SELECT 절에 `crew_penalty_history.punisher_type` 추가
- DTO 매핑 (`recentPenalties[].punisherType`)을 V8 컬럼에서 읽도록 수정
- PR 8 hardcoded `"CREW"` / `[]` 제거

PR 8 §15 (Implementation reality) 보강:
> B-2 `recentPenalties`: PR 9에서 V8 컬럼 도입 후 실데이터 projection.

### 5.6 데이터 흐름 (부과)

```
[AdminUI] POST /admin/partyrooms/{id}/penalties
  ↓
AdminCrewPenaltyCommandController     (administration adapter)
  ↓
AdminCrewPenaltyCommandService.apply  (administration application)  ← @Transactional 시작
  ├─ adminContext.currentAdministratorId()
  ├─ aggregatePort.findPartyroomById  →  validation + isTerminated 거부
  ├─ aggregatePort.findCrewById       →  validation + partyroom 멤버십 검증
  ├─ partyroomAccessCommandService.expel(partyroom, crew, isPermanent)   (party application)
  │     └─ 기존 PR 8 atomic toggle + isPermanent 시 enforceBan + saveCrew
  ├─ if (PERMANENT) crewPenaltyHistoryRepository.save(...)               (party adapter)
  │     with punisher_type=ADMIN, punisher_crew_id=null
  └─ eventPublisher.publishEvent(AdminCrewPenalizedEvent)
       ↓ @EventListener (synchronous, same TX)
PartyroomAdminActionListener.on(AdminCrewPenalizedEvent)  (administration adapter)
  └─ adminActionRepository.save(action_type=PENALIZE_CREW, target_type=CREW, ...)
  ↓
TX commit (또는 listener 실패 시 전체 롤백 — `save()` 헬퍼가 rethrow)
```

해제 흐름은 동일 구조 (admin service가 history.releaseByAdmin + crew.releaseBan 직접 수행 + listener `RELEASE_CREW_PENALTY` 핸들러).

`PartyroomAggregatePort` / `PartyroomAggregateAdapter`에는 변경 없음 — orchestration은 `AdminCrewPenaltyCommandService`가 담당.

---

## 6. Race / Concurrency Analysis

### 6.1 Admin + Crew가 같은 crew를 동시에 PERMANENT 부과

- T0: Admin TX A 시작 → `partyroomAccessCommandService.expel(crew, true)` → atomic toggle UPDATE
- T1: Crew TX B 시작 → `expel(crew, true)` → race loser면 deactivated=0 반환, idempotent path (PR 8 76d7b2c1) → `enforceBan` + saveCrew 적용
- T2: Admin TX A — history INSERT(punisher_type=ADMIN, punisher_crew_id=null)
- T3: Crew TX B — history INSERT(punisher_type=CREW, punisher_crew_id=...)
- T4: 둘 다 commit, 두 이벤트(`AdminCrewPenalizedEvent` + `CrewPenalizedEvent`) 모두 발행

**결과:**
- crew가 ban 됨 (정확) — `is_banned=true`
- `crew_penalty_history` 2 row (둘 다 unreleased 상태 + 둘 다 의미 있음 — 누가/언제 부과했는지 audit). admin이 release 시 admin-applied row 1개만 release되고, crew-applied row는 별도. 이게 의도된 시맨틱(audit 완전성).
- `partyroom_admin_action` 1 row (admin 경로만)
- `recentPenalties` 응답에 같은 crew 대상 2 row 노출 — 어드민 UI에서 자연스러움

수용 가능. 정상 시맨틱.

**역방향(admin 늦음) 동일:** crew가 먼저 ban → admin이 PERMANENT 부과 시도. expel 멱등 path 진입 + history INSERT(punisher_type=ADMIN). 결과 동일.

### 6.2 Admin + Crew가 같은 history row를 동시에 release 시도

- crew가 admin-applied (punisher_type=ADMIN) row를 release 시도 → §4.3 가드로 403 (admin path 거치도록)
- admin이 crew-applied (punisher_type=CREW) row를 release 시도 → §4.2 가드로 403
- 같은 admin-applied row에 두 admin이 동시 release → 둘 다 `findByIdAndPartyroomIdAndReleasedIsFalse` 통과(한쪽 stale) → 둘 다 `releaseByAdmin`/`save` → DB row update 멱등 (released=true). 두 admin_action audit row commit.

수용 가능. audit 중복은 rare + 노이즈 수준. PR 8 §10.2 동일 사유로 OL은 PR scope 외.

### 6.3 Listener TX 의존 — bare @EventListener

PR 8 listener 패턴 그대로 — bare `@EventListener` (synchronous, same TX). PR 9가 추가하는 핸들러도 동일 시그니처. Listener INSERT 실패 시 `save()` 헬퍼가 ERROR 로그 + rethrow → caller TX rollback (admin service의 history INSERT까지 롤백).

`MANDATORY` propagation은 사용하지 않음 — PR 8 5개 핸들러도 모두 bare이라 PR 9만 다르면 클래스 내 비대칭. 호출이 항상 `@Transactional` 컨텍스트 안에서 발생하는 invariant는 admin service 시그니처(`@Transactional` 메서드 안에서 publishEvent)로 보장.

PR 8 follow-up `76d7b2c1` 패턴(IT에서 `@Transactional` + cleanup) 동일 적용.

### 6.4 Admin release vs concurrent crew 동작

- Admin release 진행 중 crew가 voluntary exit 시도 → release `crew.releaseBan()`은 ban 플래그만 클리어, voluntary exit는 active 토글에 작용. 두 작용이 다른 컬럼이라 충돌 없음.
- Admin release 진행 중 또 다른 admin이 같은 row release 시도 → 둘 다 `findByIdAndPartyroomIdAndReleasedIsFalse` 통과(한쪽 stale) → 둘 다 `releaseByAdmin` save → DB 멱등 (released=true). 두 admin_action audit row commit. PR 8 §10.2와 동일 사유로 OL은 OOS.
- Admin release 후 crew는 자동 재입장 안 됨. 본인이 enter API 호출해야 함 (§4.2 side effect note).

---

## 7. Testing Strategy

### 7.1 단위 테스트
- `CrewPenaltyHistoryData.releaseByAdmin(now)` — released=true, releasedByCrewId=null, releaseDate 세팅
- `PunisherType` enum 변환 (Hibernate `@Enumerated(EnumType.STRING)`)
- `AdminApplyPenaltyRequest` validation — reason null/blank/길이/255 초과, crewId 누락, penaltyType=null/허용 외
- `AdminPenaltyType.toPartyEnum()` — 매핑 정확
- `AdminCrewPenaltyCommandService` (mock collaborators) — `apply` 분기 (PERMANENT vs ONE_TIME), TERMINATED 거부, partyroom-crew mismatch 거부, builder 인자 검증(`punisherType=ADMIN`, `punisherCrewId=null`), 이벤트 페이로드 검증. `release` 분기 (punisher_type=ADMIN 통과, punisher_type=CREW 거부, history release 호출, releaseBan 호출, 이벤트 발행)

### 7.2 통합 테스트 (Testcontainers MySQL)
- V8 마이그레이션 — clean DB V7 → V8 적용 → `crew_penalty_history.punisher_type` 컬럼 + DEFAULT='CREW' + NOT NULL 검증 (PR 8 G1 V7 마이그레이션 IT 패턴 참조)
- 기존 row backfill — V7 적용 후 row INSERT → V8 적용 → row.punisher_type='CREW' 자동 backfill 검증 (Flyway test fixture로 V7 종료 후 INSERT 가능, PR 8 검증 패턴 동일)
- `AdminCrewPenaltyCommandService.apply` (full IT, `@SpringBootTest`) — PERMANENT 정상(history row + ADMIN + null punisher_crew_id + AdminCrewPenalizedEvent), ONE_TIME 정상(history 없음, 이벤트만), TERMINATED 거부(409), partyroom-crew mismatch 거부(404), 이미 ban된 crew에 PERMANENT 멱등 적용(history row 새로 생성)
- `AdminCrewPenaltyCommandService.release` (full IT) — 정상(history.released=true, releasedByCrewId=null, crew.ban 해제, AdminCrewPenaltyReleasedEvent), punisher_type=CREW row → `CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE` 매핑 예외, partyroom TERMINATED 룸의 admin row release 허용
- `PartyroomAdminActionListener` end-to-end — atomic 보장 (`save()` throw 시 caller TX rollback, history INSERT까지 같이 롤백)
- `AdminPartyroomQueryService.getDetail` — recentPenalties.punisherType이 V8 컬럼 값으로 채워짐 (PR 8 hardcoded `"CREW"` / `[]` 시나리오 검증을 V8 컬럼 검증으로 갱신)

### 7.3 Web (`@WebMvcTest`)
- `AdminCrewPenaltyCommandController` POST 201/204 + body, 401/403/404/409 분기, validation 400
- `AdminCrewPenaltyCommandController` DELETE 204 + 403 (punisher_type=CREW)
- 기존 `CrewPenaltyCommandController` DELETE — punisher_type=ADMIN row → 403 (`ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE`)

### 7.4 Concurrency (`@SpringBootTest`)
- §6.1 — Admin + Crew 동시 PERMANENT 부과 → history 2 row + ban idempotent + admin_action 1 row

### 7.5 ArchUnit
- PR 8 가드 회귀 검증 (administration → party 단방향 유지)
- 새 위반 없음 (`AdminCrewPenaltyCommandController/Service`는 administration → party 방향)

### 7.6 Out of scope (test)
- Multi-instance simulation (PR 8 동일 reasoning)
- Frontend integration (별 repo)
- Bulk action (B-8 OOS)

대략 신규 테스트 ~25 (unit ~8, IT ~10, WebMvc ~5, concurrency ~2).

---

## 8. Atomic commit groupings

| 그룹 | 묶이는 변경 | 사유 |
|---|---|---|
| **G1: V8 + entity field + enum + crew path 보강** | V8 SQL + `CrewPenaltyHistoryData.punisherType` + `PunisherType` enum + `CrewPenaltyCommandService.addPenalty` builder `.punisherType(CREW)` 보강 | DDL ↔ entity 매핑 boot-or-die. crew path 보강 미포함 시 V8 default가 cover하지만 같은 commit이 명시적. 배포 순서 V8 SQL ↔ 새 jar 분리 불가 |
| **G2: 도메인 이벤트 2종 + admin enum 확장 + listener 핸들러 2개** | `AdminCrewPenalizedEvent` / `AdminCrewPenaltyReleasedEvent` (party domain) + `PartyroomAdminActionType.PENALIZE_CREW`/`RELEASE_CREW_PENALTY` + `AdminActionTargetType.CREW` + `PartyroomAdminActionListener` 핸들러 2개 | 이벤트 publish-consume 쌍이 같은 PR에 같이 들어가야 하고, enum 새 값 없이 핸들러만 들어가면 컴파일 안 되며 핸들러 없이 이벤트만 발행하면 admin TX rollback (§5.4.1). 단일 commit으로 atomic |

기타 task별 독립 commit:
- spec sync (schema.md §4.5.1 수정) — 본 design commit과 함께 이미 처리됨
- `AdminCrewPenaltyCommandController/Service` + `AdminApplyPenaltyRequest`/`Response` + `AdminPenaltyType` + `AdminApplyPenaltyCommand`
- crew DELETE 가드 + 신규 Exception code (`ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE` / `CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE`)
- read-side projection 갱신 (PR 8의 hardcoded `"CREW"` / `[]` 정리 + 관련 IT 갱신)
- 신규 IT/WebMvc/concurrency 테스트
- ArchUnit 회귀 확인

총 ~8-10 commits (G1, G2 + 6-8 task commits).

---

## 9. Risks & Mitigations

| # | 위험 | 완화 |
|---|---|---|
| 1 | V8 ALTER TABLE에서 ENUM ADD COLUMN 락 시간 | pre-launch라 데이터 거의 없음. MySQL 8.0.12+에서 `ADD COLUMN ... NOT NULL DEFAULT '...'`는 `ALGORITHM=INSTANT`(metadata-only)로 처리됨. Testcontainers IT에서 SQL 적용 검증 |
| 2 | `CrewPenaltyCommandService.addPenalty` builder에 `.punisherType(CREW)` 누락 시 V8 default로 들어가 동작은 OK이지만 명시성↓ | G1에 포함. 단위 테스트가 builder 호출 검증 |
| 3 | listener INSERT 실패 시 caller TX rollback — 정상 admin 액션이 admin_action 부재로 거부 | PR 8 §13 #1과 동일 — 의도 시맨틱(atomic). 운영 모니터링: `partyroom_admin_action` INSERT 실패 ERROR 로그를 alert 대상으로 |
| 4 | Crew DELETE 가드 누락된 path로 admin-applied 풀림 | 기존 `releaseCrewPenalty` 단일 진입점. 가드 한 줄 + WebMvc 회귀 |
| 5 | Admin + Crew 동시 PERMANENT 부과 race | §6.1 — `expel` atomic toggle (PR 8) + enforceBan 멱등. history 2 row 정상 시맨틱 (admin이 release 시 admin row만 풀림) |
| 6 | Future `released_by_type` 컬럼 필요해질 때 V8 재마이그 | pre-launch라 future 마이그(V14+)으로 처리. data 영향 0 |
| 7 | `recentPenalties.punisherType` 응답 호환성 | shape 동일 (string), 값만 변경. 클라이언트 영향 0. PR 8 IT의 hardcoded 검증을 V8 컬럼 검증으로 갱신 |
| 8 | 신규 enum 값(`PENALIZE_CREW`/`RELEASE_CREW_PENALTY`/`CREW`)과 신규 listener 핸들러의 deploy 순서 | 단일 jar 배포로 atomic 보장 (§5.4.1). 다중 인스턴스 환경 진입 시 enum widening commit + listener handler commit이 같은 release에 묶이는지 release notes에서 검증 |
| 9 | `releaseByAdmin`이 NULL 정책으로 `released_by_crew_id` 기록 → 향후 read-side에서 admin-released 표시 필요 시 | invariant 명문화 (§3 + entity Javadoc): `released_by_crew_id IS NULL ⇒ admin-released`. 보강 정보는 `partyroom_admin_action.metadata.crew_penalty_history_id` 매칭으로 회수. (Q8.9(i)) |

---

## 10. Decisions Taken (브레인스토밍 결과 9건)

1. **Q1 — 어드민 페널티 endpoint 분리** (`/api/v1/admin/partyrooms/{id}/penalties`). roadmap §10.2-A의 *재검토 조건* 발동 (V8 punisher_type='ADMIN' = "어드민이 크루 아닌 상태") + PR 8 패턴 일관 + security 분리 유지.
2. **Q2 — 어드민 가능 penalty type = `ONE_TIME_EXPULSION` + `PERMANENT_EXPULSION` 2종**. CHAT_MESSAGE_REMOVAL은 message id 컨텍스트 필요(PR 13 신고 시스템과 자연스러움), CHAT_BAN_30_SECONDS는 어드민 개입 의미 약함.
3. **Q3 — Administration BC 서비스가 orchestration 담당** (PR 8 패턴). `AdminCrewPenaltyCommandService`가 `PartyroomAggregatePort`(thin CRUD)와 `PartyroomAccessCommandService`(party application의 expel)와 `CrewPenaltyHistoryRepository`를 직접 collaborator로 사용하여 load → validate → mutate → save → publishEvent. **port에 새 메서드 추가하지 않음** — `PartyroomAggregateAdapter`는 PR 8 시점 이미 thin CRUD pass-through로 확립. Use-case port는 PR 11에서 재검토. party `CrewPenaltyCommandService`에 admin 분기 추가하지 않음 (BC 경계 유지).
4. **Q4 — 신규 도메인 이벤트 2종 (`AdminCrewPenalized*Event`)**. 기존 `CrewPenalizedEvent` 안 건드림 (회귀 위험 0). PR 8의 이벤트 종류별 분리 패턴 유지.
5. **Q5 — V8은 `crew_penalty_history`만 ALTER**, `crew_block_history` 제외. 코드 의미상 user-to-user 차단이라 admin 무관. spec §4.5.1 동기화.
6. **Q6 — Admin release endpoint 추가 + crew release 가드** (B). 권위 명확. PR 9 scope이 약간 늘지만 audit 일관성과 정책 명확성 우선.
7. **Q7 — Admin은 admin-applied만 release 가능 (α)** + `RELEASE_CREW_PENALTY` audit. 권한 비대칭 없음. PR 8의 모든 admin write action audit 패턴 유지.
8. **Q8.1~8.8 — 일괄 결정**:
   - target 권한 검증: grade hierarchy 무시, partyroom 멤버십만 검증
   - reason: required (length 1~255)
   - request DTO: 신규 `AdminApplyPenaltyRequest`, `AdminPenaltyType` enum
   - admin enum 확장: `PENALIZE_CREW`, `RELEASE_CREW_PENALTY`, target_type=CREW
   - PR 8 hardcoded `"CREW"` 정리: V8 컬럼 매핑
   - Bulk action OOS, rate limit OOS
   - 부과/해제 둘 다 이벤트 발행
9. **Q8.9 (i) — `released_by_type` 컬럼 추가하지 않음**. `released_by_crew_id`는 NULL로 두고 release 주체 식별은 `partyroom_admin_action`에 집중. read-side에서 식별 필요 발생 시 future 마이그레이션.

---

## 11. Open Items / Implementation Reality (post-build catch-up)

PR 9 구현 완료 시점에 spec과의 차이/세부 결정사항을 모은다 (PR 8 §15 패턴):

(빈 placeholder — 구현 중 발견 시 채움)

---

**다음 단계:** 본 spec이 reviewer + 사용자 승인되면 `superpowers:writing-plans`로 구현 plan 생성 (`docs/superpowers/plans/2026-04-28-admin-platform-pr9.md`).
