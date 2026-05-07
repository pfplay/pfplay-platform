# PR 12b1: Member 어드민 read API + listener skeleton + §12.10 polish — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR 12a user_activity_log 인프라 위에 Member 어드민 read API 2개(A-1 목록/검색, A-2 상세 + recentActivityLog 30건) 도입 + listener handler 2종 skeleton(`MemberTierChangedEvent` 신규 / `UserAccountWithdrawnEvent` evolution) + §12.10 polish 2건(`UserActivityLogId` 삭제, listener divider).

**Architecture:** A-1/A-2 endpoint는 PR 8 `AdminPartyroomQueryRepository(Impl)` QueryDSL 패턴 일관 — `AdminMemberQueryController` → `AdminMemberQueryService` → `AdminMemberQueryRepository(Impl)` 3-layer. detail 응답의 `recentActivityLog`는 별 `UserActivityLogRepository.findTop30...` Spring Data JPA derived query를 service가 cross-repository orchestration. listener 핸들러는 PR 12a 패턴(`@TransactionalEventListener(AFTER_COMMIT) + @Async(AsyncConfig.UAL_EXECUTOR_BEAN)`, drop-가능 swallow)으로 추가. ADMIN_ACTED_ON은 별도 이벤트 도입 안 하고 listener internal logic으로 row 2건 INSERT.

**Tech Stack:** Java 21, Spring Boot 3.2 (Spring Security 6.2, Spring Data JPA 3.2, Hibernate 6.4, QueryDSL 5.0), JUnit 5, Mockito, AssertJ, ArchUnit, Testcontainers (MySQL 8 + Redis), Awaitility (async test 미사용 — listener는 PR 12b1에서 unit test only).

**Spec source (read once, applied throughout):**
- `docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md` — 11 결정사항, 9 risks
- `docs/superpowers/specs/2026-04-19-admin-platform-features.md` §6.A (A-1, A-2)
- `docs/superpowers/specs/2026-04-19-admin-platform-schema.md` §4.7 V10
- `docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md` §11.8 (event_type catalog 10종)

**Branching:** Continue on `feature/admin-auth-iam-schema`. Spec commits: `3efec2d6` + `c87996db`. PR 12b1 builds on PR 12a HEAD `47cf0e23` (= last PR 12a code commit `cb20666e` + spec backfill `47cf0e23`).

**Out of scope (defer)** — spec §2.2:
- A-3 PATCH `/.../{id}/tier` + `MemberTierChangedEvent` publish (PR 12b2)
- A-4 POST `/.../{id}/withdraw` + 비식별화 + `UserAccountWithdrawnEvent` publish (PR 12b2)
- listener handler end-to-end IT (PR 12b2 — publish source 도입 후)
- `crew.user_id NOT NULL` V11 ALTER (future schema PR)
- Queue size monitoring (future 운영 PR)
- AvatarBody/Face/Icon nesting (PR 12b2 또는 future)
- `activities` (DJ_PNT/ROOM_ACT) + `walletAddress` 응답 (future)
- `member.last_activity_at` denormalized column (future)

---

## Atomic commit groupings

Per-task commits are the default. The following groups MUST land as a single commit so the tree stays green:

| Group | Tasks (chunks) | Reason |
|---|---|---|
| **G1: §12.10 polish** | Chunk 1 (Tasks 1-3) | dead code 정리(UserActivityLogId 삭제) + Repository Javadoc 갱신 + listener divider — write API 시작 전 깔끔한 baseline 확보. 단일 commit. |
| **G2: 이벤트 evolution + listener skeleton** | Chunk 2 (Tasks 4-8) | publish-consume pair atomic + forward-evolution discipline. event 시그니처 변경 ↔ 모든 caller 동시 적용 (UserAccountData.withdraw 무인자 → 1-arg). |
| **G3: A-2 detail endpoint** | Chunk 3 (Tasks 9-15) | endpoint 단위 PR 8/9 패턴 — DTO + Repo + derived query + Service + Controller + WebMvc + IT. |
| **G4: A-1 list endpoint** | Chunk 4 (Tasks 16-21) | A-2 위에 build (Repository class 공유). filter + 3 sort + pagination + WebMvc + IT. |

기타 task별 독립 commit:
- ArchUnit 회귀 검증 (자동 — 기존 annotation-driven rule이 새 handlers cover)
- spec catch-up §12 backfill (Chunk 5)

Within each group:
- Per-task step lists remain a checklist.
- **Skip the `git commit` step at the end of each task in the group.**
- Single combined commit at the end of the group's last task with the message specified there.

---

## Hard precondition (verify BEFORE Chunk 1)

- [ ] **Step 1: Confirm spec commits on HEAD ancestry**

```bash
git log --oneline -5
```

Expected: HEAD includes `c87996db docs(spec): PR 12b1 polish ...` and `3efec2d6 docs(spec): PR 12b1 design ...`. Working tree clean.

- [ ] **Step 2: Working tree clean**

```bash
git status -s
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

Expected: BUILD SUCCESSFUL. **예상 5-15분 (cold)** — Testcontainers MySQL boot 포함, Docker daemon 가동 필요. PR 12a HEAD 위에서 회귀 0 보장. `:user:test`도 G2 cascade 영향 받으므로 baseline에 포함. Documented flaky `PartyroomRepositoryAtomicUpdateIT.unused_excludes_terminated`(PR 9 §11)는 isolated 실행 시 통과 — 본 baseline에서 fail해도 PR 12b1과 무관.

- [ ] **Step 5: Inventory of existing UserAccountWithdrawnEvent + UserActivityLogId callers (Task 0)**

**Ground truth (verified during plan writing — do not deviate):**
- `UserAccountWithdrawnEvent` 사용처 4곳 (cascade target):
  1. `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java:132` (production publisher in `withdraw()`)
  2. `user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountDataTest.java:47` (`withdraw_registersUserAccountWithdrawnEvent` test)
  3. `user/src/test/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEventTest.java:10`
  4. `user/src/test/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEventTest.java:18`
- `UserAccountData.withdraw()` production caller 0건 — 시그니처 evolve 안전 (PR 12b2 A-4가 첫 caller).
- `UserActivityLogId` 사용처 3곳 (삭제 대상):
  1. `app/src/main/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogId.java` (자체)
  2. `app/src/test/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogIdTest.java` (자체 test)
  3. `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/UserActivityLogRepository.java:12` (Javadoc 멘션만 — 갱신)
- `UserActivityLogData.java` PK 매핑 노트 (line 19-24)는 보존 — DB-level composite PK 컨셉 설명.
- `UserActivityLogRepository`는 현재 `JpaRepository<UserActivityLogData, Long>` (PR 12a G1 결정).

**API ground truth (PR 12a §12.9 그대로):**
- `JsonMetadata.data()` accessor / `.empty()` / `.of(map)`. `Map.of(null)` / `JsonMetadata.of(null)` 모두 EMPTY singleton.
- `AsyncConfig.UAL_EXECUTOR_BEAN` 상수 — listener `@Async` qualifier 컴파일 타임 linkage.
- `UserId.create(Long)` / `getUid()` returns Long.
- IT 컨벤션: `app/src/test/java/.../*IT.java` + `extends AbstractIntegrationTest`.

```bash
# Re-confirm by grep:
grep -rn "new UserAccountWithdrawnEvent\|UserAccountWithdrawnEvent(" --include="*.java" user/ app/ | head -10
grep -rn "UserActivityLogId" --include="*.java" app/ | head -10
```

Expected: 4 + 3 occurrences as above.

- [ ] **Step 6: Inventory of AdminPartyroomQueryRepository pattern (PR 8 precedent)**

```bash
ls -la app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/
ls -la app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/
```

Expected: `AdminPartyroomQueryRepository.java` (interface) + `impl/AdminPartyroomQueryRepositoryImpl.java` (QueryDSL `JPAQueryFactory` 기반). Read both before Task 11 / Task 17 to mirror style.

---

## Chunk 1: G1 — §12.10 polish (UserActivityLogId 삭제 + listener divider)

**Goal of chunk:** Dead code 정리 + Repository Javadoc 정합화 + Listener readability — write API 시작 전 깔끔한 baseline 확보. 단일 G1 commit.

**End state of chunk:** `UserActivityLogId.java` + `UserActivityLogIdTest.java` 삭제. `UserActivityLogRepository.java` Javadoc 갱신. `UserActivityLogListener.java`에 `// === User/Member events ===` / `// === Party events ===` divider comment 추가. 빌드 통과 + 기존 테스트 회귀 0.

### Task 1: `UserActivityLogId` + 단위 테스트 삭제

**Files:**
- Delete: `app/src/main/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogId.java`
- Delete: `app/src/test/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogIdTest.java`

- [ ] **Step 1: 두 파일 삭제**

```bash
rm app/src/main/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogId.java
rm app/src/test/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogIdTest.java
```

- [ ] **Step 2: 컴파일 확인 — UserActivityLogId 잔존 참조 0건**

```bash
grep -rn "UserActivityLogId" --include="*.java" app/src
```

Expected: 1 hit only — `UserActivityLogRepository.java:12` Javadoc 멘션 (Task 2에서 갱신 예정). production code 참조 0건.

⚠️ **Skip commit** — G1 묶음.

### Task 2: `UserActivityLogRepository` Javadoc 갱신

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/UserActivityLogRepository.java`

- [ ] **Step 1: 현재 Javadoc read**

```bash
cat app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/UserActivityLogRepository.java
```

확인: line 12 `*` `UserActivityLogId` 값 객체는 향후 projection/검색 용도로 보존.`

- [ ] **Step 2: Javadoc 갱신**

기존:
```java
/**
 * user_activity_log JPA repository.
 *
 * <p>JPA `@Id`는 `log_id` 단독 (Hibernate가 `@IdClass + IDENTITY` 조합을 거부).
 * DB 레벨 composite PK `(log_id, occurred_at)`는 V10 스키마가 보장 — `log_id`가
 * AUTO_INCREMENT라 글로벌 유일하므로 JPA 식별자로 충분.
 * `UserActivityLogId` 값 객체는 향후 projection/검색 용도로 보존.
 *
 * <p>PR 12a는 save만으로 충분.
 * PR 12b A-2 `recentActivityLog` projection 메서드는 PR 12b에서 추가.
 */
public interface UserActivityLogRepository extends JpaRepository<UserActivityLogData, Long> {
}
```

갱신:
```java
/**
 * user_activity_log JPA repository.
 *
 * <p>JPA `@Id`는 `log_id` 단독 (Hibernate가 `@IdClass + IDENTITY` 조합을 거부).
 * DB 레벨 composite PK `(log_id, occurred_at)`는 V10 스키마가 보장 — `log_id`가
 * AUTO_INCREMENT라 글로벌 유일하므로 JPA 식별자로 충분.
 *
 * <p>PR 12b1 A-2 `recentActivityLog` projection은 단순 derived query
 * `findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc`로 cover —
 * composite key 식별자 불필요. log_id DESC tie-breaker로 같은 occurred_at row의 결정적 순서 보장.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §5.1, §11 #9
 */
public interface UserActivityLogRepository extends JpaRepository<UserActivityLogData, Long> {
    // PR 12b1 Task 10에서 findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc 추가
}
```

- [ ] **Step 3: 빌드 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

⚠️ **Skip commit** — G1 묶음.

### Task 3: `UserActivityLogListener` grouping divider comments

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java`

- [ ] **Step 1: 현재 listener read**

```bash
cat app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java | head -130
```

확인: 7 handlers 순서 (PR 12a chunk별로 추가됨) — `MemberRegisteredEvent`, `UserProfileChangedEvent`, `AdminCrewPenalizedEvent`, `CrewPenalizedEvent`, `PartyroomCreatedEvent`, `UserAccountSignedInEvent`, `CrewAccessedEvent`.

- [ ] **Step 2: divider comment 삽입 + handler 순서 재배치**

PR 12a 시점 7 handlers를 spec §5.2 / §11 #11 cadence 따라 재배치 + divider 추가:

```java
// === User/Member events ===
public void on(MemberRegisteredEvent e) { ... }       // SIGNED_UP
public void on(UserAccountSignedInEvent e) { ... }    // SIGNED_IN
public void on(UserProfileChangedEvent e) { ... }     // PROFILE_UPDATED
// (PR 12b1 Task 6/7에서 MemberTierChangedEvent / UserAccountWithdrawnEvent 추가)

// === Party events ===
public void on(PartyroomCreatedEvent e) { ... }       // PARTYROOM_CREATED
public void on(CrewAccessedEvent e) { ... }           // PARTYROOM_ENTERED/_EXITED
public void on(CrewPenalizedEvent e) { ... }          // PENALIZED_IN_PARTYROOM (by=CREW)
public void on(AdminCrewPenalizedEvent e) { ... }     // PENALIZED_IN_PARTYROOM (by=ADMIN)
```

순서 변경:
- `User/Member events` 그룹: MemberRegisteredEvent → UserAccountSignedInEvent → UserProfileChangedEvent
- `Party events` 그룹: PartyroomCreatedEvent → CrewAccessedEvent → CrewPenalizedEvent → AdminCrewPenalizedEvent

method body는 그대로 유지 (move only).

⚠️ **주의**: 메서드 body는 절대 손대지 않음 — divider comment 추가 + 메서드 reorder만. ArchUnit annotation rule (PR 12a chunk 7)은 모든 변경 후에도 통과해야 함.

- [ ] **Step 3: 단위 테스트 회귀 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerTest*" --tests "*CrossContextDependencyTest*"
```

Expected: PASS — 10 listener tests + ArchUnit 4 tests. handler order 변경은 행위에 영향 없음.

- [ ] **Step 4: G1 단일 commit**

```bash
git add -u app/src/main/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogId.java \
            app/src/test/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogIdTest.java \
            app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/UserActivityLogRepository.java \
            app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java
```

Note: 삭제된 두 파일도 `-u`로 stage. `git status` 확인 후 정확한 path로.

```bash
git commit -m "$(cat <<'EOF'
refactor(administration): §12.10 polish — UserActivityLogId 삭제 + listener divider (PR 12b1 G1)

PR 12a §12.10 미결정 polish 2건 정리:
- UserActivityLogId.java + UserActivityLogIdTest.java 삭제 (dead code, PR 12a Hibernate
  @IdClass + IDENTITY 거부로 unused). Repository는 JpaRepository<..., Long> 그대로.
- UserActivityLogRepository Javadoc에서 UserActivityLogId 멘션 제거 + PR 12b1 derived query 도입 노트.
- UserActivityLogListener에 // === User/Member events === / // === Party events === divider
  추가 + handler 순서 재배치 (semantically grouped). 메서드 body 미변경 — ArchUnit annotation
  rule 회귀 0.

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §5.1, §5.2

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Verify:
```bash
git log --oneline -3
```

Expected: HEAD `feat(administration): ... (PR 12b1 G1)`.

---

## Chunk 2: G2 — 이벤트 evolution + listener skeleton

**Goal of chunk:** `MemberTierChangedEvent` 신규 + `UserAccountWithdrawnEvent` evolution(`byAdministratorId` 추가) + `UserAccountData.withdraw()` 시그니처 evolve + 기존 cascade 갱신 + listener 핸들러 2종 추가 + 단위 테스트. 단일 G2 commit.

**End state of chunk:** Listener 9 handlers (existing 7 + new 2). 5개 user 사용처 모두 7-arg 또는 갱신된 시그니처로 cascade. publish source는 PR 12b2까지 dead path — 단위 테스트로 logic 활성.

### Task 4: `MemberTierChangedEvent` 신규 (user domain)

**Files:**
- Create: `user/src/main/java/com/pfplaybackend/api/user/domain/event/MemberTierChangedEvent.java`
- Create: `user/src/test/java/com/pfplaybackend/api/user/domain/event/MemberTierChangedEventTest.java`

- [ ] **Step 1: 단위 테스트 먼저 작성**

```java
package com.pfplaybackend.api.user.domain.event;

import com.pfplaybackend.api.common.enums.AuthorityTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberTierChangedEventTest {

    @Test
    @DisplayName("필드 5개 + DomainEvent 자동 stamp")
    void event_carries_fields() {
        MemberTierChangedEvent event = new MemberTierChangedEvent(
                100L, 50L, AuthorityTier.AM, AuthorityTier.FM, 999L);

        assertThat(event.getUserAccountId()).isEqualTo(100L);
        assertThat(event.getMemberId()).isEqualTo(50L);
        assertThat(event.getOldTier()).isEqualTo(AuthorityTier.AM);
        assertThat(event.getNewTier()).isEqualTo(AuthorityTier.FM);
        assertThat(event.getByAdministratorId()).isEqualTo(999L);
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getEventType()).isEqualTo("MemberTierChangedEvent");
        assertThat(event.getAggregateId()).isEqualTo("50");
    }
}
```

> Note: spec §11 #10 결정 — `getAggregateId()`는 `memberId`(aggregate). `userAccountId`는 listener-friendly subject.

- [ ] **Step 2: 테스트 컴파일 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :user:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 에러 (`MemberTierChangedEvent` 미존재).

- [ ] **Step 3: 이벤트 클래스 작성**

```java
package com.pfplaybackend.api.user.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import lombok.Getter;

/**
 * Member의 authority_tier가 변경된 도메인 이벤트.
 * - UserActivityLogListener listen → user_activity_log TIER_CHANGED + ADMIN_ACTED_ON 2 row (PR 12b1)
 *
 * `userAccountId`는 listener의 audit row subject.
 * `memberId`는 DDD aggregate 식별자(`getAggregateId()`)로만 사용 — listener metadata 미기록 (spec §11 #10).
 * `byAdministratorId`는 ADMIN_ACTED_ON metadata에 기록.
 *
 * 발행 source: PR 12b2 `AdminMemberTierCommandService.changeTier()` (현재 미구현).
 */
@Getter
public class MemberTierChangedEvent extends DomainEvent {
    private final Long userAccountId;
    private final Long memberId;
    private final AuthorityTier oldTier;
    private final AuthorityTier newTier;
    private final Long byAdministratorId;

    public MemberTierChangedEvent(Long userAccountId, Long memberId,
                                  AuthorityTier oldTier, AuthorityTier newTier,
                                  Long byAdministratorId) {
        super();
        this.userAccountId = userAccountId;
        this.memberId = memberId;
        this.oldTier = oldTier;
        this.newTier = newTier;
        this.byAdministratorId = byAdministratorId;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(memberId);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :user:test --tests "*MemberTierChangedEventTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G2 묶음.

### Task 5: `UserAccountWithdrawnEvent` evolution + 사용처 cascade

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEvent.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java`
- Modify: `user/src/test/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEventTest.java`
- Modify: `user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountDataTest.java`

- [ ] **Step 1: `UserAccountWithdrawnEventTest` evolve 먼저 (TDD red)**

기존 2 sites를 3-arg form으로 갱신:

```java
@Test
void aggregateId_returnsUserAccountId() {
    var event = new UserAccountWithdrawnEvent(42L, "withdrawn-42@withdrawn.local", 999L);
    assertThat(event.getAggregateId()).isEqualTo("42");
    assertThat(event.getByAdministratorId()).isEqualTo(999L);    // 🆕 추가 단언
}

@Test
void event_carriesEmail() {
    var event = new UserAccountWithdrawnEvent(42L, "withdrawn-42@withdrawn.local", 999L);
    assertThat(event.getUserAccountId()).isEqualTo(42L);
    assertThat(event.getAnonymizedEmail()).isEqualTo("withdrawn-42@withdrawn.local");
    assertThat(event.getByAdministratorId()).isEqualTo(999L);    // 🆕 추가 단언
}
```

(정확한 method 이름은 기존 file read 후 그대로 따름.)

- [ ] **Step 2: 컴파일 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :user:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 에러 (`UserAccountWithdrawnEvent` 생성자 시그니처 + `getByAdministratorId` 미존재).

- [ ] **Step 3: 이벤트 클래스 evolve**

`UserAccountWithdrawnEvent.java` 전체 교체:

```java
package com.pfplaybackend.api.user.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import lombok.Getter;

/**
 * UserAccount 탈퇴 이벤트.
 * - UserActivityLogListener listen → user_activity_log WITHDREW + ADMIN_ACTED_ON 2 row (PR 12b1)
 *
 * `byAdministratorId`는 PR 12b1 forward-evolution(PR 1 origin) — admin-trigger only 시맨틱.
 * spec roadmap §11.2: "탈퇴 시 last_login_at 보존, admin이 trigger". 사용자 self-withdrawal은 OOS.
 *
 * 발행 source: PR 12b2 `AdminMemberWithdrawCommandService` (현재 미구현). G2 시점 publisher
 * `UserAccountData.withdraw(Long byAdministratorId)`만 변경 — 호출자 0건이라 cascade 안전.
 */
@Getter
public class UserAccountWithdrawnEvent extends DomainEvent {
    private final Long userAccountId;
    private final String anonymizedEmail;
    private final Long byAdministratorId;        // 🆕 PR 12b1 추가

    public UserAccountWithdrawnEvent(Long userAccountId, String anonymizedEmail, Long byAdministratorId) {
        super();
        this.userAccountId = userAccountId;
        this.anonymizedEmail = anonymizedEmail;
        this.byAdministratorId = byAdministratorId;
    }

    @Override
    public String getAggregateId() {
        return userAccountId.toString();
    }
}
```

- [ ] **Step 4: `UserAccountData.withdraw()` 시그니처 evolve**

기존 (line 126-133, idempotency guard 포함):
```java
public void withdraw() {
    if (isWithdrawn()) {
        return; // idempotent
    }
    this.withdrawnAt = LocalDateTime.now();
    this.email = "withdrawn-" + this.userId.getUid() + "@withdrawn.local";
    registerEvent(new UserAccountWithdrawnEvent(this.userId.getUid(), this.email));
}
```

갱신 (idempotency guard 보존, 시그니처에 `Long byAdministratorId` 추가, registerEvent에 3rd arg 전달):
```java
public void withdraw(Long byAdministratorId) {
    if (isWithdrawn()) {
        return; // idempotent
    }
    this.withdrawnAt = LocalDateTime.now();
    this.email = "withdrawn-" + this.userId.getUid() + "@withdrawn.local";
    registerEvent(new UserAccountWithdrawnEvent(this.userId.getUid(), this.email, byAdministratorId));
}
```

> Note: production caller 0건이라 cascade 안전. PR 12b2 A-4가 첫 caller로 `userAccount.withdraw(adminContext.currentAdministratorId())` 호출. `lastLoginAt`은 변경 없음 — spec roadmap §11.2.2 "탈퇴 시 last_login_at 보존" 준수.

- [ ] **Step 5: `UserAccountDataTest.withdraw_registersUserAccountWithdrawnEvent` 갱신**

기존 test가 `userAccount.withdraw()`를 무인자로 호출 — 1-arg form으로 갱신:

```java
@Test
@DisplayName("withdraw(byAdministratorId): UserAccountWithdrawnEvent 등록 + 비식별화")
void withdraw_registersUserAccountWithdrawnEvent() {
    UserAccountData ua = /* 기존 fixture */;

    ua.withdraw(999L);                              // 🆕 byAdministratorId

    // existing assertions ...
    // 추가:
    UserAccountWithdrawnEvent event = ua.pollDomainEvents().stream()
            .filter(e -> e instanceof UserAccountWithdrawnEvent)
            .map(e -> (UserAccountWithdrawnEvent) e)
            .findFirst().orElseThrow();
    assertThat(event.getByAdministratorId()).isEqualTo(999L);
}
```

(정확한 fixture는 기존 test read 후 그대로 따름.)

- [ ] **Step 6: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :user:test --tests "*UserAccountWithdrawnEventTest*" --tests "*UserAccountDataTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G2 묶음.

### Task 6: `UserActivityLogListener.on(MemberTierChangedEvent)` handler — TDD

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java`

- [ ] **Step 1: 단위 테스트 추가 (TDD red)**

`UserActivityLogListenerTest`에 추가:

```java
@Test
@DisplayName("MemberTierChangedEvent → TIER_CHANGED + ADMIN_ACTED_ON 2 row INSERT")
void on_MemberTierChangedEvent_inserts_two_rows() {
    MemberTierChangedEvent event = new MemberTierChangedEvent(
            100L, 50L, AuthorityTier.AM, AuthorityTier.FM, 999L);

    listener.on(event);

    ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
    verify(repository, times(2)).save(cap.capture());
    var rows = cap.getAllValues();

    // Row 1: TIER_CHANGED (insertion order — log_id ASC)
    assertThat(rows.get(0).getEventType()).isEqualTo(UserActivityEventType.TIER_CHANGED.name());
    assertThat(rows.get(0).getUserAccountId()).isEqualTo(100L);
    assertThat(rows.get(0).getPartyroomId()).isNull();
    assertThat(rows.get(0).getMetadata().data())
            .containsEntry("old_tier", "AM")
            .containsEntry("new_tier", "FM")
            .containsEntry("by_administrator_id", 999L);
    assertThat(rows.get(0).getOccurredAt()).isEqualTo(event.getOccurredAt());

    // Row 2: ADMIN_ACTED_ON
    assertThat(rows.get(1).getEventType()).isEqualTo(UserActivityEventType.ADMIN_ACTED_ON.name());
    assertThat(rows.get(1).getUserAccountId()).isEqualTo(100L);
    assertThat(rows.get(1).getMetadata().data())
            .containsEntry("action_type", "TIER_CHANGED")
            .containsEntry("by_administrator_id", 999L);
    assertThat(rows.get(1).getOccurredAt()).isEqualTo(event.getOccurredAt());
}
```

import 추가: `com.pfplaybackend.api.user.domain.event.MemberTierChangedEvent`, `com.pfplaybackend.api.common.enums.AuthorityTier`.

- [ ] **Step 2: 테스트 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerTest*"
```

Expected: 1 FAIL (handler 미존재).

- [ ] **Step 3: listener에 핸들러 추가**

`UserActivityLogListener.java`의 `// === User/Member events ===` 그룹 끝(UserProfileChangedEvent 후)에 추가:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async(AsyncConfig.UAL_EXECUTOR_BEAN)
public void on(MemberTierChangedEvent e) {
    // Row 1: 대상 user 관점 TIER_CHANGED (insertion 먼저)
    Map<String, Object> tierMeta = new HashMap<>();
    tierMeta.put("old_tier", e.getOldTier().name());
    tierMeta.put("new_tier", e.getNewTier().name());
    tierMeta.put("by_administrator_id", e.getByAdministratorId());
    log(e.getUserAccountId(), UserActivityEventType.TIER_CHANGED, null,
        JsonMetadata.of(tierMeta), e.getOccurredAt());

    // Row 2: 대상 user 관점 ADMIN_ACTED_ON (log_id가 더 큼 → ORDER BY DESC에서 먼저 노출)
    Map<String, Object> actMeta = new HashMap<>();
    actMeta.put("action_type", "TIER_CHANGED");
    actMeta.put("by_administrator_id", e.getByAdministratorId());
    log(e.getUserAccountId(), UserActivityEventType.ADMIN_ACTED_ON, null,
        JsonMetadata.of(actMeta), e.getOccurredAt());
}
```

import 추가: `com.pfplaybackend.api.user.domain.event.MemberTierChangedEvent`.

- [ ] **Step 4: 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerTest*"
```

Expected: PASS (existing 10 + new 1 = 11 tests).

⚠️ **Skip commit** — G2 묶음.

### Task 7: `UserActivityLogListener.on(UserAccountWithdrawnEvent)` handler — TDD

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java`

- [ ] **Step 1: 단위 테스트 추가 (TDD red)**

```java
@Test
@DisplayName("UserAccountWithdrawnEvent → WITHDREW + ADMIN_ACTED_ON 2 row INSERT")
void on_UserAccountWithdrawnEvent_inserts_two_rows() {
    UserAccountWithdrawnEvent event = new UserAccountWithdrawnEvent(
            100L, "withdrawn-100@withdrawn.local", 999L);

    listener.on(event);

    ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
    verify(repository, times(2)).save(cap.capture());
    var rows = cap.getAllValues();

    // Row 1: WITHDREW
    assertThat(rows.get(0).getEventType()).isEqualTo(UserActivityEventType.WITHDREW.name());
    assertThat(rows.get(0).getUserAccountId()).isEqualTo(100L);
    assertThat(rows.get(0).getPartyroomId()).isNull();
    assertThat(rows.get(0).getMetadata().data())
            .containsEntry("by_administrator_id", 999L);
    assertThat(rows.get(0).getOccurredAt()).isEqualTo(event.getOccurredAt());

    // Row 2: ADMIN_ACTED_ON
    assertThat(rows.get(1).getEventType()).isEqualTo(UserActivityEventType.ADMIN_ACTED_ON.name());
    assertThat(rows.get(1).getUserAccountId()).isEqualTo(100L);
    assertThat(rows.get(1).getMetadata().data())
            .containsEntry("action_type", "WITHDRAW")
            .containsEntry("by_administrator_id", 999L);
}
```

import: `com.pfplaybackend.api.user.domain.event.UserAccountWithdrawnEvent`.

- [ ] **Step 2: 테스트 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerTest*"
```

Expected: 1 FAIL (handler 미존재).

- [ ] **Step 3: listener에 핸들러 추가**

Task 6 핸들러 다음(여전히 `// === User/Member events ===` 그룹 안):

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async(AsyncConfig.UAL_EXECUTOR_BEAN)
public void on(UserAccountWithdrawnEvent e) {
    // Row 1: WITHDREW
    Map<String, Object> wMeta = new HashMap<>();
    wMeta.put("by_administrator_id", e.getByAdministratorId());
    log(e.getUserAccountId(), UserActivityEventType.WITHDREW, null,
        JsonMetadata.of(wMeta), e.getOccurredAt());

    // Row 2: ADMIN_ACTED_ON
    Map<String, Object> actMeta = new HashMap<>();
    actMeta.put("action_type", "WITHDRAW");
    actMeta.put("by_administrator_id", e.getByAdministratorId());
    log(e.getUserAccountId(), UserActivityEventType.ADMIN_ACTED_ON, null,
        JsonMetadata.of(actMeta), e.getOccurredAt());
}
```

import: `com.pfplaybackend.api.user.domain.event.UserAccountWithdrawnEvent`.

- [ ] **Step 4: 단위 테스트 통과 확인 + ArchUnit 회귀**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerTest*" --tests "*CrossContextDependencyTest*"
```

Expected: PASS — 12 listener tests (existing 10 + new 2) + 4 ArchUnit tests (annotation-driven rule이 9 handlers 자동 cover).

⚠️ **Skip commit** — G2 묶음.

### Task 8: G2 단일 commit

**Files:** (Tasks 4-7 변경분 합산)

- [ ] **Step 1: 변경 파일 점검**

```bash
git status
```

Expected: 6 modified/new files:
- `user/src/main/java/com/pfplaybackend/api/user/domain/event/MemberTierChangedEvent.java` (new)
- `user/src/main/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEvent.java` (modified)
- `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java` (modified)
- `user/src/test/java/com/pfplaybackend/api/user/domain/event/MemberTierChangedEventTest.java` (new)
- `user/src/test/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEventTest.java` (modified)
- `user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountDataTest.java` (modified)
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java` (modified)
- `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java` (modified)

총 8 files (4 main + 4 test).

- [ ] **Step 2: 회귀 빌드**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :user:test :app:test
```

Expected: BUILD SUCCESSFUL. user 모듈 + app 모듈 unit tests 모두 통과.

- [ ] **Step 3: G2 단일 commit**

```bash
git add user/src/main/java/com/pfplaybackend/api/user/domain/event/MemberTierChangedEvent.java \
    user/src/main/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEvent.java \
    user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java \
    user/src/test/java/com/pfplaybackend/api/user/domain/event/MemberTierChangedEventTest.java \
    user/src/test/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEventTest.java \
    user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountDataTest.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java \
    app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java
```

```bash
git commit -m "$(cat <<'EOF'
feat(user+administration): MemberTierChanged + UserAccountWithdrawn evolution + 2 listener handlers (PR 12b1 G2)

User domain event evolution + listener consumer skeleton:
- MemberTierChangedEvent (user domain, 신규) — userAccountId/memberId/oldTier/newTier/byAdministratorId.
- UserAccountWithdrawnEvent (PR 1) forward-evolution — byAdministratorId 3rd 필드 추가.
- UserAccountData.withdraw() → withdraw(Long byAdministratorId). production caller 0건이라 cascade 안전.
- UserActivityLogListener: on(MemberTierChangedEvent) → TIER_CHANGED + ADMIN_ACTED_ON 2 row.
- UserActivityLogListener: on(UserAccountWithdrawnEvent) → WITHDREW + ADMIN_ACTED_ON 2 row.

Listener handler skeleton dead path (publish source PR 12b2까지) — unit test로 logic 활성.

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §4.1, §4.2, §4.3, §11 #6, §11 #10

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Verify:
```bash
git log --oneline -4
```

Expected: HEAD `feat(user+administration): MemberTierChanged + UserAccountWithdrawn evolution + 2 listener handlers (PR 12b1 G2)`.

---

## Chunk 3: G3 — A-2 detail endpoint

**Goal of chunk:** `GET /api/v1/admin/members/{memberId}` end-to-end — DTO + repository + derived query + service + controller + WebMvc + IT. 단일 G3 commit.

**End state of chunk:** 어드민이 `/api/v1/admin/members/{id}` 호출 시 member + linked userAccount + profile + recentActivityLog 30건 응답. PR 12a audit log가 어드민 인터페이스에서 검증됨.

### Task 9: Response DTOs (`AdminMemberDetailResponse`, `RecentActivityLogItem`, `UserAccountSummary`, `MemberProfileSummary`)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminMemberDetailResponse.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/RecentActivityLogItem.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/UserAccountSummary.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/MemberProfileSummary.java`

- [ ] **Step 1: DTO 4개 작성 (records)**

`AdminMemberDetailResponse.java`:
```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.common.enums.AuthorityTier;

import java.time.LocalDateTime;
import java.util.List;

public record AdminMemberDetailResponse(
        Long memberId,
        UserAccountSummary userAccount,
        MemberProfileSummary profile,
        AuthorityTier authorityTier,
        LocalDateTime createdAt,
        List<RecentActivityLogItem> recentActivityLog
) {}
```

`UserAccountSummary.java`:
```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;

import java.time.LocalDateTime;

public record UserAccountSummary(
        Long userAccountId,
        String email,
        ProviderType providerType,
        LocalDateTime lastLoginAt,
        LocalDateTime withdrawnAt
) {}
```

`MemberProfileSummary.java`:
```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

public record MemberProfileSummary(
        String nickname,
        String introduction
) {}
```

`RecentActivityLogItem.java`:
```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.administration.domain.value.JsonMetadata;

import java.time.LocalDateTime;

public record RecentActivityLogItem(
        String eventType,
        Long partyroomId,
        JsonMetadata metadata,
        LocalDateTime occurredAt
) {}
```

- [ ] **Step 2: 빌드 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

⚠️ **Skip commit** — G3 묶음.

### Task 10: `UserActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDesc` derived query

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/UserActivityLogRepository.java`

- [ ] **Step 1: derived method 추가**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * user_activity_log JPA repository.
 *
 * <p>JPA `@Id`는 `log_id` 단독 (Hibernate가 `@IdClass + IDENTITY` 조합을 거부).
 * DB 레벨 composite PK `(log_id, occurred_at)`는 V10 스키마가 보장 — `log_id`가
 * AUTO_INCREMENT라 글로벌 유일하므로 JPA 식별자로 충분.
 *
 * <p>PR 12b1 A-2 `recentActivityLog` projection은 단순 derived query로 cover.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §3.2, §6
 */
public interface UserActivityLogRepository extends JpaRepository<UserActivityLogData, Long> {

    /**
     * Member detail의 recentActivityLog 응답용 — 특정 user의 최근 30건.
     * idx_ual_user_time DESC + LIMIT 30 cover. partition pruning은 안 됨(WHERE on user_account_id).
     * tie-breaker: log_id DESC (Spring Data JPA derived query는 자동 ID 추가하지 않으므로,
     * 같은 occurred_at의 row는 DB 기본 순서로 노출 — 단위 테스트로 검증).
     */
    List<UserActivityLogData> findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(Long userAccountId);
}
```

> Note: 메서드명에 `LogIdDesc` 추가 — 같은 `occurred_at` row의 결정적 순서 보장 (spec §11 #9).

- [ ] **Step 2: derived query 컴파일 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. (Spring Data JPA derived query는 runtime 검증 — IT에서 정확성 확인.)

⚠️ **Skip commit** — G3 묶음.

### Task 11: `AdminMemberQueryRepository` interface + `AdminMemberDetailRow` projection

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminMemberQueryRepository.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/dto/AdminMemberDetailRow.java`

- [ ] **Step 1: Repository interface 작성**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberDetailRow;

import java.util.Optional;

/**
 * Member 어드민 query repository (read-only).
 * QueryDSL 구현 — PR 8 AdminPartyroomQueryRepositoryImpl 패턴 일관.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §6
 */
public interface AdminMemberQueryRepository {

    /**
     * A-2: member + linked userAccount join으로 detail row 1건 조회.
     * recentActivityLog는 별 UserActivityLogRepository 호출(service orchestration).
     */
    Optional<AdminMemberDetailRow> findDetail(Long memberId);

    // A-1 search 메서드는 Task 17에서 추가 (G4 chunk).
}
```

- [ ] **Step 2: Projection DTO 작성**

`AdminMemberDetailRow.java`:
```java
package com.pfplaybackend.api.administration.adapter.out.persistence.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.enums.AuthorityTier;

import java.time.LocalDateTime;

/**
 * AdminMemberQueryRepository.findDetail Projection — member + userAccount 합본.
 * service에서 AdminMemberDetailResponse + recentActivityLog로 합성.
 */
public record AdminMemberDetailRow(
        Long memberId,
        Long userAccountId,
        String email,
        ProviderType providerType,
        LocalDateTime lastLoginAt,
        LocalDateTime withdrawnAt,
        String nickname,
        String introduction,
        AuthorityTier authorityTier,
        LocalDateTime createdAt
) {}
```

- [ ] **Step 3: 빌드 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

⚠️ **Skip commit** — G3 묶음.

### Task 12: `AdminMemberQueryRepositoryImpl.findDetail` (QueryDSL)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminMemberQueryRepositoryImpl.java`

- [ ] **Step 1: QueryDSL 구현 (findDetail만 — search는 Task 17)**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdminMemberQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberDetailRow;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.pfplaybackend.api.user.domain.entity.data.QMemberData.memberData;
import static com.pfplaybackend.api.user.domain.entity.data.QUserAccountData.userAccountData;

@Repository
@RequiredArgsConstructor
public class AdminMemberQueryRepositoryImpl implements AdminMemberQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<AdminMemberDetailRow> findDetail(Long memberId) {
        AdminMemberDetailRow row = queryFactory
                .select(Projections.constructor(AdminMemberDetailRow.class,
                        memberData.memberId,
                        memberData.userAccountId,
                        userAccountData.email,
                        userAccountData.providerType,
                        userAccountData.lastLoginAt,
                        userAccountData.withdrawnAt,
                        memberData.profileData.nickname,
                        memberData.profileData.introduction,
                        memberData.authorityTier,
                        userAccountData.createdAt))
                .from(memberData)
                .leftJoin(userAccountData).on(userAccountData.userId.uid.eq(memberData.userAccountId))
                .where(memberData.memberId.eq(memberId))
                .fetchOne();
        return Optional.ofNullable(row);
    }
}
```

> Note: QueryDSL `Q*` 클래스명/필드명은 컴파일러 annotation processor가 자동 생성. 정확한 path는 PR 8 `AdminPartyroomQueryRepositoryImpl` 참고. `userAccountData.userId.uid` join 컬럼 정확성은 IT에서 검증.

- [ ] **Step 2: 빌드 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. Q-class 자동생성되어 import 통과. 만약 Q-class 미존재로 fail 시 `./gradlew clean compileJava`로 annotation processor 재실행.

⚠️ **Skip commit** — G3 묶음.

### Task 13: `AdminMemberQueryService.getDetail` — TDD

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminMemberQueryService.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/exception/AdminMemberException.java`
- Create: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminMemberQueryServiceTest.java`

- [ ] **Step 1: Exception 코드 추가**

```java
package com.pfplaybackend.api.administration.application.exception;

import com.pfplaybackend.api.common.exception.ErrorType;
import com.pfplaybackend.api.common.exception.ExceptionDefinition;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AdminMemberException implements ExceptionDefinition {

    MEMBER_NOT_FOUND("MBR-001", "Member 가 존재하지 않음", ErrorType.NOT_FOUND);

    private final String code;
    private final String message;
    private final ErrorType errorType;
}
```

> Note: `ExceptionDefinition` / `ErrorType` 정확한 import는 PR 8 `PartyroomException` 참고.

- [ ] **Step 2: 단위 테스트 먼저 작성 — getDetail orchestration**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberDetailResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminMemberQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberDetailRow;
import com.pfplaybackend.api.administration.application.exception.AdminMemberException;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.RestApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMemberQueryServiceTest {

    @Mock AdminMemberQueryRepository memberRepo;
    @Mock UserActivityLogRepository ualRepo;
    AdminMemberQueryService service;

    @BeforeEach
    void setUp() {
        service = new AdminMemberQueryService(memberRepo, ualRepo);
    }

    @Test
    @DisplayName("getDetail: member + recentActivityLog 합산 응답")
    void getDetail_combines_two_sources() {
        AdminMemberDetailRow row = new AdminMemberDetailRow(
                50L, 100L, "u@x", ProviderType.GOOGLE,
                LocalDateTime.of(2026, 4, 28, 10, 0), null,
                "Nick", "intro", AuthorityTier.FM,
                LocalDateTime.of(2025, 12, 1, 0, 0));
        when(memberRepo.findDetail(50L)).thenReturn(Optional.of(row));

        UserActivityLogData log1 = UserActivityLogData.of(
                100L, UserActivityEventType.PARTYROOM_ENTERED, 1L,
                JsonMetadata.empty(), LocalDateTime.of(2026, 4, 28, 12, 0));
        when(ualRepo.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(100L))
                .thenReturn(List.of(log1));

        AdminMemberDetailResponse response = service.getDetail(50L);

        assertThat(response.memberId()).isEqualTo(50L);
        assertThat(response.userAccount().userAccountId()).isEqualTo(100L);
        assertThat(response.userAccount().email()).isEqualTo("u@x");
        assertThat(response.profile().nickname()).isEqualTo("Nick");
        assertThat(response.authorityTier()).isEqualTo(AuthorityTier.FM);
        assertThat(response.recentActivityLog()).hasSize(1);
        assertThat(response.recentActivityLog().get(0).eventType()).isEqualTo("PARTYROOM_ENTERED");
        assertThat(response.recentActivityLog().get(0).partyroomId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getDetail: memberId 없으면 MEMBER_NOT_FOUND")
    void getDetail_throws_when_missing() {
        when(memberRepo.findDetail(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(99L))
                .isInstanceOf(RestApiException.class);
        // 정확한 exception 매핑은 ExceptionCreator 패턴 PR 8 참고
    }
}
```

- [ ] **Step 3: 테스트 컴파일 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 에러 (`AdminMemberQueryService` 미존재).

- [ ] **Step 4: Service 구현**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.*;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminMemberQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberDetailRow;
import com.pfplaybackend.api.administration.application.exception.AdminMemberException;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminMemberQueryService {

    private static final int RECENT_ACTIVITY_LIMIT = 30;    // spec §3.2

    private final AdminMemberQueryRepository memberRepository;
    private final UserActivityLogRepository userActivityLogRepository;

    @Transactional(readOnly = true)
    public AdminMemberDetailResponse getDetail(Long memberId) {
        AdminMemberDetailRow row = memberRepository.findDetail(memberId)
                .orElseThrow(() -> ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND));

        List<UserActivityLogData> logs =
                userActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(row.userAccountId());

        List<RecentActivityLogItem> activityItems = logs.stream()
                .map(d -> new RecentActivityLogItem(
                        d.getEventType(), d.getPartyroomId(), d.getMetadata(), d.getOccurredAt()))
                .toList();

        return new AdminMemberDetailResponse(
                row.memberId(),
                new UserAccountSummary(row.userAccountId(), row.email(), row.providerType(),
                        row.lastLoginAt(), row.withdrawnAt()),
                new MemberProfileSummary(row.nickname(), row.introduction()),
                row.authorityTier(),
                row.createdAt(),
                activityItems);
    }
}
```

- [ ] **Step 5: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberQueryServiceTest*"
```

Expected: PASS (2 tests).

⚠️ **Skip commit** — G3 묶음.

### Task 14: `AdminMemberQueryController` GET `{memberId}` + WebMvc

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryController.java`
- Create: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryControllerTest.java`

- [ ] **Step 1: WebMvc 단위 테스트 먼저 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.*;
import com.pfplaybackend.api.administration.application.exception.AdminMemberException;
import com.pfplaybackend.api.administration.application.service.AdminMemberQueryService;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
// ... 추가 imports (PR 8 AbstractAdminWebMvcTest 패턴 참조)

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
// ...

class AdminMemberQueryControllerTest extends AbstractAdminWebMvcTest {

    @MockBean AdminMemberQueryService adminMemberQueryService;
    // @MockBean — when(...) stub-test 패턴 (PR 8 AbstractAdminWebMvcTest 일관)

    @Test
    @DisplayName("GET /admin/members/{id} 200 — detail 응답")
    void getDetail_returns_200() throws Exception {
        AdminMemberDetailResponse response = new AdminMemberDetailResponse(
                50L,
                new UserAccountSummary(100L, "u@x", ProviderType.GOOGLE, LocalDateTime.now(), null),
                new MemberProfileSummary("Nick", "intro"),
                AuthorityTier.FM,
                LocalDateTime.now(),
                List.of());
        when(adminMemberQueryService.getDetail(50L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/admin/members/50")
                        .with(/* admin context */))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(50))
                .andExpect(jsonPath("$.data.userAccount.userAccountId").value(100))
                .andExpect(jsonPath("$.data.profile.nickname").value("Nick"))
                .andExpect(jsonPath("$.data.recentActivityLog").isArray());
    }

    @Test
    @DisplayName("GET /admin/members/{id} 404 — MEMBER_NOT_FOUND")
    void getDetail_returns_404() throws Exception {
        when(adminMemberQueryService.getDetail(99L))
                .thenThrow(ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/admin/members/99")
                        .with(/* admin context */))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /admin/members/{id} 401 — 미인증")
    void getDetail_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/members/50"))
                .andExpect(status().isUnauthorized());
    }
}
```

> Note: `AbstractAdminWebMvcTest` 패턴은 PR 8 admin webmvc test (e.g. `AdminPartyroomCommandControllerTest`) 그대로 — `@MockBean AdminMemberQueryService` + admin context fixture.

- [ ] **Step 2: 테스트 컴파일 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 에러 (`AdminMemberQueryController` 미존재).

- [ ] **Step 3: Controller 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberDetailResponse;
import com.pfplaybackend.api.administration.application.service.AdminMemberQueryService;
import com.pfplaybackend.api.common.response.ApiCommonResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin Member Query API")
@RequestMapping("/api/v1/admin/members")
@RestController
@RequiredArgsConstructor
public class AdminMemberQueryController {

    private final AdminMemberQueryService adminMemberQueryService;

    @GetMapping("/{memberId}")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<AdminMemberDetailResponse>> getDetail(
            @PathVariable Long memberId) {
        AdminMemberDetailResponse response = adminMemberQueryService.getDetail(memberId);
        return ResponseEntity.ok(ApiCommonResponse.success(response));
    }

    // GET / list 메서드는 Task 21에서 추가 (G4 chunk).
}
```

- [ ] **Step 4: WebMvc 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberQueryControllerTest*"
```

Expected: PASS (3 tests — 200, 404, 401). 만약 admin 권한 fixture 미일치 시 PR 8 `AbstractAdminWebMvcTest` 패턴 그대로 따름.

⚠️ **Skip commit** — G3 묶음.

### Task 15: G3 IT — A-2 detail end-to-end + G3 commit

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminMemberQueryServiceIT.java`

- [ ] **Step 1: IT 작성 — 실제 DB + member 30+1 activity 행 SEED + recentActivityLog 정확히 30 limit 검증**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberDetailResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminMemberQueryServiceIT extends AbstractIntegrationTest {

    @Autowired AdminMemberQueryService service;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired UserActivityLogRepository userActivityLogRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private Long userAccountId;     // saved.getUserId().getUid() — UserAccountData 저장 후 캡처
    private Long memberId;

    @BeforeEach
    void seed() {
        // **인벤토리 선행**: UserAccountData/MemberData/ProfileData factory 시그니처는
        // 본 IT 작성 직전 PR 8 IT fixture (예: AdminPartyroomQueryRepositoryImplIT) read해서
        // 정확한 형태 확인. 일반적으로 `UserAccountData.createForLocal(userId, email, hashedPwd)`
        // 또는 `createForGoogle(...)`. `UserId`는 `new UserId(SEED_LITERAL)` (literal long) 또는
        // 자동 생성. 본 IT는 saveAndFlush 후 `saved.getUserId().getUid()`로 capture하여 테스트 내내 사용.

        // 1) UserAccount SEED — 저장 후 userAccountId 캡처
        UserAccountData ua = /* PR 8 IT fixture 패턴: createForLocal/createForGoogle */;
        UserAccountData savedUa = userAccountRepository.saveAndFlush(ua);
        this.userAccountId = savedUa.getUserId().getUid();

        // 2) Member SEED — userAccountId를 FK로 사용
        MemberData member = MemberData.create(userAccountId, AuthorityTier.FM, /* ProfileData factory call */);
        MemberData savedMember = memberRepository.saveAndFlush(member);
        this.memberId = savedMember.getMemberId();

        // 3) user_activity_log 31 row SEED — 30 limit 검증
        LocalDateTime base = LocalDateTime.of(2026, 4, 28, 12, 0);
        for (int i = 0; i < 31; i++) {
            userActivityLogRepository.save(UserActivityLogData.of(
                    userAccountId, UserActivityEventType.PARTYROOM_ENTERED, (long)(i + 1),
                    JsonMetadata.of(Map.of("seq", i)),
                    base.plusSeconds(i)));
        }
    }

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status -> {
            userActivityLogRepository.deleteAll();
            memberRepository.deleteAll();
            userAccountRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("getDetail: member + recentActivityLog 정확히 30 limit + DESC 정렬")
    void getDetail_returns_30_recent_activities_in_descending_order() {
        AdminMemberDetailResponse response = service.getDetail(memberId);

        assertThat(response.memberId()).isEqualTo(memberId);
        assertThat(response.userAccount().userAccountId()).isEqualTo(userAccountId);
        assertThat(response.recentActivityLog()).hasSize(30);

        // DESC 정렬 확인 — 가장 최근 row(seq=30, base+30s)가 첫 번째
        assertThat(response.recentActivityLog().get(0).metadata().data())
                .containsEntry("seq", 30);
        assertThat(response.recentActivityLog().get(29).metadata().data())
                .containsEntry("seq", 1);
    }
}
```

> Note: 정확한 `MemberData.create(...)` / `UserAccountData.createForLocal(...)` / `ProfileData` 인자 등은 IT 작성 직전에 PR 8 `AdminPartyroomQueryRepositoryImplIT` 등 기존 IT의 SEED helper를 read하여 그대로 mirror. `userId`는 generated value이므로 `userAccountId = savedUa.getUserId().getUid()` 패턴으로 capture (`USER_ACCOUNT_ID = 7777L` 같은 literal 고정 시 generated value mismatch).

- [ ] **Step 2: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*AdminMemberQueryServiceIT*"
```

(timeout=900000.) Expected: PASS.

만약 `JpaRepository.deleteAll()` 또는 SEED 단계에서 partition / FK 이슈 → cleanup 순서 점검(UAL → Member → UserAccount).

- [ ] **Step 3: 회귀 빌드**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest
```

Expected: BUILD SUCCESSFUL except documented flaky `PartyroomRepositoryAtomicUpdateIT.unused_excludes_terminated`(PR 9 §11).

- [ ] **Step 4: G3 단일 commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminMemberDetailResponse.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/RecentActivityLogItem.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/UserAccountSummary.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/MemberProfileSummary.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/UserActivityLogRepository.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminMemberQueryRepository.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/dto/AdminMemberDetailRow.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminMemberQueryRepositoryImpl.java \
    app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminMemberQueryService.java \
    app/src/main/java/com/pfplaybackend/api/administration/application/exception/AdminMemberException.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryController.java \
    app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminMemberQueryServiceTest.java \
    app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryControllerTest.java \
    app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminMemberQueryServiceIT.java
```

```bash
git commit -m "$(cat <<'EOF'
feat(administration): A-2 GET /admin/members/{id} — detail + recentActivityLog 30 (PR 12b1 G3)

- AdminMemberQueryRepository(Impl) — QueryDSL findDetail (member + userAccount join).
- UserActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc — derived query.
- AdminMemberQueryService.getDetail — cross-repository orchestration (member + UAL).
- AdminMemberQueryController GET /api/v1/admin/members/{memberId} + @adminAuth.isAdmin().
- DTO: AdminMemberDetailResponse / UserAccountSummary / MemberProfileSummary / RecentActivityLogItem.
- AdminMemberException.MEMBER_NOT_FOUND (MBR-001).
- 단위/WebMvc/IT 테스트 — IT는 31 row SEED → 정확히 30 limit + DESC 정렬 검증.

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §3.2, §6, §8.1, §8.2

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Verify:
```bash
git log --oneline -5
```

Expected: HEAD `feat(administration): A-2 GET /admin/members/{id} — detail + recentActivityLog 30 (PR 12b1 G3)`.

---

## Chunk 4: G4 — A-1 list endpoint

**Goal of chunk:** `GET /api/v1/admin/members` end-to-end — filter (email LIKE / tier / dates) + 3 sort + pagination + WebMvc + IT. 단일 G4 commit.

**End state of chunk:** 어드민이 `/api/v1/admin/members?email=u&tier=FM&sort=last_activity_desc&page=0&size=50` 호출 시 Member 목록 응답.

### Task 16: `AdminMemberSummaryResponse` + `AdminMemberSummaryRow` + `AdminMemberListQuery`

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminMemberSummaryResponse.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminMemberListQuery.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/dto/AdminMemberSummaryRow.java`

- [ ] **Step 1: DTO 3개 작성**

`AdminMemberSummaryResponse.java`:
```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.enums.AuthorityTier;

import java.time.LocalDateTime;

public record AdminMemberSummaryResponse(
        Long memberId,
        Long userAccountId,
        String email,
        ProviderType providerType,
        String nickname,
        AuthorityTier authorityTier,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        boolean withdrawn,
        LocalDateTime withdrawnAt
) {}
```

`AdminMemberListQuery.java`:
```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.common.enums.AuthorityTier;

import java.time.LocalDate;

/**
 * A-1 list query parameters. Controller에서 Spring binding.
 *
 * sort 허용 값: created_at_desc(default), created_at_asc, last_activity_desc.
 * size cap 200 (Controller validation).
 */
public record AdminMemberListQuery(
        String email,
        AuthorityTier tier,
        LocalDate joinedFrom,
        LocalDate joinedTo,
        String sort
) {
    public static final String SORT_CREATED_AT_DESC = "created_at_desc";
    public static final String SORT_CREATED_AT_ASC = "created_at_asc";
    public static final String SORT_LAST_ACTIVITY_DESC = "last_activity_desc";
}
```

`AdminMemberSummaryRow.java`:
```java
package com.pfplaybackend.api.administration.adapter.out.persistence.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.enums.AuthorityTier;

import java.time.LocalDateTime;

public record AdminMemberSummaryRow(
        Long memberId,
        Long userAccountId,
        String email,
        ProviderType providerType,
        String nickname,
        AuthorityTier authorityTier,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime withdrawnAt
) {}
```

- [ ] **Step 2: 빌드 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

⚠️ **Skip commit** — G4 묶음.

### Task 17: `AdminMemberQueryRepository.search` + Impl QueryDSL

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminMemberQueryRepository.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminMemberQueryRepositoryImpl.java`

- [ ] **Step 1: Repository interface에 search 메서드 추가**

```java
// 기존 findDetail 아래에 추가:
Page<AdminMemberSummaryRow> search(AdminMemberListQuery query, Pageable pageable);
```

import 추가: `org.springframework.data.domain.Page`, `org.springframework.data.domain.Pageable`, `com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberListQuery`, `com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberSummaryRow`.

- [ ] **Step 2: Impl에 search 구현 (QueryDSL)**

```java
// AdminMemberQueryRepositoryImpl 클래스 안에 추가:

@Override
public Page<AdminMemberSummaryRow> search(AdminMemberListQuery query, Pageable pageable) {
    BooleanBuilder where = new BooleanBuilder();
    if (query.email() != null && !query.email().isBlank()) {
        where.and(userAccountData.email.containsIgnoreCase(query.email()));
    }
    if (query.tier() != null) {
        where.and(memberData.authorityTier.eq(query.tier()));
    }
    if (query.joinedFrom() != null) {
        where.and(userAccountData.createdAt.goe(query.joinedFrom().atStartOfDay()));
    }
    if (query.joinedTo() != null) {
        where.and(userAccountData.createdAt.lt(query.joinedTo().plusDays(1).atStartOfDay()));
    }

    JPAQuery<AdminMemberSummaryRow> baseQuery = queryFactory
            .select(Projections.constructor(AdminMemberSummaryRow.class,
                    memberData.memberId,
                    memberData.userAccountId,
                    userAccountData.email,
                    userAccountData.providerType,
                    memberData.profileData.nickname,
                    memberData.authorityTier,
                    userAccountData.lastLoginAt,
                    userAccountData.createdAt,
                    userAccountData.withdrawnAt))
            .from(memberData)
            .leftJoin(userAccountData).on(userAccountData.userId.uid.eq(memberData.userAccountId))
            .where(where);

    // last_activity_desc 처리 (spec §6 SQL)
    if (AdminMemberListQuery.SORT_LAST_ACTIVITY_DESC.equals(query.sort())) {
        baseQuery
                .leftJoin(userActivityLogData).on(userActivityLogData.userAccountId.eq(memberData.userAccountId))
                .groupBy(memberData.memberId)
                .orderBy(userActivityLogData.occurredAt.max().coalesce(userAccountData.createdAt).desc(),
                         memberData.memberId.desc());
    } else if (AdminMemberListQuery.SORT_CREATED_AT_ASC.equals(query.sort())) {
        baseQuery.orderBy(userAccountData.createdAt.asc(), memberData.memberId.asc());
    } else {
        // default: created_at_desc
        baseQuery.orderBy(userAccountData.createdAt.desc(), memberData.memberId.desc());
    }

    List<AdminMemberSummaryRow> rows = baseQuery
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

    Long total = queryFactory
            .select(memberData.count())
            .from(memberData)
            .leftJoin(userAccountData).on(userAccountData.userId.uid.eq(memberData.userAccountId))
            .where(where)
            .fetchOne();

    return new PageImpl<>(rows, pageable, total != null ? total : 0L);
}
```

import 추가: `com.querydsl.core.BooleanBuilder`, `com.querydsl.jpa.impl.JPAQuery`, `org.springframework.data.domain.*`, `static com.pfplaybackend.api.administration.domain.entity.QUserActivityLogData.userActivityLogData`.

> Note: `QUserActivityLogData` Q-class는 annotation processor가 자동 생성. 정확한 path는 빌드 후 확인.

- [ ] **Step 3: 빌드 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

⚠️ **Skip commit** — G4 묶음.

### Task 18: `AdminMemberQueryService.getList` — TDD

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminMemberQueryService.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminMemberQueryServiceTest.java`

- [ ] **Step 1: 단위 테스트 추가 (TDD red)**

```java
@Test
@DisplayName("getList: filter + sort + pagination 인자 위임")
void getList_delegates_to_repository_search() {
    AdminMemberListQuery query = new AdminMemberListQuery(
            "u@", AuthorityTier.FM, null, null, "created_at_desc");
    Pageable pageable = PageRequest.of(0, 50);

    AdminMemberSummaryRow row = new AdminMemberSummaryRow(
            50L, 100L, "u@x", ProviderType.GOOGLE,
            "Nick", AuthorityTier.FM,
            LocalDateTime.now(), LocalDateTime.now(), null);
    when(memberRepo.search(query, pageable))
            .thenReturn(new PageImpl<>(List.of(row), pageable, 1L));

    Page<AdminMemberSummaryResponse> result = service.getList(query, pageable);

    assertThat(result.getTotalElements()).isEqualTo(1L);
    assertThat(result.getContent().get(0).memberId()).isEqualTo(50L);
    assertThat(result.getContent().get(0).withdrawn()).isFalse();
}

@Test
@DisplayName("getList: withdrawnAt non-null 시 withdrawn=true 매핑")
void getList_maps_withdrawn_flag_correctly() {
    AdminMemberSummaryRow row = new AdminMemberSummaryRow(
            50L, 100L, "withdrawn-100@withdrawn.local", ProviderType.LOCAL,
            "탈퇴한 회원", AuthorityTier.AM,
            null, LocalDateTime.now(),
            LocalDateTime.of(2026, 4, 28, 14, 0));
    AdminMemberListQuery query = new AdminMemberListQuery(null, null, null, null, null);
    Pageable pageable = PageRequest.of(0, 50);
    when(memberRepo.search(query, pageable))
            .thenReturn(new PageImpl<>(List.of(row), pageable, 1L));

    Page<AdminMemberSummaryResponse> result = service.getList(query, pageable);

    assertThat(result.getContent().get(0).withdrawn()).isTrue();
    assertThat(result.getContent().get(0).withdrawnAt()).isNotNull();
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberQueryServiceTest*"
```

Expected: 2 FAIL (`getList` 메서드 미존재).

- [ ] **Step 3: Service에 getList 추가**

```java
// AdminMemberQueryService 안에 추가:

@Transactional(readOnly = true)
public Page<AdminMemberSummaryResponse> getList(AdminMemberListQuery query, Pageable pageable) {
    Page<AdminMemberSummaryRow> rows = memberRepository.search(query, pageable);
    return rows.map(r -> new AdminMemberSummaryResponse(
            r.memberId(),
            r.userAccountId(),
            r.email(),
            r.providerType(),
            r.nickname(),
            r.authorityTier(),
            r.lastLoginAt(),
            r.createdAt(),
            r.withdrawnAt() != null,            // withdrawn flag
            r.withdrawnAt()));
}
```

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberQueryServiceTest*"
```

Expected: PASS (4 tests — 2 existing + 2 new).

⚠️ **Skip commit** — G4 묶음.

### Task 19: `AdminMemberQueryController` GET / + WebMvc + size validation

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryController.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryControllerTest.java`

- [ ] **Step 1: WebMvc 단위 테스트 추가**

```java
@Test
@DisplayName("GET /admin/members 200 + 빈 결과")
void getList_returns_200_empty_content() throws Exception {
    when(adminMemberQueryService.getList(any(), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0L));

    mockMvc.perform(get("/api/v1/admin/members")
                    .with(/* admin context */))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isEmpty())
            .andExpect(jsonPath("$.data.pageInfo.totalElements").value(0));
}

@Test
@DisplayName("GET /admin/members 400 — size > 200")
void getList_returns_400_when_size_exceeds_cap() throws Exception {
    mockMvc.perform(get("/api/v1/admin/members?size=10000")
                    .with(/* admin context */))
            .andExpect(status().isBadRequest());
}

@Test
@DisplayName("GET /admin/members 400 — joined_from > joined_to")
void getList_returns_400_when_date_range_invalid() throws Exception {
    mockMvc.perform(get("/api/v1/admin/members?joined_from=2026-12-31&joined_to=2026-01-01")
                    .with(/* admin context */))
            .andExpect(status().isBadRequest());
}

@Test
@DisplayName("GET /admin/members 400 — sort 허용 외 값")
void getList_returns_400_when_sort_invalid() throws Exception {
    mockMvc.perform(get("/api/v1/admin/members?sort=random_xyz")
                    .with(/* admin context */))
            .andExpect(status().isBadRequest());
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberQueryControllerTest*"
```

Expected: 4 FAIL (GET / 메서드 + validation 미구현).

- [ ] **Step 3a: `AdminMemberException`에 `INVALID_LIST_QUERY` 추가**

`app/src/main/java/com/pfplaybackend/api/administration/application/exception/AdminMemberException.java` 갱신:

```java
@Getter
@AllArgsConstructor
public enum AdminMemberException implements ExceptionDefinition {

    MEMBER_NOT_FOUND("MBR-001", "Member 가 존재하지 않음", ErrorType.NOT_FOUND),
    INVALID_LIST_QUERY("MBR-002", "Member 목록 조회 query 파라미터가 유효하지 않음", ErrorType.BAD_REQUEST);

    private final String code;
    private final String message;
    private final ErrorType errorType;
}
```

> Note: `ErrorType.BAD_REQUEST`는 project `RestApiException` → 400 매핑. PR 8 `PartyroomException` 패턴 동형. project `GlobalExceptionHandler`는 `IllegalArgumentException` handler 부재 → 500 반환하므로 반드시 `ExceptionCreator.create(...)` 패턴 사용.

- [ ] **Step 3b: Controller에 GET / 메서드 + validation 추가**

```java
// AdminMemberQueryController 안에 추가:

@GetMapping
@PreAuthorize("@adminAuth.isAdmin()")
public ResponseEntity<ApiCommonResponse<Page<AdminMemberSummaryResponse>>> getList(
        @RequestParam(required = false) String email,
        @RequestParam(required = false) AuthorityTier tier,
        @RequestParam(name = "joined_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
        @RequestParam(name = "joined_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(defaultValue = "created_at_desc") String sort
) {
    if (size > 200) {
        throw ExceptionCreator.create(AdminMemberException.INVALID_LIST_QUERY);
    }
    if (joinedFrom != null && joinedTo != null && joinedFrom.isAfter(joinedTo)) {
        throw ExceptionCreator.create(AdminMemberException.INVALID_LIST_QUERY);
    }
    if (!isValidSort(sort)) {
        throw ExceptionCreator.create(AdminMemberException.INVALID_LIST_QUERY);
    }

    AdminMemberListQuery query = new AdminMemberListQuery(email, tier, joinedFrom, joinedTo, sort);
    Pageable pageable = PageRequest.of(page, size);
    Page<AdminMemberSummaryResponse> result = adminMemberQueryService.getList(query, pageable);
    return ResponseEntity.ok(ApiCommonResponse.success(result));
}

private static boolean isValidSort(String sort) {
    return AdminMemberListQuery.SORT_CREATED_AT_DESC.equals(sort)
            || AdminMemberListQuery.SORT_CREATED_AT_ASC.equals(sort)
            || AdminMemberListQuery.SORT_LAST_ACTIVITY_DESC.equals(sort);
}
```

import 추가: `com.pfplaybackend.api.administration.application.exception.AdminMemberException`, `com.pfplaybackend.api.common.exception.ExceptionCreator`, `com.pfplaybackend.api.common.enums.AuthorityTier` 등.

> Note: `IllegalArgumentException` 사용하면 project `GlobalExceptionHandler`가 `RuntimeException`으로 catch하여 500 반환 → WebMvc 400 단언 fail. 반드시 `ExceptionCreator.create(AdminMemberException.INVALID_LIST_QUERY)` 사용.

- [ ] **Step 4: WebMvc 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMemberQueryControllerTest*"
```

Expected: PASS (7 tests — 3 existing + 4 new).

⚠️ **Skip commit** — G4 묶음.

### Task 20: G4 IT — A-1 filter/sort/pagination + G4 commit

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminMemberQueryRepositoryImplIT.java`

- [ ] **Step 1: IT 작성 — 5 member SEED + filter/sort/pagination 검증**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberListQuery;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminMemberQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberSummaryRow;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.enums.AuthorityTier;
// ... user/MemberRepository, UserAccountRepository imports ...

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AdminMemberQueryRepositoryImplIT extends AbstractIntegrationTest {

    @Autowired AdminMemberQueryRepository adminMemberQueryRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired UserActivityLogRepository userActivityLogRepository;
    @Autowired TransactionTemplate transactionTemplate;

    // SEED — 5 member with varying email/tier/createdAt + activity rows for last_activity_desc

    @Test
    @DisplayName("search: email LIKE filter")
    void search_filters_by_email_like() {
        AdminMemberListQuery q = new AdminMemberListQuery("foo", null, null, null, "created_at_desc");
        Page<AdminMemberSummaryRow> result = adminMemberQueryRepository.search(q, PageRequest.of(0, 50));

        assertThat(result.getContent())
                .allMatch(row -> row.email().contains("foo"));
    }

    @Test
    @DisplayName("search: tier filter")
    void search_filters_by_tier() {
        AdminMemberListQuery q = new AdminMemberListQuery(null, AuthorityTier.FM, null, null, "created_at_desc");
        Page<AdminMemberSummaryRow> result = adminMemberQueryRepository.search(q, PageRequest.of(0, 50));

        assertThat(result.getContent())
                .allMatch(row -> row.authorityTier() == AuthorityTier.FM);
    }

    @Test
    @DisplayName("search: joined date range")
    void search_filters_by_date_range() {
        AdminMemberListQuery q = new AdminMemberListQuery(
                null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), "created_at_desc");
        Page<AdminMemberSummaryRow> result = adminMemberQueryRepository.search(q, PageRequest.of(0, 50));

        assertThat(result.getContent())
                .allMatch(row -> !row.createdAt().isBefore(LocalDateTime.of(2026, 1, 1, 0, 0))
                              && row.createdAt().isBefore(LocalDateTime.of(2026, 7, 1, 0, 0)));
    }

    @Test
    @DisplayName("sort created_at_desc — 최근 가입자 먼저")
    void sort_created_at_desc() {
        AdminMemberListQuery q = new AdminMemberListQuery(null, null, null, null, "created_at_desc");
        Page<AdminMemberSummaryRow> result = adminMemberQueryRepository.search(q, PageRequest.of(0, 50));

        // 정렬 확인 — DESC monotonic
        for (int i = 1; i < result.getContent().size(); i++) {
            assertThat(result.getContent().get(i - 1).createdAt())
                    .isAfterOrEqualTo(result.getContent().get(i).createdAt());
        }
    }

    @Test
    @DisplayName("sort last_activity_desc — 활동 0건 member도 포함 (createdAt fallback)")
    void sort_last_activity_desc_includes_inactive_members() {
        // SEED: member A는 활동 30개, member B는 활동 0개. 둘 다 결과에 포함되어야 함.
        // ... fixture 셋업 ...

        AdminMemberListQuery q = new AdminMemberListQuery(null, null, null, null, "last_activity_desc");
        Page<AdminMemberSummaryRow> result = adminMemberQueryRepository.search(q, PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSizeGreaterThanOrEqualTo(2);
        // 활동 있는 member가 활동 없는 member보다 먼저 (만약 활동 시각 > 가입 시각)
    }

    @Test
    @DisplayName("pagination: page=1, size=2")
    void pagination_works() {
        AdminMemberListQuery q = new AdminMemberListQuery(null, null, null, null, "created_at_desc");
        Page<AdminMemberSummaryRow> result = adminMemberQueryRepository.search(q, PageRequest.of(1, 2));

        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getContent()).hasSizeLessThanOrEqualTo(2);
    }

    // SEED helpers + cleanup
}
```

> Note: SEED setup은 `AdminMemberQueryServiceIT`(Task 15) 패턴 활용. 5 member + 다양한 email/tier/createdAt + 활동 rows. `@BeforeEach seed()` + `@AfterEach cleanup()`.

- [ ] **Step 2: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*AdminMemberQueryRepositoryImplIT*"
```

(timeout=900000.) Expected: PASS (6 tests).

- [ ] **Step 3: 회귀 빌드**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest
```

Expected: BUILD SUCCESSFUL except documented flaky `PartyroomRepositoryAtomicUpdateIT.unused_excludes_terminated`.

- [ ] **Step 4: G4 단일 commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminMemberSummaryResponse.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminMemberListQuery.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/dto/AdminMemberSummaryRow.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminMemberQueryRepository.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminMemberQueryRepositoryImpl.java \
    app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminMemberQueryService.java \
    app/src/main/java/com/pfplaybackend/api/administration/application/exception/AdminMemberException.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryController.java \
    app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminMemberQueryServiceTest.java \
    app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminMemberQueryControllerTest.java \
    app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminMemberQueryRepositoryImplIT.java
```

```bash
git commit -m "$(cat <<'EOF'
feat(administration): A-1 GET /admin/members — list/filter/sort/pagination (PR 12b1 G4)

- AdminMemberListQuery + AdminMemberSummaryRow + AdminMemberSummaryResponse DTO.
- AdminMemberQueryRepository.search — QueryDSL filter (email LIKE / tier / dates) + 3 sort + pagination + count.
- last_activity_desc: user_activity_log MAX(occurred_at) GROUP BY LEFT JOIN, COALESCE(..., m.created_at) fallback (활동 0건 member도 포함).
- AdminMemberQueryService.getList — Page<Row> → Page<Response> 매핑 + withdrawn flag derive.
- AdminMemberQueryController GET / + size cap(200) / date range / sort enum validation (400 매핑).
- 단위 4 + WebMvc 4 + IT 6 tests.

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §3.1, §6, §11 #11

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Verify:
```bash
git log --oneline -6
```

---

## Chunk 5: Spec §12 catch-up

**Goal of chunk:** PR 12b1 구현 완료 시점에 spec과의 차이/세부 결정사항을 §12에 backfill (PR 8 §15 / PR 9 §11 / PR 12a §12 / PR 12b1 §12 패턴).

### Task 21: Spec §12 backfill + Q-class 갱신 누락 점검 + 최종 회귀

**Files:**
- Modify: `docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md`

- [ ] **Step 1: 최종 회귀 빌드**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest :user:test
```

Expected: BUILD SUCCESSFUL except documented flaky.

- [ ] **Step 2: Spec §12 backfill 작성**

`docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md`의 §12 placeholder를 채움:

```markdown
## 12. Open Items / Implementation Reality (post-build catch-up)

### 12.1 G1 — §12.10 polish (Chunk 1)

- **G1 commit `<G1 sha>`**: `UserActivityLogId.java` + `UserActivityLogIdTest.java` 삭제 (dead code, PR 12a §12.10 결정 (b)). `UserActivityLogRepository` Javadoc에서 `UserActivityLogId` 멘션 제거 + PR 12b1 derived query 도입 노트. `UserActivityLogListener` divider comment 추가 + handler 순서 재배치 (User/Member events 그룹: Member/SignedIn/Profile/TierChanged/Withdrawn; Party events 그룹: PartyroomCreated/CrewAccessed/CrewPenalized/AdminCrewPenalized).

### 12.2 G2 — 이벤트 evolution + listener skeleton (Chunk 2)

- **G2 commit `<G2 sha>`**: `MemberTierChangedEvent` 신규 (user domain) + `UserAccountWithdrawnEvent` PR 1 forward-evolution(`byAdministratorId` 추가) + `UserAccountData.withdraw()` → `withdraw(Long byAdministratorId)` 시그니처 evolve. Production caller 0건이라 cascade 안전. PR 12b2 A-4가 첫 caller. listener 2 핸들러 추가 — TIER_CHANGED + ADMIN_ACTED_ON 2 row, WITHDREW + ADMIN_ACTED_ON 2 row. metadata에 `by_administrator_id` 기록.
- **`MemberTierChangedEvent.memberId` 미사용**: spec §11 #10 결정대로 listener metadata에 미기록. `getAggregateId()` 한정 사용.

### 12.3 G3 — A-2 detail endpoint (Chunk 3)

- **G3 commit `<G3 sha>`**: `AdminMemberQueryRepository(Impl)` QueryDSL findDetail (member + userAccount join) + `UserActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc` derived query (LogIdDesc tie-breaker로 결정적 순서 보장 — spec §11 #9) + `AdminMemberQueryService.getDetail` cross-repository orchestration + Controller GET `{memberId}` + 4 DTO + WebMvc + IT (31 row SEED → 30 limit + DESC 검증).

### 12.4 G4 — A-1 list endpoint (Chunk 4)

- **G4 commit `<G4 sha>`**: `AdminMemberQueryRepository.search` QueryDSL filter (email LIKE / tier / dates) + 3 sort + pagination + count. `last_activity_desc`는 user_activity_log MAX(occurred_at) GROUP BY LEFT JOIN + COALESCE(..., m.created_at) fallback. Service `getList` Page<Row> → Page<Response> 매핑 + withdrawn flag derive (`withdrawnAt != null`). Controller validation: size cap 200 / date range / sort enum.

### 12.5 spec features.md A-3/A-4 reconciliation (PR 12b2 publisher 시점)

- **spec features.md A-3 line 92** ("리스너가 `partyroom_admin_action`에 `action_type='CHANGE_MEMBER_TIER'` 1건 기록") 및 A-4 line 113 ("user_activity_log WITHDREW 기록")은 partyroom-scoped table에 member-level admin action을 기록한다는 mismatch. PR 12b1 Q2 결정 (B)대로 `user_activity_log`의 `ADMIN_ACTED_ON` 단독으로 통합. `partyroom_admin_action` 손대지 않음. PR 12b2 publish source 도입 후 features.md 본문 수정 별 doc commit으로 반영.

### 12.6 Future polish 잔존 항목

- **listener skeleton dead path 활성화**: PR 12b2 A-3/A-4 publish source 도입 시 end-to-end IT 추가 (현재 unit test only).
- **`memberId` listener metadata 추가 검토**: PR 12b2/추후 admin UI가 member ID 기반 navigation 필요 시 `MemberTierChangedEvent` listener의 `tierMeta`에 `member_id` 추가.
- **`UserAccountData.withdraw(Long byAdministratorId)` self-withdrawal extension**: 사용자 self-withdrawal 기능 추가 시 `byAdministratorId=null` 허용 또는 별 메서드 도입.
- **`activities` (DJ_PNT/ROOM_ACT) + `walletAddress` + Avatar nesting**: A-2 detail response 누락 — scoring/wallet 시스템 + PR 11 Avatar query port 통합 시점에 도입.
- **`member.last_activity_at` denormalized column**: 회원 1만 명+ 시점에 GROUP BY join 비용 회피용 도입 검토.
```

`<G1 sha>` 등 placeholder는 Step 4 commit 후 amendment commit으로 채움 (PR 12a G7.1/G7.2 패턴).

- [ ] **Step 3: spec catch-up commit**

```bash
git add docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md
```

```bash
git commit -m "$(cat <<'EOF'
docs(spec): catch up PR 12b1 design to implementation reality (§12 backfill)

§12 backfill — chunk별 commit SHA + deviations + future polish:
- §12.1 G1 polish (UserActivityLogId 삭제, listener divider).
- §12.2 G2 이벤트 evolution + listener skeleton (UserAccountWithdrawnEvent PR 1 forward-evolution).
- §12.3 G3 A-2 detail (QueryDSL findDetail + UAL derived query + cross-repo orchestration).
- §12.4 G4 A-1 list (filter/3 sort/pagination + last_activity_desc COALESCE fallback).
- §12.5 spec features.md A-3/A-4 partyroom_admin_action 라인 reconciliation 메모 (PR 12b2 본문 수정 시점).
- §12.6 future polish (listener dead path 활성화, memberId metadata, withdraw self extension, response nesting, denormalized column).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: SHA backfill (G5.1 amendment commit)**

위 commit이 안착되면 `git log --oneline -10` 으로 G1/G2/G3/G4 SHA 조회 후 spec §12.1~§12.4 placeholder 4개를 실제 SHA로 채워 amendment commit. PR 12a G7.1/G7.2 패턴 동형.

```bash
# spec 파일에서 <G1 sha>, <G2 sha>, <G3 sha>, <G4 sha> 4곳을 실제 SHA로 치환
# git log --oneline -10 결과에서 commit subject 매칭
git add docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md
git commit -m "$(cat <<'EOF'
docs(spec): backfill chunk SHAs in PR 12b1 §12 (G5.1)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: 최종 git log 확인**

```bash
git log --oneline -10
```

Expected (top down): G5.1 SHA backfill, G5 spec catch-up, G4, G3, G2, G1, PR 12b1 spec polish, PR 12b1 spec init, PR 12a G7.2, PR 12a G7.1.

총 ~7 commits (G1, G2, G3, G4 단일 commits + G5 spec catch-up + G5.1 SHA backfill + 그 위 spec polish 1).

---

**다음 단계:** plan 실행은 `superpowers:subagent-driven-development`로 진행. fresh subagent가 각 chunk별 task 실행 + two-stage review.
