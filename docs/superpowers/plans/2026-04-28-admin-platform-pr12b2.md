# PR 12b2: Member 어드민 write API + Bean Validation standardize + reviewer follow-up — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR 12b1 listener handler skeleton(`MemberTierChangedEvent` / `UserAccountWithdrawnEvent`)에 publish source 도입 + Member 어드민 write API 2개(A-3 tier 변경, A-4 회원 탈퇴) 완성 + PR 12b1 final reviewer 권고 3건(M3 Bean Validation 표준화 / M2 WebMvc 403 / M1 listener 2-row INSERT non-atomicity 문서화) 흡수.

**Architecture:** A-3/A-4는 PR 8 `AdminCrewPenaltyCommandController(Service)` write 패턴 일관 — Controller(`@PreAuthorize @Valid @RequestBody`) → Service(`@Transactional`) → MemberRepository/UserAccountRepository → Domain method → `registerEvent` → AFTER_COMMIT listener fan-out. `MemberData.changeTier()` domain method 신규 추가(pure mutation + event registration). `UserAccountData.withdraw(byAdministratorId)`는 PR 12b1 G2에서 evolve된 도메인 메서드 그대로 활용 — idempotency guard + email PII erase + lastLoginAt 미변경 모두 보유. G1 Bean Validation 마이그레이션은 `@Validated` controller 클래스 + `@Min/@Max/@Pattern` per-`@RequestParam` 패턴, cross-field `joinedFrom > joinedTo` 검증은 inline 보존.

**Tech Stack:** Java 21, Spring Boot 3.2 (Spring Security 6.2, Spring Data JPA 3.2, Hibernate 6.4, QueryDSL 5.0), Jakarta Bean Validation 3.0, JUnit 5, Mockito, AssertJ, ArchUnit, Testcontainers (MySQL 8 + Redis), Awaitility (listener AFTER_COMMIT async 검증).

**Spec source (read once, applied throughout):**
- `docs/superpowers/specs/2026-04-28-admin-platform-pr12b2-design.md` — 12 결정사항, 9 risks
- `docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md` — listener 패턴 + event 형태 (PR 12b1 G2 evolve)
- `docs/superpowers/specs/2026-04-19-admin-platform-features.md` §6.A (A-3, A-4)
- `docs/superpowers/specs/2026-04-19-admin-platform-schema.md` §4.7 V10
- `docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md` §11.8 (event_type catalog)

**Branching:** Continue on `feature/admin-auth-iam-schema`. PR 12b2 builds on PR 12b1 HEAD `e6daf108` (= last G5.1 SHA backfill commit).

**Out of scope (defer)** — spec §2.2:
- A-3 self-protection (어드민이 자기 member 항목 demote 차단) — future
- Withdraw reason 필드 + `UserAccountWithdrawnEvent` 4-arg evolve — future
- Withdraw 후 재가입 동일 email 정책 — future
- Listener 2-row INSERT atomic wrap (M1 흡수 후 코드 작업) — future
- Tier 변경 history 별도 테이블 — future
- Optimistic lock(version 컬럼) 동시 어드민 race 방지 — future
- `partyroom_admin_action` member-action 라인 features.md 본문 정정은 본 PR §12.4(G4) doc commit으로 흡수

---

## Atomic commit groupings

Per-task commits 기본. 다음 그룹은 단일 commit으로 land:

| Group | Tasks (chunks) | Reason |
|---|---|---|
| **G1: Bean Validation 표준화 + WebMvc 403 (M3 + M2)** | Chunk 1 (Tasks 1-5) | reviewer follow-up 흡수 baseline. controller `@Validated` 마이그레이션 + GlobalExceptionHandler 핸들러 추가 + WebMvc 400/403 case 갱신 — 같이 land 안 하면 빌드 깨짐 |
| **G2: A-3 PATCH tier 변경** | Chunk 2 (Tasks 6-15) | endpoint 단위 PR 8/9/12b1 패턴. domain method + repo + service + controller + DTO + WebMvc + IT + listener end-to-end IT |
| **G3: A-4 POST withdraw** | Chunk 3 (Tasks 16-23) | endpoint 단위. domain method 재사용(PR 12b1 G2). idempotent 응답 + lastLoginAt 미변경 검증 + listener end-to-end IT |
| **G4: spec §12 catch-up + M1 limitation doc + features.md 정정** | Chunk 4 (Tasks 24-26) | doc only. PR 12b1 §12.6에 M1 limitation 추가 + features.md A-3/A-4 본문 정정 + PR 12b2 §12 chunk SHAs backfill |
| **G4.1: SHA backfill follow-up** | Chunk 4 마지막 task | G4 commit SHA를 §12.4에 채우는 별 commit (PR 12a/12b1 G7.2 / G5.1 패턴) |

기타:
- ArchUnit 회귀 검증 — annotation-driven rule이 자동 cover (신규 service만 추가, listener annotation 변경 0).

Within each group:
- Per-task step lists remain a checklist.
- **Skip the `git commit` step at the end of each task in the group.**
- Single combined commit at the end of the group's last task with the message specified there.

---

## Hard precondition (verify BEFORE Chunk 1)

- [ ] **Step 1: Confirm HEAD ancestry**

```bash
git -C "C:/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-platform" log --oneline -3
```

Expected: HEAD `e6daf108 docs(spec): backfill chunk SHAs in PR 12b1 §12 (G5.1)`. Working tree clean.

- [ ] **Step 2: Working tree clean**

```bash
git -C "C:/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-platform" status -s
```

Expected: empty.

- [ ] **Step 3: JDK 21 환경**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew --version
```

Expected: Gradle ~8.10, JVM 21.0.x.

- [ ] **Step 4: Baseline build + test pass**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest :user:test
```

Expected: BUILD SUCCESSFUL. **예상 5-15분 (cold)** — Testcontainers MySQL boot 포함, Docker daemon 가동 필요. PR 12b1 HEAD 위에서 회귀 0 보장. Documented flaky `PartyroomRepositoryAtomicUpdateIT.unused_excludes_terminated`(PR 9 §11)는 isolated 실행 시 통과 — 본 baseline에서 fail해도 PR 12b2와 무관.

- [ ] **Step 5: Inventory ground-truth (verified during plan writing — do not deviate)**

**Repository names (이미 존재 — `MemberDataRepository` 명칭은 misnomer)**:
- `MemberRepository extends JpaRepository<MemberData, Long>, MemberRepositoryCustom` — `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository.java`
- `UserAccountRepository` — 동 패키지에 존재 — `findById(Long)` Spring Data 기본 메서드 사용 가능

**선례 controller (G2/G3가 mirror)**:
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminCrewPenaltyCommandController.java` — PATCH/POST + `@Valid @RequestBody` + `@PreAuthorize` + 201 CREATED + `ApiCommonResponse.success(...)` 응답 패턴
- `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandService.java` — `@Service @RequiredArgsConstructor @Transactional` 패턴, `AdminContext` 주입 방식 확인용

**AdminContext 패턴**: implementer는 `AdminCrewPenaltyCommandController` + `AdminCrewPenaltyCommandService`를 **Task 6 / Task 16 시작 시 read** — `byAdministratorId` 획득 방식(예: `@AuthenticationPrincipal AdminPrincipal`, `AdminContext` bean DI, `SecurityContextHolder.getContext()...`) 그대로 follow. 신규 패턴 도입 금지.

**AdminMemberException 현재 entries**:
```java
MEMBER_NOT_FOUND("MBR-001", "Member 가 존재하지 않습니다.", ErrorType.NOT_FOUND),
INVALID_LIST_QUERY("MBR-002", "Member 목록 조회 query 파라미터가 유효하지 않습니다.", ErrorType.BAD_REQUEST);
```

PR 12b2 G2가 추가:
```java
TIER_UNCHANGED("MBR-003", "현재 tier 와 동일합니다.", ErrorType.BAD_REQUEST),
```

**MemberData (`user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/MemberData.java`)**:
- `private AuthorityTier authorityTier` (`@Enumerated(EnumType.STRING)`)
- `private Long userAccountId` (`@Column(nullable=false)`)
- `BaseEntity` extends → `registerEvent(...)` 패턴 보유
- 현재 `changeTier(...)` 도메인 메서드 부재 — G2 Task 6에서 신설

**UserAccountData (`user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java`)**:
- `withdraw(Long byAdministratorId)` 도메인 메서드 — PR 12b1 G2에서 evolve 완료. 본 PR은 그대로 호출만.
- idempotency guard `if (isWithdrawn()) return;` 보존 — 재호출 시 `registerEvent` 미발생 → listener row 추가 없음(idempotent 의도).
- email PII erase: `withdrawn-{uid}@withdrawn.local` 형태.
- `lastLoginAt` 미변경 (roadmap §11.2.2 compliance).

**MemberTierChangedEvent / UserAccountWithdrawnEvent — 이미 존재**:
- 양쪽 다 PR 12b1 G2에서 정의/evolve. listener handlers도 wired (`UserActivityLogListener` 9 handlers).
- 본 PR은 publish source(domain method `registerEvent` 호출)만 활성.

**GlobalExceptionHandler (`common/src/main/java/com/pfplaybackend/api/common/exception/GlobalExceptionHandler.java`)**:
- `MethodArgumentNotValidException` 핸들러 존재 → 400.
- `ConstraintViolationException` 핸들러 부재 → G1 Task 2에서 추가.

**AbstractAdminWebMvcTest (`app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java`)**:
- 현재 11 controllers + 11 `@MockBean` services 등록.
- G2/G3가 추가: `AdminMemberTierCommandController.class`, `AdminMemberWithdrawCommandController.class` + `@MockBean AdminMemberTierCommandService`, `@MockBean AdminMemberWithdrawCommandService`.

**SecurityFilterChain**: `/api/v1/admin/**` `hasRole("ADMIN")`. 401(anonymous) / 403(인증된 non-admin role).

**WebMvc 403 case fixture**: `@WithMockUser(username = "user", roles = {"USER"})` — Spring Security가 `ROLE_USER` prefix 자동 추가, `@PreAuthorize("@adminAuth.isAdmin()")` 검증 실패 → 403.

**IT cleanup pattern (PR 12b1 `AdminMemberQueryServiceIT` mirror)**:
- `g4it.local` 도메인 격리 — V5 super-admin SEED 보호.
- `userActivityLogRepository.deleteAll()` 사용 가능(scoped, 다른 테스트 독립).
- `memberRepository`/`userAccountRepository`도 `deleteAll()` 가능 — V5 super-admin은 `email` 도메인이 다르고 SEED 자체는 globally shared이므로 wipe하면 다른 IT 영향 발생 → **scoped DELETE 필요**: `transactionTemplate.executeWithoutResult(s -> em.createNativeQuery("DELETE FROM member WHERE user_account_id IN (...)").executeUpdate())`. PR 12b1 IT class 패턴 mirror.

**Listener handler IT 패턴**: PR 12b1은 `UserActivityLogListenerTest` 단위만 (publish source 부재). 본 PR G2/G3 IT가 처음으로 end-to-end Awaitility 패턴 도입:
```java
await().atMost(5, SECONDS).untilAsserted(() ->
    assertThat(userActivityLogRepository.count()).isEqualTo(2));
```

```bash
# Re-confirm:
grep -rn "MemberRepository\|UserAccountRepository" --include="*.java" user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/ | head -5
grep -rn "ConstraintViolationException" --include="*.java" common/src/main/java/com/pfplaybackend/api/common/exception/ | head -5
```

Expected: MemberRepository / UserAccountRepository 양쪽 존재. ConstraintViolationException 핸들러 부재(0 hits).

---

## Chunk 1: G1 — M3 Bean Validation 표준화 + M2 WebMvc 403 (Reviewer Follow-up)

**Goal of chunk:** A-1/A-2 controller validation을 Bean Validation 표준 패턴으로 마이그레이션 + GlobalExceptionHandler `ConstraintViolationException` 핸들러 추가 + A-1/A-2 WebMvc 403 case 명시. write API 시작 전 baseline polish.

**End state of chunk:** `AdminMemberQueryController`가 `@Validated` 클래스 + `@Min/@Max/@Pattern` per-`@RequestParam`. cross-field 검증(`joinedFrom > joinedTo`)은 inline 보존(`INVALID_LIST_QUERY` 사용처 1건 — dead 아님). `GlobalExceptionHandler`가 `ConstraintViolationException` → 400 매핑. WebMvc test가 새 400 응답 형태 + 403 case 단언. 빌드/테스트 통과. 단일 G1 commit.

### Task 1: `AdminMemberQueryController` `@Validated` 마이그레이션

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryController.java`

- [ ] **Step 1: 현재 controller read**

```bash
cat app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryController.java
```

확인: `getList` inline `if(size>200)`, `if(page<0)`, `if(joinedFrom>joinedTo)`, `if(!isValidSort(sort))` 4건.

- [ ] **Step 2: 클래스 어노테이션 + import 추가**

`@RestController` 위에 `@Validated` 추가:
```java
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
@Validated  // ← 신규
public class AdminMemberQueryController {
```

`jakarta.validation.constraints.*` import:
```java
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
```

- [ ] **Step 3: `getList` 메서드 시그니처 갱신**

기존 inline validation 4건 중 3건(size, page, sort)을 Bean Validation으로 교체. cross-field(joinedFrom > joinedTo)만 inline 보존:

```java
private static final String SORT_PATTERN = "created_at_desc|created_at_asc|last_activity_desc";

@GetMapping
@PreAuthorize("@adminAuth.isAdmin()")
public ResponseEntity<ApiCommonResponse<Page<AdminMemberSummaryResponse>>> getList(
        @RequestParam(required = false) @Size(max = 255) String email,
        @RequestParam(required = false) AuthorityTier tier,
        @RequestParam(name = "joined_from", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
        @RequestParam(name = "joined_to", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size,
        @RequestParam(defaultValue = AdminMemberListQuery.SORT_CREATED_AT_DESC) @Pattern(regexp = SORT_PATTERN) String sort
) {
    // cross-field check (Bean Validation 표준 부재 — inline 보존)
    if (joinedFrom != null && joinedTo != null && joinedFrom.isAfter(joinedTo)) {
        throw ExceptionCreator.create(AdminMemberException.INVALID_LIST_QUERY);
    }
    AdminMemberListQuery query = AdminMemberListQuery.builder()
            .email(email).tier(tier)
            .joinedFrom(joinedFrom).joinedTo(joinedTo)
            .sort(sort)
            .build();
    Pageable pageable = PageRequest.of(page, size);
    return ResponseEntity.ok(ApiCommonResponse.success(adminMemberQueryService.getList(query, pageable)));
}
```

- [ ] **Step 4: 미사용 헬퍼 제거**

`isValidSort(String sort)` private 메서드가 inline check에서만 사용됐다면 제거. `MAX_PAGE_SIZE` 상수는 `@Max(MAX_PAGE_SIZE)`에서 참조하므로 보존(`Max.value()`는 `long` literal — `MAX_PAGE_SIZE`는 `int` 상수면 OK).

- [ ] **Step 5: 컴파일 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava
```

Expected: BUILD SUCCESSFUL.

⚠️ **Skip commit** — G1 묶음.

### Task 2: `GlobalExceptionHandler`에 `ConstraintViolationException` 핸들러 추가

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: 핸들러 추가 (test 없음 — 기존 `MethodArgumentNotValidException` 핸들러 패턴 mirror)**

```java
import jakarta.validation.ConstraintViolationException;

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

배치 위치: 기존 `handleValidationFailure(MethodArgumentNotValidException)` 핸들러 직전 또는 직후.

- [ ] **Step 2: 컴파일 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :common:compileJava
```

Expected: BUILD SUCCESSFUL.

⚠️ **Skip commit** — G1 묶음.

### Task 3: `AdminMemberQueryControllerTest` 400 case 갱신 + 403 case 추가 (M2)

**Files:**
- Modify: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryControllerTest.java`

- [ ] **Step 1: 현재 test 구조 확인**

```bash
cat app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryControllerTest.java | head -100
```

확인: 현재 `@WithMockUser(roles="ADMIN")` 200/anonymous 401/`size=0` `size=999` `page=-1` `sort=invalid` 400 case들. 403 case 없음.

- [ ] **Step 2: 기존 400 case 단언 완화**

기존:
```java
.andExpect(status().isBadRequest())
.andExpect(jsonPath("$.error.code").value("MBR-002"))
```

새:
```java
.andExpect(status().isBadRequest())
// MBR-002(INVALID_LIST_QUERY)는 cross-field(joinedFrom>joinedTo)만 사용.
// size/page/sort 위반은 ConstraintViolationException → code 없는 일반 400.
```

`size=0`/`size=999`/`page=-1`/`sort=invalid` 4 case는 status 400만 단언 (응답 body code 단언 제거).

`joinedFrom > joinedTo` case만 `MBR-002` code 단언 보존.

- [ ] **Step 3: A-1 403 case 추가**

```java
@Test
@DisplayName("A-1 GET /admin/members — 인증된 non-admin → 403")
@WithMockUser(username = "user", roles = {"USER"})
void list_authenticatedNonAdmin_returns403() throws Exception {
    mockMvc.perform(get("/api/v1/admin/members"))
            .andExpect(status().isForbidden());
}
```

- [ ] **Step 4: A-2 403 case 추가**

```java
@Test
@DisplayName("A-2 GET /admin/members/{id} — 인증된 non-admin → 403")
@WithMockUser(username = "user", roles = {"USER"})
void detail_authenticatedNonAdmin_returns403() throws Exception {
    mockMvc.perform(get("/api/v1/admin/members/{id}", 1L))
            .andExpect(status().isForbidden());
}
```

- [ ] **Step 5: WebMvc test 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberQueryControllerTest*"
```

Expected: PASS — 모든 case (기존 + 신규 403 2건).

⚠️ **Skip commit** — G1 묶음.

### Task 4: G1 회귀 검증 — 전체 unit + integration test

- [ ] **Step 1: 영향 범위 단위/IT 회귀**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest :common:test
```

Expected: BUILD SUCCESSFUL. PR 12b1 IT 회귀 0 — `AdminMemberQueryServiceIT`(service-layer)는 controller 변경에 영향 없음.

⚠️ **Skip commit** — G1 묶음.

### Task 5: G1 단일 commit

**Files staged**:
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryController.java`
- `common/src/main/java/com/pfplaybackend/api/common/exception/GlobalExceptionHandler.java`
- `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryControllerTest.java`

- [ ] **Step 1: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryController.java \
        common/src/main/java/com/pfplaybackend/api/common/exception/GlobalExceptionHandler.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryControllerTest.java

git commit -m "refactor(administration): A-1/A-2 Bean Validation 표준화 + WebMvc 403 보강 (PR 12b2 G1)

- @Validated controller + @Min/@Max/@Pattern @RequestParam — inline if() 패턴 폐기
- GlobalExceptionHandler ConstraintViolationException → 400 핸들러 신설
- A-1/A-2 WebMvc 403 case 명시 (@WithMockUser roles=USER)
- cross-field joinedFrom>joinedTo 검증만 inline 보존 (INVALID_LIST_QUERY 사용처 1건)

PR 12b1 final reviewer 권고 M3(Bean Validation 마이그레이션) + M2(WebMvc 403) 흡수."
```

✅ **G1 commit 완료**

---

## Chunk 2: G2 — A-3 PATCH `/admin/members/{memberId}/tier`

**Goal of chunk:** Member tier 변경 endpoint 도입 + `MemberTierChangedEvent` publish source 활성. listener AFTER_COMMIT row 2건(`TIER_CHANGED` + `ADMIN_ACTED_ON`) end-to-end 검증.

**End state of chunk:** `AdminMemberTierCommandController` PATCH endpoint + `AdminMemberTierCommandService` + `MemberData.changeTier()` domain method + `AdminMemberException.TIER_UNCHANGED` + DTO 2종 + WebMvc + service IT(listener Awaitility 검증). 빌드 통과 + 회귀 0. 단일 G2 commit.

### Task 6: `AdminCrewPenaltyCommandController/Service` 패턴 read (참조 only)

- [ ] **Step 1: 선례 read**

```bash
cat app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminCrewPenaltyCommandController.java
cat app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandService.java
```

확인:
- Controller `@PreAuthorize`, `@Valid @RequestBody`, response wrapping 패턴.
- Service `@Transactional`, `AdminContext` / `byAdministratorId` 획득 방식.
- DTO `*Request.toCommand()` 헬퍼 패턴(또는 단순 record).

이 형태 그대로 G2/G3 mirror.

### Task 7: `AdminMemberException.TIER_UNCHANGED` 추가

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/domain/exception/AdminMemberException.java`

- [ ] **Step 1: enum entry 추가**

```java
TIER_UNCHANGED("MBR-003", "현재 tier 와 동일합니다.", ErrorType.BAD_REQUEST),
```

`MEMBER_NOT_FOUND` / `INVALID_LIST_QUERY` 다음 위치.

- [ ] **Step 2: 컴파일 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava
```

⚠️ **Skip commit** — G2 묶음.

### Task 8: `MemberData.changeTier()` 도메인 메서드 + 단위 테스트 (TDD)

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/MemberData.java`
- Test (신규): `user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/MemberDataChangeTierTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.pfplaybackend.api.user.domain.entity.data;

import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.domain.event.MemberTierChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberDataChangeTierTest {

    @Test
    @DisplayName("changeTier: tier 변경 + MemberTierChangedEvent 1건 등록")
    void changeTier_changesAuthorityTier_andRegistersEvent() {
        MemberData member = MemberData.createForUserAccount(7777L);
        // 초기 tier는 도메인 default — getter 확인 후 다른 값으로 변경
        AuthorityTier oldTier = member.getAuthorityTier();
        AuthorityTier newTier = (oldTier == AuthorityTier.GT) ? AuthorityTier.AM : AuthorityTier.GT;

        member.changeTier(newTier, 99L);

        assertThat(member.getAuthorityTier()).isEqualTo(newTier);
        assertThat(member.domainEvents()).hasSize(1);
        Object event = member.domainEvents().iterator().next();
        assertThat(event).isInstanceOf(MemberTierChangedEvent.class);
        MemberTierChangedEvent e = (MemberTierChangedEvent) event;
        assertThat(e.getUserAccountId()).isEqualTo(7777L);
        assertThat(e.getMemberId()).isEqualTo(member.getMemberId());
        assertThat(e.getOldTier()).isEqualTo(oldTier);
        assertThat(e.getNewTier()).isEqualTo(newTier);
        assertThat(e.getByAdministratorId()).isEqualTo(99L);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :user:test --tests "*MemberDataChangeTierTest*"
```

Expected: FAIL — `changeTier` 메서드 없음.

- [ ] **Step 3: 도메인 메서드 추가**

```java
public void changeTier(AuthorityTier newTier, Long byAdministratorId) {
    AuthorityTier oldTier = this.authorityTier;
    this.authorityTier = newTier;
    registerEvent(new MemberTierChangedEvent(
            this.userAccountId, this.memberId, oldTier, newTier, byAdministratorId));
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :user:test --tests "*MemberDataChangeTierTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G2 묶음.

### Task 9: DTO 2종 — Request/Response

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminMemberTierChangeRequest.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminMemberTierChangeResponse.java`

- [ ] **Step 1: Request DTO 작성**

```java
package com.pfplaybackend.api.administration.application.dto;

import com.pfplaybackend.api.common.enums.AuthorityTier;
import jakarta.validation.constraints.NotNull;

public record AdminMemberTierChangeRequest(
        @NotNull AuthorityTier targetTier
) {}
```

- [ ] **Step 2: Response DTO 작성**

```java
package com.pfplaybackend.api.administration.application.dto;

import com.pfplaybackend.api.common.enums.AuthorityTier;

public record AdminMemberTierChangeResponse(
        Long memberId,
        AuthorityTier oldTier,
        AuthorityTier newTier
) {}
```

- [ ] **Step 3: 컴파일 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava
```

⚠️ **Skip commit** — G2 묶음.

### Task 10: `AdminMemberTierCommandService` + 단위 테스트 (TDD)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminMemberTierCommandService.java`
- Test (신규): `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminMemberTierCommandServiceTest.java`

- [ ] **Step 1: 단위 테스트 3건 작성 (happy / TIER_UNCHANGED 400 / MEMBER_NOT_FOUND 404)**

```java
@ExtendWith(MockitoExtension.class)
class AdminMemberTierCommandServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock AdminContext adminContext;  // 또는 코드베이스 패턴 follow

    @InjectMocks AdminMemberTierCommandService service;

    @Test
    @DisplayName("changeTier: GT → AM 변경 + response oldTier/newTier 단언")
    void changeTier_returnsResponse() {
        Long memberId = 1L;
        Long byAdminId = 99L;
        MemberData member = mock(MemberData.class);
        given(member.getAuthorityTier()).willReturn(AuthorityTier.GT);
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(adminContext.administratorId()).willReturn(byAdminId);

        AdminMemberTierChangeResponse res = service.changeTier(memberId,
                new AdminMemberTierChangeRequest(AuthorityTier.AM), adminContext);

        verify(member).changeTier(AuthorityTier.AM, byAdminId);
        verify(memberRepository).save(member);
        assertThat(res.memberId()).isEqualTo(memberId);
        assertThat(res.oldTier()).isEqualTo(AuthorityTier.GT);
        assertThat(res.newTier()).isEqualTo(AuthorityTier.AM);
    }

    @Test
    @DisplayName("changeTier: 같은 tier → TIER_UNCHANGED")
    void changeTier_sameTier_throwsTierUnchanged() {
        MemberData member = mock(MemberData.class);
        given(member.getAuthorityTier()).willReturn(AuthorityTier.AM);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        assertThatThrownBy(() -> service.changeTier(1L,
                new AdminMemberTierChangeRequest(AuthorityTier.AM), adminContext))
                .matches(ex -> ex instanceof BadRequestException ||
                                ((DomainException)((BaseException)ex).getDomainException())
                                        .getErrorCode().equals("MBR-003"));
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("changeTier: memberId 부재 → MEMBER_NOT_FOUND")
    void changeTier_memberNotFound_throws() {
        given(memberRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeTier(999L,
                new AdminMemberTierChangeRequest(AuthorityTier.AM), adminContext))
                .matches(ex -> ((BaseException)ex).getDomainException()
                                .getErrorCode().equals("MBR-001"));
    }
}
```

(implementer가 정확한 exception 타입은 codebase 확인 후 단언 — `NotFoundException` / `BadRequestException` 등 concrete class)

- [ ] **Step 2: 테스트 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberTierCommandServiceTest*"
```

Expected: FAIL — service 클래스 없음.

- [ ] **Step 3: Service 작성**

```java
@Service
@RequiredArgsConstructor
@Transactional
public class AdminMemberTierCommandService {

    private final MemberRepository memberRepository;

    public AdminMemberTierChangeResponse changeTier(Long memberId,
            AdminMemberTierChangeRequest request, AdminContext adminContext) {
        MemberData member = memberRepository.findById(memberId)
                .orElseThrow(() -> ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND));
        if (member.getAuthorityTier() == request.targetTier()) {
            throw ExceptionCreator.create(AdminMemberException.TIER_UNCHANGED);
        }
        AuthorityTier oldTier = member.getAuthorityTier();
        member.changeTier(request.targetTier(), adminContext.administratorId());
        memberRepository.save(member);
        return new AdminMemberTierChangeResponse(memberId, oldTier, request.targetTier());
    }
}
```

(`AdminContext` injection 방식은 Task 6 read 결과 follow.)

- [ ] **Step 4: 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberTierCommandServiceTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G2 묶음.

### Task 11: `AdminMemberTierCommandController` + WebMvc 테스트 (TDD)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberTierCommandController.java`
- Test (신규): `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberTierCommandControllerTest.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java`

- [ ] **Step 1: `AbstractAdminWebMvcTest` 갱신**

`@WebMvcTest({...})` 배열에 `AdminMemberTierCommandController.class` 추가. `@MockBean protected AdminMemberTierCommandService adminMemberTierCommandService;` 필드 추가.

- [ ] **Step 2: WebMvc 테스트 6 case 작성**

```java
class AdminMemberTierCommandControllerTest extends AbstractAdminWebMvcTest {

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /admin/members/{id}/tier — admin → 200")
    void changeTier_admin_returns200() throws Exception {
        given(adminMemberTierCommandService.changeTier(eq(1L), any(), any()))
                .willReturn(new AdminMemberTierChangeResponse(1L, AuthorityTier.GT, AuthorityTier.AM));
        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"AM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.oldTier").value("GT"))
                .andExpect(jsonPath("$.data.newTier").value("AM"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH — targetTier null → 400")
    void changeTier_nullTier_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH — targetTier enum 외 값 → 400")
    void changeTier_invalidEnum_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH — TIER_UNCHANGED → 400 MBR-003")
    void changeTier_unchanged_returns400() throws Exception {
        given(adminMemberTierCommandService.changeTier(eq(1L), any(), any()))
                .willThrow(ExceptionCreator.create(AdminMemberException.TIER_UNCHANGED));
        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"AM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MBR-003"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH — memberId 부재 → 404 MBR-001")
    void changeTier_notFound_returns404() throws Exception {
        given(adminMemberTierCommandService.changeTier(eq(999L), any(), any()))
                .willThrow(ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND));
        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 999L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"AM\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MBR-001"));
    }

    @Test
    @DisplayName("PATCH — anonymous → 401")
    void changeTier_anonymous_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"AM\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("PATCH — 인증된 non-admin → 403")
    void changeTier_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"AM\"}"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberTierCommandControllerTest*"
```

Expected: FAIL — controller 없음.

- [ ] **Step 4: Controller 작성**

```java
@Tag(name = "Admin Member Tier API")
@RequestMapping("/api/v1/admin/members")
@RestController
@RequiredArgsConstructor
public class AdminMemberTierCommandController {

    private final AdminMemberTierCommandService adminMemberTierCommandService;

    @PatchMapping("/{memberId}/tier")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<AdminMemberTierChangeResponse>> changeTier(
            @PathVariable Long memberId,
            @Valid @RequestBody AdminMemberTierChangeRequest request,
            // AdminContext 주입 방식은 Task 6 read 결과 follow
            AdminContext adminContext) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                adminMemberTierCommandService.changeTier(memberId, request, adminContext)));
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberTierCommandControllerTest*"
```

Expected: PASS — 7 case all green.

⚠️ **Skip commit** — G2 묶음.

### Task 12: `AdminMemberTierCommandServiceIT` — listener end-to-end (Awaitility)

**Files:**
- Test (신규): `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminMemberTierCommandServiceIT.java`

- [ ] **Step 1: IT skeleton — PR 12b1 `AdminMemberQueryServiceIT` 패턴 mirror**

```java
@SpringBootTest
@AutoConfigureMockMvc
class AdminMemberTierCommandServiceIT extends AbstractIntegrationTest {

    private static final long SEED_USER_ACCOUNT_ID = 8888L;  // V5 super-admin과 분리
    private static final long SEED_BY_ADMIN_ID = 99L;

    @Autowired AdminMemberTierCommandService service;
    @Autowired MemberRepository memberRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired UserActivityLogRepository userActivityLogRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @MockBean AdminContext adminContext;

    private Long memberId;

    @BeforeEach
    void seed() {
        UserAccountData ua = UserAccountData.createForLocal(
                new UserId(SEED_USER_ACCOUNT_ID), "g4it.local-tier@g4it.local", "h");
        userAccountRepository.saveAndFlush(ua);
        MemberData member = MemberData.createForUserAccount(SEED_USER_ACCOUNT_ID);
        // initial tier에서 다른 tier로 변경 — initial 확인 후 fixture 조정
        memberId = memberRepository.saveAndFlush(member).getMemberId();
        given(adminContext.administratorId()).willReturn(SEED_BY_ADMIN_ID);
    }

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(s -> {
            userActivityLogRepository.deleteAll();
            memberRepository.deleteAllById(List.of(memberId));
            userAccountRepository.deleteAllById(List.of(SEED_USER_ACCOUNT_ID));
        });
    }

    @Test
    @DisplayName("happy: tier 변경 + AFTER_COMMIT listener 2 row(TIER_CHANGED + ADMIN_ACTED_ON)")
    void changeTier_emits2AuditRows() {
        AuthorityTier oldTier = memberRepository.findById(memberId).orElseThrow().getAuthorityTier();
        AuthorityTier newTier = (oldTier == AuthorityTier.GT) ? AuthorityTier.AM : AuthorityTier.GT;

        AdminMemberTierChangeResponse res = service.changeTier(memberId,
                new AdminMemberTierChangeRequest(newTier), adminContext);

        assertThat(res.oldTier()).isEqualTo(oldTier);
        assertThat(res.newTier()).isEqualTo(newTier);
        assertThat(memberRepository.findById(memberId).orElseThrow().getAuthorityTier())
                .isEqualTo(newTier);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UserActivityLogData> logs = userActivityLogRepository
                    .findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(SEED_USER_ACCOUNT_ID);
            assertThat(logs).hasSize(2);
            // ORDER BY occurred_at DESC, log_id DESC → ADMIN_ACTED_ON 먼저 (audit-first 읽기)
            assertThat(logs.get(0).getEventType()).isEqualTo(UserActivityEventType.ADMIN_ACTED_ON);
            assertThat(logs.get(1).getEventType()).isEqualTo(UserActivityEventType.TIER_CHANGED);
        });
    }

    @Test
    @DisplayName("TIER_UNCHANGED → 400 + audit row 0")
    void changeTier_unchanged_throws_andEmitsNoAuditRow() {
        AuthorityTier sameTier = memberRepository.findById(memberId).orElseThrow().getAuthorityTier();

        assertThatThrownBy(() -> service.changeTier(memberId,
                new AdminMemberTierChangeRequest(sameTier), adminContext))
                .matches(ex -> ((BaseException)ex).getDomainException()
                                .getErrorCode().equals("MBR-003"));

        // listener 발화 없음 검증 — short delay 후 확인
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        assertThat(userActivityLogRepository
                .findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(SEED_USER_ACCOUNT_ID))
                .isEmpty();
    }

    @Test
    @DisplayName("MEMBER_NOT_FOUND → 404")
    void changeTier_notFound_throws() {
        assertThatThrownBy(() -> service.changeTier(99999L,
                new AdminMemberTierChangeRequest(AuthorityTier.AM), adminContext))
                .matches(ex -> ((BaseException)ex).getDomainException()
                                .getErrorCode().equals("MBR-001"));
    }
}
```

(`Awaitility` import는 `org.awaitility.Awaitility.await`, codebase 의존성 확인.)

- [ ] **Step 2: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*AdminMemberTierCommandServiceIT*"
```

Expected: PASS — 3 case (happy 2-row + TIER_UNCHANGED + NOT_FOUND).

⚠️ **Skip commit** — G2 묶음.

### Task 13: G2 ArchUnit + 회귀 검증

- [ ] **Step 1: 영향 범위 회귀**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest :user:test :common:test
```

Expected: BUILD SUCCESSFUL. 회귀 0.

⚠️ **Skip commit** — G2 묶음.

### Task 14: G2 단일 commit

**Files staged**:
- `app/src/main/java/com/pfplaybackend/api/administration/domain/exception/AdminMemberException.java`
- `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/MemberData.java`
- `user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/MemberDataChangeTierTest.java`
- `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminMemberTierChangeRequest.java`
- `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminMemberTierChangeResponse.java`
- `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminMemberTierCommandService.java`
- `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminMemberTierCommandServiceTest.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberTierCommandController.java`
- `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberTierCommandControllerTest.java`
- `app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java`
- `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminMemberTierCommandServiceIT.java`

- [ ] **Step 1: Commit**

```bash
git add <files above>

git commit -m "feat(administration): A-3 PATCH /admin/members/{id}/tier — MemberTierChangedEvent publish (PR 12b2 G2)

- Domain: MemberData.changeTier(newTier, byAdminId) — pure mutation + registerEvent
- Application: AdminMemberTierCommandService — TIER_UNCHANGED guard + repository.save 위임
- Adapter: AdminMemberTierCommandController PATCH endpoint + DTO 2종
- AdminMemberException.TIER_UNCHANGED(MBR-003, BAD_REQUEST) 추가
- AbstractAdminWebMvcTest에 신규 controller + service 등록
- WebMvc 7 case (200/400 invalid body/400 invalid enum/400 TIER_UNCHANGED/404/401/403)
- IT 3 case — listener AFTER_COMMIT 2 row(TIER_CHANGED + ADMIN_ACTED_ON) Awaitility 검증

PR 12b1 G2 listener handler skeleton의 publish source 활성."
```

✅ **G2 commit 완료**

---

## Chunk 3: G3 — A-4 POST `/admin/members/{memberId}/withdraw`

**Goal of chunk:** Member 탈퇴 처리 endpoint 도입 + `UserAccountWithdrawnEvent(.., byAdministratorId)` publish 활성. listener AFTER_COMMIT row 2건(`WITHDREW` + `ADMIN_ACTED_ON`) end-to-end 검증. Idempotent 재호출 + lastLoginAt 미변경 검증.

**End state of chunk:** `AdminMemberWithdrawCommandController` POST endpoint + `AdminMemberWithdrawCommandService` + Response DTO 1종 + WebMvc + service IT(idempotent + lastLoginAt + listener Awaitility 검증). 도메인 메서드 `UserAccountData.withdraw(byAdministratorId)`는 PR 12b1 G2 evolve된 그대로 활용. 빌드 통과 + 회귀 0. 단일 G3 commit.

### Task 15: Response DTO

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminMemberWithdrawResponse.java`

- [ ] **Step 1: Response DTO 작성**

```java
package com.pfplaybackend.api.administration.application.dto;

import java.time.LocalDateTime;

public record AdminMemberWithdrawResponse(
        Long memberId,
        Long userAccountId,
        LocalDateTime withdrawnAt,
        boolean alreadyWithdrawn
) {}
```

- [ ] **Step 2: 컴파일 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava
```

⚠️ **Skip commit** — G3 묶음.

### Task 16: `AdminMemberWithdrawCommandService` + 단위 테스트 (TDD)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminMemberWithdrawCommandService.java`
- Test (신규): `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminMemberWithdrawCommandServiceTest.java`

- [ ] **Step 1: 단위 테스트 4건**

```java
@ExtendWith(MockitoExtension.class)
class AdminMemberWithdrawCommandServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock UserAccountRepository userAccountRepository;
    @Mock AdminContext adminContext;

    @InjectMocks AdminMemberWithdrawCommandService service;

    @Test
    @DisplayName("withdraw: 1차 호출 — alreadyWithdrawn=false")
    void withdraw_firstCall_returnsAlreadyWithdrawnFalse() {
        Long memberId = 1L, uaId = 7L, byAdminId = 99L;
        MemberData member = mock(MemberData.class);
        UserAccountData ua = mock(UserAccountData.class);
        given(member.getUserAccountId()).willReturn(uaId);
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(userAccountRepository.findById(uaId)).willReturn(Optional.of(ua));
        given(ua.isWithdrawn()).willReturn(false);
        given(ua.getWithdrawnAt()).willReturn(LocalDateTime.of(2026, 4, 28, 12, 0));
        given(adminContext.administratorId()).willReturn(byAdminId);

        AdminMemberWithdrawResponse res = service.withdraw(memberId, adminContext);

        verify(ua).withdraw(byAdminId);
        verify(userAccountRepository).save(ua);
        assertThat(res.memberId()).isEqualTo(memberId);
        assertThat(res.alreadyWithdrawn()).isFalse();
    }

    @Test
    @DisplayName("withdraw: 2차 호출 — alreadyWithdrawn=true, withdraw() 호출 안 함")
    void withdraw_idempotent_returnsTrue() {
        // 검사 시점에 isWithdrawn()=true → 도메인 호출 skip
        Long memberId = 1L, uaId = 7L;
        MemberData member = mock(MemberData.class);
        UserAccountData ua = mock(UserAccountData.class);
        given(member.getUserAccountId()).willReturn(uaId);
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(userAccountRepository.findById(uaId)).willReturn(Optional.of(ua));
        given(ua.isWithdrawn()).willReturn(true);
        given(ua.getWithdrawnAt()).willReturn(LocalDateTime.of(2026, 4, 28, 12, 0));

        AdminMemberWithdrawResponse res = service.withdraw(memberId, adminContext);

        // domain method 호출 안 함 (service-layer pre-check로 idempotent 분기)
        verify(ua, never()).withdraw(anyLong());
        verify(userAccountRepository, never()).save(any());
        assertThat(res.alreadyWithdrawn()).isTrue();
    }

    @Test
    @DisplayName("withdraw: memberId 부재 → MEMBER_NOT_FOUND")
    void withdraw_memberNotFound_throws() {
        given(memberRepository.findById(999L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.withdraw(999L, adminContext))
                .matches(ex -> ((BaseException)ex).getDomainException()
                                .getErrorCode().equals("MBR-001"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

- [ ] **Step 3: Service 작성**

```java
@Service
@RequiredArgsConstructor
@Transactional
public class AdminMemberWithdrawCommandService {

    private final MemberRepository memberRepository;
    private final UserAccountRepository userAccountRepository;

    public AdminMemberWithdrawResponse withdraw(Long memberId, AdminContext adminContext) {
        MemberData member = memberRepository.findById(memberId)
                .orElseThrow(() -> ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND));
        UserAccountData userAccount = userAccountRepository.findById(member.getUserAccountId())
                .orElseThrow(() -> ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND));

        if (userAccount.isWithdrawn()) {
            return new AdminMemberWithdrawResponse(memberId, userAccount.getUserId().getUid(),
                    userAccount.getWithdrawnAt(), true);
        }

        userAccount.withdraw(adminContext.administratorId());
        userAccountRepository.save(userAccount);
        return new AdminMemberWithdrawResponse(memberId, userAccount.getUserId().getUid(),
                userAccount.getWithdrawnAt(), false);
    }
}
```

(주의: `service-layer pre-check`로 idempotent 분기 — 도메인의 idempotency guard도 안전망 존재. service에서 `alreadyWithdrawn` flag 정확히 응답하기 위해 pre-check 필요.)

- [ ] **Step 4: 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberWithdrawCommandServiceTest*"
```

⚠️ **Skip commit** — G3 묶음.

### Task 17: `AdminMemberWithdrawCommandController` + WebMvc 테스트 (TDD)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberWithdrawCommandController.java`
- Test (신규): `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberWithdrawCommandControllerTest.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java`

- [ ] **Step 1: `AbstractAdminWebMvcTest` 갱신**

`@WebMvcTest({...})` 배열에 `AdminMemberWithdrawCommandController.class` 추가. `@MockBean protected AdminMemberWithdrawCommandService adminMemberWithdrawCommandService;` 추가.

- [ ] **Step 2: WebMvc 테스트 4 case**

```java
class AdminMemberWithdrawCommandControllerTest extends AbstractAdminWebMvcTest {

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /admin/members/{id}/withdraw — admin → 200")
    void withdraw_admin_returns200() throws Exception {
        given(adminMemberWithdrawCommandService.withdraw(eq(1L), any()))
                .willReturn(new AdminMemberWithdrawResponse(
                        1L, 7L, LocalDateTime.of(2026, 4, 28, 12, 0), false));
        mockMvc.perform(post("/api/v1/admin/members/{id}/withdraw", 1L)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyWithdrawn").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST — memberId 부재 → 404 MBR-001")
    void withdraw_notFound_returns404() throws Exception {
        given(adminMemberWithdrawCommandService.withdraw(eq(999L), any()))
                .willThrow(ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND));
        mockMvc.perform(post("/api/v1/admin/members/{id}/withdraw", 999L)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MBR-001"));
    }

    @Test
    @DisplayName("POST — anonymous → 401")
    void withdraw_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/members/{id}/withdraw", 1L)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("POST — 인증된 non-admin → 403")
    void withdraw_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/members/{id}/withdraw", 1L)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 3: Controller 작성**

```java
@Tag(name = "Admin Member Withdraw API")
@RequestMapping("/api/v1/admin/members")
@RestController
@RequiredArgsConstructor
public class AdminMemberWithdrawCommandController {

    private final AdminMemberWithdrawCommandService adminMemberWithdrawCommandService;

    @PostMapping("/{memberId}/withdraw")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<AdminMemberWithdrawResponse>> withdraw(
            @PathVariable Long memberId,
            AdminContext adminContext) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                adminMemberWithdrawCommandService.withdraw(memberId, adminContext)));
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberWithdrawCommandControllerTest*"
```

Expected: PASS — 4 case.

⚠️ **Skip commit** — G3 묶음.

### Task 18: `AdminMemberWithdrawCommandServiceIT` — listener + idempotency + lastLoginAt

**Files:**
- Test (신규): `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminMemberWithdrawCommandServiceIT.java`

- [ ] **Step 1: IT 5 case**

```java
@SpringBootTest
@AutoConfigureMockMvc
class AdminMemberWithdrawCommandServiceIT extends AbstractIntegrationTest {

    private static final long SEED_USER_ACCOUNT_ID = 9999L;
    private static final long SEED_BY_ADMIN_ID = 99L;

    @Autowired AdminMemberWithdrawCommandService service;
    @Autowired MemberRepository memberRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired UserActivityLogRepository userActivityLogRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @MockBean AdminContext adminContext;

    private Long memberId;
    private LocalDateTime seedLastLoginAt;

    @BeforeEach
    void seed() {
        UserAccountData ua = UserAccountData.createForLocal(
                new UserId(SEED_USER_ACCOUNT_ID), "g4it.local-wd@g4it.local", "h");
        // lastLoginAt 셋팅
        seedLastLoginAt = LocalDateTime.of(2026, 4, 1, 10, 0);
        // ua.recordLogin(seedLastLoginAt) 또는 reflection — 도메인 메서드 확인
        userAccountRepository.saveAndFlush(ua);
        MemberData member = MemberData.createForUserAccount(SEED_USER_ACCOUNT_ID);
        memberId = memberRepository.saveAndFlush(member).getMemberId();
        given(adminContext.administratorId()).willReturn(SEED_BY_ADMIN_ID);
    }

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(s -> {
            userActivityLogRepository.deleteAll();
            memberRepository.deleteAllById(List.of(memberId));
            userAccountRepository.deleteAllById(List.of(SEED_USER_ACCOUNT_ID));
        });
    }

    @Test
    @DisplayName("happy: withdraw + email PII erase + lastLoginAt 미변경 + listener 2 row")
    void withdraw_emits2AuditRows_andPreservesLastLoginAt() {
        AdminMemberWithdrawResponse res = service.withdraw(memberId, adminContext);

        assertThat(res.alreadyWithdrawn()).isFalse();
        UserAccountData ua = userAccountRepository.findById(SEED_USER_ACCOUNT_ID).orElseThrow();
        assertThat(ua.isWithdrawn()).isTrue();
        assertThat(ua.getEmail()).startsWith("withdrawn-").endsWith("@withdrawn.local");
        assertThat(ua.getLastLoginAt()).isEqualTo(seedLastLoginAt);  // 미변경

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UserActivityLogData> logs = userActivityLogRepository
                    .findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(SEED_USER_ACCOUNT_ID);
            assertThat(logs).hasSize(2);
            assertThat(logs.get(0).getEventType()).isEqualTo(UserActivityEventType.ADMIN_ACTED_ON);
            assertThat(logs.get(1).getEventType()).isEqualTo(UserActivityEventType.WITHDREW);
        });
    }

    @Test
    @DisplayName("idempotent 재호출: alreadyWithdrawn=true + listener row count 변화 없음")
    void withdraw_idempotent_noNewAuditRows() {
        service.withdraw(memberId, adminContext);
        // 1차 호출 listener 완료 대기
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(userActivityLogRepository
                        .findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(SEED_USER_ACCOUNT_ID))
                        .hasSize(2));

        AdminMemberWithdrawResponse res = service.withdraw(memberId, adminContext);

        assertThat(res.alreadyWithdrawn()).isTrue();
        // 추가 row 없음 (listener publish 0)
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        assertThat(userActivityLogRepository
                .findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(SEED_USER_ACCOUNT_ID))
                .hasSize(2);
    }

    @Test
    @DisplayName("MEMBER_NOT_FOUND → 404")
    void withdraw_notFound_throws() {
        assertThatThrownBy(() -> service.withdraw(99999L, adminContext))
                .matches(ex -> ((BaseException)ex).getDomainException()
                                .getErrorCode().equals("MBR-001"));
    }
}
```

- [ ] **Step 2: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*AdminMemberWithdrawCommandServiceIT*"
```

Expected: PASS — 3 case (happy + idempotent + NOT_FOUND).

⚠️ **Skip commit** — G3 묶음.

### Task 19: G3 회귀 검증

- [ ] **Step 1: 영향 범위 회귀**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest :user:test :common:test
```

Expected: BUILD SUCCESSFUL. 회귀 0.

⚠️ **Skip commit** — G3 묶음.

### Task 20: G3 단일 commit

- [ ] **Step 1: Commit**

```bash
git add <files above>

git commit -m "feat(administration): A-4 POST /admin/members/{id}/withdraw — UserAccountWithdrawnEvent publish (PR 12b2 G3)

- Application: AdminMemberWithdrawCommandService — service-layer idempotent pre-check + UserAccountData.withdraw(byAdminId) 위임
- Adapter: AdminMemberWithdrawCommandController POST endpoint + AdminMemberWithdrawResponse DTO
- AbstractAdminWebMvcTest 신규 controller + service 등록
- WebMvc 4 case (200/404/401/403)
- IT 3 case — happy(2 audit rows + email PII erase + lastLoginAt 미변경) + idempotent(row count 변화 없음) + NOT_FOUND
- 도메인 메서드 UserAccountData.withdraw(byAdminId)는 PR 12b1 G2에서 evolve된 그대로 활용

PR 12b1 G2 listener handler skeleton의 publish source 활성 — Member 어드민 write API A-3/A-4 완성."
```

✅ **G3 commit 완료**

---

## Chunk 4: G4 — spec §12 catch-up + M1 limitation doc + features.md 정정

**Goal of chunk:** PR 12b2 design.md §12에 chunk SHAs + deviations backfill. PR 12b1 design.md §12.6에 M1(listener 2-row INSERT non-atomicity) limitation 추가. features.md A-3/A-4 본문에 partyroom_admin_action mismatch 정정(PR 12b1 §12.5 약속 이행).

**End state of chunk:** doc only. spec catch-up + M1 흡수 + features.md 정정. G4 commit 후 G4.1 SHA backfill follow-up commit.

### Task 21: PR 12b2 design.md §12 backfill (chunk SHAs + deviations)

**Files:**
- Modify: `docs/superpowers/specs/2026-04-28-admin-platform-pr12b2-design.md`

- [ ] **Step 1: Implementer가 발견한 deviations + chunk SHAs 명시**

§12.1/§12.2/§12.3에 G1/G2/G3 commit SHA를 채움(이 시점은 placeholder, G4.1에서 본 commit SHA 추가).

§12.5 deviations: implementer가 chunks 동안 발견한 spec과 코드 차이 항목들 backfill — 예:
- Repository 명칭이 `MemberDataRepository` (spec) → `MemberRepository` (코드 ground truth) 정정.
- `AdminContext` 주입 방식의 정확한 패턴 (`@AuthenticationPrincipal` vs bean DI vs `SecurityContextHolder`).
- 기타 spec 가정과 다른 도메인 메서드 형태(`getUid()` vs `getId()` 등).

### Task 22: PR 12b1 design.md §12.6에 M1 limitation 추가

**Files:**
- Modify: `docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md`

- [ ] **Step 1: §12.6 future polish 잔존 항목에 신규 항목 추가**

```markdown
- **Listener 2-row INSERT non-atomicity (M1, PR 12b1 final reviewer 권고)**: 핸들러 안 2개의 INSERT(`PartyroomCreatedEvent`의 OWNER+GUEST, `MemberTierChangedEvent`의 TIER_CHANGED+ADMIN_ACTED_ON, `UserAccountWithdrawnEvent`의 WITHDREW+ADMIN_ACTED_ON, `CrewAccessedEvent`의 ENTERED/EXITED 등 2-row 핸들러 일반)는 각 try/catch swallow로 독립 실행 — 부분 실패 시 audit row 누락 가능성. 본 PR은 limitation 명시만, 완화 옵션은 future PR로:
  - (a) 단일 transaction wrap (single try/catch) — 첫 실패 시 둘 다 rollback, second-row error 발생 시 first-row INSERT 보존 안 됨.
  - (b) Compensating retry queue (실패 row를 별 큐에 저장, 주기 재시도).
  - (c) Outbox 패턴 (event를 transaction 안 outbox 테이블에 atomic write, async dispatcher가 listener fan-out).
  PR 12b2가 listener 코드 변경 없이 publish source만 활성화 — 본 limitation은 PR 12b2 활성된 핸들러 4쌍 모두 동일하게 적용. atomic 보장은 코드 작업 필요한 별 PR로 분리.
```

### Task 23: features.md A-3/A-4 본문 정정 (PR 12b1 §12.5 약속 이행)

**Files:**
- Modify: `docs/superpowers/specs/2026-04-19-admin-platform-features.md`

- [ ] **Step 1: A-3 / A-4 라인 정정**

PR 12b1 §12.5에 명시된 partyroom_admin_action mismatch 정정 — features.md A-3 line 92 ("리스너가 `partyroom_admin_action`에 `action_type='CHANGE_MEMBER_TIER'` 1건 기록") 및 A-4 line 113 ("user_activity_log WITHDREW 기록")을 다음으로 정정:

A-3:
- 기존: "리스너가 `partyroom_admin_action`에 `action_type='CHANGE_MEMBER_TIER'` 1건 기록"
- 정정: "리스너가 `user_activity_log`에 row 2건 기록(`TIER_CHANGED` + `ADMIN_ACTED_ON`). `partyroom_admin_action`은 partyroom-scoped 테이블이라 member-level admin action에 부적합 — PR 12b1 Q2 결정 (B)에 따라 `user_activity_log.ADMIN_ACTED_ON` 단독 사용."

A-4:
- 기존: "user_activity_log WITHDREW 기록"
- 정정: "리스너가 `user_activity_log`에 row 2건 기록(`WITHDREW` + `ADMIN_ACTED_ON`). 비식별화 시 email은 `withdrawn-{uid}@withdrawn.local`로 PII erase, `lastLoginAt`은 audit 보존을 위해 미변경(roadmap §11.2.2 compliance)."

### Task 24: G4 commit (단일)

- [ ] **Step 1: 컴파일 검증**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test
```

doc only — 코드 영향 없으므로 빠른 sanity check.

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-04-28-admin-platform-pr12b2-design.md \
        docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md \
        docs/superpowers/specs/2026-04-19-admin-platform-features.md

git commit -m "docs(spec): PR 12b2 §12 catch-up + PR 12b1 §12.6 M1 limitation + features.md A-3/A-4 정정 (PR 12b2 G4)

- PR 12b2 design.md §12: chunk deviations + ground-truth 정정 (MemberRepository 명칭 등)
- PR 12b1 design.md §12.6: listener 2-row INSERT non-atomicity limitation (M1) — 완화 옵션 (a/b/c) future PR
- features.md A-3/A-4 본문: partyroom_admin_action mismatch → user_activity_log ADMIN_ACTED_ON 정정 (PR 12b1 §12.5 약속 이행)

PR 12b1 final reviewer 권고 M1 흡수 + 누적 spec deviation reconciliation."
```

✅ **G4 commit 완료**

### Task 25: G4.1 SHA backfill follow-up commit (PR 12a/12b1 G7.2 / G5.1 패턴)

**Files:**
- Modify: `docs/superpowers/specs/2026-04-28-admin-platform-pr12b2-design.md`

- [ ] **Step 1: G1/G2/G3/G4 commit SHA를 §12.1/§12.2/§12.3/§12.4에 채움**

```bash
git log --oneline -5
```

- [ ] **Step 2: §12.1 ~ §12.4 placeholder `<sha>` → 실제 SHA 교체**

각 섹션 첫 줄:
```markdown
**§12.1 G1 commit `4621129e` ...**
```
같은 패턴으로 G1/G2/G3/G4 SHA 채움.

- [ ] **Step 3: SHA backfill 단일 commit**

```bash
git add docs/superpowers/specs/2026-04-28-admin-platform-pr12b2-design.md

git commit -m "docs(spec): backfill chunk SHAs in PR 12b2 §12 (G4.1)"
```

✅ **G4.1 commit 완료** — PR 12b2 series 종료.

---

## Final verification

- [ ] **Step 1: 전체 테스트 회귀**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew test integrationTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: git log 확인**

```bash
git log --oneline -8
```

Expected: 최근 commits HEAD부터:
- G4.1 SHA backfill
- G4 spec §12 catch-up + M1 limitation + features.md 정정
- G3 A-4 POST withdraw
- G2 A-3 PATCH tier
- G1 Bean Validation 표준화 + WebMvc 403
- (PR 12b1 HEAD `e6daf108` ...)

- [ ] **Step 3: HEAD push 준비**

```bash
git status -s
```

Expected: empty (모든 변경 commit됨).

---

**총 5 commits (G1, G2, G3, G4, G4.1)**. PR 12b1 9 commits 대비 단순 — 단일 entity 2 endpoints + reviewer follow-up + doc 갯수.
