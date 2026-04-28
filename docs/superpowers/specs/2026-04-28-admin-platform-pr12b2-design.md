# PR 12b2: Member 어드민 write API + Bean Validation standardize + reviewer follow-up — Design

> Brainstormed design spec. Implementation plan is generated separately via `superpowers:writing-plans`.

**Date:** 2026-04-28
**Branch:** `feature/admin-auth-iam-schema` (PR 12b1 HEAD `e6daf108` 위에 빌드)
**Roadmap row:** §9.1 PR 12 — *member 관리 API (A-1~A-4)* split (b) 중 write 부분
**Milestone:** M5 (PR 12-13, 유저 관리 + 활동 로그 + 신고 시스템) 진행
**Split rationale:** PR 12b1 (read + listener skeleton + polish)에 이어 PR 12b2 (write A-3/A-4). PR 12b1 final reviewer 권고 3건(M1 2-row INSERT non-atomicity 문서화 / M2 WebMvc 403 명시 / M3 Bean Validation 표준화)을 본 PR 시작 chunk(G1)에 흡수 — write 진입 전 baseline polish.

---

## 1. Goal

PR 12b1이 도입한 listener handler skeleton(`MemberTierChangedEvent` / `UserAccountWithdrawnEvent` 양쪽 9 handlers)에 publish source를 도입하고 Member 어드민 write API 절반(A-3 tier 변경, A-4 회원 비식별화 탈퇴)을 완성한다. 동시에 PR 12b1 reviewer 권고 3건 흡수 — A-1/A-2 controller를 Bean Validation 표준 패턴(`@Validated` + `@RequestParam` per-field constraints)으로 마이그레이션, A-1/A-2 WebMvc 403 (인증된 non-admin) 명시, listener 2-row INSERT 부분 실패 limitation을 PR 12b1 §12.6 future polish 항목에 명시 doc.

본 PR이 끝나면:
- 어드민이 Member tier 변경 가능 (`PATCH /api/v1/admin/members/{memberId}/tier`) — `MemberTierChangedEvent` publish + listener 2 row(`TIER_CHANGED` + `ADMIN_ACTED_ON`) 활성.
- 어드민이 Member 회원 탈퇴 처리 가능 (`POST /api/v1/admin/members/{memberId}/withdraw`) — 비식별화 + `UserAccountWithdrawnEvent(.., byAdministratorId)` publish + listener 2 row(`WITHDREW` + `ADMIN_ACTED_ON`) 활성.
- A-1/A-2 controller validation이 Bean Validation 표준 패턴으로 통일 — 이후 PR(13+)도 동형으로 작성.
- A-1/A-2 WebMvc 403 (`@WithMockUser(roles="USER")`) 케이스 명시.
- PR 12b1 §12.6에 listener 2-row INSERT non-atomicity limitation 추가 doc.

---

## 2. Scope

### 2.1 In Scope (PR 12b2)

1. **G1 — M3 Bean Validation 표준화 + M2 WebMvc 403 보강 (reviewer follow-up)**:
   - `AdminMemberQueryController.getList` inline `if(size>200) throw INVALID_LIST_QUERY` 패턴 → `@Validated` controller + `@Min/@Max/@Pattern` per-field constraints + `ConstraintViolationException` 핸들러 신설.
   - 비교적 cross-field 검증(`joinedFrom > joinedTo`)은 inline 보존 — Bean Validation은 단일 `@RequestParam` 단위라 cross-field 표준 패턴 부재.
   - A-1/A-2 WebMvc test에 `@WithMockUser(username="user", roles={"USER"})` 403 case 추가.
2. **A-3 endpoint** — `PATCH /api/v1/admin/members/{memberId}/tier` (`@PreAuthorize("@adminAuth.isAdmin()")`). Body: `AdminMemberTierChangeRequest { AuthorityTier targetTier }`. Response: 200 + `AdminMemberTierChangeResponse(memberId, oldTier, newTier)`. `MemberTierChangedEvent` publish (Spring Data `@DomainEvents` aggregate event 또는 명시적 `ApplicationEventPublisher.publishEvent` — 기존 codebase 패턴 일관 채택).
3. **A-4 endpoint** — `POST /api/v1/admin/members/{memberId}/withdraw` (`@PreAuthorize("@adminAuth.isAdmin()")`). Body: empty (no DTO). Response: 200 + `AdminMemberWithdrawResponse(memberId, withdrawnAt, alreadyWithdrawn)`. `UserAccountData.withdraw(byAdministratorId)` 호출 — 기존 도메인 메서드 그대로 활용 (idempotency guard + email PII erase + event 발행 모두 보유).
4. **Domain method 신규 1종**:
   - `MemberData.changeTier(AuthorityTier newTier, Long byAdministratorId)` — guard `if (this.authorityTier == newTier) throw TIER_UNCHANGED;` (TIER_UNCHANGED는 explicit 400 — 어드민이 의도적으로 같은 tier 입력 시 audit 측면 명시 피드백). domain event registration via `registerEvent(...)`.
5. **Administration BC 신규 컴포넌트**:
   - `AdminMemberTierCommandController` (`administration/adapter/in/web/`)
   - `AdminMemberWithdrawCommandController` (`administration/adapter/in/web/`)
   - `AdminMemberTierCommandService` (`administration/application/service/`) — `@Transactional` (read-write).
   - `AdminMemberWithdrawCommandService` (`administration/application/service/`) — `@Transactional`.
   - DTO: `AdminMemberTierChangeRequest`, `AdminMemberTierChangeResponse`, `AdminMemberWithdrawResponse`.
6. **AdminMemberException 확장**:
   - `INVALID_TIER_REQUEST(MBR-003, BAD_REQUEST)` — Bean Validation 자체는 `MethodArgumentNotValidException` → 400. 본 코드는 service-layer 비즈니스 검증용(예: enum 외 값을 reflection으로 우회 시 — 사실상 dead, 보존만).
   - `TIER_UNCHANGED(MBR-004, BAD_REQUEST)` — A-3 도메인 검증 결과.
7. **GlobalExceptionHandler 확장**:
   - `ConstraintViolationException` 핸들러 추가 (`@Validated` controller 클래스 레벨 검증 위반 → 400 매핑). `MethodArgumentNotValidException` 핸들러는 PR 12b1 시점 이미 존재.
8. **AbstractAdminWebMvcTest 등록**:
   - `AdminMemberTierCommandController.class` + `AdminMemberWithdrawCommandController.class` 추가.
   - `@MockBean AdminMemberTierCommandService`, `@MockBean AdminMemberWithdrawCommandService` 추가.
9. **PR 12b1 design.md §12.6 backfill (G4)**:
   - Listener 2-row INSERT non-atomicity limitation 명시 — `PartyroomCreatedEvent` listener의 OWNER + GUEST 2 row 독립 try/catch swallow는 부분 실패 시 audit row 누락 가능. 동일 패턴이 PR 12b2가 활성화한 `MemberTierChangedEvent` / `UserAccountWithdrawnEvent` 핸들러에도 적용 (TIER_CHANGED/WITHDREW + ADMIN_ACTED_ON 2 row 독립 INSERT).
   - 완화 옵션: (a) 단일 transaction wrap (single try/catch), (b) compensating retry queue, (c) outbox 패턴 — 모두 future PR.
10. **테스트** — 단위/IT/WebMvc/ArchUnit:
    - 신규: A-3/A-4 controller WebMvc + service unit + service IT.
    - 수정: A-1/A-2 controller WebMvc(@Validated 기반 400 case + 403 case 추가).
    - 수정: `MemberTierChangedEvent` listener IT 활성화(publish source 도입 후 end-to-end 검증) — 별도 IT 또는 기존 listener IT 확장.

### 2.2 Out of Scope (future PR)

| 항목 | 위치 | 사유 |
|---|---|---|
| A-3 self-protection (어드민이 자신을 demote 차단) | future | AuthorityTier(FM/AM/GT)는 Member tier로 admin role과 독립. 자기 자신 member 항목 변경은 가능하지만 admin role 자체에는 영향 없음 |
| Withdraw reason 필드 | future | DTO에 reason 추가 시 `UserAccountWithdrawnEvent` evolve 필요(4-arg) — PR 1 또 evolve보단 별 PR로 격리. 현재 audit는 admin 식별만 |
| Withdraw 후 재가입 동일 email 정책 | future | 이메일 비식별화 패턴(`withdrawn-{uid}@withdrawn.local`)이 unique 제약 보장하지만 새 가입 시 동일 raw email 허용 여부는 별 도메인 결정 |
| Listener 2-row INSERT atomic 보장 | future | M1 limitation doc 후 별 PR로 단일 transaction wrap 또는 outbox |
| Tier 변경 history 별도 테이블 | future | `user_activity_log.TIER_CHANGED` 1 row로 충분. 별 audit 테이블은 분석 요구 시 도입 |
| `partyroom_admin_action` member-action 라인 spec backfill | future doc PR | PR 12b1 §12.5에서 features.md 본문 수정으로 이동 약속 — PR 12b2 publish 활성 후 별 doc commit |
| `withdraw` reason audit log | future | reason 필드 미도입과 동시 |

---

## 3. Endpoint Specification

### 3.1 `PATCH /api/v1/admin/members/{memberId}/tier` — A-3 tier 변경

| 항목 | 값 |
|---|---|
| Auth | `@PreAuthorize("@adminAuth.isAdmin()")` |
| Cookie | `AdminAccessToken` |
| Path | `memberId` (Long, `@Min(1)` @Validated) |
| Body | `AdminMemberTierChangeRequest { @NotNull AuthorityTier targetTier }` |
| Response | `200 OK`, `ApiCommonResponse<AdminMemberTierChangeResponse>` |

**Request**:
```json
{ "targetTier": "AM" }
```

**Response**:
```json
{
  "data": {
    "memberId": 123,
    "oldTier": "GT",
    "newTier": "AM"
  },
  "meta": {...}
}
```

**Domain flow**:
1. `AdminMemberTierCommandService.changeTier(memberId, request, adminContext)`:
   - Load `MemberData` via `MemberDataRepository.findById(memberId)` — `MEMBER_NOT_FOUND` if missing.
   - `member.changeTier(request.targetTier(), adminContext.administratorId())`:
     - Domain method records `oldTier`, sets `newTier`, `registerEvent(new MemberTierChangedEvent(userAccountId, memberId, oldTier, newTier, byAdministratorId))`.
     - Guard `if (oldTier == newTier) throw TIER_UNCHANGED;`.
   - `memberDataRepository.save(member)` — Spring Data flush + `@AfterDomainEventPublication` clears events.
   - Return `AdminMemberTierChangeResponse(memberId, oldTier, newTier)`.
2. Listener `on(MemberTierChangedEvent)` (AFTER_COMMIT, async): row 2건 INSERT(`TIER_CHANGED` + `ADMIN_ACTED_ON`).

**Errors**:
| HTTP | code | 사유 |
|---|---|---|
| 400 | `MethodArgumentNotValidException` (handled) | `targetTier` null 또는 enum 외 값 |
| 400 | `TIER_UNCHANGED` (MBR-004) | 같은 tier 입력 |
| 401 | — | 어드민 미인증 |
| 403 | — | 인증된 non-admin (`@WithMockUser(roles="USER")`) |
| 404 | `MEMBER_NOT_FOUND` (MBR-001) | memberId 부재 |

**Validation**:
- `targetTier`: `@NotNull` (jakarta.validation). enum binding은 Spring Jackson 자동 — 외 값은 `MethodArgumentNotValidException` 발생.

### 3.2 `POST /api/v1/admin/members/{memberId}/withdraw` — A-4 회원 탈퇴

| 항목 | 값 |
|---|---|
| Auth | `@PreAuthorize("@adminAuth.isAdmin()")` |
| Path | `memberId` (Long, `@Min(1)`) |
| Body | empty (`@RequestBody` 없음) |
| Response | `200 OK`, `ApiCommonResponse<AdminMemberWithdrawResponse>` |

**Request**: body 없음

**Response (1차 호출)**:
```json
{
  "data": {
    "memberId": 123,
    "userAccountId": 456,
    "withdrawnAt": "2026-04-28T12:00:00Z",
    "alreadyWithdrawn": false
  },
  "meta": {...}
}
```

**Response (재호출 — idempotent)**:
```json
{
  "data": {
    "memberId": 123,
    "userAccountId": 456,
    "withdrawnAt": "2026-04-28T12:00:00Z",
    "alreadyWithdrawn": true
  },
  "meta": {...}
}
```

**Domain flow**:
1. `AdminMemberWithdrawCommandService.withdraw(memberId, adminContext)`:
   - Load `MemberData.findById(memberId)` — `MEMBER_NOT_FOUND` if missing.
   - Load `UserAccountData.findById(member.getUserAccountId())` — schema FK 보장하므로 정상 시 항상 존재(`@Column(nullable=false)`). 부재 시 도메인 무결성 결함이라 별 처리 불필요(자연스럽게 NPE → 500, audit 가시).
   - `boolean wasAlreadyWithdrawn = userAccount.isWithdrawn();`
   - `userAccount.withdraw(adminContext.administratorId())`:
     - 도메인 메서드 (PR 12b1 G2에서 `byAdministratorId` evolve) — idempotency guard `if (isWithdrawn()) return;`, email PII 비식별화(`withdrawn-{uid}@withdrawn.local`), `lastLoginAt` 미변경, `registerEvent(new UserAccountWithdrawnEvent(...))`.
   - `userAccountDataRepository.save(userAccount)` — flush + event publish.
   - Return `AdminMemberWithdrawResponse(memberId, userAccountId, withdrawnAt, wasAlreadyWithdrawn)`.
2. Listener `on(UserAccountWithdrawnEvent)`: row 2건 INSERT(`WITHDREW` + `ADMIN_ACTED_ON`).
   - **재호출 시**: idempotent — domain method가 early return하므로 `registerEvent`가 호출되지 않음. listener row 1세트만 (1차 호출 분).

**Errors**:
| HTTP | code | 사유 |
|---|---|---|
| 401 | — | 어드민 미인증 |
| 403 | — | 인증된 non-admin |
| 404 | `MEMBER_NOT_FOUND` | memberId 부재 |

**Idempotency**:
- 1차 호출: `withdrawnAt = now`, `email = withdrawn-{uid}@withdrawn.local`, event publish.
- N차 호출: `alreadyWithdrawn=true` 응답, state 변경 0, event publish 0.
- 200 status는 양쪽 동일. 클라이언트는 `alreadyWithdrawn` flag로 토스트 분기 가능.

---

## 4. G1 — M3 Bean Validation Standardize + M2 WebMvc 403 (Reviewer Follow-up)

### 4.1 M3 — `AdminMemberQueryController.getList` Bean Validation 마이그레이션

**현재 (PR 12b1 G4)**:
```java
@GetMapping
public ResponseEntity<...> getList(
    @RequestParam(required = false) String email,
    @RequestParam(required = false) AuthorityTier tier,
    @RequestParam(name = "joined_from", required = false) @DateTimeFormat(...) LocalDate joinedFrom,
    @RequestParam(name = "joined_to", required = false) @DateTimeFormat(...) LocalDate joinedTo,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "50") int size,
    @RequestParam(defaultValue = AdminMemberListQuery.SORT_CREATED_AT_DESC) String sort
) {
    if (size < 1 || size > MAX_PAGE_SIZE) throw ExceptionCreator.create(INVALID_LIST_QUERY);
    if (page < 0) throw ExceptionCreator.create(INVALID_LIST_QUERY);
    if (joinedFrom != null && joinedTo != null && joinedFrom.isAfter(joinedTo))
        throw ExceptionCreator.create(INVALID_LIST_QUERY);
    if (!isValidSort(sort)) throw ExceptionCreator.create(INVALID_LIST_QUERY);
    // ...
}
```

**PR 12b2 G1 후**:
```java
@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
@Validated  // ← 클래스 레벨 — @Min/@Max 등 @RequestParam 검증 활성화
public class AdminMemberQueryController {
    private static final String SORT_PATTERN = "created_at_desc|created_at_asc|last_activity_desc";

    @GetMapping
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<Page<AdminMemberSummaryResponse>>> getList(
        @RequestParam(required = false) @Size(max = 255) String email,
        @RequestParam(required = false) AuthorityTier tier,
        @RequestParam(name = "joined_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
        @RequestParam(name = "joined_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
        @RequestParam(defaultValue = AdminMemberListQuery.SORT_CREATED_AT_DESC) @Pattern(regexp = SORT_PATTERN) String sort
    ) {
        // cross-field check (Bean Validation cannot express @RequestParam cross-field)
        if (joinedFrom != null && joinedTo != null && joinedFrom.isAfter(joinedTo)) {
            throw ExceptionCreator.create(AdminMemberException.INVALID_LIST_QUERY);
        }
        // ... delegate to service
    }
}
```

**효과**:
- size cap, page non-negative, sort enum-like → Bean Validation `ConstraintViolationException` → `GlobalExceptionHandler` 400 매핑.
- 비교적 cross-field 검증만 inline 보존(`INVALID_LIST_QUERY` 사용).
- `INVALID_LIST_QUERY` enum entry는 보존(cross-field 검증용).

### 4.2 GlobalExceptionHandler 확장

**현재** (PR 12b1 시점):
- `MethodArgumentNotValidException` → 400 ✓ (`@Valid @RequestBody` DTO 검증 위반).
- `ConstraintViolationException` 핸들러 부재.

**PR 12b2 G1 추가**:
```java
@ExceptionHandler(ConstraintViolationException.class)
public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
    String detail = e.getConstraintViolations().stream()
            .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
            .collect(Collectors.joining("; "));
    log.error("Constraint violation: {}", detail);
    return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiErrorResponse.of(HttpStatus.BAD_REQUEST, null, detail));
}
```

`MethodArgumentNotValidException`(존재) + `ConstraintViolationException`(신규) 양쪽 → 400. `@Validated` 클래스 + `@Valid` 메서드 양쪽 패턴 cover.

### 4.3 M2 — A-1/A-2 WebMvc 403 case 명시

**현재 (PR 12b1)**: A-1/A-2 WebMvc test에 401 anonymous + 200 admin happy-path만. 403(인증된 non-admin)은 implicit cover라 가정.

**PR 12b2 G1 후**: A-1/A-2 controller test에 다음 추가:
```java
@Test
@DisplayName("A-1 GET /admin/members — non-admin user → 403")
@WithMockUser(username = "user", roles = {"USER"})
void list_authenticatedNonAdmin_returns403() throws Exception {
    mockMvc.perform(get("/api/v1/admin/members"))
            .andExpect(status().isForbidden());
}

@Test
@DisplayName("A-2 GET /admin/members/{id} — non-admin user → 403")
@WithMockUser(username = "user", roles = {"USER"})
void detail_authenticatedNonAdmin_returns403() throws Exception {
    mockMvc.perform(get("/api/v1/admin/members/{id}", 1L))
            .andExpect(status().isForbidden());
}
```

A-3/A-4 신규 WebMvc test도 처음부터 401 + 403 + 200 + 4xx case 모두 cover.

---

## 5. Domain Method — `MemberData.changeTier(...)`

**파일:** `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/MemberData.java`

```java
public void changeTier(AuthorityTier newTier, Long byAdministratorId) {
    if (this.authorityTier == newTier) {
        throw ExceptionCreator.create(AdminMemberException.TIER_UNCHANGED);
    }
    AuthorityTier oldTier = this.authorityTier;
    this.authorityTier = newTier;
    registerEvent(new MemberTierChangedEvent(
            this.userAccountId, this.memberId, oldTier, newTier, byAdministratorId));
}
```

**미세 결정**:
- **Domain throws AdminMemberException**: domain 레이어가 administration BC의 exception을 throw — DDD bounded context 측면 약간 어색하지만 codebase는 `domain/exception/`이 administration 모듈 안에 있음(메모리 ground truth). Member entity는 user 모듈인데 administration BC의 예외를 던지는 cross-module reference 발생. 우회: `user.domain.exception` 패키지에 `MemberException.TIER_UNCHANGED` 신설(user 모듈 예외) 후 GlobalExceptionHandler가 어느 쪽이든 cover. 또는 service layer에서 검증.
  - **결정**: service layer에서 검증으로 단순화. domain method는 oldTier 캡처 + setter + registerEvent만 수행. service가 `if (member.getAuthorityTier() == request.targetTier()) throw TIER_UNCHANGED;` 선검사. 이는 코드베이스의 `AdminMemberException`을 administration application service에서 사용 — 자연스러움.
- **Re-decision**:
```java
// MemberData (user module) — pure mutation + event registration
public void changeTier(AuthorityTier newTier, Long byAdministratorId) {
    AuthorityTier oldTier = this.authorityTier;
    this.authorityTier = newTier;
    registerEvent(new MemberTierChangedEvent(
            this.userAccountId, this.memberId, oldTier, newTier, byAdministratorId));
}

// AdminMemberTierCommandService (administration module) — guard + delegate
@Transactional
public AdminMemberTierChangeResponse changeTier(Long memberId, AdminMemberTierChangeRequest req, AdminContext ctx) {
    MemberData member = memberDataRepository.findById(memberId)
            .orElseThrow(() -> ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND));
    if (member.getAuthorityTier() == req.targetTier()) {
        throw ExceptionCreator.create(AdminMemberException.TIER_UNCHANGED);
    }
    AuthorityTier oldTier = member.getAuthorityTier();
    member.changeTier(req.targetTier(), ctx.administratorId());
    memberDataRepository.save(member);
    return new AdminMemberTierChangeResponse(memberId, oldTier, req.targetTier());
}
```
이 형태가 cross-module 일관 + 테스트 용이.

---

## 6. AdminContext / `byAdministratorId` 획득 패턴

기존 `AdminCrewPenaltyCommandService` 등의 PR 8/9 admin write 엔드포인트가 사용하는 패턴 그대로 follow. 정확한 bean/injection 형태(`AdminContext` 필드, `@AuthenticationPrincipal AdminPrincipal`, 또는 `SecurityContextHolder`)는 implementer가 선례 controller 1개를 read한 뒤 적용. 신규 패턴 도입 금지.

---

## 7. Event Publish Mechanism

`MemberData` / `UserAccountData`는 `BaseEntity`의 `registerEvent()` 패턴 — Spring Data `@DomainEvents` (entity의 `Collection<Object> domainEvents()` 메서드를 Spring Data가 `save()` 시점에 publish + `@AfterDomainEventPublication`으로 clear).

PR 12b1 G2 listener 등록은 `@TransactionalEventListener(phase = AFTER_COMMIT) + @Async(UAL_EXECUTOR_BEAN)` — 즉 `save()` → publish → AFTER_COMMIT phase → async dispatch → listener INSERT.

**미세 결정**:
- **publish 시점**: domain method가 `registerEvent` 호출. service의 `repository.save(member)` 호출 후 transaction commit 시점에 listener 발화.
- **idempotent withdraw 재호출**: domain method early return → `registerEvent` 미호출 → listener 발화 0 → `alreadyWithdrawn=true` 응답에서 audit row 추가 안 됨(올바른 idempotent 의도).

---

## 8. Testing Strategy

### 8.1 단위 테스트 (신규)

- `MemberDataChangeTierTest` — `member.changeTier()` 도메인 메서드: oldTier → newTier 변경 + event 1건 registered (5 필드 정확 검증).
- `AdminMemberTierCommandServiceTest` — mock `MemberDataRepository` + `AdminContext`:
  - happy: 200 + response.
  - `MEMBER_NOT_FOUND` → 404.
  - `TIER_UNCHANGED` (현재 tier == request tier) → 400.
- `AdminMemberWithdrawCommandServiceTest` — mock `MemberDataRepository` + `UserAccountDataRepository` + `AdminContext`:
  - 1차 호출: 200, `alreadyWithdrawn=false`, `userAccount.withdraw(...)` 1회 호출 검증.
  - 2차 호출 (이미 `withdrawnAt != null`): 200, `alreadyWithdrawn=true`, `withdraw` 호출은 1회(idempotent return하므로 state mutation 없음 — Mockito spy로 호출 수 검증).
  - `MEMBER_NOT_FOUND` → 404.

### 8.2 IT (Testcontainers MySQL, 신규)

- `AdminMemberTierCommandServiceIT extends AbstractIntegrationTest`:
  - SEED: super-admin V5 시드는 보존, 별 admin user + member SEED. Scoped DELETE cleanup (`g4it.local` 도메인 격리).
  - happy: tier 변경 + DB 반영 검증 + AFTER_COMMIT listener row 2건(`TIER_CHANGED` + `ADMIN_ACTED_ON`) Awaitility로 대기 후 검증.
  - `TIER_UNCHANGED` → 400.
  - `MEMBER_NOT_FOUND` → 404.
- `AdminMemberWithdrawCommandServiceIT extends AbstractIntegrationTest`:
  - happy: withdraw 호출 → `withdrawnAt` set + email PII erase 검증 + listener row 2건(`WITHDREW` + `ADMIN_ACTED_ON`) 검증.
  - 재호출 (idempotent): 200 + `alreadyWithdrawn=true` + listener row count 변화 없음 (1차 호출 분만 — 2건 그대로).
  - `MEMBER_NOT_FOUND` → 404.
- `lastLoginAt` 미변경 검증(spec roadmap §11.2.2 compliance assertion).

### 8.3 WebMvc (신규 + 수정)

- `AdminMemberTierCommandControllerTest` (신규):
  - 200 admin happy + 400 invalid body(`targetTier=null`, enum 외 값) + 400 TIER_UNCHANGED + 401 anonymous + 403 non-admin role + 404 not-found.
- `AdminMemberWithdrawCommandControllerTest` (신규):
  - 200 admin happy + 401 + 403 + 404.
- `AdminMemberQueryControllerTest` (수정):
  - A-1/A-2 403 case 추가.
  - A-1 400 cases는 Bean Validation 기반(`size=0`, `size=999`, `page=-1`, `sort=invalid` → `ConstraintViolationException` 응답 형태) 검증으로 변경.

### 8.4 ArchUnit

기존 `CrossContextDependencyTest`의 listener annotation rule(`@TransactionalEventListener` + `@Async`)이 이미 9 handlers cover (자동). 신규 publish/save 패턴은 service-layer라 ArchUnit 영향 0.

`user.domain.event` → administration 의존 가드(PR 12a)는 신규 endpoint도 자동 cover.

### 8.5 Out of scope (future PR)
- Race / concurrency 시나리오(동시 두 어드민이 같은 member 변경) — DB-level row lock + 마지막 write 우선. 명시적 race test는 future.
- Listener 부분 실패 시 retry/compensation 검증 — M1 limitation 상속.

대략 신규 테스트 ~22 (unit ~7, IT ~6, WebMvc ~9 — 신규 controller 2개 × ~5 case + A-1/A-2 patch 4 case + listener IT 활성화 ~4).

---

## 9. Atomic Commit Groupings

| Group | 묶음 | 사유 |
|---|---|---|
| **G1: Bean Validation 표준화 + WebMvc 403 (M3 + M2)** | A-1/A-2 controller `@Validated` 마이그레이션 + GlobalExceptionHandler `ConstraintViolationException` 핸들러 + A-1/A-2 WebMvc 403 case + 기존 400 case Bean Validation 기반 갱신 | reviewer follow-up 흡수 — write API 시작 전 baseline 표준화. PR 12b1 §12.6 future polish 1건 해소(M3). |
| **G2: A-3 PATCH tier 변경** | `MemberData.changeTier()` domain method + `MemberDataRepository`(없으면 신설) + `AdminMemberTierCommandService` + `AdminMemberTierCommandController` + DTO 2종 + `AdminMemberException` 코드 2종 추가 + `AbstractAdminWebMvcTest` 등록 + WebMvc + IT + listener end-to-end IT 활성 | endpoint 단위 PR 8/9/12b1 패턴. publish-consume pair 활성. |
| **G3: A-4 POST withdraw** | `AdminMemberWithdrawCommandService` + `AdminMemberWithdrawCommandController` + DTO 1종 + `AbstractAdminWebMvcTest` 등록 + WebMvc + IT(happy + idempotent + lastLoginAt 미변경) + listener end-to-end IT 활성 | endpoint 단위. domain method `withdraw(byAdministratorId)`는 PR 12b1 G2에서 이미 evolve됨. |
| **G4: spec catch-up + M1 limitation doc** | docs/superpowers/specs/PR 12b2 design.md §12 backfill (chunk SHAs + deviations) + PR 12b1 design.md §12.6에 listener 2-row INSERT non-atomicity limitation 추가 + features.md A-3/A-4 본문 정정(partyroom_admin_action mismatch — PR 12b1 §12.5 약속 이행) | M1 흡수 + PR 12b1 §12.5 약속 이행 + 누적 deviations §12 catch-up. PR 8/PR 9/PR 12a/PR 12b1 동형 패턴. |

총 4 chunks (PR 12b1 5 chunks와 비교해 더 좁음 — 단일 entity 2 endpoints + reviewer follow-up).

`G4.1` SHA backfill follow-up commit (PR 12a/12b1 패턴) — G4 commit 직후 chunk SHAs를 §12에 채워 별 commit.

---

## 10. Risks & Mitigations

| # | 위험 | 완화 |
|---|---|---|
| 1 | `MemberData.changeTier`가 user 모듈 entity인데 service에서 `AdminMemberException` throw — cross-module dependency | service-layer에서 guard 검증 후 domain method는 mutation + event 한정. cross-module reference 회피 |
| 2 | Spring Data `@DomainEvents` publish 시점이 transaction 외 — listener AFTER_COMMIT가 expected | save 직후 commit 직전 publish 큐, AFTER_COMMIT phase로 listener 발화 — Spring Data 표준 동작 |
| 3 | A-4 idempotent 재호출의 `alreadyWithdrawn=true` 응답 타이밍 — 1차 호출 listener async 미완료 시점에 재호출 시 audit row가 insert 안 된 상태 | listener IT는 Awaitility `await().until(() -> repo.count() == 2)` 패턴. 재호출 idempotency assertion은 1차의 audit row가 이미 commit된 상태 가정(`@Transactional` 분리). API 호출자 입장에서 `alreadyWithdrawn` flag만 정확하면 됨 |
| 4 | `TIER_UNCHANGED` 400 vs 200 idempotent — 사용자 메모리 "YAGNI vs 실제 고통" 측면 적용 | 400 채택 — 어드민이 실수로 같은 tier 입력 시 명시 피드백이 의도. withdraw는 본질적으로 자연스러운 idempotent 의미(이미 탈퇴된 회원 다시 탈퇴 요청 = no-op)이지만 tier 변경은 "변경하려 했는데 변화 없음" = 실수일 가능성 높음 |
| 5 | A-3/A-4 동시 두 어드민 호출 race | DB row lock + 마지막 write win — MVP scope에서 acceptable. Optimistic lock(version 컬럼) 추가는 future |
| 6 | `MemberDataRepository` 부재 가능성 — current codebase grep 안 됨 | implementer가 G2 시작 시 확인 후 없으면 `JpaRepository<MemberData, Long>` 신설(PR 8/12b1 패턴) |
| 7 | A-1 controller `@Validated` 마이그레이션 후 IT 회귀 — 기존 `AdminMemberQueryServiceIT`는 service-layer test라 영향 없음. WebMvc test는 400 응답 형태 변경 | WebMvc test 수정 — 기존 inline `INVALID_LIST_QUERY`(MBR-002) 응답 형태에서 ConstraintViolationException 응답으로 단언 변경. response detail 메시지 형태 변경 — IT가 정확한 메시지를 단언하지 않도록 단언 완화(`status().isBadRequest()`만 검증) |
| 8 | A-1/A-2 controller 변경이 기존 400 case test와 충돌 | G1 chunk 안에서 controller + WebMvc test 동시 갱신 — 단일 commit. spec features.md A-1/A-2 응답 spec 영향 0(http status 400 동일) |
| 9 | UserAccountWithdrawnEvent listener metadata `by_administrator_id` 필드명 일관성 | PR 12b1 G2에서 metadata key `by_administrator_id` 확정. 본 PR은 활성화만 |

---

## 11. Decisions Taken (브레인스토밍 결과)

1. **G1 시작점 (메모리 사용자 결정 "B + 나")**: M3 Bean Validation 표준화 + M2 WebMvc 403 보강을 본 PR 시작 chunk에 흡수. A-3/A-4는 표준화된 controller 패턴으로 처음부터 작성.
2. **`@Validated` controller class + `@Min/@Max/@Pattern` per-`@RequestParam` 패턴 채택**: cross-field 검증(`joinedFrom > joinedTo`)은 Bean Validation 표준 부재라 inline 보존 — `INVALID_LIST_QUERY` enum entry 보존 정당화.
3. **`ConstraintViolationException` 핸들러 추가**: `MethodArgumentNotValidException`(`@Valid @RequestBody`) + `ConstraintViolationException`(`@Validated @RequestParam`) 양쪽 → 400. GlobalExceptionHandler 1건 추가.
4. **A-3 TIER_UNCHANGED 정책 (400)**: idempotent silent 200 대신 explicit 400. 어드민이 같은 tier로 변경 시도는 실수일 확률 높고, audit-friendly 명시 피드백이 의도.
5. **A-3 service-layer guard**: domain method `changeTier(newTier, byAdministratorId)`는 pure mutation + registerEvent. TIER_UNCHANGED guard는 service에서. cross-module dependency 회피.
6. **A-4 idempotent 200 + `alreadyWithdrawn` flag**: 본질적 idempotent (이미 탈퇴 = no-op). 200 status로 통일. flag로 client-side 분기.
7. **A-4 reason 필드 미도입**: `UserAccountWithdrawnEvent` 4-arg evolve 회피. PR 1 또 evolve보다 별 PR로 격리.
8. **A-4 self-protection 미도입**: AuthorityTier(FM/AM/GT)는 Member tier로 admin role과 독립. 어드민 자기 member 항목 탈퇴는 가능 — admin role 자체에 영향 없음.
9. **`MemberTierChangedEvent` / `UserAccountWithdrawnEvent` listener는 PR 12b1 G2에서 이미 wired**: 본 PR은 publish source 도입만 — listener 코드 변경 0. listener IT 활성(end-to-end Awaitility 검증)은 신규.
10. **M1 (2-row INSERT non-atomicity)는 PR 12b1 §12.6 future polish doc backfill**: PR 12b2에서 listener 코드 변경 없으므로 limitation 흡수는 doc 작업만. atomic transaction wrap은 future PR.
11. **`AdminMemberException.INVALID_LIST_QUERY` (MBR-002) 보존**: cross-field 검증용 — Bean Validation 마이그레이션 후에도 사용처 1건(joined_from > joined_to). dead code 아님.
12. **Empty-body POST `/withdraw`**: DTO 미도입 — reason 필드 OOS와 동시. body 자체 없는 POST는 RESTful 컨벤션 약함이라 future에 reason DTO 추가 시 자연스러운 evolve.

---

## 12. Open Items / Implementation Reality (post-build catch-up)

PR 12b1 §12 패턴 follow.

### 12.1 G1 — Bean Validation 표준화 + WebMvc 403 (Reviewer Follow-up M3 + M2)

- **G1 commit `5a1e36b2`**: `AdminMemberQueryController`에 `@Validated` 클래스 어노테이션 + `@Min(0) page`, `@Min(1) @Max(MAX_PAGE_SIZE) size`, `@Pattern(SORT_PATTERN) sort`, `@Size(max=255) email`. inline `if (size > 200)` / `if (page < 0)` / `if (!isValidSort(sort))` 3건 폐기. cross-field `joinedFrom > joinedTo`만 inline 보존(`INVALID_LIST_QUERY` 사용처 1건 잔존). 미사용 `isValidSort` private 헬퍼 제거.
- **GlobalExceptionHandler 확장**: `ConstraintViolationException → 400` 핸들러 신설 — `@Validated @RequestParam` per-field 위반 cover. `MethodArgumentNotValidException` 핸들러는 PR 12b1 시점부터 존재(`@Valid @RequestBody` cover).
- **A-1/A-2 WebMvc 403 case 명시 보강**: `@WithMockUser(username="user", roles={"USER"})` fixture로 인증된 non-admin 케이스 2건(detail/list) 추가.

### 12.2 G2 — A-3 PATCH /admin/members/{id}/tier

- **G2 commit `d425285c`** (11 files):
  - `MemberData.changeTier(AuthorityTier, Long)` 도메인 메서드 — pure mutation + `registerEvent(MemberTierChangedEvent)`. service-layer guard로 cross-module exception throw 회피.
  - `AdminMemberTierCommandService` — `MemberRepository.findById` → service-layer `if (member.getAuthorityTier() == request.targetTier()) throw TIER_UNCHANGED;` guard → `member.changeTier(...)` → `repository.save` → **ADR-004 hybrid publish**: `member.pollDomainEvents().forEach(eventPublisher::publishEvent)`.
  - `AdminMemberTierCommandController` PATCH endpoint — `@PreAuthorize("@adminAuth.isAdmin()")` + `@Valid @RequestBody AdminMemberTierChangeRequest`.
  - DTO 2종 record: `AdminMemberTierChangeRequest(@NotNull AuthorityTier targetTier)`, `AdminMemberTierChangeResponse(memberId, oldTier, newTier)`.
  - `AdminMemberException.TIER_UNCHANGED("MBR-003", BAD_REQUEST)` 추가.
  - `AbstractAdminWebMvcTest` controller + `@MockBean` service 등록.
  - WebMvc 7 case + service unit 3 case + IT 3 case (Awaitility 5s — TIER_CHANGED + ADMIN_ACTED_ON 2 row order ADMIN_ACTED_ON 먼저).

### 12.3 G3 — A-4 POST /admin/members/{id}/withdraw

- **G3 commit `38fbf941`** (7 files):
  - `AdminMemberWithdrawCommandService` — `MemberRepository.findById` → `UserAccountRepository.findById(new UserId(...))` → **service-layer pre-check**: `if (userAccount.isWithdrawn()) return Response(.., alreadyWithdrawn=true)` (idempotent — domain method 호출 skip → event publish 0 → audit row 추가 0). 1차 호출만 `userAccount.withdraw(byAdministratorId)` → `save` → `pollDomainEvents → publishEvent`.
  - `AdminMemberWithdrawCommandController` POST endpoint, body 없음. AdminContext 주입은 service-layer 위임(controller는 pure).
  - DTO record: `AdminMemberWithdrawResponse(memberId, userAccountId, withdrawnAt, alreadyWithdrawn)`.
  - WebMvc 5 case + service unit 3 case + IT 3 case.
  - IT happy: `email` PII erase(`withdrawn-{uid}@withdrawn.local`) + `lastLoginAt` 미변경(roadmap §11.2.2 — `isCloseTo(seedLastLoginAt, within(1, MILLIS))` tolerance — MySQL DATETIME(6) round-trip 보정) + listener 2 row.
  - IT idempotent: 2차 호출 alreadyWithdrawn=true + listener row count 그대로(2건 — 1차 호출 분만).

### 12.4 G4 — spec §12 catch-up + M1 limitation + features.md 정정

- **G4 commit (본 commit, SHA은 G4.1에서 backfill)**:
  - PR 12b2 design.md §12 backfill (본 섹션).
  - PR 12b1 design.md §12.6에 listener 2-row INSERT non-atomicity limitation 명시 + 완화 옵션 (a/b/c) future PR 분리. 동시에 PR 12b2가 흡수한 reviewer 권고 M2(WebMvc 403) + listener 활성화 항목을 ✅ 완료 표시.
  - features.md A-3/A-4 본문을 PR 12b2 ground truth로 정정 — `partyroom_admin_action` 1건 기록 라인(A-3) → `user_activity_log` ADMIN_ACTED_ON 단독 통합으로 교체. A-4 `last_login_at = NULL` / 풍부한 profile 익명화 라인 → 최소 비식별화(email + withdrawnAt) + `lastLoginAt` 보존 + profile 익명화는 future PR로 명시.

### 12.5 Deviations / ground-truth 정정 (implementer 발견)

- **Repository 명칭**: spec 초안에 `MemberDataRepository` / `UserAccountDataRepository`로 명시했으나 ground-truth는 `MemberRepository` / `UserAccountRepository`(extends `JpaRepository<MemberData, Long>` 및 `JpaRepository<UserAccountData, UserId>`).
- **AdminContext API**: spec/plan에 `adminContext.administratorId()`로 가정했으나 실제는 `adminContext.currentAdministratorId()`. `currentUserId()` / `currentAdministratorId()` 두 메서드 보유.
- **DTO 패키지**: PR 12b2 새 DTO는 `adapter/in/web/dto/` (PR 12b1 read DTO와 동일 위치, 일관성). PR 8 penalty의 `application/dto/command/` + `adapter/in/web/payload/request/` 분리 패턴은 follow하지 않음 — spec의 cleaner choice.
- **Domain event publish 메커니즘**: spec/plan이 "Spring Data `@DomainEvents` 자동 publish"라 가정했으나 ground-truth는 ADR-004 hybrid — `BaseEntity`의 `registerEvent(...)` + `pollDomainEvents()` 명시 polling, application service가 `eventPublisher.publishEvent(...)`로 직접 dispatch. `@DomainEvents`/`@AfterDomainEventPublication` 어노테이션 미사용. PR 12b2 service 2종(`AdminMemberTierCommandService`, `AdminMemberWithdrawCommandService`)은 `pollDomainEvents().forEach(eventPublisher::publishEvent)` 패턴 채택.
- **`UserAccountRepository` PK 타입**: `JpaRepository<UserAccountData, UserId>` — `@EmbeddedId UserId`이라 `findById(new UserId(longUid))` 호출. `member.getUserAccountId()` (Long)을 `UserId` VO로 wrap 필요.
- **`UserActivityLogData.getEventType()` 반환 타입**: spec 가정과 달리 `String` (enum.name() 저장 형태). IT 단언은 `assertThat(logs.get(0).getEventType()).isEqualTo(UserActivityEventType.ADMIN_ACTED_ON.name())`.
- **`ApiErrorResponse` JSON shape**: flat record `{status, errorCode, message}`. WebMvc test JSON path는 `$.errorCode` (NOT `$.error.code` envelope).
- **MySQL DATETIME(6) round-trip**: lastLoginAt nano 값이 MySQL 저장 → 재로드 시 micro로 truncate/round, 1µs off-by-one 발생 가능. assertion은 `isCloseTo(within(1, MILLIS))` tolerance 채택.

### 12.6 Future polish 잔존

- Listener 2-row INSERT atomic wrap (M1 — PR 12b1 §12.6에 흡수, 별 PR).
- Withdraw reason DTO 도입 + `UserAccountWithdrawnEvent` 4-arg evolve.
- Re-registration 동일 email 정책.
- Tier 변경 history 별도 테이블(분석 요구 시).
- Optimistic lock(version 컬럼) — 동시 어드민 race 방지.
- Member profile 익명화(`profileData.nickname = "탈퇴한 회원"` / introduction NULL / avatar 리셋) — features.md A-4 원안 항목, MVP scope에서 보류.
- AuthorityTier 변경 시 self-protection (admin이 자신을 demote 차단) — Member tier(FM/AM/GT)와 admin role 독립이라 현재 미적용. 정책 명문화 필요 시 추가.
- A-3 service의 service-layer guard 대신 도메인-level guard로 재배치 (cross-module exception 우회 방안 더 elegant — `user.domain.exception.MemberException.TIER_UNCHANGED` 신설 검토).

---

**다음 단계:** 본 spec이 reviewer + 사용자 승인되면 `superpowers:writing-plans`로 구현 plan 생성 (`docs/superpowers/plans/2026-04-28-admin-platform-pr12b2.md`).
