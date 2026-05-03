# PR 13: V13 partyroom_report + 유저용 신고 API + 어드민 검토 API (C-1~C-2) — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Administration BC에 모더레이션 도메인 1개(`PartyroomReport`) + V13 마이그레이션 + 유저용 C-1 신고 endpoint + 어드민용 C-2 list/detail/PATCH 3 endpoints 추가. M5 (PR 12-13) 완성, M6 (PR 14, 프런트) 의존 해소.

**Architecture:** PR 8 `AdminCrewPenaltyCommandController(Service)` write 패턴 일관 — Controller(`@PreAuthorize @Validated @Valid @RequestBody`) → Service(`@Transactional`) → Repository → Domain method. 도메인 메서드 4종(`startReview/resolve/dismiss/hold`)은 status 전이 매트릭스 guard 포함. Cross-context loose-ref read 4종(member×2, userAccount, partyroom)은 application service 레이어에서 `findByUserAccountId/findById` 직접 호출 — orphan tolerance(`null` 응답). ADR-004 hybrid publish 패턴은 본 PR scope에 적용 0(D4 결정 — `ReportStatusChanged` 미발행). `partyroom_admin_action` 미기록(D5 결정 — `partyroom_report` 자체가 audit). Bean Validation은 PR 12b2 G1에서 정착된 `@Validated` 클래스 + `@Min/@Max/@Pattern` 패턴을 신규 controller 처음부터 적용.

**Tech Stack:** Java 21, Spring Boot 3.2 (Spring Security 6.2, Spring Data JPA 3.2, Hibernate 6.4, QueryDSL 5.0), Jakarta Bean Validation 3.0, JUnit 5, Mockito, AssertJ, ArchUnit, Testcontainers (MySQL 8 + Redis), Flyway 9.

**Spec source (read once, applied throughout):**
- `docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md` — 17 결정사항, 10 risks
- `docs/superpowers/specs/2026-04-19-admin-platform-features.md` §6.C-1, §6.C-2
- `docs/superpowers/specs/2026-04-19-admin-platform-schema.md` §4.8 V13
- `docs/superpowers/specs/2026-04-28-admin-platform-pr12b2-design.md` §4 (Bean Validation 표준 패턴)
- `docs/superpowers/specs/2026-04-19-admin-platform-design.md` §5 (admin auth + adminAuth bean)
- `docs/adr/ADR-004-hybrid-domain-event-publish.md` — 본 PR은 발행 미적용이지만 패턴 인지

**Branching:** Continue on `feature/admin-auth-iam-schema`. PR 13 builds on PR 12b2 HEAD `16907ee3` (= last G4.1 SHA backfill commit).

**Out of scope (defer)** — spec §2.2:
- `ReportStatusChanged` 이벤트 발행 — D4 future evolve
- `partyroom_admin_action.REPORT_REVIEWED` 액션 도입 — D5 future evolve
- 24h 중복 unique index — D1 abuse 발견 시 future
- Bulk PATCH / 첨부 파일 / 자동 분류 / 알림 / Optimistic lock / metrics dashboard — features 별 항목

---

## Atomic commit groupings

Per-task commits 기본. 다음 그룹은 단일 commit으로 land:

| Group | Tasks (chunks) | Reason |
|---|---|---|
| **G1: V13 + PartyroomReport 도메인** | Chunk 1 (Tasks 1-7) | 마이그 + 엔티티 + enum + repository + exception + 단위 테스트 — 같이 land 안 하면 V13 적용된 빌드가 엔티티 0 상태로 남음 |
| **G2: C-1 유저 신고 endpoint** | Chunk 2 (Tasks 8-13) | endpoint 단위 PR 8/12b1 패턴. cross-context partyroom read + 24h 중복/self-report/active 검증 + WebMvc + IT |
| **G3: C-2 어드민 list/detail** | Chunk 3 (Tasks 14-21) | Query 측 endpoint pair. cross-context loose-ref read 4종 + DTO nested + WebMvc + IT |
| **G4: C-2 PATCH status 전이** | Chunk 4 (Tasks 22-26) | Command 측 endpoint. detail 응답 재사용(`AdminReportQueryService.buildDetailResponse`) — G3 의존 |
| **G5: spec §12 catch-up + features.md 정정 + roadmap 갱신** | Chunk 5 (Tasks 27-29) | doc only. PR 8/9/12a/12b1/12b2 동형 §12 catch-up 패턴 |
| **G5.1: SHA backfill follow-up** | Chunk 5 마지막 task | G5 commit SHA를 §13 본문에 채우는 별 commit (PR 12a/12b1/12b2 G7.2/G5.1/G4.1 패턴) |

기타:
- ArchUnit 회귀 검증 — annotation-driven rule이 자동 cover. cross-context loose-ref read 4종은 PR 7+ 시점부터 허용 패턴.

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

Expected: HEAD `16907ee3 docs(spec): backfill G4 SHA dc4b6ce2 in PR 12b2 §12.4 (G4.1)`. Working tree clean.

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

Expected: BUILD SUCCESSFUL. **예상 5-15분 (cold)** — Testcontainers MySQL boot 포함, Docker daemon 가동 필요. Documented flaky `PartyroomRepositoryAtomicUpdateIT.unused_excludes_terminated`(PR 9 §11)는 isolated 실행 시 통과 — 본 baseline에서 fail해도 PR 13과 무관.

- [ ] **Step 5: Inventory ground-truth (verified during plan writing — do not deviate)**

**선례 controller (G2/G3/G4가 mirror)**:
- Admin write: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberTierCommandController.java` (PR 12b2 G2) + `AdminMemberWithdrawCommandController.java` (PR 12b2 G3) — `@PreAuthorize("@adminAuth.isAdmin()")` + `@Valid @RequestBody` + `ApiCommonResponse.success(...)` 응답.
- Admin query: `AdminMemberQueryController.java` (PR 12b2 G1 마이그레이션 후) — `@Validated` 클래스 + `@Min/@Max/@Pattern @RequestParam` 패턴.
- User-facing endpoint: G2 Task 8 시작 시 ground-truth 확인 — `partyroom` 생성 등 기존 user endpoint 1개 read하여 Member 인증/`@AuthenticationPrincipal` 패턴 mirror. 신규 패턴 도입 금지.

**선례 service (G2/G3/G4가 mirror)**:
- `AdminMemberTierCommandService.java` — `@Service @RequiredArgsConstructor @Transactional`, ADR-004 hybrid publish 패턴(`pollDomainEvents().forEach(eventPublisher::publishEvent)`) — 본 PR은 publish 0이라 `pollDomainEvents` 불요.
- `AdminMemberQueryService.java` (PR 12b1) — list/detail 양쪽 보유 + cross-context loose-ref read 패턴 mirror.

**Repository names (이미 존재)**:
- `MemberRepository extends JpaRepository<MemberData, Long>, MemberRepositoryCustom` — `findByUserAccountId(Long)` Spring Data 메서드 또는 custom impl 확인.
- `UserAccountRepository extends JpaRepository<UserAccountData, UserId>` — `findById(new UserId(longUid))`로 호출(PR 12b2 §12.5 ground-truth).
- `PartyroomRepository` — PR 7+ 시점 존재. `findById(Long)` 기본. `getHostId()` / `getStatus()` 접근자 ground-truth 확인 필요.
- `PartyroomReportRepository` — 본 PR G1 Task 4에서 신설.

**AdminContext API (PR 12b2 §12.5 ground-truth)**:
- `currentAdministratorId()` (NOT `administratorId()`) — G4 service에서 사용.
- `currentUserId()` — Member context는 별 패턴(C-1 service에서 사용) — G2 Task 8 시작 시 ground-truth 재확인.

**ApiCommonResponse / ApiErrorResponse shape (PR 12b2 §12.5 ground-truth)**:
- `ApiErrorResponse`: flat record `{status, errorCode, message}` — WebMvc test JSON path는 `$.errorCode` (NOT `$.error.code` envelope).
- `ApiCommonResponse.success(data)` 패턴 — PR 12b2 controller mirror.

**AbstractAdminWebMvcTest (`app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java`)**:
- 현재 PR 12b2 G3까지 13 controllers + 13 `@MockBean` services 등록.
- G3/G4가 추가: `AdminReportQueryController.class`, `AdminReportCommandController.class` + `@MockBean AdminReportQueryService`, `@MockBean AdminReportCommandService`.

**`ConstraintViolationException` 핸들러 (PR 12b2 G1 추가됨)**:
- `GlobalExceptionHandler`에 이미 존재 — `@Validated @RequestParam` 위반 → 400. 신규 controller는 자동 cover.

**`MethodArgumentNotValidException` 핸들러**:
- 이미 존재 — `@Valid @RequestBody` 위반 → 400.

**SecurityFilterChain**: `/api/v1/admin/**` `hasRole("ADMIN")`. 401(anonymous) / 403(인증된 non-admin role).
- 유저 endpoint(`/api/v1/partyrooms/**`)는 `authenticated()` 또는 별 정책 — G2 Task 8 시 SecurityConfig read 후 확인.

**WebMvc 403 case fixture**:
- 어드민용: `@WithMockUser(username = "user", roles = {"USER"})` — `@PreAuthorize("@adminAuth.isAdmin()")` 검증 실패 → 403.
- 유저용 C-1 Guest 차단: `@WithMockUser(roles = {"GUEST"})` 또는 service-layer Member tier check — G2 Task 9 시 결정.

**MemberData (`user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/MemberData.java`)**:
- `private AuthorityTier authorityTier` — service-layer Guest 차단 시 `member.getAuthorityTier() == GT` 체크 필요할 수 있음.

**PartyroomData (`*/partyroom/.../PartyroomData.java`)**:
- 현재 시점 `isReportable()` 메서드 부재 추정 — G2 Task 8 시 ground-truth 확인. 신설 시 `status` enum 기반(`status != TERMINATED && status != SUSPENDED`).
- `getHostId()` 접근자 ground-truth 확인.

**IT cleanup pattern (PR 12b2 mirror)**:
- `g4it.local` 도메인 격리 — V5 super-admin SEED 보호.
- `partyroomReportRepository.deleteAll()` — 신규 테이블이라 다른 IT 영향 0.
- Member/UserAccount/Partyroom seed는 PR 12b2 IT 동형 패턴 — scoped DELETE.

**Flyway 마이그 위치**:
- `app/src/main/resources/db/migration/V13__create_partyroom_report.sql` (PR 12b1 V10 위치 mirror).

**Pre-flight grep**:
```bash
grep -rn "PartyroomData\|PartyroomRepository" --include="*.java" app/src/main/java/com/pfplaybackend/api/partyroom/ | head -5
grep -rn "ConstraintViolationException" --include="*.java" common/src/main/java/com/pfplaybackend/api/common/exception/ | head -3
grep -rn "AdminContext\|currentAdministratorId" --include="*.java" administration/ | head -5
ls app/src/main/resources/db/migration/ | grep -E "V[0-9]+"
```

Expected: PartyroomData/Repository 존재. ConstraintViolationException 핸들러 1 hit (PR 12b2 G1). AdminContext.currentAdministratorId() 존재. V10이 마지막 마이그(V13 미존재).

---

## Chunk 1: G1 — V13 마이그 + PartyroomReport 도메인

**Goal of chunk:** V13 마이그레이션 + `PartyroomReportData` 엔티티 + `ReportStatus`/`ReportCategory` enum + `PartyroomReportRepository` + `AdminReportException` enum + 단위 테스트. service/controller 부재 — 본 chunk 후 빌드 통과하나 endpoint 0.

**End state of chunk:** Flyway가 V13 적용 시 `partyroom_report` 테이블 생성. JPA 매핑 검증(IT 부재해도 entity scan 시 컬럼 mismatch 없어야 함). `PartyroomReportData` 도메인 메서드 4종 단위 테스트 + `ReportStatus.canTransitionTo` 4×4 매트릭스 테스트 통과. 단일 G1 commit.

### Task 1: V13 마이그레이션 SQL 작성

**Files:**
- Create: `app/src/main/resources/db/migration/V13__create_partyroom_report.sql`

- [ ] **Step 1: schema spec 확인**

```bash
sed -n '524,555p' docs/superpowers/specs/2026-04-19-admin-platform-schema.md
```

확인: schema §4.8.1 DDL.

- [ ] **Step 2: V13 SQL 파일 작성**

schema spec DDL을 그대로 사용. day_bucket 컬럼/unique index 추가 안 함(D1 결정).

```sql
-- =====================================================
-- V13: Administration context — PartyroomReport
--
-- 유저가 파티룸을 신고, 어드민이 검토.
-- =====================================================

CREATE TABLE partyroom_report (
    report_id                      BIGINT       NOT NULL AUTO_INCREMENT,
    partyroom_id                   BIGINT       NOT NULL,
    reporter_user_account_id       BIGINT       NOT NULL,
    category                       ENUM('INAPPROPRIATE_CONTENT','HARASSMENT','SPAM','COPYRIGHT','OTHER') NOT NULL,
    description                    TEXT         NULL,
    status                         ENUM('PENDING','REVIEWING','RESOLVED','DISMISSED') NOT NULL DEFAULT 'PENDING',
    reviewed_by_administrator_id   BIGINT       NULL,
    resolution_note                TEXT         NULL,
    created_at                     DATETIME     NOT NULL,
    resolved_at                    DATETIME     NULL,
    PRIMARY KEY (report_id),
    CONSTRAINT fk_pr_reviewed_by
        FOREIGN KEY (reviewed_by_administrator_id)
        REFERENCES administrator(administrator_id),
    INDEX idx_pr_status_created (status, created_at DESC),
    INDEX idx_pr_partyroom (partyroom_id, created_at DESC),
    INDEX idx_pr_reporter (reporter_user_account_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **Step 3: `created_at` BaseEntity 충돌 검증**

JPA 엔티티가 `BaseEntity`(audit 컬럼) 상속하면 `created_at`/`updated_at`이 자동 추가될 수 있음. schema spec V13 DDL은 `created_at`만 명시(updated_at 부재). 충돌 옵션:
- 옵션 A: 엔티티에서 BaseEntity 상속 안 함, 직접 `private LocalDateTime createdAt` 보유.
- 옵션 B: BaseEntity 상속 + DDL에 `updated_at` 추가.
- 옵션 C: BaseEntity 상속 + 엔티티에서 `@Column(updatable=false, insertable=false)` 등으로 `updated_at` 무시.

**결정**: 옵션 A — schema spec DDL 그대로 보존. PartyroomReportData가 BaseEntity 미상속(spec §4.1 코드 예시 그대로). updated_at 부재는 spec 의도(신고 lifecycle은 `resolved_at`이 충분).

**Acceptance**: V13 SQL 파일 생성. 다음 task에서 엔티티가 BaseEntity 상속 안 한 채 컴파일.

### Task 2: `ReportStatus` enum 작성

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/domain/value/ReportStatus.java` (or `.../entity/value/`, ground-truth 일관)

- [ ] **Step 1: 기존 administration enum 패키지 위치 확인**

```bash
grep -rn "package com.pfplaybackend.api.administration.domain" administration/src/main/java --include="*.java" -l | head -5
ls administration/src/main/java/com/pfplaybackend/api/administration/domain/
```

기존 enum 위치(`domain/value/` vs `domain/entity/value/`) 확인 후 일관 follow. PR 8/9 enum(`PartyroomActionType` 등) 위치를 mirror.

- [ ] **Step 2: enum 작성**

```java
package com.pfplaybackend.api.administration.domain.value;

public enum ReportStatus {
    PENDING, REVIEWING, RESOLVED, DISMISSED;

    public boolean isTerminal() {
        return this == RESOLVED || this == DISMISSED;
    }

    public boolean isOpen() {
        return !isTerminal();
    }

    public boolean canTransitionTo(ReportStatus target) {
        if (this == target) return false;
        return switch (this) {
            case PENDING -> target == REVIEWING || target == DISMISSED;
            case REVIEWING -> target == RESOLVED || target == DISMISSED || target == PENDING;
            case RESOLVED, DISMISSED -> false;
        };
    }
}
```

**Acceptance**: enum 컴파일. PR 13 spec §3.4 transition 매트릭스 일관.

### Task 3: `ReportCategory` enum 작성

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/domain/value/ReportCategory.java`

- [ ] **Step 1: enum 작성**

```java
package com.pfplaybackend.api.administration.domain.value;

public enum ReportCategory {
    INAPPROPRIATE_CONTENT,
    HARASSMENT,
    SPAM,
    COPYRIGHT,
    OTHER;
}
```

**Acceptance**: enum 컴파일. V13 DDL `category` ENUM 값과 정확 일치(name() 매칭).

### Task 4: `AdminReportException` enum 작성

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/domain/exception/AdminReportException.java`

- [ ] **Step 1: 기존 exception enum 패턴 확인**

```bash
cat administration/src/main/java/com/pfplaybackend/api/administration/domain/exception/AdminMemberException.java
```

확인: 기존 코드 패턴 — `enum implements ExceptionType` + `(code, message, ErrorType)` constructor.

- [ ] **Step 2: AdminReportException 작성**

```java
package com.pfplaybackend.api.administration.domain.exception;

import com.pfplaybackend.api.common.exception.ExceptionType;
import com.pfplaybackend.api.common.exception.ErrorType;

public enum AdminReportException implements ExceptionType {
    REPORT_NOT_FOUND("RPT-001", "신고가 존재하지 않습니다.", ErrorType.NOT_FOUND),
    INVALID_STATE_TRANSITION("RPT-002", "허용되지 않는 신고 상태 전이입니다.", ErrorType.BAD_REQUEST),
    RESOLUTION_NOTE_REQUIRED("RPT-003", "처리 완료(RESOLVED/DISMISSED) 시 처리 메모가 필요합니다.", ErrorType.BAD_REQUEST),
    INVALID_LIST_QUERY("RPT-004", "신고 목록 조회 query 파라미터가 유효하지 않습니다.", ErrorType.BAD_REQUEST),
    PARTYROOM_NOT_REPORTABLE("RPT-005", "신고할 수 없는 파티룸 상태입니다.", ErrorType.BAD_REQUEST),
    SELF_REPORT_FORBIDDEN("RPT-006", "본인이 호스트인 파티룸은 신고할 수 없습니다.", ErrorType.BAD_REQUEST),
    DUPLICATE_REPORT("RPT-007", "최근 24시간 내 동일 카테고리로 이미 신고하였습니다.", ErrorType.BAD_REQUEST);

    private final String code;
    private final String message;
    private final ErrorType errorType;
    // constructor + getters — AdminMemberException 패턴 mirror
}
```

**Note**: `PARTYROOM_NOT_FOUND`는 기존 partyroom BC enum에 존재할 가능성 — G2 Task 8 시 확인 후 재사용 OR 본 enum에 추가. spec §2.1.10 deviation 가능성.

**Acceptance**: enum 컴파일. 각 entry의 message/code/errorType 명확.

### Task 5: `PartyroomReportData` 엔티티 작성

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/PartyroomReportData.java`

- [ ] **Step 1: 기존 administration entity 패턴 확인**

```bash
cat administration/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/AdministratorData.java
cat administration/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/PartyroomAdminActionData.java
```

확인: `@Entity @Table @Getter` + 필드 매핑 + factory `create(...)` + 도메인 메서드 패턴.

- [ ] **Step 2: 엔티티 작성**

spec §4.1 코드 예시 그대로 적용 + ground-truth deviation 발견 시 §13.1 backfill.

핵심:
- BaseEntity 미상속 (Task 1 §3 결정).
- `@Id @GeneratedValue(strategy = IDENTITY)`.
- `@Enumerated(STRING)` for status/category.
- `@Column(name = "...")` snake_case 매핑.
- factory: `static PartyroomReportData create(partyroomId, reporterUserAccountId, category, description)` — `status = PENDING`, `createdAt = LocalDateTime.now()`.
- 도메인 메서드 4종: `startReview/resolve/dismiss/hold` — 각 `guardTransition(target)` 선행 호출.
- `guardTransition`: `if (!this.status.canTransitionTo(target)) throw ExceptionCreator.create(INVALID_STATE_TRANSITION);`.

전체 코드는 spec §4.1 참조. 단, `ExceptionCreator.create(...)` 호출 패턴 ground-truth 확인 — `AdminMemberException`이 어떻게 throw되는지 mirror.

- [ ] **Step 3: ExceptionCreator import 패턴 확인**

```bash
grep -rn "ExceptionCreator.create" administration/src/main/java --include="*.java" | head -3
```

확인: `ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND)` 같은 패턴.

**Acceptance**: 엔티티 컴파일. JPA mapping이 V13 DDL과 정확 일치(컬럼명/타입). 도메인 메서드 4종 + factory 정의.

### Task 6: `PartyroomReportRepository` 작성

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/PartyroomReportRepository.java`

- [ ] **Step 1: 기존 administration repository 패턴 확인**

```bash
ls administration/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/
cat administration/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/PartyroomAdminActionRepository.java
```

확인: `extends JpaRepository` + custom interface(필요 시) 분리 패턴.

- [ ] **Step 2: Repository 작성**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.value.ReportCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface PartyroomReportRepository extends JpaRepository<PartyroomReportData, Long> {

    boolean existsByReporterUserAccountIdAndPartyroomIdAndCategoryAndCreatedAtAfter(
            Long reporterUserAccountId,
            Long partyroomId,
            ReportCategory category,
            LocalDateTime since);

    // 어드민 list query — Spring Data method query 또는 별 custom impl
    // 파라미터(status List, category List, createdFrom/To, Pageable)는 G3 Task 14에서 확정
}
```

list query는 G3 Task 14에서 추가 — 본 task는 24h 중복 검증용 method만 작성. 또는 미리 확장 — implementer 판단.

**Acceptance**: Repository 컴파일. method query 명명이 Spring Data 규약 통과.

### Task 7: 단위 테스트 — `PartyroomReportData` + `ReportStatus`

**Files:**
- Create: `administration/src/test/java/com/pfplaybackend/api/administration/domain/entity/data/PartyroomReportDataTest.java`
- Create: `administration/src/test/java/com/pfplaybackend/api/administration/domain/value/ReportStatusTest.java`

- [ ] **Step 1: `ReportStatusTest` — 4×4 매트릭스 parameterized**

```java
@ParameterizedTest
@MethodSource("transitionMatrix")
@DisplayName("canTransitionTo matrix — 16 케이스")
void canTransitionTo_matrix(ReportStatus from, ReportStatus to, boolean expected) {
    assertThat(from.canTransitionTo(to)).isEqualTo(expected);
}

static Stream<Arguments> transitionMatrix() {
    return Stream.of(
        // PENDING → ?
        arguments(PENDING, PENDING, false),
        arguments(PENDING, REVIEWING, true),
        arguments(PENDING, RESOLVED, false),
        arguments(PENDING, DISMISSED, true),
        // REVIEWING → ?
        arguments(REVIEWING, PENDING, true),
        arguments(REVIEWING, REVIEWING, false),
        arguments(REVIEWING, RESOLVED, true),
        arguments(REVIEWING, DISMISSED, true),
        // RESOLVED → ?
        arguments(RESOLVED, PENDING, false),
        arguments(RESOLVED, REVIEWING, false),
        arguments(RESOLVED, RESOLVED, false),
        arguments(RESOLVED, DISMISSED, false),
        // DISMISSED → ?
        arguments(DISMISSED, PENDING, false),
        arguments(DISMISSED, REVIEWING, false),
        arguments(DISMISSED, RESOLVED, false),
        arguments(DISMISSED, DISMISSED, false)
    );
}
```

`isTerminal()`/`isOpen()` 별도 단언 4건 추가.

- [ ] **Step 2: `PartyroomReportDataTest` — 도메인 메서드 매트릭스**

spec §4.3 매트릭스 9 케이스 + parameterized terminal-from 4 케이스. 각 happy + INVALID_STATE_TRANSITION throws 검증.

핵심 단언:
- `startReview_fromPending_setsReviewerAndStatus`: `report.startReview(adminId)` → `status == REVIEWING`, `reviewedByAdministratorId == adminId`.
- `resolve_fromReviewing`: `status == RESOLVED`, `resolvedAt != null`, `resolutionNote == "..."`.
- `dismiss_fromPending`: `status == DISMISSED`, `reviewedByAdministratorId == adminId`(직접 set), `resolvedAt != null`.
- `hold_fromReviewing`: `status == PENDING`, `reviewedByAdministratorId` 보존(이전 admin id 그대로).
- `*_fromTerminal_throws`: `RESOLVED`/`DISMISSED` 시작 후 모든 메서드 호출 → `INVALID_STATE_TRANSITION`.

- [ ] **Step 3: 테스트 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :administration:test --tests "*PartyroomReport*"
```

Expected: BUILD SUCCESSFUL. ~10-15 테스트 통과.

- [ ] **Step 4: G1 commit**

```bash
git add app/src/main/resources/db/migration/V13__create_partyroom_report.sql \
        administration/src/main/java/com/pfplaybackend/api/administration/domain/value/ReportStatus.java \
        administration/src/main/java/com/pfplaybackend/api/administration/domain/value/ReportCategory.java \
        administration/src/main/java/com/pfplaybackend/api/administration/domain/exception/AdminReportException.java \
        administration/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/PartyroomReportData.java \
        administration/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/PartyroomReportRepository.java \
        administration/src/test/java/com/pfplaybackend/api/administration/domain/entity/data/PartyroomReportDataTest.java \
        administration/src/test/java/com/pfplaybackend/api/administration/domain/value/ReportStatusTest.java
```

```bash
git commit -m "feat(administration): V13 partyroom_report + 도메인 (PR 13 G1)

- V13__create_partyroom_report.sql — schema §4.8.1 그대로 (no day_bucket / no unique index, D1 앱 검증 채택)
- PartyroomReportData entity (BaseEntity 미상속, spec §4.1)
- ReportStatus enum + canTransitionTo 4×4 matrix (D3)
- ReportCategory enum (5 categories)
- AdminReportException (RPT-001 ~ RPT-007)
- PartyroomReportRepository (24h 중복 검증 method query)
- 단위 테스트: 도메인 메서드 9 + matrix 16 + isTerminal/isOpen 4 = ~29 cases"
```

**Acceptance**: 빌드 성공. 단위 테스트 통과. V13 마이그레이션은 IT 실행 시 적용(다음 chunk부터 시드 통과 검증).

---

## Chunk 2: G2 — C-1 유저 신고 endpoint

**Goal of chunk:** `POST /api/v1/partyrooms/{partyroomId}/reports` — Member 인증 + 24h 중복/self-report/active 검증 + DB INSERT.

**End state of chunk:** 인증된 Member가 active partyroom 신고 가능. 비-active / self-report / 24h 중복은 400. WebMvc + IT 통과. 단일 G2 commit.

### Task 8: ground-truth 확인 — User endpoint 패턴 + PartyroomData 접근자

- [ ] **Step 1: 기존 user-facing endpoint 1개 read**

```bash
ls app/src/main/java/com/pfplaybackend/api/partyroom/adapter/in/web/ 2>/dev/null
ls partyroom/src/main/java/com/pfplaybackend/api/partyroom/adapter/in/web/ 2>/dev/null
grep -rn "@PostMapping\|@AuthenticationPrincipal" partyroom/src/main/java/com/pfplaybackend/api/partyroom/adapter/in/web/ --include="*.java" | head -10
```

확인: Member 인증 컨텍스트 추출 패턴(`@AuthenticationPrincipal MemberPrincipal` / `SecurityContextHolder` / 별 bean DI).

- [ ] **Step 2: PartyroomData 접근자 + isReportable 확인**

```bash
grep -n "getHostId\|getStatus\|isReportable\|isActive" partyroom/src/main/java/com/pfplaybackend/api/partyroom/domain/entity/data/PartyroomData.java
```

확인: `getHostId()` 존재 / `isReportable()` 부재 가능성.

- [ ] **Step 3: PartyroomStatus enum 값 확인**

```bash
cat partyroom/src/main/java/com/pfplaybackend/api/partyroom/domain/value/PartyroomStatus.java 2>/dev/null
grep -rn "enum PartyroomStatus" partyroom/src/main/java --include="*.java" -A 10
```

확인: `OPEN/ACTIVE/SUSPENDED/TERMINATED` 등 enum 값.

- [ ] **Step 4: PartyroomNotFoundException 또는 동치 enum 확인**

```bash
grep -rn "PARTYROOM_NOT_FOUND" partyroom/src/main/java --include="*.java" | head -3
```

확인: 기존 partyroom BC에 enum 존재 시 재사용 / 부재 시 `AdminReportException`에 `PARTYROOM_NOT_FOUND` 추가.

**Acceptance**: ground-truth 4건 확인 완료. deviation 발생 항목은 §13.2에 backfill.

### Task 9: `PartyroomData.isReportable()` 메서드 신설 (필요 시)

**Files:**
- Modify (조건부): `partyroom/src/main/java/com/pfplaybackend/api/partyroom/domain/entity/data/PartyroomData.java`

- [ ] **Step 1: 신설 결정**

Task 8 §2/§3 ground-truth 기반:
- `isReportable()` 존재 → 재사용, 본 task skip.
- 부재 → 신설:

```java
public boolean isReportable() {
    return this.status != PartyroomStatus.TERMINATED
        && this.status != PartyroomStatus.SUSPENDED;
}
```

정확한 active state 정의는 PR 7 enum 기반. terminated/suspended 외 모든 상태(`OPEN`, `ACTIVE`, `PENDING` 등)는 신고 가능 — features §6.C-1 spec spirit.

- [ ] **Step 2: 단위 테스트 추가 (신설 시)**

```bash
ls partyroom/src/test/java/com/pfplaybackend/api/partyroom/domain/entity/data/
```

기존 `PartyroomDataTest`에 case 추가: `isReportable_whenActive_returnsTrue`, `isReportable_whenTerminated_returnsFalse` 등 enum 값별.

**Acceptance**: 메서드 신설(필요 시) + 단위 테스트 통과. PR 7 partyroom 기존 코드 회귀 0.

### Task 10: `PartyroomReportCommandService` 작성

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/application/service/PartyroomReportCommandService.java`
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/application/dto/command/PartyroomReportCreateCommand.java` (필요 시)

- [ ] **Step 1: Service 작성**

```java
@Service
@RequiredArgsConstructor
@Transactional
public class PartyroomReportCommandService {

    private final PartyroomReportRepository reportRepository;
    private final PartyroomRepository partyroomRepository;  // cross-context loose-ref read

    public PartyroomReportCreateResponse create(Long partyroomId,
                                                PartyroomReportCreateRequest request,
                                                Long reporterUserAccountId) {
        // 1. partyroom 존재/active 검증
        PartyroomData partyroom = partyroomRepository.findById(partyroomId)
                .orElseThrow(() -> ExceptionCreator.create(/* PARTYROOM_NOT_FOUND */));

        if (!partyroom.isReportable()) {
            throw ExceptionCreator.create(AdminReportException.PARTYROOM_NOT_REPORTABLE);
        }

        // 2. self-report 차단
        if (partyroom.getHostId() != null && partyroom.getHostId().equals(reporterUserAccountId)) {
            throw ExceptionCreator.create(AdminReportException.SELF_REPORT_FORBIDDEN);
        }

        // 3. 24h 중복 검증
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        if (reportRepository.existsByReporterUserAccountIdAndPartyroomIdAndCategoryAndCreatedAtAfter(
                reporterUserAccountId, partyroomId, request.category(), since)) {
            throw ExceptionCreator.create(AdminReportException.DUPLICATE_REPORT);
        }

        // 4. save
        PartyroomReportData report = PartyroomReportData.create(
                partyroomId, reporterUserAccountId, request.category(), request.description());
        reportRepository.save(report);

        return new PartyroomReportCreateResponse(report.getReportId());
    }
}
```

- [ ] **Step 2: 단위 테스트 작성**

`PartyroomReportCommandServiceTest` (mock repos):
- happy 201.
- `PARTYROOM_NOT_FOUND` (partyroomRepository.findById empty).
- `PARTYROOM_NOT_REPORTABLE` (`partyroom.isReportable()` false).
- `SELF_REPORT_FORBIDDEN` (partyroom.hostId == reporterId).
- `DUPLICATE_REPORT` (existsBy* true).
- happy with `description=null` (description optional).

**Acceptance**: Service + 5+ 단위 테스트 통과.

### Task 11: DTO 2종 + Controller 작성

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/PartyroomReportCreateRequest.java`
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/PartyroomReportCreateResponse.java`
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/PartyroomReportCommandController.java`

- [ ] **Step 1: DTO 작성**

```java
public record PartyroomReportCreateRequest(
    @NotNull ReportCategory category,
    @Size(max = 2000) String description
) {}

public record PartyroomReportCreateResponse(Long reportId) {}
```

- [ ] **Step 2: Controller 작성**

```java
@RestController
@RequestMapping("/api/v1/partyrooms")
@RequiredArgsConstructor
@Validated
public class PartyroomReportCommandController {

    private final PartyroomReportCommandService reportCommandService;

    @PostMapping("/{partyroomId}/reports")
    @PreAuthorize("isAuthenticated()")  // Member tier 차단은 service-layer 또는 별 SpEL bean
    public ResponseEntity<ApiCommonResponse<PartyroomReportCreateResponse>> create(
            @PathVariable @Min(1) Long partyroomId,
            @Valid @RequestBody PartyroomReportCreateRequest request,
            @AuthenticationPrincipal /* MemberPrincipal */ principal) {

        Long reporterUserAccountId = /* extract from principal — ground-truth Task 8 */;
        PartyroomReportCreateResponse response = reportCommandService.create(
                partyroomId, request, reporterUserAccountId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiCommonResponse.success(response));
    }
}
```

Member tier(Guest 차단) 결정:
- 옵션 A: SecurityConfig에 `/api/v1/partyrooms/**`가 이미 Member-only(Guest 차단)면 SpEL 추가 불요.
- 옵션 B: `@PreAuthorize("@memberAuth.isMember()")` SpEL bean 신설.
- 옵션 C: service-layer `if (!authority.isMember()) throw 403` — controller에서 추출 시 ground-truth 패턴 follow.

ground-truth 확인 후 결정. §13.2 backfill 대상.

**Acceptance**: Controller 컴파일 + DTO 2종.

### Task 12: WebMvc 테스트 작성

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/PartyroomReportCommandControllerTest.java`

- [ ] **Step 1: WebMvc test base 결정**

User-facing controller라 `AbstractAdminWebMvcTest` 부적합. 옵션:
- 별 base class 존재 시 mirror.
- 없으면 단독 `@WebMvcTest(PartyroomReportCommandController.class) + @MockBean` 패턴.

```bash
ls app/src/test/java/com/pfplaybackend/api/ | head -10
grep -rn "@WebMvcTest" app/src/test/java --include="*.java" -l | head -5
```

기존 user-facing WebMvc test 1개 mirror.

- [ ] **Step 2: Test cases**

- 201 happy + body assertion (`$.data.reportId`).
- 400 — `category` null.
- 400 — `category` enum 외 (e.g. `"INVALID"`).
- 400 — `description` >2000자 (`@Size` 위반).
- 400 — `partyroomId` `@Min(1)` 위반 (path 0 또는 음수).
- 401 — anonymous(`@WithAnonymousUser` 또는 fixture 미적용).
- 403 — Guest tier (Member 인증 차단 — Task 11 결정 follow).
- 404 — `PARTYROOM_NOT_FOUND`.
- 400 — `PARTYROOM_NOT_REPORTABLE`.
- 400 — `SELF_REPORT_FORBIDDEN`.
- 400 — `DUPLICATE_REPORT`.

각 case service mock 동작 setup.

**Acceptance**: ~11 case 통과.

### Task 13: IT 작성 + G2 commit

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/administration/application/service/PartyroomReportCommandServiceIT.java`

- [ ] **Step 1: IT 시드 패턴**

PR 12b2 IT mirror — `g4it.local` 도메인 격리 + scoped DELETE cleanup.

SEED:
- 1 admin (V5 super-admin과 별도, `g4it.local` 도메인).
- 2 member (host용 + reporter용).
- 1 active partyroom (host=member1).
- 1 terminated partyroom.

- [ ] **Step 2: IT cases**

- happy 201 + DB row + `status=PENDING` + `createdAt` 검증.
- 24h 중복 동일 카테고리 → DUPLICATE_REPORT (앞 신고 시드 후 재호출).
- 24h 후 동일 카테고리 → 허용 (`created_at = now-25h` 사전 시드 후 신규 호출).
- 24h 내 다른 카테고리 → 허용.
- self-report → SELF_REPORT_FORBIDDEN.
- 비-active partyroom → PARTYROOM_NOT_REPORTABLE.
- non-existent partyroom → PARTYROOM_NOT_FOUND.

- [ ] **Step 3: 빌드 + 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :administration:test :app:test :app:integrationTest --tests "*Report*"
```

Expected: 모든 신규 테스트 통과. 회귀 0.

- [ ] **Step 4: G2 commit**

```bash
git add administration/src/main/java/com/pfplaybackend/api/administration/application/service/PartyroomReportCommandService.java \
        administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/PartyroomReportCreateRequest.java \
        administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/PartyroomReportCreateResponse.java \
        administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/PartyroomReportCommandController.java \
        administration/src/test/java/com/pfplaybackend/api/administration/application/service/PartyroomReportCommandServiceTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/PartyroomReportCommandControllerTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/service/PartyroomReportCommandServiceIT.java \
        partyroom/src/main/java/com/pfplaybackend/api/partyroom/domain/entity/data/PartyroomData.java  # 조건부 (isReportable 신설 시)
```

```bash
git commit -m "feat(administration): C-1 POST /partyrooms/{id}/reports — 유저 신고 접수 (PR 13 G2)

- PartyroomReportCommandController + Service + DTO 2종
- 검증: partyroom active(D6) / self-report 차단(D2) / 24h 중복 앱 검증(D1)
- Member 인증 + Guest 차단(ground-truth follow)
- 단위 테스트 6 + WebMvc 11 + IT 7
- (조건부) PartyroomData.isReportable() 신설"
```

**Acceptance**: G2 commit 통과. C-1 endpoint 통합 동작.

---

## Chunk 3: G3 — C-2 어드민 list/detail

**Goal of chunk:** `GET /admin/reports` (list + 필터 + sort + pagination) + `GET /admin/reports/{id}` (detail + cross-context loose-ref join 4종).

**End state of chunk:** 어드민이 신고 목록/상세 조회 가능. cross-context join orphan tolerance(`null`) 동작. `AdminReportQueryService.buildDetailResponse(...)`가 G4에서 재사용 가능. 단일 G3 commit.

### Task 14: Repository list query 확장

**Files:**
- Modify: `administration/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/PartyroomReportRepository.java`
- Create (필요 시): `administration/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/PartyroomReportRepositoryCustom.java` + Impl

- [ ] **Step 1: list query 옵션**

- 옵션 A: Spring Data method query 조합 — `findByStatusInAndCategoryInAndCreatedAtBetween(...)` 등. status/category가 nullable List라 `if (status == null) return findAll(...)` 분기 다수 필요.
- 옵션 B: QueryDSL custom impl — 동적 query 자연스러움. PR 12b1 `MemberRepositoryCustom` 패턴 mirror.
- 추천: **B** — PR 12b1 `AdminMemberQueryService`가 이미 QueryDSL 패턴 사용. 일관성.

- [ ] **Step 2: PR 12b1 QueryDSL 패턴 확인**

```bash
ls administration/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/ | grep -i custom
cat administration/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/MemberRepositoryCustomImpl.java 2>/dev/null
```

PR 12b1이 user 모듈에 두었는지 administration에 두었는지 확인 후 일관 follow.

- [ ] **Step 3: list query 작성**

QueryDSL 동적 query + Pageable. status `IN` / category `IN` / createdAt range / sort key 분기(`created_at_desc | created_at_asc`).

```java
public Page<PartyroomReportData> findList(
        List<ReportStatus> statuses,  // null/empty → 전체
        List<ReportCategory> categories,  // null/empty → 전체
        LocalDate createdFrom,  // null → 미적용
        LocalDate createdTo,
        Pageable pageable) { /* QueryDSL where + offset/limit + total count */ }
```

**Acceptance**: list query method 컴파일. unit-friendly(integration-only OK).

### Task 15: `AdminReportSummaryResponse` + `AdminReportDetailResponse` DTO 작성

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminReportSummaryResponse.java`
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminReportDetailResponse.java`

- [ ] **Step 1: Summary record**

```java
public record AdminReportSummaryResponse(
    Long reportId,
    Long partyroomId,
    Long reporterUserAccountId,
    ReportCategory category,
    ReportStatus status,
    LocalDateTime createdAt,
    Long reviewedByAdministratorId,
    LocalDateTime resolvedAt
) {
    public static AdminReportSummaryResponse from(PartyroomReportData report) { /* ... */ }
}
```

- [ ] **Step 2: Detail record + nested**

```java
public record AdminReportDetailResponse(
    Long reportId,
    ReportStatus status,
    ReportCategory category,
    String description,
    Reporter reporter,
    Partyroom partyroom,
    Review review,
    LocalDateTime createdAt
) {
    public record Reporter(Long userAccountId, String email, String nickname) {}
    public record Partyroom(Long partyroomId, String title, Host host) {}
    public record Host(Long userAccountId, String nickname) {}
    public record Review(Long reviewedByAdministratorId, String resolutionNote, LocalDateTime resolvedAt) {}
}
```

cross-context fields 부재 시 `null` 채택(orphan tolerance). 옵션: `Reporter.nickname == null` 또는 `Reporter` 자체 `null`.
**결정**: nested record 자체는 항상 build, 내부 nullable 필드만 null. 클라이언트 렌더링 일관성.

**Acceptance**: DTO 2종 컴파일.

### Task 16: `AdminReportQueryService` 작성

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/application/service/AdminReportQueryService.java`

- [ ] **Step 1: Service 작성**

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminReportQueryService {

    private final PartyroomReportRepository reportRepository;
    private final MemberRepository memberRepository;
    private final UserAccountRepository userAccountRepository;
    private final PartyroomRepository partyroomRepository;

    public Page<AdminReportSummaryResponse> getList(AdminReportListQuery query, Pageable pageable) {
        // cross-field validation은 controller에서 수행 — service는 query 그대로 사용
        return reportRepository.findList(
                query.statuses(), query.categories(),
                query.createdFrom(), query.createdTo(), pageable)
                .map(AdminReportSummaryResponse::from);
    }

    public AdminReportDetailResponse getDetail(Long reportId) {
        PartyroomReportData report = reportRepository.findById(reportId)
                .orElseThrow(() -> ExceptionCreator.create(AdminReportException.REPORT_NOT_FOUND));
        return buildDetailResponse(report);
    }

    public AdminReportDetailResponse buildDetailResponse(PartyroomReportData report) {
        // cross-context loose-ref reads — orphan tolerance
        Reporter reporter = buildReporter(report.getReporterUserAccountId());
        Partyroom partyroom = buildPartyroom(report.getPartyroomId());
        Review review = new Review(
                report.getReviewedByAdministratorId(),
                report.getResolutionNote(),
                report.getResolvedAt());
        return new AdminReportDetailResponse(
                report.getReportId(),
                report.getStatus(),
                report.getCategory(),
                report.getDescription(),
                reporter,
                partyroom,
                review,
                report.getCreatedAt());
    }

    private Reporter buildReporter(Long userAccountId) {
        String email = userAccountRepository.findById(new UserId(userAccountId))
                .map(UserAccountData::getEmail)
                .orElse(null);
        String nickname = memberRepository.findByUserAccountId(userAccountId)
                .map(/* MemberData.profileData.nickname or 동치 */).orElse(null);
        return new Reporter(userAccountId, email, nickname);
    }

    private Partyroom buildPartyroom(Long partyroomId) {
        return partyroomRepository.findById(partyroomId)
                .map(p -> new Partyroom(
                        partyroomId,
                        p.getTitle(),  // ground-truth 확인
                        buildHost(p.getHostId())))
                .orElse(new Partyroom(partyroomId, null, null));
    }

    private Host buildHost(Long hostUserAccountId) {
        if (hostUserAccountId == null) return null;
        String nickname = memberRepository.findByUserAccountId(hostUserAccountId)
                .map(/* nickname */).orElse(null);
        return new Host(hostUserAccountId, nickname);
    }
}
```

- [ ] **Step 2: AdminReportListQuery DTO**

```java
public record AdminReportListQuery(
    List<ReportStatus> statuses,
    List<ReportCategory> categories,
    LocalDate createdFrom,
    LocalDate createdTo,
    String sortKey  // "created_at_desc" | "created_at_asc"
) {
    public static final String SORT_CREATED_AT_DESC = "created_at_desc";
    public static final String SORT_CREATED_AT_ASC = "created_at_asc";
    // sort enum 매핑은 service 내부 또는 controller에서 Pageable build
}
```

- [ ] **Step 3: 단위 테스트**

`AdminReportQueryServiceTest` (mock 4 repos):
- list happy.
- detail happy + cross-context all populated.
- detail orphan reporter (memberRepository.findByUserAccountId empty) → `nickname=null`.
- detail orphan partyroom → `partyroom.title=null`.
- `REPORT_NOT_FOUND` → 404.

**Acceptance**: Service + 5+ 단위 테스트.

### Task 17: `AdminReportQueryController` 작성

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminReportQueryController.java`

- [ ] **Step 1: Controller 작성**

```java
@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
@Validated
public class AdminReportQueryController {

    private static final String SORT_PATTERN = "created_at_desc|created_at_asc";

    private final AdminReportQueryService queryService;

    @GetMapping
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<Page<AdminReportSummaryResponse>>> getList(
            @RequestParam(required = false) List<ReportStatus> status,
            @RequestParam(required = false) List<ReportCategory> category,
            @RequestParam(name = "created_from", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(name = "created_to", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            @RequestParam(defaultValue = AdminReportListQuery.SORT_CREATED_AT_DESC)
                @Pattern(regexp = SORT_PATTERN) String sort) {

        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw ExceptionCreator.create(AdminReportException.INVALID_LIST_QUERY);
        }

        Pageable pageable = PageRequest.of(page, size,
                sort.equals("created_at_asc") ? Sort.by("createdAt").ascending() : Sort.by("createdAt").descending());

        AdminReportListQuery query = new AdminReportListQuery(status, category, createdFrom, createdTo, sort);
        return ResponseEntity.ok(ApiCommonResponse.success(queryService.getList(query, pageable)));
    }

    @GetMapping("/{reportId}")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<AdminReportDetailResponse>> getDetail(
            @PathVariable @Min(1) Long reportId) {
        return ResponseEntity.ok(ApiCommonResponse.success(queryService.getDetail(reportId)));
    }
}
```

**Acceptance**: Controller 컴파일 + 2 endpoints + Bean Validation 표준 패턴.

### Task 18: `AbstractAdminWebMvcTest` 등록 갱신

**Files:**
- Modify: `app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java`

- [ ] **Step 1: 신규 controller + service mock 추가**

```java
@WebMvcTest(controllers = {
    /* 기존 13개 */,
    AdminReportQueryController.class,
    AdminReportCommandController.class  // G4에서 추가 — 본 task에서 미리 추가 OR G4 task에서
})
public abstract class AbstractAdminWebMvcTest { /* ... */ 
    @MockBean protected AdminReportQueryService adminReportQueryService;
    @MockBean protected AdminReportCommandService adminReportCommandService;  // G4
}
```

본 task에서 G4 controller도 미리 등록하면 G4가 별 수정 불필요. 옵션:
- 옵션 A: G3에서 query만 등록, G4에서 command 추가.
- 옵션 B: G3에서 양쪽 등록(`AdminReportCommandController`는 미존재 — 컴파일 fail 발생). 안 됨.
- 결정: **A** — G3는 query만 등록.

**Acceptance**: AbstractAdminWebMvcTest 컴파일 + 기존 controller test 회귀 0.

### Task 19: `AdminReportQueryControllerTest` 작성

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminReportQueryControllerTest.java`

- [ ] **Step 1: Test cases**

list:
- 200 happy + Page envelope 검증.
- 400 — `size=999` (`@Max(200)`).
- 400 — `page=-1` (`@Min(0)`).
- 400 — `sort=invalid` (`@Pattern`).
- 400 — cross-field `createdFrom > createdTo` (INVALID_LIST_QUERY).
- 401 anonymous.
- 403 non-admin (`roles={"USER"}`).

detail:
- 200 happy + nested DTO 검증 (`$.data.reporter.nickname`, `$.data.partyroom.title` 등).
- 200 orphan tolerance (`$.data.reporter.email == null`).
- 401, 403, 404.

**Acceptance**: ~13 case 통과.

### Task 20: `AdminReportQueryServiceIT` 작성

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminReportQueryServiceIT.java`

- [ ] **Step 1: SEED**

5 reports (다양한 status/category/createdAt) + 1 host member + 1 reporter member + 1 partyroom + 1 admin.

- [ ] **Step 2: Cases**

- list status=`PENDING` → 1건.
- list category=`HARASSMENT` 다중 status → N건.
- list `createdFrom > createdTo` → INVALID_LIST_QUERY.
- list page/size pagination 검증.
- list sort `created_at_asc` vs `created_at_desc` 정렬 검증.
- detail happy + cross-context join (reporter nickname/email + host nickname).
- detail orphan reporter (member 삭제 후 detail 호출) → `reporter.nickname=null`, `reporter.email`은 userAccount 잔존이라 populated.
- detail orphan partyroom (partyroom 삭제 후) → `partyroom.title=null, partyroom.host=null`.
- detail not-found → REPORT_NOT_FOUND.

**Acceptance**: ~9 case 통과.

### Task 21: 빌드 + G3 commit

- [ ] **Step 1: 빌드 + 테스트**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest :administration:test
```

Expected: BUILD SUCCESSFUL. 신규 ~20 case 통과.

- [ ] **Step 2: G3 commit**

```bash
git add administration/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/PartyroomReportRepository*.java \
        administration/src/main/java/com/pfplaybackend/api/administration/application/service/AdminReportQueryService.java \
        administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminReportSummaryResponse.java \
        administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminReportDetailResponse.java \
        administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminReportListQuery.java \
        administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminReportQueryController.java \
        administration/src/test/java/com/pfplaybackend/api/administration/application/service/AdminReportQueryServiceTest.java \
        app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminReportQueryControllerTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminReportQueryServiceIT.java
```

```bash
git commit -m "feat(administration): C-2 GET /admin/reports + /admin/reports/{id} — 어드민 list/detail (PR 13 G3)

- AdminReportQueryController + Service (Bean Validation @Validated 표준)
- DTO: Summary + Detail(nested Reporter/Partyroom/Host/Review)
- Cross-context loose-ref read 4종 (member×2, userAccount, partyroom) — orphan tolerance(null)
- list 필터(status/category/createdFrom~To) + pagination + sort
- AbstractAdminWebMvcTest controller 1건 등록
- 단위 테스트 5 + WebMvc 13 + IT 9"
```

**Acceptance**: G3 commit 통과. list/detail endpoints 통합 동작.

---

## Chunk 4: G4 — C-2 PATCH status 전이

**Goal of chunk:** `PATCH /admin/reports/{id}` — 4 transition + INVALID_STATE_TRANSITION + RESOLUTION_NOTE_REQUIRED 검증.

**End state of chunk:** 어드민이 신고 status 전이 가능. 응답은 G3 detail shape 재사용. 단일 G4 commit.

### Task 22: `AdminReportStatusUpdateRequest` DTO 작성

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminReportStatusUpdateRequest.java`

```java
public record AdminReportStatusUpdateRequest(
    @NotNull ReportStatus status,
    @Size(max = 2000) String resolutionNote
) {}
```

terminal 진입 시 `@NotBlank` 추가는 service-layer guard로 처리 — DTO에서는 size만 검증.

**Acceptance**: DTO 컴파일.

### Task 23: `AdminReportCommandService` 작성

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/application/service/AdminReportCommandService.java`

- [ ] **Step 1: Service 작성**

```java
@Service
@RequiredArgsConstructor
@Transactional
public class AdminReportCommandService {

    private final PartyroomReportRepository reportRepository;
    private final AdminReportQueryService queryService;  // buildDetailResponse 재사용

    public AdminReportDetailResponse changeStatus(
            Long reportId,
            AdminReportStatusUpdateRequest request,
            AdminContext adminContext) {

        PartyroomReportData report = reportRepository.findById(reportId)
                .orElseThrow(() -> ExceptionCreator.create(AdminReportException.REPORT_NOT_FOUND));

        Long byAdminId = adminContext.currentAdministratorId();

        switch (request.status()) {
            case REVIEWING -> report.startReview(byAdminId);
            case RESOLVED -> {
                requireResolutionNote(request.resolutionNote());
                report.resolve(byAdminId, request.resolutionNote());
            }
            case DISMISSED -> {
                requireResolutionNote(request.resolutionNote());
                report.dismiss(byAdminId, request.resolutionNote());
            }
            case PENDING -> report.hold(byAdminId);
        }

        reportRepository.save(report);
        return queryService.buildDetailResponse(report);
    }

    private void requireResolutionNote(String note) {
        if (note == null || note.isBlank()) {
            throw ExceptionCreator.create(AdminReportException.RESOLUTION_NOTE_REQUIRED);
        }
    }
}
```

- [ ] **Step 2: 단위 테스트**

`AdminReportCommandServiceTest` (mock repos + queryService):
- 4 transition happy (각 from-status 시드).
- INVALID_STATE_TRANSITION (terminal에서 변경 — entity guard에서 throw).
- RESOLUTION_NOTE_REQUIRED (terminal note blank).
- REPORT_NOT_FOUND.

**Acceptance**: Service + 7+ 단위 테스트.

### Task 24: `AdminReportCommandController` 작성 + AbstractAdminWebMvcTest 갱신

**Files:**
- Create: `administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminReportCommandController.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java`

- [ ] **Step 1: Controller 작성**

```java
@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
@Validated
public class AdminReportCommandController {

    private final AdminReportCommandService commandService;

    @PatchMapping("/{reportId}")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<AdminReportDetailResponse>> changeStatus(
            @PathVariable @Min(1) Long reportId,
            @Valid @RequestBody AdminReportStatusUpdateRequest request,
            /* AdminContext injection — ground-truth */) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                commandService.changeStatus(reportId, request, adminContext)));
    }
}
```

`AdminContext` 주입 패턴은 PR 12b2 `AdminMemberTierCommandController` mirror — `currentAdministratorId()` 호출.

- [ ] **Step 2: AbstractAdminWebMvcTest 갱신**

```java
@WebMvcTest(controllers = {
    /* 기존 + AdminReportQueryController (G3) */,
    AdminReportCommandController.class
})
@MockBean protected AdminReportCommandService adminReportCommandService;
```

**Acceptance**: Controller + AbstractAdminWebMvcTest 컴파일.

### Task 25: WebMvc 테스트 + IT 작성

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminReportCommandControllerTest.java`
- Create: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminReportCommandServiceIT.java`

- [ ] **Step 1: WebMvc cases**

- 200 happy 4 transition (각 from-status 시드 service mock).
- 400 — `status` null (Bean Validation).
- 400 — `status` enum 외 (Jackson binding).
- 400 — `INVALID_STATE_TRANSITION` (service throw).
- 400 — `RESOLUTION_NOTE_REQUIRED` (service throw).
- 400 — `resolutionNote` >2000 (`@Size`).
- 401 anonymous, 403 non-admin, 404 not-found.

- [ ] **Step 2: IT cases**

SEED: 4 reports (각 from-status: PENDING/REVIEWING/RESOLVED/DISMISSED) + 1 admin.

- PENDING → REVIEWING happy + `reviewedByAdministratorId` set 검증.
- PENDING → DISMISSED happy + `reviewedByAdministratorId` set + `resolvedAt` set + `resolutionNote` 기록.
- REVIEWING → RESOLVED happy + `resolvedAt` set + `resolutionNote` 기록.
- REVIEWING → PENDING (보류) happy + `reviewedByAdministratorId` 보존 + `resolvedAt` null 유지.
- RESOLVED → DISMISSED 시도 → INVALID_STATE_TRANSITION (terminal 금지).
- PENDING → RESOLVED skip 시도 → INVALID_STATE_TRANSITION.
- REVIEWING → RESOLVED + `resolutionNote=""` → RESOLUTION_NOTE_REQUIRED.
- detail 응답 shape이 G3와 동일 검증 (cross-context join 재사용).

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest --tests "*AdminReport*"
```

Expected: 모든 신규 테스트 통과.

**Acceptance**: WebMvc ~10 + IT 8 통과.

### Task 26: G4 commit

- [ ] **Step 1: G4 commit**

```bash
git add administration/src/main/java/com/pfplaybackend/api/administration/application/service/AdminReportCommandService.java \
        administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminReportStatusUpdateRequest.java \
        administration/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminReportCommandController.java \
        administration/src/test/java/com/pfplaybackend/api/administration/application/service/AdminReportCommandServiceTest.java \
        app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminReportCommandControllerTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminReportCommandServiceIT.java
```

```bash
git commit -m "feat(administration): C-2 PATCH /admin/reports/{id} — 신고 상태 전이 (PR 13 G4)

- AdminReportCommandController + Service + DTO
- 5 transition matrix (D3): PENDING→REVIEWING/DISMISSED, REVIEWING→RESOLVED/DISMISSED/PENDING(보류)
- terminal 진입 시 resolutionNote 필수 (RESOLUTION_NOTE_REQUIRED)
- detail 응답 재사용 (AdminReportQueryService.buildDetailResponse)
- AbstractAdminWebMvcTest controller 1건 등록
- 단위 테스트 7 + WebMvc 10 + IT 8"
```

**Acceptance**: G4 commit. C-2 endpoint 3종 모두 통합 동작.

---

## Chunk 5: G5 — spec §13 catch-up + features.md 정정 + roadmap 갱신

**Goal of chunk:** PR 13 design.md §13 backfill (chunk SHAs + deviations) + features.md C-1/C-2 본문 ground-truth 정정 + roadmap.md PR 13 status ✅ + M5 milestone 완료. PR 8/9/12a/12b1/12b2 동형 §12 catch-up.

### Task 27: PR 13 design.md §13 backfill

**Files:**
- Modify: `docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md`

- [ ] **Step 1: chunk SHAs 수집**

```bash
git log --oneline -10
```

확인: G1/G2/G3/G4 commit SHAs.

- [ ] **Step 2: §13.1~13.4 backfill**

각 sub-section의 `_G{n} commit: <SHA pending>_`을 실제 SHA로 채움 + 발견된 deviations 채움.

PR 12b2 §12.5 Deviations 패턴 mirror — 발견된 ground-truth 불일치를 명시 (ExceptionCreator 호출 패턴 / Repository 명칭 / AdminContext API / DTO 패키지 위치 등 implementer 발견 항목).

- [ ] **Step 3: §13.6 Deviations 채움**

빌드 중 발견된 spec과 ground-truth 불일치 모두 기록. 예시:
- `partyroom.isReportable()` 신설 여부 + 정확한 active state 정의.
- `MemberContext` API 명칭 (controller injection 패턴).
- `MemberData.profileData.nickname` 또는 동치 필드 접근 경로.
- `PartyroomData.getTitle()` 접근자 명칭.
- nested DTO `Reporter`/`Partyroom`/`Host`/`Review` 패키지 위치.

**Acceptance**: §13 모든 sub-section backfill 완료.

### Task 28: features.md C-1/C-2 정정

**Files:**
- Modify: `docs/superpowers/specs/2026-04-19-admin-platform-features.md`

- [ ] **Step 1: C-1 본문 (~line 255-268) 정정**

24h 중복 방지 메커니즘 명확화:
- 기존: "신고자(reporterUserAccountId) 동일 룸 동일 카테고리 24h 내 중복 신고 방지 (unique constraint 또는 앱 검증)"
- 변경: "**앱 검증 채택 (D1)**: `existsByReporter...CreatedAtAfter(now-24h)`. unique constraint 추가 안 함 — race window는 ms 단위, audit 영향 미미."

Member-only + Guest 차단 명시:
- 기존: "권한: 인증된 Member (크루)"
- 추가: "Guest는 service-layer 또는 SpEL bean 차단 → 403"

추가 검증 명시:
- self-report 차단 (D2) — `if (partyroom.hostId == reporterUserId) → 400 SELF_REPORT_FORBIDDEN`.
- partyroom active 검증 — `if (!partyroom.isReportable()) → 400 PARTYROOM_NOT_REPORTABLE`.
- partyroom 부재 → 404 PARTYROOM_NOT_FOUND.

- [ ] **Step 2: C-2 본문 (~line 270-287) 정정**

PATCH 응답 shape:
- 기존: 명시 없음 (Status 전이 요청 예 1건만).
- 추가: "응답: 200 + `AdminReportDetailResponse` (GET /admin/reports/{id}와 동일 shape) — cross-context loose-ref join 4종(reporter member + reporter userAccount + partyroom + host member) 포함, orphan 시 `null`."

전이 매트릭스 명시:
- 추가: "허용 transition (D3): `PENDING→REVIEWING/DISMISSED`, `REVIEWING→RESOLVED/DISMISSED/PENDING(보류)`. terminal 변경 금지. `from==to` 거부."

`ReportStatusChanged` 이벤트:
- 기존: "`ReportStatusChanged` 이벤트 발행 (필요 시)"
- 변경: "**미발행 (D4)** — 현 시점 consumer 0. consumer 도입 시 별 PR로 evolve."

`reviewedByAdministratorId` set 시점:
- 추가: "첫 PATCH (REVIEWING 진입 OR PENDING→DISMISSED 직접) 시 set. hold(REVIEWING→PENDING)에서도 보존 — audit 가시."

Audit 정책:
- 추가: "**`partyroom_admin_action` 미기록 (D5)** — `partyroom_report` 자체가 audit 테이블 (reviewedByAdministratorId + resolvedAt + resolutionNote). 중복 audit 회피."

**Acceptance**: C-1/C-2 본문이 PR 13 ground-truth와 일관.

### Task 29: roadmap.md 갱신 + G5 commit

**Files:**
- Modify: `docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md`

- [ ] **Step 1: PR 13 status 갱신**

§9.1 PR 시퀀스 테이블에서 PR 13 row에 ✅ 마커 추가 (PR 12와 동일 패턴 follow).

§9.4 Milestone에서:
- M5 (PR 12-13)에 ✅ 또는 완료 표기 (어떤 패턴인지 PR 12b2 commit 이후 상태 확인 후 일관 follow).

- [ ] **Step 2: G5 commit**

```bash
git add docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md \
        docs/superpowers/specs/2026-04-19-admin-platform-features.md \
        docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md
```

```bash
git commit -m "docs(spec): PR 13 §13 catch-up + features.md C-1/C-2 정정 + roadmap M5 완료 (PR 13 G5)

- PR 13 design.md §13.1~13.4 backfill (G1~G4 SHAs + deviations)
- PR 13 design.md §13.6 deviations (ground-truth 발견 항목)
- features.md C-1: 24h 앱 검증(D1) / Member-only / self-report 차단(D2) / active 검증(D6) 명시
- features.md C-2: PATCH 응답 shape / 전이 매트릭스(D3) / 이벤트 미발행(D4) / admin_action 미기록(D5) 명시
- roadmap.md PR 13 ✅ + M5 milestone 완료"
```

**Acceptance**: G5 commit 완료. doc only.

### Task 30: G5.1 — SHA backfill follow-up

**Files:**
- Modify: `docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md`

- [ ] **Step 1: G5 SHA 확인**

```bash
git log --oneline -1
```

- [ ] **Step 2: §13.5 G5 commit SHA 채우기**

`_G5 commit: <SHA pending>_` → `_G5 commit: {actual SHA}_`.

- [ ] **Step 3: G5.1 commit**

```bash
git add docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md
```

```bash
git commit -m "docs(spec): backfill G5 SHA in PR 13 §13.5 (G5.1)"
```

**Acceptance**: G5.1 commit 완료. PR 13 모든 §13 sub-section 실제 SHA 보유. PR 12a/12b1/12b2 동형 패턴.

---

## Final verification (after Chunk 5)

- [ ] **Step 1: HEAD 확인**

```bash
git log --oneline -7
```

Expected: G5.1 → G5 → G4 → G3 → G2 → G1 → 16907ee3 (PR 12b2 G4.1).

- [ ] **Step 2: 전체 빌드 + 테스트**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew clean build
```

Expected: BUILD SUCCESSFUL. ~10-25분 (cold). 회귀 0.

- [ ] **Step 3: 신규 테스트 카운트 확인**

신규 합계 ~32 (unit ~10, WebMvc ~24, IT ~10) 정도. 정확 수는 implementer 작업 결과로 §13 backfill.

- [ ] **Step 4: 메모리 갱신**

`memory/project_pr12b1_completed_pr12b2_entry.md`(memory file)을 PR 13 완료 상태로 업데이트:
- 새 엔트리 명: `project_pr13_completed_pr14_entry.md` 또는 기존 file rename.
- 내용: HEAD SHA(G5.1) + M5 완료 + M6 (PR 14, pfplay-admin 프런트) 진입점.

---

## Roll-back strategy

각 G-commit은 독립 revert 가능:
- G5/G5.1 revert: doc only — 코드 영향 0.
- G4 revert: PATCH endpoint 제거. C-1/C-2 list/detail 동작 유지.
- G3 revert: list/detail 제거. C-1만 동작.
- G2 revert: C-1 제거. G1 도메인만 잔존(코드 dead).
- G1 revert: V13 마이그 + 도메인 + repo 제거. **단, V13이 prod DB에 적용된 상태에서 revert 시 V13 down 마이그 부재** — Flyway는 down 자동 미지원. prod 운영 중 revert 시 별 down 마이그(V14__drop_partyroom_report.sql) 필요.

**Pre-launch 단계라 V13 revert risk는 낮음** — staging/local만 적용, prod 미배포 상태에서 자유 revert.

---

**Estimated effort:** ~6-10시간 (Spring/JPA/QueryDSL 익숙 가정). 5 chunks × 1-2시간 + 빌드/테스트 대기.

**Next PR:** PR 14 (pfplay-admin 프런트엔드 — 별 레포). PR 13 endpoint 응답 shape이 PR 14 의존성 — features.md / PR 13 design.md final 기준.
