# PR 9: V8 punisher_type + Admin Crew Penalty API Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** V8 마이그레이션(`crew_penalty_history.punisher_type`) + 어드민 크루 페널티 부과/해제 endpoint 2개 + crew release 가드 + PR8 hardcoded `recentPenalties` 정리. PR8 패턴(admin BC service가 orchestration + bare `@EventListener` audit + `DomainEvent` 상속)을 그대로 따름.

**Architecture:** `AdminCrewPenaltyCommandService`(administration application)가 `PartyroomAggregatePort` + `PartyroomAccessCommandService` + `CrewPenaltyHistoryRepository`를 직접 collaborator로 받아 load → validate → mutate → save → publishEvent 수행. `PartyroomAggregatePort`/`Adapter`는 변경 없음(thin CRUD pass-through). 신규 도메인 이벤트 2종은 `DomainEvent` 상속, listener는 bare `@EventListener` + `event.getOccurredAt()`. 모든 admin write action은 `partyroom_admin_action`에 atomic 기록.

**Tech Stack:** Java 21, Spring Boot 3.2 (Spring Security 6.2, Spring Data JPA 3.2, Hibernate 6.4), Flyway 9, JUnit 5, Mockito, AssertJ, ArchUnit, Testcontainers (MySQL 8 + Redis).

**Spec source (read once, applied throughout):**
- `docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md` — 9 결정사항, 9 risk
- `docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md` §9.1 PR 9
- `docs/superpowers/specs/2026-04-19-admin-platform-features.md` §6.B-7
- `docs/superpowers/specs/2026-04-19-admin-platform-schema.md` §4.5 V8 DDL

**Branching:** Continue on `feature/admin-auth-iam-schema`. Spec commits: `23f5b216` + `0c3cb58c`. PR 9 builds on PR 8 HEAD `76d7b2c1`.

**Out of scope (defer)** — spec §2.2:
- `CHAT_MESSAGE_REMOVAL` / `CHAT_BAN_30_SECONDS` admin 부과
- `crew_block_history.punisher_type` (영구 제외)
- Admin이 crew-applied 페널티 해제
- Bulk action에 PENALIZE_CREW
- `released_by_type` 컬럼
- Admin penalty rate limit

---

## Atomic commit groupings

Per-task commits are the default. The following groups MUST land as a single commit so the tree stays green:

| Group | Tasks | Reason |
|---|---|---|
| **G1: V8 SQL + PunisherType enum + entity 변경 + crew path 보강** | Tasks 1 + 2 + 3 + 4 | DDL ↔ entity 매핑 boot-or-die. crew path builder 미보강 시 V8 default가 cover하지만 같은 commit이 명시적. **배포 순서 V8 SQL ↔ 새 jar 분리 불가.** |
| **G2: 신규 도메인 이벤트 2종 + Admin enum 확장 + Listener 핸들러 2개** | Tasks 5 + 6 + 7 | publish-consume 쌍이 한 commit에 같이 들어가야 함. enum 새 값 없이 핸들러만 들어가면 컴파일 안 되며 핸들러 없이 이벤트만 발행하면 admin TX rollback (spec §5.4.1). |

기타 task들은 task별 독립 commit (default).

Within each group:
- Per-task step lists remain a checklist.
- **Skip the `git commit` step at the end of each task in the group.**
- Single combined commit at the end of the group's last task with the message specified there.

---

## Hard precondition (verify BEFORE Task 1)

- [ ] **Step 1: Confirm spec commits on HEAD ancestry**

```bash
git log --oneline -5
```

Expected: HEAD includes `0c3cb58c docs(spec): PR 9 design — fix layering ...` and `23f5b216 docs(spec): PR 9 design — V8 punisher_type ...` (PR 8 commits below). Working tree clean.

- [ ] **Step 2: Working tree clean**

```bash
git status -s
```

Expected: empty.

- [ ] **Step 3: V8 slot open in `db/migration/`**

```bash
ls app/src/main/resources/db/migration/ | grep -E '^V[0-9]'
```

Expected: V1, V2, V3, V4, V5, V6, V7, V13 present. **V8 must NOT exist.** (V13는 후속 PR이 미리 잡아둔 향후 slot이라 변경 없이 유지.)

- [ ] **Step 4: JDK 21 환경**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew --version
```

Expected: Gradle ~8.10, JVM 21.0.x.

- [ ] **Step 5: Baseline build + test pass**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest
```

Expected: BUILD SUCCESSFUL. **예상 5-15분 (cold)** — Testcontainers MySQL boot 포함, Docker daemon 가동 필요. PR 8 follow-up commits 위에서 회귀 0 보장 필요.

---

## Chunk 1: G1 — V8 마이그레이션 + 엔티티 변경 + crew path 보강

**Goal of chunk:** V8 SQL + `PunisherType` enum + `CrewPenaltyHistoryData.punisherType` 필드 + `releaseByAdmin` 메서드 + 기존 `CrewPenaltyCommandService.addPenalty` builder의 `.punisherType(PunisherType.CREW)` 명시화. 단일 G1 commit.

**End state of chunk:** V8 마이그까지 적용된 DB에서 어플리케이션 부팅 성공, 기존 crew 페널티 부과/해제 시 punisher_type='CREW'로 저장됨. unit test 그린.

### Task 1: V8 Flyway 마이그레이션 SQL

**Files:**
- Create: `app/src/main/resources/db/migration/V8__add_punisher_type_to_penalty_history.sql`

- [ ] **Step 1: V8 SQL 작성**

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

- [ ] **Step 2: SQL 위치/구조 sanity check**

```bash
ls app/src/main/resources/db/migration/V8__add_punisher_type_to_penalty_history.sql && grep -E 'ALTER TABLE|ADD COLUMN' app/src/main/resources/db/migration/V8__add_punisher_type_to_penalty_history.sql | wc -l
```

Expected: 파일 존재, 출력 `1`.

⚠️ **Skip commit** — G1 묶음.

### Task 2: `PunisherType` enum (party domain)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/enums/PunisherType.java`

- [ ] **Step 1: enum 작성**

```java
package com.pfplaybackend.api.party.domain.enums;

/**
 * crew_penalty_history.punisher_type 컬럼(V8) 매핑.
 * - CREW: 호스트/모더레이터(crew)가 부과
 * - ADMIN: 어드민(crew 아님)이 부과 — punisher_crew_id는 NULL, administrator_id는 partyroom_admin_action에 기록
 */
public enum PunisherType {
    CREW, ADMIN
}
```

⚠️ **Skip commit** — G1 묶음.

### Task 3: `CrewPenaltyHistoryData` 필드/메서드 추가 + 기존 builder 영향

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/history/CrewPenaltyHistoryData.java`

- [ ] **Step 1: 단위 테스트 먼저 작성 — `releaseByAdmin` 동작 검증**

`app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/history/CrewPenaltyHistoryDataTest.java` (기존 파일에 추가):

```java
@Test
@DisplayName("releaseByAdmin: released=true + releasedByCrewId=null + releaseDate set")
void releaseByAdmin_marks_admin_release() {
    LocalDateTime now = LocalDateTime.of(2026, 4, 28, 12, 0);
    CrewPenaltyHistoryData history = CrewPenaltyHistoryData.builder()
            .partyroomId(new PartyroomId(1L))
            .punishedCrewId(new CrewId(10L))
            .punisherCrewId(null)
            .punisherType(PunisherType.ADMIN)
            .penaltyType(PenaltyType.PERMANENT_EXPULSION)
            .penaltyReason("admin reason")
            .penaltyDate(LocalDateTime.of(2026, 4, 28, 11, 0))
            .released(false)
            .build();

    history.releaseByAdmin(now);

    assertThat(history.isReleased()).isTrue();
    assertThat(history.getReleasedByCrewId()).isNull();
    assertThat(history.getReleaseDate()).isEqualTo(now);
}
```

- [ ] **Step 2: 테스트 컴파일 실패 확인 (PunisherType 필드/releaseByAdmin 미존재)**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -20
```

Expected: 컴파일 에러 (`punisherType`, `releaseByAdmin` symbol not found).

- [ ] **Step 3: `CrewPenaltyHistoryData` 엔티티 변경**

기존 파일에 다음 필드 추가 (line 50 근방, `penaltyType` 다음):

```java
@Enumerated(EnumType.STRING)
@Column(name = "punisher_type", nullable = false)
private PunisherType punisherType;
```

기존 파일에 다음 메서드 추가 (line 80 근방, `release(...)` 메서드 옆):

```java
/**
 * 어드민이 부과한 페널티의 해제.
 * admin-released signal: released_by_crew_id IS NULL (Q8.9(i)에서 별도 released_by_type 컬럼 미도입).
 * V1 스키마에서 released_by_crew_id는 nullable이라 추가 마이그 불필요.
 * admin 정체는 partyroom_admin_action.administrator_id 경로로 식별
 * (correlation: metadata.crew_penalty_history_id).
 */
public void releaseByAdmin(LocalDateTime now) {
    this.released = true;
    this.releasedByCrewId = null;
    this.releaseDate = now;
}
```

import 추가:
```java
import com.pfplaybackend.api.party.domain.enums.PunisherType;
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.entity.data.history.CrewPenaltyHistoryDataTest"
```

Expected: PASS (기존 테스트 + `releaseByAdmin_marks_admin_release` 신규).

⚠️ **Skip commit** — G1 묶음.

### Task 4: Crew path builder 보강 (`CrewPenaltyCommandService.addPenalty`)

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/CrewPenaltyCommandService.java`

`punisher_type` 컬럼이 V8에서 `DEFAULT 'CREW'` NOT NULL이므로 builder 미보강 시에도 동작은 OK. 명시성을 위해 한 줄 추가.

- [ ] **Step 1: 단위 테스트 보강 — crew path builder가 `.punisherType(CREW)`를 명시적으로 세팅하는지**

기존 `CrewPenaltyCommandServiceTest.java`에 `addPenalty` PERMANENT_EXPULSION case 검증을 보강 (이미 PERMANENT 시 history save 검증이 있으면 그 검증에 `getPunisherType() == PunisherType.CREW` 한 줄 추가). 만약 기존 테스트가 mock인 경우 `ArgumentCaptor`로 builder 결과 검증.

```java
@Test
@DisplayName("addPenalty (PERMANENT): punisher_type=CREW로 기록")
void addPenalty_permanent_saves_with_punisher_type_crew() {
    // ... 기존 setup ...
    given(crewPenaltyHistoryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

    service.addPenalty(new PartyroomId(1L), new PunishPenaltyCommand(
            10L, PenaltyType.PERMANENT_EXPULSION, "reason"));

    ArgumentCaptor<CrewPenaltyHistoryData> captor = ArgumentCaptor.forClass(CrewPenaltyHistoryData.class);
    verify(crewPenaltyHistoryRepository).save(captor.capture());
    assertThat(captor.getValue().getPunisherType()).isEqualTo(PunisherType.CREW);
}
```

- [ ] **Step 2: 테스트 실행으로 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.CrewPenaltyCommandServiceTest.addPenalty_permanent_saves_with_punisher_type_crew"
```

Expected: FAIL (builder가 `punisherType` 미설정 → field=null assertion 실패).

- [ ] **Step 3: `CrewPenaltyCommandService.addPenalty` builder 보강**

기존 파일 line 67~77 근방 builder 호출에 `.punisherType(PunisherType.CREW)` 추가:

```java
CrewPenaltyHistoryData crewPenaltyHistoryData = CrewPenaltyHistoryData.builder()
        .partyroomId(partyroomId)
        .punishedCrewId(punishedCrewId)
        .punisherCrewId(new CrewId(punisherCrew.getId()))
        .punisherType(PunisherType.CREW)            // [PR 9] V8 컬럼 명시화
        .penaltyReason(command.detail())
        .penaltyDate(LocalDateTime.now(clock))
        .penaltyType(command.penaltyType())
        .released(false)
        .build();
```

import 추가:
```java
import com.pfplaybackend.api.party.domain.enums.PunisherType;
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.CrewPenaltyCommandServiceTest"
```

Expected: PASS (기존 + 보강 테스트 모두).

- [ ] **Step 5: G1 일괄 빌드 + IT 회귀**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest
```

Expected: BUILD SUCCESSFUL. V8 마이그레이션이 Testcontainers MySQL 부팅 시 자동 적용됨 — 부팅 실패 시 V8 SQL syntax 점검.

- [ ] **Step 6: G1 commit**

```bash
git add app/src/main/resources/db/migration/V8__add_punisher_type_to_penalty_history.sql \
        app/src/main/java/com/pfplaybackend/api/party/domain/enums/PunisherType.java \
        app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/history/CrewPenaltyHistoryData.java \
        app/src/main/java/com/pfplaybackend/api/party/application/service/CrewPenaltyCommandService.java \
        app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/history/CrewPenaltyHistoryDataTest.java \
        app/src/test/java/com/pfplaybackend/api/party/application/service/CrewPenaltyCommandServiceTest.java

git commit -m "$(cat <<'EOF'
feat(party): V8 crew_penalty_history.punisher_type + entity + crew path (PR 9 G1)

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §3, §5.1

- V8 Flyway: ADD COLUMN punisher_type ENUM('CREW','ADMIN') NOT NULL DEFAULT 'CREW'
- PunisherType enum 신규 (party domain)
- CrewPenaltyHistoryData: punisherType 필드 + releaseByAdmin(now) 메서드
- CrewPenaltyCommandService.addPenalty: builder에 .punisherType(CREW) 명시화
  (V8 default가 cover하지만 같은 commit으로 명시적 — builder/entity boot-or-die 정합)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Chunk 2: G2 — 도메인 이벤트 + Admin enum 확장 + Listener 핸들러

**Goal of chunk:** `AdminCrewPenalizedEvent`/`AdminCrewPenaltyReleasedEvent` 신규(party domain, `DomainEvent` 상속) + `PartyroomAdminActionType.PENALIZE_CREW`/`RELEASE_CREW_PENALTY` enum 값 추가 + `AdminActionTargetType.CREW` 추가 + `PartyroomAdminActionListener`에 핸들러 2개 추가. 단일 G2 commit.

**End state of chunk:** 신규 이벤트 publish 시 `PartyroomAdminActionListener`가 받아 `partyroom_admin_action`에 `PENALIZE_CREW`/`RELEASE_CREW_PENALTY` row INSERT. 단, publisher는 아직 없으므로 listener IT는 직접 publish 시나리오로 검증.

### Task 5: 신규 도메인 이벤트 2종

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/event/AdminCrewPenalizedEvent.java`
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/event/AdminCrewPenaltyReleasedEvent.java`

- [ ] **Step 1: `AdminCrewPenalizedEvent` 작성**

```java
package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 어드민이 부과한 크루 페널티 이벤트.
 * - PartyroomAdminActionListener listen → admin_action PENALIZE_CREW
 * occurredAt은 DomainEvent 기반 클래스가 LocalDateTime.now()로 자동 설정.
 *
 * administratorId를 party-domain event에 포함하는 것은 PR 8 PartyroomTerminatedEvent
 * 등이 잡은 선례 — admin id는 party domain에서 loose ref(integer)로 다룸.
 */
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
```

- [ ] **Step 2: `AdminCrewPenaltyReleasedEvent` 작성**

```java
package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 어드민이 부과한 페널티의 해제 이벤트.
 * - PartyroomAdminActionListener listen → admin_action RELEASE_CREW_PENALTY
 */
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

- [ ] **Step 3: 컴파일 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

⚠️ **Skip commit** — G2 묶음.

### Task 6: Admin enum 확장 (`PartyroomAdminActionType`, `AdminActionTargetType`)

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/domain/enums/PartyroomAdminActionType.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/domain/enums/AdminActionTargetType.java`

- [ ] **Step 1: `PartyroomAdminActionType`에 `PENALIZE_CREW`, `RELEASE_CREW_PENALTY` 추가**

```java
package com.pfplaybackend.api.administration.domain.enums;

public enum PartyroomAdminActionType {
    SUSPEND_PARTYROOM,
    RESTORE_PARTYROOM,
    TERMINATE_PARTYROOM,
    SET_FEATURED,
    SET_HIDDEN,
    SET_NORMAL,
    UPDATE_PARTYROOM_META,
    PENALIZE_CREW,           // [PR 9]
    RELEASE_CREW_PENALTY     // [PR 9]
    // CHANGE_MEMBER_TIER, WITHDRAW_MEMBER는 PR 12에서 추가
    // 컬럼은 VARCHAR(32)라 마이그레이션 불필요
}
```

- [ ] **Step 2: `AdminActionTargetType`에 `CREW` 추가**

```java
package com.pfplaybackend.api.administration.domain.enums;

public enum AdminActionTargetType {
    PARTYROOM,
    CREW                     // [PR 9] target_id = crew id, partyroom_id 컬럼 = 부모 룸 id
    // MEMBER는 PR 12에서 추가
}
```

⚠️ **Skip commit** — G2 묶음.

### Task 7: `PartyroomAdminActionListener` 핸들러 2개 추가

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/PartyroomAdminActionListener.java`

- [ ] **Step 1: 단위 테스트 또는 IT 작성**

`app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/PartyroomAdminActionListenerTest.java` (mock repo, 핸들러 단위 검증):

```java
package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.AdminActionTargetType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenaltyReleasedEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PartyroomAdminActionListenerTest {

    @Mock PartyroomAdminActionRepository repo;
    @InjectMocks PartyroomAdminActionListener listener;

    @Test
    @DisplayName("on(AdminCrewPenalizedEvent): action_type=PENALIZE_CREW, target_type=CREW, metadata 매핑")
    void on_AdminCrewPenalizedEvent_inserts_audit_row() {
        AdminCrewPenalizedEvent event = new AdminCrewPenalizedEvent(
                100L, new PartyroomId(1L), new CrewId(10L),
                PenaltyType.PERMANENT_EXPULSION, 999L, "abuse");

        listener.on(event);

        ArgumentCaptor<PartyroomAdminActionData> captor = ArgumentCaptor.forClass(PartyroomAdminActionData.class);
        verify(repo).save(captor.capture());
        PartyroomAdminActionData saved = captor.getValue();
        assertThat(saved.getActionType()).isEqualTo(PartyroomAdminActionType.PENALIZE_CREW);
        assertThat(saved.getTargetType()).isEqualTo(AdminActionTargetType.CREW);
        assertThat(saved.getTargetId()).isEqualTo(10L);
        assertThat(saved.getPartyroomId()).isEqualTo(1L);
        assertThat(saved.getReason()).isEqualTo("abuse");
        assertThat(saved.getOccurredAt()).isEqualTo(event.getOccurredAt());
        assertThat(saved.getMetadata().data())
                .containsEntry("penalty_type", "PERMANENT_EXPULSION")
                .containsEntry("crew_penalty_history_id", 999L);
    }

    @Test
    @DisplayName("on(AdminCrewPenalizedEvent) ONE_TIME: metadata에 crew_penalty_history_id 없음")
    void on_AdminCrewPenalizedEvent_one_time_omits_history_id() {
        AdminCrewPenalizedEvent event = new AdminCrewPenalizedEvent(
                100L, new PartyroomId(1L), new CrewId(10L),
                PenaltyType.ONE_TIME_EXPULSION, null, "warning");

        listener.on(event);

        ArgumentCaptor<PartyroomAdminActionData> captor = ArgumentCaptor.forClass(PartyroomAdminActionData.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getMetadata().data())
                .containsEntry("penalty_type", "ONE_TIME_EXPULSION")
                .doesNotContainKey("crew_penalty_history_id");
    }

    @Test
    @DisplayName("on(AdminCrewPenaltyReleasedEvent): action_type=RELEASE_CREW_PENALTY, reason=null, metadata만 history_id")
    void on_AdminCrewPenaltyReleasedEvent_inserts_audit_row() {
        AdminCrewPenaltyReleasedEvent event = new AdminCrewPenaltyReleasedEvent(
                100L, new PartyroomId(1L), new CrewId(10L), 999L);

        listener.on(event);

        ArgumentCaptor<PartyroomAdminActionData> captor = ArgumentCaptor.forClass(PartyroomAdminActionData.class);
        verify(repo).save(captor.capture());
        PartyroomAdminActionData saved = captor.getValue();
        assertThat(saved.getActionType()).isEqualTo(PartyroomAdminActionType.RELEASE_CREW_PENALTY);
        assertThat(saved.getTargetType()).isEqualTo(AdminActionTargetType.CREW);
        assertThat(saved.getTargetId()).isEqualTo(10L);
        assertThat(saved.getPartyroomId()).isEqualTo(1L);
        assertThat(saved.getReason()).isNull();
        assertThat(saved.getMetadata().data()).containsEntry("crew_penalty_history_id", 999L);
    }
}
```

기존 PR 8 테스트 클래스가 이미 있다면 신규 메서드 3개만 추가.

- [ ] **Step 2: 테스트 컴파일 실패 확인 (핸들러 미존재)**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 에러 또는 method-not-found.

- [ ] **Step 3: `PartyroomAdminActionListener`에 핸들러 2개 추가**

기존 파일 끝에 추가 (기존 `private void save(...)` 헬퍼 위):

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
            event.getPunishedCrewId().getId(),
            event.getPartyroomId().getId(),
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
            event.getReleasedCrewId().getId(),
            event.getPartyroomId().getId(),
            null,                                   // release는 unstructured reason 받지 않음
            JsonMetadata.of(Map.of("crew_penalty_history_id", event.getCrewPenaltyHistoryId())),
            event.getOccurredAt()));
}
```

import 추가:
```java
import com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenaltyReleasedEvent;
import java.util.HashMap;
```

- [ ] **Step 4: 단위 테스트 통과**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.adapter.in.listener.PartyroomAdminActionListenerTest"
```

Expected: PASS (3 신규 + 기존 5 핸들러 테스트).

- [ ] **Step 5: G2 일괄 빌드 + IT 회귀**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest
```

Expected: BUILD SUCCESSFUL. PR 8 listener IT는 기존 5 핸들러만 검증하므로 회귀 없어야 함.

- [ ] **Step 6: G2 commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/event/AdminCrewPenalizedEvent.java \
        app/src/main/java/com/pfplaybackend/api/party/domain/event/AdminCrewPenaltyReleasedEvent.java \
        app/src/main/java/com/pfplaybackend/api/administration/domain/enums/PartyroomAdminActionType.java \
        app/src/main/java/com/pfplaybackend/api/administration/domain/enums/AdminActionTargetType.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/PartyroomAdminActionListener.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/PartyroomAdminActionListenerTest.java

git commit -m "$(cat <<'EOF'
feat(administration): admin penalty events + audit listener handlers (PR 9 G2)

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §5.1, §5.4

- AdminCrewPenalizedEvent / AdminCrewPenaltyReleasedEvent (party domain,
  DomainEvent 상속, occurredAt 자동 설정)
- PartyroomAdminActionType += PENALIZE_CREW, RELEASE_CREW_PENALTY
- AdminActionTargetType += CREW (target_id=crew id, partyroom_id=부모 룸 id)
- PartyroomAdminActionListener: 2 핸들러 추가 (bare @EventListener, PR 8 5개와 동일 패턴)

이벤트 publish-consume 쌍 + enum 새 값 + listener 핸들러를 atomic 묶음으로
배포 — handler 부재로 admin TX rollback 회피 (spec §5.4.1).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Chunk 3: 신규 Exception 코드 + DTO/Command + AdminPenaltyType ACL

**Goal of chunk:** PR 9 신규 endpoint 도입에 필요한 페널티 예외 코드 + 어드민 어댑터 측 DTO/Command/enum 추가. 본 chunk는 의존성 없이 독립적이므로 순차 commit.

### Task 8: `PenaltyException` 신규 에러 코드 2개

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/exception/PenaltyException.java`

- [ ] **Step 1: 코드 추가**

```java
@Getter
public enum PenaltyException implements DomainException {
    PERMANENT_EXPULSION("PNT-001", "이용이 정지된 사용자입니다", ErrorType.FORBIDDEN),
    PENALTY_HISTORY_NOT_FOUND("PNT-002", "페널티 이력을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE(
            "PNT-003",
            "어드민이 부과한 페널티는 어드민만 해제할 수 있습니다",
            ErrorType.FORBIDDEN),
    CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE(
            "PNT-004",
            "크루가 부과한 페널티는 어드민이 해제할 수 없습니다",
            ErrorType.FORBIDDEN);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    PenaltyException(String errorCode, String message, ErrorType errorType) {
        this.message = message;
        this.errorCode = errorCode;
        this.errorType = errorType;
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/exception/PenaltyException.java
git commit -m "feat(party): PenaltyException — admin/crew release 가드 코드 (PR 9)

PNT-003: 크루가 admin-applied 페널티 release 시도 → 403
PNT-004: admin이 crew-applied 페널티 release 시도 → 403

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §4.2/§4.3

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

### Task 9: `AdminPenaltyType` enum + Command + Request/Response DTO

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/AdminApplyPenaltyRequest.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/AdminApplyPenaltyResponse.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/dto/command/AdminApplyPenaltyCommand.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/enums/AdminPenaltyType.java`

`AdminPenaltyType`은 administration BC가 party의 4종 enum 전체를 노출하지 않게 하는 anti-corruption layer. expulsion 2종만.

- [ ] **Step 1: `AdminPenaltyType` 작성**

```java
package com.pfplaybackend.api.administration.domain.enums;

import com.pfplaybackend.api.party.domain.enums.PenaltyType;

/**
 * 어드민이 부과 가능한 페널티 종류 (Q2=B에서 expulsion 2종으로 한정).
 * party의 PenaltyType을 그대로 노출하지 않고 ACL로 둠.
 */
public enum AdminPenaltyType {
    ONE_TIME_EXPULSION(PenaltyType.ONE_TIME_EXPULSION),
    PERMANENT_EXPULSION(PenaltyType.PERMANENT_EXPULSION);

    private final PenaltyType partyEnum;

    AdminPenaltyType(PenaltyType partyEnum) {
        this.partyEnum = partyEnum;
    }

    public PenaltyType toPartyEnum() {
        return partyEnum;
    }
}
```

- [ ] **Step 2: `AdminApplyPenaltyRequest` 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.pfplaybackend.api.administration.application.dto.command.AdminApplyPenaltyCommand;
import com.pfplaybackend.api.administration.domain.enums.AdminPenaltyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class AdminApplyPenaltyRequest {

    @NotNull(message = "crewId is required.")
    private Long crewId;

    @NotNull(message = "penaltyType is required.")
    private AdminPenaltyType penaltyType;

    @NotBlank(message = "reason is required.")
    @Size(min = 1, max = 255, message = "reason length must be 1..255")
    private String reason;

    public AdminApplyPenaltyCommand toCommand() {
        return new AdminApplyPenaltyCommand(crewId, penaltyType, reason);
    }
}
```

- [ ] **Step 3: `AdminApplyPenaltyResponse` 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

/**
 * penaltyId는 PERMANENT_EXPULSION일 때만 non-null. ONE_TIME_EXPULSION은 history row 없음 → null.
 * 클라이언트는 PERMANENT 사례에서만 release 호출 가능.
 */
public record AdminApplyPenaltyResponse(Long penaltyId) {}
```

- [ ] **Step 4: `AdminApplyPenaltyCommand` 작성**

```java
package com.pfplaybackend.api.administration.application.dto.command;

import com.pfplaybackend.api.administration.domain.enums.AdminPenaltyType;

public record AdminApplyPenaltyCommand(
        Long crewId,
        AdminPenaltyType penaltyType,
        String reason
) {}
```

- [ ] **Step 5: 단위 테스트 — DTO validation**

`app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/AdminApplyPenaltyRequestTest.java`:

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.pfplaybackend.api.administration.domain.enums.AdminPenaltyType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdminApplyPenaltyRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }
    @AfterAll static void close() { factory.close(); }

    private static AdminApplyPenaltyRequest of(Long crewId, AdminPenaltyType type, String reason) throws Exception {
        AdminApplyPenaltyRequest req = new AdminApplyPenaltyRequest();
        for (var f : new Field[]{
                AdminApplyPenaltyRequest.class.getDeclaredField("crewId"),
                AdminApplyPenaltyRequest.class.getDeclaredField("penaltyType"),
                AdminApplyPenaltyRequest.class.getDeclaredField("reason")
        }) f.setAccessible(true);
        AdminApplyPenaltyRequest.class.getDeclaredField("crewId").set(req, crewId);
        AdminApplyPenaltyRequest.class.getDeclaredField("penaltyType").set(req, type);
        AdminApplyPenaltyRequest.class.getDeclaredField("reason").set(req, reason);
        return req;
    }

    @Test @DisplayName("정상 입력")
    void valid() throws Exception {
        Set<ConstraintViolation<AdminApplyPenaltyRequest>> v = validator.validate(
                of(10L, AdminPenaltyType.PERMANENT_EXPULSION, "abuse"));
        assertThat(v).isEmpty();
    }

    @Test @DisplayName("crewId null → 위반")
    void crewId_null() throws Exception {
        Set<ConstraintViolation<AdminApplyPenaltyRequest>> v = validator.validate(
                of(null, AdminPenaltyType.PERMANENT_EXPULSION, "abuse"));
        assertThat(v).hasSize(1);
    }

    @Test @DisplayName("penaltyType null → 위반")
    void penaltyType_null() throws Exception {
        Set<ConstraintViolation<AdminApplyPenaltyRequest>> v = validator.validate(
                of(10L, null, "abuse"));
        assertThat(v).hasSize(1);
    }

    @Test @DisplayName("reason blank → 위반")
    void reason_blank() throws Exception {
        Set<ConstraintViolation<AdminApplyPenaltyRequest>> v = validator.validate(
                of(10L, AdminPenaltyType.ONE_TIME_EXPULSION, "  "));
        assertThat(v).isNotEmpty();
    }

    @Test @DisplayName("reason 256자 → 위반")
    void reason_too_long() throws Exception {
        Set<ConstraintViolation<AdminApplyPenaltyRequest>> v = validator.validate(
                of(10L, AdminPenaltyType.ONE_TIME_EXPULSION, "x".repeat(256)));
        assertThat(v).isNotEmpty();
    }
}
```

- [ ] **Step 6: 테스트 + 컴파일 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.adapter.in.web.payload.request.AdminApplyPenaltyRequestTest"
```

Expected: PASS (5 cases).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/domain/enums/AdminPenaltyType.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/AdminApplyPenaltyRequest.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/AdminApplyPenaltyResponse.java \
        app/src/main/java/com/pfplaybackend/api/administration/application/dto/command/AdminApplyPenaltyCommand.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/AdminApplyPenaltyRequestTest.java

git commit -m "feat(administration): admin penalty DTO/Command/AdminPenaltyType ACL (PR 9)

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §4.1, §5.3

AdminPenaltyType은 expulsion 2종(ONE_TIME/PERMANENT)만 노출하는 ACL —
party의 PenaltyType 4종 전체를 administration BC에서 직접 import하지 않게 함.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

---

## Chunk 4: AdminCrewPenaltyCommandService (apply/release)

**Goal of chunk:** orchestration 담당 service 구현 — load → validate → mutate (expel + history INSERT/release) → publish event. 그 후 controller가 wiring.

### Task 10: `AdminCrewPenaltyCommandService` (apply + release)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandService.java`
- Create: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandServiceTest.java`

- [ ] **Step 1: 단위 테스트 작성 — `apply` 분기 5가지 + `release` 분기 3가지**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.dto.command.AdminApplyPenaltyCommand;
import com.pfplaybackend.api.administration.domain.enums.AdminPenaltyType;
import com.pfplaybackend.api.common.exception.BusinessException;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewPenaltyHistoryRepository;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.history.CrewPenaltyHistoryData;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.PunisherType;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenaltyReleasedEvent;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.exception.PenaltyException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminCrewPenaltyCommandServiceTest {

    @Mock PartyroomAggregatePort aggregatePort;
    @Mock PartyroomAccessCommandService partyroomAccessCommandService;
    @Mock CrewPenaltyHistoryRepository crewPenaltyHistoryRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AdminContext adminContext;

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-28T12:00:00Z"), ZoneId.of("UTC"));

    private AdminCrewPenaltyCommandService service;

    private static final long PARTYROOM_ID = 1L;
    private static final long CREW_ID = 10L;
    private static final long ADMIN_ID = 100L;

    @BeforeEach
    void setUp() {
        service = new AdminCrewPenaltyCommandService(
                aggregatePort, partyroomAccessCommandService, crewPenaltyHistoryRepository,
                eventPublisher, adminContext, clock);
    }

    private PartyroomData mockPartyroom(PartyroomStatus status) {
        PartyroomData p = org.mockito.Mockito.mock(PartyroomData.class);
        // lenient: apply_crew_other_partyroom 등 일부 시나리오에서 isTerminated()가 호출되지 않을 수 있음.
        // mockPartyroom 헬퍼는 분기마다 다른 호출 path를 갖는 테스트들이 공유하므로 strict stub 위반 회피.
        org.mockito.Mockito.lenient().when(p.isTerminated()).thenReturn(status == PartyroomStatus.TERMINATED);
        return p;
    }

    private CrewData mockCrew(long crewId, long partyroomId) {
        CrewData c = org.mockito.Mockito.mock(CrewData.class);
        org.mockito.Mockito.lenient().when(c.getId()).thenReturn(crewId);
        org.mockito.Mockito.lenient().when(c.getPartyroomId()).thenReturn(new PartyroomId(partyroomId));
        return c;
    }

    @Test @DisplayName("apply PERMANENT: history 저장 + 이벤트 발행, historyId 반환")
    void apply_permanent() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(PartyroomStatus.ACTIVE);
        CrewData crew = mockCrew(CREW_ID, PARTYROOM_ID);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));
        given(aggregatePort.findCrewById(CREW_ID)).willReturn(Optional.of(crew));
        given(crewPenaltyHistoryRepository.save(any())).willAnswer(inv -> {
            CrewPenaltyHistoryData h = inv.getArgument(0);
            // simulate generated id
            try {
                var f = CrewPenaltyHistoryData.class.getDeclaredField("id");
                f.setAccessible(true);
                f.set(h, 999L);
            } catch (Exception e) { throw new RuntimeException(e); }
            return h;
        });

        Long result = service.apply(PARTYROOM_ID, new AdminApplyPenaltyCommand(
                CREW_ID, AdminPenaltyType.PERMANENT_EXPULSION, "abuse"));

        assertThat(result).isEqualTo(999L);
        verify(partyroomAccessCommandService).expel(partyroom, crew, true);

        ArgumentCaptor<CrewPenaltyHistoryData> hCap = ArgumentCaptor.forClass(CrewPenaltyHistoryData.class);
        verify(crewPenaltyHistoryRepository).save(hCap.capture());
        assertThat(hCap.getValue().getPunisherType()).isEqualTo(PunisherType.ADMIN);
        assertThat(hCap.getValue().getPunisherCrewId()).isNull();
        assertThat(hCap.getValue().getPenaltyType()).isEqualTo(PenaltyType.PERMANENT_EXPULSION);

        ArgumentCaptor<AdminCrewPenalizedEvent> eCap = ArgumentCaptor.forClass(AdminCrewPenalizedEvent.class);
        verify(eventPublisher).publishEvent(eCap.capture());
        assertThat(eCap.getValue().getAdministratorId()).isEqualTo(ADMIN_ID);
        assertThat(eCap.getValue().getCrewPenaltyHistoryId()).isEqualTo(999L);
        assertThat(eCap.getValue().getPenaltyType()).isEqualTo(PenaltyType.PERMANENT_EXPULSION);
    }

    @Test @DisplayName("apply ONE_TIME: history 저장 안 함, 이벤트만 historyId=null")
    void apply_one_time() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(PartyroomStatus.ACTIVE);
        CrewData crew = mockCrew(CREW_ID, PARTYROOM_ID);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));
        given(aggregatePort.findCrewById(CREW_ID)).willReturn(Optional.of(crew));

        Long result = service.apply(PARTYROOM_ID, new AdminApplyPenaltyCommand(
                CREW_ID, AdminPenaltyType.ONE_TIME_EXPULSION, "warning"));

        assertThat(result).isNull();
        verify(partyroomAccessCommandService).expel(partyroom, crew, false);
        verify(crewPenaltyHistoryRepository, never()).save(any());

        ArgumentCaptor<AdminCrewPenalizedEvent> eCap = ArgumentCaptor.forClass(AdminCrewPenalizedEvent.class);
        verify(eventPublisher).publishEvent(eCap.capture());
        assertThat(eCap.getValue().getCrewPenaltyHistoryId()).isNull();
    }

    @Test @DisplayName("apply: partyroom not found → PartyroomException.NOT_FOUND_ROOM")
    void apply_partyroom_not_found() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(PARTYROOM_ID, new AdminApplyPenaltyCommand(
                CREW_ID, AdminPenaltyType.PERMANENT_EXPULSION, "abuse")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(PartyroomException.NOT_FOUND_ROOM.getMessage());
    }

    @Test @DisplayName("apply: partyroom TERMINATED → ALREADY_TERMINATED")
    void apply_partyroom_terminated() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(PartyroomStatus.TERMINATED);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));

        assertThatThrownBy(() -> service.apply(PARTYROOM_ID, new AdminApplyPenaltyCommand(
                CREW_ID, AdminPenaltyType.PERMANENT_EXPULSION, "abuse")))
                .hasMessageContaining(PartyroomException.ALREADY_TERMINATED.getMessage());
    }

    @Test @DisplayName("apply: crew가 다른 룸 소속 → CrewException.NOT_FOUND_ROOM")
    void apply_crew_other_partyroom() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(PartyroomStatus.ACTIVE);
        CrewData crew = mockCrew(CREW_ID, 999L);   // 다른 룸
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));
        given(aggregatePort.findCrewById(CREW_ID)).willReturn(Optional.of(crew));

        assertThatThrownBy(() -> service.apply(PARTYROOM_ID, new AdminApplyPenaltyCommand(
                CREW_ID, AdminPenaltyType.PERMANENT_EXPULSION, "abuse")))
                .hasMessageContaining(CrewException.NOT_FOUND_ROOM.getMessage());
    }

    @Test @DisplayName("release: punisher_type=ADMIN → 정상 release")
    void release_admin() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(PartyroomStatus.ACTIVE);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));

        CrewPenaltyHistoryData history = org.mockito.Mockito.mock(CrewPenaltyHistoryData.class);
        given(history.getPunisherType()).willReturn(PunisherType.ADMIN);
        given(history.getPunishedCrewId()).willReturn(new CrewId(CREW_ID));
        given(crewPenaltyHistoryRepository.findByIdAndPartyroomIdAndReleasedIsFalse(999L, new PartyroomId(PARTYROOM_ID)))
                .willReturn(Optional.of(history));

        CrewData crew = mockCrew(CREW_ID, PARTYROOM_ID);
        given(aggregatePort.findCrewById(CREW_ID)).willReturn(Optional.of(crew));

        service.release(PARTYROOM_ID, 999L);

        verify(crew).releaseBan();
        verify(aggregatePort).saveCrew(crew);
        verify(history).releaseByAdmin(any());
        verify(crewPenaltyHistoryRepository).save(history);
        verify(eventPublisher).publishEvent(any(AdminCrewPenaltyReleasedEvent.class));
    }

    @Test @DisplayName("release: punisher_type=CREW → CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE")
    void release_crew_applied_blocked() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(PartyroomStatus.ACTIVE);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));

        CrewPenaltyHistoryData history = org.mockito.Mockito.mock(CrewPenaltyHistoryData.class);
        given(history.getPunisherType()).willReturn(PunisherType.CREW);
        given(crewPenaltyHistoryRepository.findByIdAndPartyroomIdAndReleasedIsFalse(999L, new PartyroomId(PARTYROOM_ID)))
                .willReturn(Optional.of(history));

        assertThatThrownBy(() -> service.release(PARTYROOM_ID, 999L))
                .hasMessageContaining(PenaltyException.CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE.getMessage());
        verify(eventPublisher, never()).publishEvent(any(AdminCrewPenaltyReleasedEvent.class));
    }

    @Test @DisplayName("release: history not found → PENALTY_HISTORY_NOT_FOUND (404 매핑)")
    void release_history_not_found() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(PartyroomStatus.ACTIVE);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));
        given(crewPenaltyHistoryRepository.findByIdAndPartyroomIdAndReleasedIsFalse(any(), any()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.release(PARTYROOM_ID, 999L))
                .hasMessageContaining(PenaltyException.PENALTY_HISTORY_NOT_FOUND.getMessage());
    }
}
```

import 검증: `BusinessException`은 `ExceptionCreator.create`가 throw하는 wrapper 클래스. 기존 PR 8 테스트가 같은 import를 쓰는지 점검 후 동일 import 사용. (없으면 단순 `Throwable.class`로도 OK.)

- [ ] **Step 2: 테스트 컴파일 실패 확인 (서비스 미존재)**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -20
```

Expected: `AdminCrewPenaltyCommandService symbol not found`.

- [ ] **Step 3: `AdminCrewPenaltyCommandService` 작성**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.dto.command.AdminApplyPenaltyCommand;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewPenaltyHistoryRepository;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.history.CrewPenaltyHistoryData;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.PunisherType;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenaltyReleasedEvent;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.exception.PenaltyException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 어드민 크루 페널티 부과/해제 — orchestration 담당.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §5.3
 *
 * PR 8 AdminPartyroomCommandService와 동일 패턴 (load → validate → mutate → save → publish):
 * - port impl(PartyroomAggregateAdapter)은 thin CRUD pass-through 유지
 * - 본 service가 collaborator 직접 사용
 *
 * Cross-BC: administration → party (PR 8 ArchUnit 가드 단방향, 합법).
 */
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

        // PERMANENT_EXPULSION만 history row 저장 — 기존 CrewPenaltyCommandService 동작과 대칭.
        // 이미 ban된 crew에 다시 PERMANENT를 부과해도 멱등 + 새 history row 생성 (audit 완전성, spec §4.1 #6).
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

        // partyroom 존재 검증만 (status TERMINATED여도 release 허용 — cleanup이 종료 후 발생할 수 있음).
        aggregatePort.findPartyroomById(partyroomId)
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));

        CrewPenaltyHistoryData history = crewPenaltyHistoryRepository
                .findByIdAndPartyroomIdAndReleasedIsFalse(penaltyId, pid)
                .orElseThrow(() -> ExceptionCreator.create(PenaltyException.PENALTY_HISTORY_NOT_FOUND));

        if (history.getPunisherType() != PunisherType.ADMIN) {
            throw ExceptionCreator.create(PenaltyException.CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE);
        }

        // 1. crew의 ban 해제 — 자동 재입장 없음 (release는 ban 플래그만 클리어).
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

- [ ] **Step 3.5: `CrewException.NOT_FOUND_ROOM` 코드 추가 (필수)**

기존 `CrewException.java`에는 `NOT_FOUND_ACTIVE_ROOM`(CRW-001)과 `INVALID_ACTIVE_ROOM`(CRW-002)만 존재 — `NOT_FOUND_ROOM`은 코드베이스에 없으므로 본 PR에서 신규 추가한다.

`CrewException.java`에 추가:

```java
NOT_FOUND_ROOM("CRW-003", "파티룸의 크루가 아닙니다", ErrorType.NOT_FOUND),
```

(이미 NOT_FOUND_ACTIVE_ROOM이 있어 이름 충돌 없음. 의미 차별화: ACTIVE_ROOM = 본인 active 룸 / NOT_FOUND_ROOM = 지정 룸의 크루 일반 검증.)

- [ ] **Step 4: 단위 테스트 통과**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.application.service.AdminCrewPenaltyCommandServiceTest"
```

Expected: PASS (8 cases).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandService.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandServiceTest.java \
        app/src/main/java/com/pfplaybackend/api/party/domain/exception/CrewException.java

git commit -m "feat(administration): AdminCrewPenaltyCommandService — apply + release (PR 9)

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §5.3

orchestration 담당 service. PR 8 AdminPartyroomCommandService 패턴:
load → validate → mutate (expel + history INSERT/release) → publishEvent.

- apply: partyroom/crew validation → expel(isPermanent) → PERMANENT만 history row INSERT
  (punisher_type=ADMIN, punisher_crew_id=null) → AdminCrewPenalizedEvent
- release: partyroom 존재만 확인 (status 무관, cleanup post-termination 허용),
  punisher_type=ADMIN guard → crew.releaseBan + history.releaseByAdmin + 이벤트
- CrewException.NOT_FOUND_ROOM 코드 추가 (다른 룸 crew 차단용)

Cross-BC: administration → party (PR 8 ArchUnit 단방향).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

---

## Chunk 5: AdminCrewPenaltyCommandController

### Task 11: Controller + WebMvc 테스트

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminCrewPenaltyCommandController.java`
- Create: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminCrewPenaltyCommandControllerTest.java`

- [ ] **Step 1: WebMvc 테스트 먼저 — endpoint 시그니처 + 권한 + status 분기**

PR 8 `AdminPartyroomCommandControllerTest` 의 셋업 패턴을 그대로 따른다. 핵심 케이스:

```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.application.service.AdminCrewPenaltyCommandService;
// ... PR 8 패턴의 imports + WebMvc test 셋업 (MockMvc, @WebMvcTest with security, mock JWT, ApiCommonResponse 검증) ...

@WebMvcTest(AdminCrewPenaltyCommandController.class)
@Import({/* PR 8과 동일 security test config */})
class AdminCrewPenaltyCommandControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean AdminCrewPenaltyCommandService service;

    @Test @DisplayName("POST 정상 → 201 + body penaltyId")
    void post_ok() throws Exception {
        given(service.apply(eq(1L), any())).willReturn(999L);

        mockMvc.perform(post("/api/v1/admin/partyrooms/1/penalties")
                        .with(/* admin JWT */)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crewId":10,"penaltyType":"PERMANENT_EXPULSION","reason":"abuse"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.penaltyId").value(999));
    }

    @Test @DisplayName("POST validation 위반 → 400")
    void post_validation() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/1/penalties")
                        .with(/* admin JWT */)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crewId":null,"penaltyType":"PERMANENT_EXPULSION","reason":"x"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test @DisplayName("POST 비-admin JWT → 403")
    void post_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/1/penalties")
                        .with(/* user JWT (not admin) */)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crewId":10,"penaltyType":"PERMANENT_EXPULSION","reason":"abuse"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test @DisplayName("POST 미인증 → 401")
    void post_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/1/penalties")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crewId":10,"penaltyType":"PERMANENT_EXPULSION","reason":"abuse"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test @DisplayName("POST partyroom not found → 404")
    void post_not_found() throws Exception {
        given(service.apply(eq(1L), any()))
                .willThrow(ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));

        mockMvc.perform(post("/api/v1/admin/partyrooms/1/penalties")
                        .with(/* admin JWT */)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crewId":10,"penaltyType":"PERMANENT_EXPULSION","reason":"abuse"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test @DisplayName("POST partyroom TERMINATED → 403 (ALREADY_TERMINATED is FORBIDDEN type)")
    void post_terminated() throws Exception {
        given(service.apply(eq(1L), any()))
                .willThrow(ExceptionCreator.create(PartyroomException.ALREADY_TERMINATED));

        mockMvc.perform(post("/api/v1/admin/partyrooms/1/penalties")
                        .with(/* admin JWT */)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crewId":10,"penaltyType":"PERMANENT_EXPULSION","reason":"abuse"}
                                """))
                .andExpect(status().isForbidden());   // ALREADY_TERMINATED → ErrorType.FORBIDDEN
    }

    @Test @DisplayName("DELETE 정상 → 204")
    void delete_ok() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/partyrooms/1/penalties/999")
                        .with(/* admin JWT */))
                .andExpect(status().isNoContent());
        verify(service).release(1L, 999L);
    }

    @Test @DisplayName("DELETE crew-applied → 403")
    void delete_blocked_crew_applied() throws Exception {
        org.mockito.Mockito.doThrow(ExceptionCreator.create(PenaltyException.CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE))
                .when(service).release(1L, 999L);

        mockMvc.perform(delete("/api/v1/admin/partyrooms/1/penalties/999")
                        .with(/* admin JWT */))
                .andExpect(status().isForbidden());
    }

    @Test @DisplayName("DELETE history not found → 404")
    void delete_not_found() throws Exception {
        org.mockito.Mockito.doThrow(ExceptionCreator.create(PenaltyException.PENALTY_HISTORY_NOT_FOUND))
                .when(service).release(1L, 999L);

        mockMvc.perform(delete("/api/v1/admin/partyrooms/1/penalties/999")
                        .with(/* admin JWT */))
                .andExpect(status().isNotFound());
    }
}
```

**중요**: `ALREADY_TERMINATED`은 `ErrorType.FORBIDDEN`(403). spec §4.1 errors 표는 409로 적었으나 실제 에러 매핑은 PartyroomException.ALREADY_TERMINATED(FORBIDDEN)을 그대로 사용함 → 403 응답. plan 따른다 (test 기준은 코드 실제 동작).

> spec drift note: §4.1 errors 표의 409는 PartyroomException 코드의 FORBIDDEN(403) 매핑과 어긋남. 본 PR은 기존 코드의 FORBIDDEN 시맨틱 유지하고, post-build catch-up §11에 명시.

- [ ] **Step 2: 테스트 컴파일 실패 확인 (controller 미존재)**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 실패.

- [ ] **Step 3: Controller 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.AdminApplyPenaltyRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminApplyPenaltyResponse;
import com.pfplaybackend.api.administration.application.service.AdminCrewPenaltyCommandService;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.exception.PenaltyException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 어드민의 크루 페널티 부과/해제 endpoint.
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §4.1, §4.2
 *
 * 기존 /api/v1/partyrooms/{id}/penalties (CrewPenaltyCommandController)는
 * 크루 페널티 경로 그대로 — 본 controller는 어드민 전용 분리 경로.
 */
@Tag(name = "Admin Partyroom Penalty API")
@RequestMapping("/api/v1/admin/partyrooms/{partyroomId}/penalties")
@RestController
@RequiredArgsConstructor
public class AdminCrewPenaltyCommandController {

    private final AdminCrewPenaltyCommandService service;

    @Operation(summary = "어드민 페널티 부과",
            description = "ONE_TIME_EXPULSION 또는 PERMANENT_EXPULSION을 어드민 권한으로 부과한다.")
    @ApiResponse(responseCode = "201", description = "부과 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PartyroomException.class, CrewException.class})
    @PostMapping
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<AdminApplyPenaltyResponse>> apply(
            @Parameter(description = "파티룸 ID") @PathVariable("partyroomId") Long partyroomId,
            @Valid @RequestBody AdminApplyPenaltyRequest req) {
        Long penaltyId = service.apply(partyroomId, req.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(new AdminApplyPenaltyResponse(penaltyId)));
    }

    @Operation(summary = "어드민 페널티 해제",
            description = "어드민이 부과한 페널티만 해제 가능. crew-applied는 403.")
    @ApiResponse(responseCode = "204", description = "해제 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PartyroomException.class, PenaltyException.class})
    @DeleteMapping("/{penaltyId}")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<Void> release(
            @Parameter(description = "파티룸 ID") @PathVariable("partyroomId") Long partyroomId,
            @Parameter(description = "페널티 이력 ID") @PathVariable("penaltyId") Long penaltyId) {
        service.release(partyroomId, penaltyId);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: WebMvc 테스트 통과**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.adapter.in.web.AdminCrewPenaltyCommandControllerTest"
```

Expected: PASS (~9 cases).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminCrewPenaltyCommandController.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminCrewPenaltyCommandControllerTest.java

git commit -m "feat(administration): AdminCrewPenaltyCommandController — POST/DELETE endpoints (PR 9)

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §4.1, §4.2

POST /api/v1/admin/partyrooms/{id}/penalties (B-7) — admin penalty 부과
DELETE /api/v1/admin/partyrooms/{id}/penalties/{penaltyId} — admin release

@PreAuthorize(\"@adminAuth.isAdmin()\") (PR 5 SpEL bean) + admin cookie chain.
ApiCommonResponse 래핑으로 PR 0~8 패턴과 일관.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

---

## Chunk 6: 기존 crew DELETE 가드 + Read-side 보강

### Task 12: 기존 `CrewPenaltyCommandService.releaseCrewPenalty` 가드 추가

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/CrewPenaltyCommandService.java`

- [ ] **Step 1: 단위 테스트 보강 — admin-applied row 시 거부**

`CrewPenaltyCommandServiceTest.java`에 추가:

```java
@Test @DisplayName("releaseCrewPenalty: admin-applied (punisher_type=ADMIN) → 거부")
void release_admin_applied_blocked() {
    AuthContext ctx = AuthContext.builder()
            .userId(new UserId(1L))
            .build();
    ThreadLocalContext.setAuthContext(ctx);

    PartyroomData partyroom = org.mockito.Mockito.mock(PartyroomData.class);
    given(partyroomQueryService.getPartyroomById(any())).willReturn(partyroom);

    CrewData releaserCrew = org.mockito.Mockito.mock(CrewData.class);
    given(releaserCrew.isBelowGrade(GradeType.MODERATOR)).willReturn(false);
    given(releaserCrew.getId()).willReturn(99L);
    given(partyroomQueryService.getCrewOrThrow(any(), any())).willReturn(releaserCrew);

    CrewPenaltyHistoryData history = org.mockito.Mockito.mock(CrewPenaltyHistoryData.class);
    given(history.getPunisherType()).willReturn(PunisherType.ADMIN);
    given(crewPenaltyHistoryRepository.findByIdAndPartyroomIdAndReleasedIsFalse(eq(999L), any()))
            .willReturn(Optional.of(history));

    assertThatThrownBy(() -> service.releaseCrewPenalty(new PartyroomId(1L), 999L))
            .hasMessageContaining(PenaltyException.ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE.getMessage());
    verify(history, never()).release(any(), any());
}
```

- [ ] **Step 2: 테스트 실행으로 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.CrewPenaltyCommandServiceTest.release_admin_applied_blocked"
```

Expected: FAIL (현재 가드 없으므로 release가 정상 진행).

- [ ] **Step 3: `releaseCrewPenalty`에 가드 한 줄 추가**

`CrewPenaltyCommandService.java` line 88~107 근방:

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

    // [PR 9] admin-applied 페널티는 admin endpoint를 통해서만 release 가능 (spec §4.3)
    if (historyData.getPunisherType() == PunisherType.ADMIN) {
        throw ExceptionCreator.create(PenaltyException.ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE);
    }

    // 1. Release ban on crew
    CrewData crew = aggregatePort.findCrewById(historyData.getPunishedCrewId().getId())
            .orElseThrow();
    crew.releaseBan();
    aggregatePort.saveCrew(crew);

    // 2. Update history
    historyData.release(new CrewId(releaserCrewForValidation.getId()), LocalDateTime.now(clock));
    crewPenaltyHistoryRepository.save(historyData);
}
```

import 추가:
```java
import com.pfplaybackend.api.party.domain.enums.PunisherType;
```

- [ ] **Step 4: 테스트 통과**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.CrewPenaltyCommandServiceTest"
```

Expected: PASS (전 테스트).

- [ ] **Step 5: 기존 `CrewPenaltyCommandController` WebMvc 테스트도 가드 검증**

`CrewPenaltyCommandControllerTest.java`에 추가:

```java
@Test @DisplayName("DELETE: admin-applied 페널티 → 403 ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE")
void delete_admin_applied_blocked() throws Exception {
    org.mockito.Mockito.doThrow(ExceptionCreator.create(PenaltyException.ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE))
            .when(crewPenaltyCommandService).releaseCrewPenalty(any(), eq(999L));

    mockMvc.perform(delete("/api/v1/partyrooms/1/penalties/999")
                    .with(/* user JWT */))
            .andExpect(status().isForbidden());
}
```

- [ ] **Step 6: WebMvc 테스트 통과**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.adapter.in.web.CrewPenaltyCommandControllerTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/application/service/CrewPenaltyCommandService.java \
        app/src/test/java/com/pfplaybackend/api/party/application/service/CrewPenaltyCommandServiceTest.java \
        app/src/test/java/com/pfplaybackend/api/party/adapter/in/web/CrewPenaltyCommandControllerTest.java

git commit -m "feat(party): crew release endpoint 가드 — admin-applied 거부 (PR 9)

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §4.3

기존 DELETE /api/v1/partyrooms/{id}/penalties/{penaltyId} 호출 시 history의
punisher_type=ADMIN이면 PNT-003 (ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE)
로 403 반환. unreleased row만 가드 트리거 — 이미 released된 admin row는 기존
findByIdAndPartyroomIdAndReleasedIsFalse가 404 (PENALTY_HISTORY_NOT_FOUND).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

### Task 13: PR 8 detail 응답의 `recentPenalties` V8 컬럼 wiring

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminPartyroomQueryService.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/CrewPenaltyHistoryRepository.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminPartyroomQueryServiceTest.java` (PR 8 IT 갱신)

PR 8은 `recentPenalties = emptyList()` hardcoded. PR 9는 `crew_penalty_history`에서 partyroomId 기준 최신 N개를 가져와 매핑.

- [ ] **Step 1: Repository 메서드 추가**

`CrewPenaltyHistoryRepository.java`:

```java
package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.party.domain.entity.data.history.CrewPenaltyHistoryData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrewPenaltyHistoryRepository extends JpaRepository<CrewPenaltyHistoryData, Long> {
    List<CrewPenaltyHistoryData> findAllByPartyroomIdAndReleasedIsFalse(PartyroomId partyroomId);
    Optional<CrewPenaltyHistoryData> findByIdAndPartyroomIdAndReleasedIsFalse(Long id, PartyroomId partyroomId);

    /** [PR 9] partyroom의 최근 페널티 (released 포함). admin detail 응답용. */
    List<CrewPenaltyHistoryData> findTop5ByPartyroomIdOrderByPenaltyDateDesc(PartyroomId partyroomId);
}
```

- [ ] **Step 2: `AdminPartyroomQueryService` 수정**

PR 8에서 정의된 `AdminPartyroomDetailResponse.PenaltySummary` record 시그니처 (변경 안 함):

```java
public record PenaltySummary(
        Long id,
        Long crewId,
        PenaltyType penaltyType,    // party domain enum
        String punisherType,        // V8 컬럼 값(ENUM "CREW"|"ADMIN"의 .name())
        String reason,
        LocalDateTime date
) {}
```

기존 (line 124~126):
```java
// 6. Recent penalties / reports — PR 8 MVP returns empty (PR 9 / PR 13 will populate).
var recentPenalties = Collections.<AdminPartyroomDetailResponse.PenaltySummary>emptyList();
var recentReports = Collections.<AdminPartyroomDetailResponse.ReportSummary>emptyList();
```

PR 9에서:
```java
// 6a. Recent penalties — PR 9 V8 컬럼에서 채움.
List<AdminPartyroomDetailResponse.PenaltySummary> recentPenalties =
        crewPenaltyHistoryRepository.findTop5ByPartyroomIdOrderByPenaltyDateDesc(partyroomId)
                .stream()
                .map(h -> new AdminPartyroomDetailResponse.PenaltySummary(
                        h.getId(),
                        h.getPunishedCrewId() == null ? null : h.getPunishedCrewId().getId(),
                        h.getPenaltyType(),
                        h.getPunisherType().name(),
                        h.getPenaltyReason(),
                        h.getPenaltyDate()))
                .toList();

// 6b. Recent reports — PR 13에서 채움.
var recentReports = Collections.<AdminPartyroomDetailResponse.ReportSummary>emptyList();
```

constructor에 `CrewPenaltyHistoryRepository crewPenaltyHistoryRepository` 추가.

import 추가:
```java
import com.pfplaybackend.api.party.adapter.out.persistence.CrewPenaltyHistoryRepository;
```

- [ ] **Step 3: PR 8 IT/단위 테스트 보강**

기존 `AdminPartyroomQueryServiceTest.java`에 페널티 row 사전 INSERT + projection 검증 추가. 또는 IT (`AdminPartyroomQueryRepositoryImplIT`)에 한 케이스 추가:

```java
@Test @DisplayName("getDetail: recentPenalties는 V8 punisher_type 컬럼 값으로 채워짐")
void detail_recentPenalties_includes_punisherType() {
    // Arrange: partyroom 1 + crew 10 + crew_penalty_history row 2개 (CREW + ADMIN)
    crewPenaltyHistoryRepository.save(CrewPenaltyHistoryData.builder()
            .partyroomId(new PartyroomId(1L))
            .punishedCrewId(new CrewId(10L))
            .punisherCrewId(new CrewId(20L))
            .punisherType(PunisherType.CREW)
            .penaltyType(PenaltyType.PERMANENT_EXPULSION)
            .penaltyReason("crew reason")
            .penaltyDate(LocalDateTime.of(2026, 4, 27, 10, 0))
            .released(false)
            .build());
    crewPenaltyHistoryRepository.save(CrewPenaltyHistoryData.builder()
            .partyroomId(new PartyroomId(1L))
            .punishedCrewId(new CrewId(11L))
            .punisherCrewId(null)
            .punisherType(PunisherType.ADMIN)
            .penaltyType(PenaltyType.PERMANENT_EXPULSION)
            .penaltyReason("admin reason")
            .penaltyDate(LocalDateTime.of(2026, 4, 28, 10, 0))
            .released(false)
            .build());

    // Act
    AdminPartyroomDetailResponse resp = service.getDetail(new PartyroomId(1L));

    // Assert
    assertThat(resp.recentPenalties()).hasSize(2);
    assertThat(resp.recentPenalties()).extracting(AdminPartyroomDetailResponse.PenaltySummary::punisherType)
            .containsExactly("ADMIN", "CREW");   // 시간 desc 순
}
```

PR 8 IT의 `recentPenalties()`가 `[]` 검증 코드는 V8 컬럼 검증으로 갱신.

- [ ] **Step 4: 테스트 통과**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/CrewPenaltyHistoryRepository.java \
        app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminPartyroomQueryService.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminPartyroomQueryServiceTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminPartyroomQueryRepositoryImplIT.java

git commit -m "feat(administration): recentPenalties projection from V8 column (PR 9)

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §5.5

PR 8은 detail 응답의 recentPenalties를 emptyList로 hardcode 했음 — PR 9에서
V8 punisher_type 컬럼 도입 후 실데이터 projection으로 전환.

- CrewPenaltyHistoryRepository.findTop5ByPartyroomIdOrderByPenaltyDateDesc 추가
- AdminPartyroomQueryService.getDetail: 6a step에서 V8 컬럼 매핑
- PR 8 IT의 [] 검증을 V8 컬럼 (CREW/ADMIN) 검증으로 갱신
- 응답 shape 동일(string), 클라이언트 호환성 영향 0

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

---

## Chunk 7: End-to-End IT + Concurrency 회귀

### Task 14: AdminCrewPenaltyCommandService end-to-end IT

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandServiceIT.java`

PR 8 `AdminPartyroomCommandServiceIT` 패턴(@SpringBootTest + Testcontainers + admin/partyroom/crew fixture) 그대로 따른다.

- [ ] **Step 1: IT 작성 — apply / release 풀 시나리오**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.application.dto.command.AdminApplyPenaltyCommand;
import com.pfplaybackend.api.administration.domain.enums.AdminPenaltyType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewPenaltyHistoryRepository;
import com.pfplaybackend.api.party.domain.entity.data.history.CrewPenaltyHistoryData;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.PunisherType;
// ... 기타 imports + Testcontainers + admin/partyroom/crew fixture

@SpringBootTest
@Testcontainers
@Transactional
class AdminCrewPenaltyCommandServiceIT {

    // ... 기존 PR 8 IT의 fixture 셋업 그대로 (administrator + partyroom + crew). adminContext mock으로 administratorId 주입 ...

    @Test @DisplayName("apply PERMANENT: history(ADMIN) + admin_action(PENALIZE_CREW) atomic INSERT")
    void apply_permanent_atomic() {
        // arrange: partyroom + crew 활성 상태로 셋업
        // act
        Long historyId = service.apply(partyroomId.getId(), new AdminApplyPenaltyCommand(
                crewId.getId(), AdminPenaltyType.PERMANENT_EXPULSION, "abuse"));
        // assert: history row + admin_action row가 같은 TX로 commit
        assertThat(historyId).isNotNull();
        CrewPenaltyHistoryData h = crewPenaltyHistoryRepository.findById(historyId).orElseThrow();
        assertThat(h.getPunisherType()).isEqualTo(PunisherType.ADMIN);
        assertThat(h.getPunisherCrewId()).isNull();
        assertThat(h.getPenaltyType()).isEqualTo(PenaltyType.PERMANENT_EXPULSION);

        var actions = adminActionRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId.getId());
        assertThat(actions).extracting(a -> a.getActionType())
                .contains(PartyroomAdminActionType.PENALIZE_CREW);
    }

    @Test @DisplayName("apply ONE_TIME: history 없음, admin_action만")
    void apply_one_time_no_history() {
        Long historyId = service.apply(partyroomId.getId(), new AdminApplyPenaltyCommand(
                crewId.getId(), AdminPenaltyType.ONE_TIME_EXPULSION, "warning"));
        assertThat(historyId).isNull();
        // history 없음 (partyroom 룸의 모든 history row count == 사전 카운트)
        // admin_action에 PENALIZE_CREW row 존재
    }

    @Test @DisplayName("apply: TERMINATED 룸 거부 → ALREADY_TERMINATED")
    void apply_terminated_rejected() {
        // arrange: partyroom.terminate() 사전 적용
        assertThatThrownBy(() -> service.apply(partyroomId.getId(), new AdminApplyPenaltyCommand(
                crewId.getId(), AdminPenaltyType.PERMANENT_EXPULSION, "abuse")))
                .hasMessageContaining(PartyroomException.ALREADY_TERMINATED.getMessage());
    }

    @Test @DisplayName("apply: 다른 룸 crew 거부")
    void apply_other_partyroom_crew_rejected() {
        // arrange: 또 다른 partyroom의 crew를 시도
        // assertThatThrownBy ... CrewException.NOT_FOUND_ROOM
    }

    @Test @DisplayName("apply 멱등: 이미 ban된 crew에 PERMANENT 다시 부과 → history row 새로 생성")
    void apply_already_banned_creates_new_history() {
        // arrange: crew.enforceBan() 사전 적용 + history row 1개 사전 INSERT
        // act
        Long historyId = service.apply(partyroomId.getId(), new AdminApplyPenaltyCommand(
                crewId.getId(), AdminPenaltyType.PERMANENT_EXPULSION, "again"));
        // assert: history row count +1 (총 2)
        assertThat(crewPenaltyHistoryRepository.findAllByPartyroomIdAndReleasedIsFalse(partyroomId)).hasSize(2);
    }

    @Test @DisplayName("release: punisher_type=ADMIN row → 정상 release + RELEASE_CREW_PENALTY audit")
    void release_admin_row() {
        Long historyId = service.apply(partyroomId.getId(), new AdminApplyPenaltyCommand(
                crewId.getId(), AdminPenaltyType.PERMANENT_EXPULSION, "abuse"));
        service.release(partyroomId.getId(), historyId);

        CrewPenaltyHistoryData h = crewPenaltyHistoryRepository.findById(historyId).orElseThrow();
        assertThat(h.isReleased()).isTrue();
        assertThat(h.getReleasedByCrewId()).isNull();

        var actions = adminActionRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId.getId());
        assertThat(actions).extracting(a -> a.getActionType())
                .contains(PartyroomAdminActionType.RELEASE_CREW_PENALTY);
    }

    @Test @DisplayName("release: punisher_type=CREW row → CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE")
    void release_crew_row_blocked() {
        // arrange: punisher_type=CREW history row 사전 INSERT (crew 경로 호출 또는 직접 save)
        // act + assert
        assertThatThrownBy(() -> service.release(partyroomId.getId(), crewHistoryId))
                .hasMessageContaining(PenaltyException.CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE.getMessage());
    }

    @Test @DisplayName("release: TERMINATED 룸도 release 허용 (cleanup)")
    void release_terminated_partyroom_allowed() {
        Long historyId = service.apply(partyroomId.getId(), new AdminApplyPenaltyCommand(
                crewId.getId(), AdminPenaltyType.PERMANENT_EXPULSION, "abuse"));
        // partyroom.terminate() 사후 적용 + save
        service.release(partyroomId.getId(), historyId);   // throw 없음
    }
}
```

- [ ] **Step 2: IT 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "com.pfplaybackend.api.administration.application.service.AdminCrewPenaltyCommandServiceIT"
```

Expected: PASS (~8 cases). **예상 1-2분 (Testcontainers cold)**.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandServiceIT.java
git commit -m "test(administration): AdminCrewPenaltyCommandService end-to-end IT (PR 9)

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §7.2

@SpringBootTest + Testcontainers MySQL — apply/release full path 8 cases:
- PERMANENT/ONE_TIME 부과 + admin_action atomic INSERT
- TERMINATED 룸 거부 + 다른 룸 crew 거부 + 멱등 (이미 ban 된 crew 재부과)
- ADMIN row release 정상 + CREW row release 거부 + TERMINATED 룸 release 허용

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

### Task 15: Concurrency 회귀 — Admin + Crew 동시 PERMANENT 부과

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyConcurrencyIT.java`

spec §6.1 race 시나리오 검증.

- [ ] **Step 1: 테스트 작성**

```java
@Test @DisplayName("Admin + Crew 동시 PERMANENT 부과 → history 2 row + ban idempotent + admin_action 1 row")
void concurrent_permanent_admin_and_crew() throws Exception {
    // arrange: partyroom + crew (active) + 호스트 crew (호스트 권한으로 punish 가능)
    AtomicReference<Throwable> adminErr = new AtomicReference<>();
    AtomicReference<Throwable> crewErr = new AtomicReference<>();

    Thread adminT = new Thread(() -> {
        try {
            adminCrewPenaltyCommandService.apply(partyroomId.getId(), new AdminApplyPenaltyCommand(
                    crewId.getId(), AdminPenaltyType.PERMANENT_EXPULSION, "admin abuse"));
        } catch (Throwable t) { adminErr.set(t); }
    });
    Thread crewT = new Thread(() -> {
        // host의 ThreadLocalContext 셋업 + crewPenaltyCommandService.addPenalty 호출
        try { /* ... */ } catch (Throwable t) { crewErr.set(t); }
    });

    adminT.start(); crewT.start();
    adminT.join(); crewT.join();

    assertThat(adminErr.get()).isNull();
    assertThat(crewErr.get()).isNull();

    // 두 history row 존재
    List<CrewPenaltyHistoryData> rows = crewPenaltyHistoryRepository.findAllByPartyroomIdAndReleasedIsFalse(partyroomId);
    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(CrewPenaltyHistoryData::getPunisherType)
            .containsExactlyInAnyOrder(PunisherType.ADMIN, PunisherType.CREW);

    // admin_action은 admin path만 1 row
    var actions = adminActionRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId.getId());
    long penalizeCount = actions.stream()
            .filter(a -> a.getActionType() == PartyroomAdminActionType.PENALIZE_CREW)
            .count();
    assertThat(penalizeCount).isEqualTo(1L);

    // ban 적용
    CrewData c = aggregatePort.findCrewById(crewId.getId()).orElseThrow();
    assertThat(c.isBanned()).isTrue();
}
```

테스트 셋업이 PR 8 concurrency IT (예: terminate vs enter)와 비슷. PR 8 패턴 재사용.

- [ ] **Step 2: IT 통과**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "com.pfplaybackend.api.administration.application.service.AdminCrewPenaltyConcurrencyIT"
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyConcurrencyIT.java
git commit -m "test(administration): concurrent admin+crew PERMANENT race (PR 9)

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §6.1

Admin과 Crew가 같은 crew에 동시에 PERMANENT_EXPULSION 부과 시:
- crew_penalty_history 2 row (CREW + ADMIN punisher_type 각각)
- crew.is_banned=true (enforceBan 멱등)
- partyroom_admin_action에 PENALIZE_CREW 1 row (admin path만)
spec의 정상 시맨틱 회귀 검증.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

### Task 16: ArchUnit 회귀 검증

**Files:**
- 없음 (기존 `CrossContextDependencyTest` 그대로)

PR 8의 ArchUnit 가드(`administration → party` 단방향)에 PR 9가 신규 위반을 추가하지 않는지 검증.

- [ ] **Step 1: ArchUnit 테스트 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*CrossContextDependencyTest"
```

Expected: PASS. PR 9 추가 의존:
- `AdminCrewPenaltyCommandService` (administration) → `PartyroomAggregatePort`/`PartyroomAccessCommandService`/`CrewPenaltyHistoryRepository` (party) — administration → party 단방향 합법.
- `AdminCrewPenalized*Event` (party) — administratorId 포함은 PR 8 `PartyroomTerminatedEvent` 선례라 가드에 영향 없음.
- `PartyroomAdminActionListener` (administration) → `AdminCrewPenalized*Event` (party) — listener가 party 이벤트 listen은 administration → party 합법.

만약 PASS되지 않으면 새 위반 위치 보고.

- [ ] **Step 2: 회귀 0 확인 후 별도 commit 없음** (테스트 패스만 확인 — 이미 commit된 상태)

---

## Chunk 8: 통합 회귀 + Open Items 동기화

### Task 17: 전체 빌드 + IT 통과 확인

- [ ] **Step 1: 클린 빌드 + 전 테스트 + IT 회귀**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew clean :app:test :app:integrationTest
```

Expected: BUILD SUCCESSFUL. **예상 10-20분 (clean cold)**.

- [ ] **Step 2: PR 8 회귀 0 점검**

다음 항목이 PR 8 시점과 동일하게 동작하는지 spot check:
- `AdminPartyroomCommandService.terminate/suspend/restore/setDisplayFlag/updateMeta` — 5 개 use case 단위 IT
- `PartyroomAdminActionListener` 기존 5 핸들러 (UPDATE_PARTYROOM_META, SET_FEATURED 등)
- `AdminPartyroomQueryService.getDetail` 응답에 `recentPenalties`만 변경 (PR 9), `recentReports/playback/dj/crew/admin_actions`는 PR 8 그대로
- `AdminPartyroomCommandController` 7 endpoint

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.*"
```

Expected: PASS (PR 8 + PR 9 모두).

- [ ] **Step 3: spec design doc § 11 (Implementation reality) 갱신**

`docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md` § 11에 구현 중 발견 사항을 추가:

(예시 내용은 task 11 step 1의 spec drift note "ALREADY_TERMINATED는 ErrorType.FORBIDDEN(403) — spec §4.1 errors 표의 409와 다름"을 반영. 그 외 발견 사항.)

```markdown
## 11. Open Items / Implementation Reality (post-build catch-up)

PR 9 구현 완료 시점에 spec과의 차이/세부 결정사항을 기록한다.

- **§4.1 errors 표의 409 → 실제 403**: `PartyroomException.ALREADY_TERMINATED`이 `ErrorType.FORBIDDEN`(403)으로 매핑되어 있어 spec 표의 409 표기와 어긋남. 본 PR은 기존 매핑 유지 (코드 일관성), spec drift note만 명문화.
- **`CrewException.NOT_FOUND_ROOM` 신규 코드**: 다른 룸 crew를 path로 시도 시 거부용. 기존 `NOT_FOUND_ACTIVE_ROOM`(active 룸 미참여)과 의미 차별화 (CRW-003).
- **Admin/Crew DTO/Service의 `partyroomId` 받는 시점**: path variable로 받은 `Long`을 service에 그대로 전달하고 service 내부에서 `new PartyroomId(...)` wrapping. PR 8 패턴과 일관.
```

- [ ] **Step 4: 갱신 commit**

```bash
git add docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md
git commit -m "docs(spec): catch up PR 9 design to implementation reality

Spec §11 (Implementation Reality) 채움 — spec drift 1건 + 신규 exception code 1건.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

---

## End State

- 모든 G1, G2, 및 task별 commit 적용 완료.
- V8 마이그레이션 + admin penalty 부과/해제 endpoint + crew release 가드 + read-side projection + listener 핸들러 + concurrency 회귀.
- PR 8 패턴(admin BC service orchestration + bare `@EventListener` + `DomainEvent` 상속)과 일관.
- 회귀 0: PR 8 endpoint/listener/projection 그대로 동작.

총 ~17 commits (G1 + G2 + 단발 commit 12개 + spec catch-up 1).

---

**다음 단계:** plan execution. `superpowers:subagent-driven-development`로 task별 fresh subagent 디스패치, 두 단계 review.
