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
1. SecurityContext → `administratorId` (PR 5 admin principal)
2. partyroom 존재 + status ∈ {ACTIVE, SUSPENDED}. TERMINATED 거부 (이미 종료된 룸은 의미 없음 → 409)
3. punished crew 존재 + `crew.partyroomId == path partyroomId` (다른 룸 crew 차단 → 404)
4. crew.isActive 무관 (이미 inactive라도 PERMANENT는 `enforceBan` 멱등)
5. grade hierarchy 검증 **없음** (Q8.1)

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
1. partyroom 존재 (validation만)
2. penalty history 존재 + `partyroomId` 일치 + `released == false`
3. `punisher_type == ADMIN` (Q7α). crew-applied면 403 (`CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE`)

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
    this.releasedByCrewId = null;   // admin은 crew 아님 — Q8.9 (i)
    this.releaseDate = now;
}
```
기존 `release(CrewId, LocalDateTime)`은 그대로 (crew path). `BaseEntity` 수정 불필요.

**Domain events (party domain, 신규):**
```java
public record AdminCrewPenalizedEvent(
    Long administratorId,
    PartyroomId partyroomId,
    CrewId punishedCrewId,
    PenaltyType penaltyType,
    Long crewPenaltyHistoryId,   // PERMANENT_EXPULSION일 때만 non-null, ONE_TIME은 null
    String reason
) {}

public record AdminCrewPenaltyReleasedEvent(
    Long administratorId,
    PartyroomId partyroomId,
    CrewId releasedCrewId,
    Long crewPenaltyHistoryId
) {}
```

기존 `CrewPenalizedEvent` 안 건드림 (Q4=A) — 회귀 위험 0.

### 5.2 Party BC — Aggregate Port 신규 메서드

`PartyroomAggregatePort` (party domain):
```java
/**
 * 어드민의 크루 페널티 부과. expel + history INSERT(punisher_type=ADMIN, punisher_crew_id=null) +
 * AdminCrewPenalizedEvent publish를 한 TX 내에서 수행.
 *
 * @return PERMANENT_EXPULSION이면 신규 history row id, ONE_TIME_EXPULSION이면 null.
 */
Long applyAdminPenalty(Long administratorId, PartyroomId partyroomId, CrewId crewId,
                       PenaltyType penaltyType, String reason);

/**
 * 어드민의 크루 페널티 해제. history.releaseByAdmin + crew.releaseBan +
 * AdminCrewPenaltyReleasedEvent publish를 한 TX 내에서 수행.
 *
 * @throws PenaltyException punisher_type != ADMIN인 경우 (cross-cut: admin endpoint 사용 시 normally pre-validated, 다중 가드용)
 */
void releaseAdminPenalty(Long administratorId, PartyroomId partyroomId, Long penaltyId);
```

구현은 `PartyroomAggregatePortImpl` (party adapter) 내부에서:
- `partyroomQueryService.getPartyroomById` (validation)
- `aggregatePort.findCrewById` (validation, 동일 인스턴스 self-ref 가능)
- `partyroomAccessCommandService.expel(partyroom, crew, isPermanent)` (기존 PR 8 atomic toggle 재사용)
- PERMANENT면 `crewPenaltyHistoryRepository.save(...)` with `punisherType=ADMIN`, `punisherCrewId=null`
- `eventPublisher.publishEvent(new AdminCrewPenalizedEvent(...))`

`releaseAdminPenalty`는:
- `crewPenaltyHistoryRepository.findByIdAndPartyroomIdAndReleasedIsFalse` (validation)
- punisher_type 검증 (defensive)
- `crew.releaseBan()` + `aggregatePort.saveCrew(crew)`
- `historyData.releaseByAdmin(now)` + `crewPenaltyHistoryRepository.save`
- `eventPublisher.publishEvent(new AdminCrewPenaltyReleasedEvent(...))`

**Cross-BC 패턴 근거:** PR 8 §3에서 잡은 "Administration BC가 `PartyroomAggregatePort`로 직접 호출, use-case port 미도입(PR 11에서 재검토)" 동일 적용. 별 use-case port 만들지 않음.

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

**`AdminCrewPenaltyCommandService`** (administration application):
```java
@Service @RequiredArgsConstructor
public class AdminCrewPenaltyCommandService {
    private final PartyroomAggregatePort aggregatePort;
    private final AdminContext adminContext;   // PR 5/PR 8 도입 헬퍼

    @Transactional
    public Long apply(Long partyroomId, AdminApplyPenaltyCommand cmd) {
        Long administratorId = adminContext.currentAdministratorId();
        return aggregatePort.applyAdminPenalty(
            administratorId,
            new PartyroomId(partyroomId),
            new CrewId(cmd.crewId()),
            cmd.penaltyType().toPartyEnum(),
            cmd.reason());
    }

    @Transactional
    public void release(Long partyroomId, Long penaltyId) {
        Long administratorId = adminContext.currentAdministratorId();
        aggregatePort.releaseAdminPenalty(administratorId, new PartyroomId(partyroomId), penaltyId);
    }
}
```

서비스는 얇음 (TX/Auth 추출 + port 위임). 비즈니스 로직 전부 port impl.

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

`PartyroomAdminActionListener`(PR 8 administration BC)에 핸들러 2개 추가:

```java
@EventListener
@Transactional(propagation = Propagation.MANDATORY)   // 호출 TX 안에서만 실행 — atomic
public void on(AdminCrewPenalizedEvent e) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("penalty_type", e.penaltyType().name());
    if (e.crewPenaltyHistoryId() != null) {
        meta.put("crew_penalty_history_id", e.crewPenaltyHistoryId());
    }
    repo.save(PartyroomAdminActionData.of(
        e.administratorId(),
        PartyroomAdminActionType.PENALIZE_CREW,
        AdminActionTargetType.CREW,
        e.punishedCrewId().getId(),
        e.partyroomId().getId(),
        e.reason(),
        JsonMetadata.of(meta),
        LocalDateTime.now(clock)));
}

@EventListener
@Transactional(propagation = Propagation.MANDATORY)
public void on(AdminCrewPenaltyReleasedEvent e) {
    repo.save(PartyroomAdminActionData.of(
        e.administratorId(),
        PartyroomAdminActionType.RELEASE_CREW_PENALTY,
        AdminActionTargetType.CREW,
        e.releasedCrewId().getId(),
        e.partyroomId().getId(),
        null,   // reason 없음 (release는 unstructured reason 받지 않음)
        JsonMetadata.of(Map.of("crew_penalty_history_id", e.crewPenaltyHistoryId())),
        LocalDateTime.now(clock)));
}
```

PR 8의 동기 listener 패턴 그대로. atomicity = same TX. Listener 실패 = caller TX 롤백.

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
AdminCrewPenaltyCommandController     (administration)
  ↓
AdminCrewPenaltyCommandService.apply  (administration)  ← @Transactional 시작
  ↓
PartyroomAggregatePort.applyAdminPenalty  (party domain)
  ↓
PartyroomAggregatePortImpl                (party adapter)
  ├─ partyroomQueryService.getPartyroomById  (validation, status check)
  ├─ aggregatePort.findCrewById              (validation, partyroom membership check)
  ├─ partyroomAccessCommandService.expel(partyroom, crew, isPermanent)
  ├─ if (PERMANENT) crewPenaltyHistoryRepository.save(...)
  │     with punisher_type=ADMIN, punisher_crew_id=null
  └─ eventPublisher.publishEvent(AdminCrewPenalizedEvent)
       ↓ @EventListener (sync, MANDATORY)
PartyroomAdminActionListener.on(AdminCrewPenalizedEvent)  (administration)
  └─ partyroomAdminActionRepository.save(action_type=PENALIZE_CREW, ...)
  ↓
TX commit (또는 listener 실패 시 전체 롤백)
```

해제 흐름은 동일 구조 (Listener `RELEASE_CREW_PENALTY` 핸들러).

---

## 6. Race / Concurrency Analysis

### 6.1 Admin + Crew가 같은 crew를 동시에 PERMANENT 부과

- T0: Admin TX A 시작 → `partyroomAccessCommandService.expel(crew, true)` → atomic toggle UPDATE
- T1: Crew TX B 시작 → `expel(crew, true)` → 1번째가 race winner면 deactivated=0 반환, idempotent path (PR 8 76d7b2c1)
- T2: Admin TX A — history INSERT(punisher_type=ADMIN, punisher_crew_id=null)
- T3: Crew TX B — history INSERT(punisher_type=CREW, punisher_crew_id=...)
- T4: 둘 다 enforceBan 적용 (멱등) — `crew.is_banned=true`
- T5: 두 history row 모두 commit, 두 admin/crew event 모두 발행

**결과:**
- crew가 ban 됨 (정확)
- history 2 row (둘 다 의미 있음 — 누가/언제 부과했는지 기록)
- admin_action 1 row (admin의 부과만)

수용 가능. 정상 시맨틱.

### 6.2 Admin + Crew가 같은 history row를 동시에 release 시도

- crew가 admin-applied (punisher_type=ADMIN) row를 release 시도 → §4.3 가드로 403 (admin path 거치도록)
- admin이 crew-applied (punisher_type=CREW) row를 release 시도 → §4.2 가드로 403
- 같은 admin-applied row에 두 admin이 동시 release → 둘 다 `findByIdAndPartyroomIdAndReleasedIsFalse` 통과(한쪽 stale) → 둘 다 `releaseByAdmin`/`save` → DB row update 멱등 (released=true). 두 admin_action audit row commit.

수용 가능. audit 중복은 rare + 노이즈 수준. PR 8 §10.2 동일 사유로 OL은 PR scope 외.

### 6.3 Listener MANDATORY로 인한 외부 TX 없는 호출

`@Transactional(propagation = MANDATORY)`로 묶이므로 publisher가 TX 없으면 `IllegalTransactionStateException`. Port impl이 항상 `@Transactional` 컨텍스트 안에서 publish하므로 정상 path 안전. 단위 테스트는 mock publisher로 분리.

PR 8 follow-up `76d7b2c1` 패턴(IT에서 `@Transactional` + cleanup) 동일 적용.

---

## 7. Testing Strategy

### 7.1 단위 테스트
- `CrewPenaltyHistoryData.releaseByAdmin(now)` — released=true, releasedByCrewId=null, releaseDate 세팅
- `PunisherType` enum 변환 (Hibernate `@Enumerated(EnumType.STRING)`)
- `AdminApplyPenaltyRequest` validation — reason null/blank/길이/255 초과, crewId 누락, penaltyType=null/허용 외
- `AdminPenaltyType.toPartyEnum()` — 매핑 정확
- `AdminCrewPenaltyCommandService` (mock port) — administratorId 추출, port 호출 인자 검증

### 7.2 통합 테스트 (Testcontainers MySQL)
- V8 마이그레이션 — clean DB V7 → V8 적용 → `crew_penalty_history.punisher_type` 컬럼 + DEFAULT='CREW' + NOT NULL 검증
- V7까지 적용 + row INSERT → V8 적용 → row punisher_type='CREW' 자동 backfill 검증
- `PartyroomAggregatePortImpl.applyAdminPenalty` — PERMANENT 정상 (history row + ADMIN + null crew_id + 이벤트), ONE_TIME 정상 (history 없음, 이벤트만), TERMINATED 거부, partyroom-crew mismatch 거부
- `PartyroomAggregatePortImpl.releaseAdminPenalty` — 정상 (history.released=true, releasedByCrewId=null, crew.ban 해제, 이벤트), punisher_type=CREW row → 403 매핑 예외
- `PartyroomAdminActionListener` end-to-end — atomic 보장 (listener throw 시 caller rollback)
- `AdminPartyroomQueryService.getDetail` — recentPenalties.punisherType이 V8 컬럼 값 (PR 8 IT 갱신)

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
| **G2: 도메인 이벤트 2종 + port 메서드 시그니처 + impl** | `AdminCrewPenalizedEvent` / `AdminCrewPenaltyReleasedEvent` + `PartyroomAggregatePort` 메서드 2개 + `PartyroomAggregatePortImpl` 구현 | 인터페이스 ↔ impl 컴파일 의존. publish-consume 쌍 |

기타 task별 독립 commit:
- spec sync (schema.md §4.5.1 수정)
- admin enum 확장 (`PartyroomAdminActionType`/`AdminActionTargetType`) + `JsonMetadata` factory 확장 시 별도
- `AdminCrewPenaltyCommandController/Service` + DTO + AdminPenaltyType
- `PartyroomAdminActionListener` 핸들러 2개 추가
- crew DELETE 가드 + Exception code
- read-side projection 갱신 (PR 8 IT 갱신 포함)
- 신규 IT/WebMvc/concurrency 테스트
- ArchUnit 회귀 확인

총 ~10-12 commits (G1, G2 + 8-10 task commits).

---

## 9. Risks & Mitigations

| # | 위험 | 완화 |
|---|---|---|
| 1 | V8 ALTER TABLE에서 ENUM ADD COLUMN 락 시간 | pre-launch라 데이터 거의 없음. MySQL 8 `ALGORITHM=INPLACE`로 빠름. Testcontainers IT에서 검증 |
| 2 | `CrewPenaltyCommandService.addPenalty` builder에 `.punisherType(CREW)` 누락 시 V8 default로 들어가 동작은 OK이지만 명시성↓ | G1에 포함. 단위 테스트가 builder 호출 검증 |
| 3 | listener `MANDATORY`로 외부 TX 없는 publish 시 실패 | port impl이 항상 `@Transactional` 안에서 publish. 단위 테스트는 mock publisher. PR 8 76d7b2c1 패턴(IT @Transactional + cleanup) 적용 |
| 4 | Crew DELETE 가드 누락된 path로 admin-applied 풀림 | 기존 `releaseCrewPenalty` 단일 진입점. 가드 한 줄 + WebMvc 회귀 |
| 5 | Admin + Crew 동시 PERMANENT 부과 race | §6.1 — `expel` atomic toggle (PR 8) + enforceBan 멱등. history 2 row 정상 시맨틱 |
| 6 | Future `released_by_type` 컬럼 필요해질 때 V8 재마이그 | pre-launch라 V14+ 추가로 처리. data 영향 0 |
| 7 | `recentPenalties.punisherType` 응답 호환성 | shape 동일 (string), 값만 변경. 클라이언트 영향 0. PR 8 IT의 hardcoded 검증을 V8 컬럼 검증으로 갱신 |
| 8 | `PartyroomAggregatePort` 인터페이스 메서드 2개 추가 → mock 사용 테스트 stub 누락 | 컴파일 강제. mock 사용 테스트는 stub 추가 필요 |

---

## 10. Decisions Taken (브레인스토밍 결과 9건)

1. **Q1 — 어드민 페널티 endpoint 분리** (`/api/v1/admin/partyrooms/{id}/penalties`). roadmap §10.2-A의 *재검토 조건* 발동 (V8 punisher_type='ADMIN' = "어드민이 크루 아닌 상태") + PR 8 패턴 일관 + security 분리 유지.
2. **Q2 — 어드민 가능 penalty type = `ONE_TIME_EXPULSION` + `PERMANENT_EXPULSION` 2종**. CHAT_MESSAGE_REMOVAL은 message id 컨텍스트 필요(PR 13 신고 시스템과 자연스러움), CHAT_BAN_30_SECONDS는 어드민 개입 의미 약함.
3. **Q3 — Administration BC 서비스 + `PartyroomAggregatePort` 직접 호출** (PR 8 패턴). Use-case port는 PR 11에서 재검토. party `CrewPenaltyCommandService`에 admin 분기 추가하지 않음 (BC 경계 유지).
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
