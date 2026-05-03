# PR 12a: V10 user_activity_log + UserActivityLogListener Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** V10 마이그레이션 + Administration BC `UserActivityLogListener` (`@TransactionalEventListener(AFTER_COMMIT) + @Async`, drop-가능) + 신규 이벤트 2종(`UserAccountSignedInEvent` / `PartyroomCreatedEvent`) + `AdminCrewPenalizedEvent` / `CrewPenalizedEvent` evolution(`punishedUserAccountId` 추가) + 7개 event_type wiring으로 회원가입·로그인·프로필·룸 lifecycle·페널티 audit timeline을 자동 누적.

**Architecture:** Administration BC가 user_activity_log 테이블을 owns. listener는 비동기 + drop-가능 (audit timeline은 비즈니스 흐름을 막지 않음). Cross-BC lookup은 이벤트 self-contain으로 회피 — `punishedUserAccountId`를 party domain event에 직접 포함. PR 8/9의 sync atomic listener와 의도적 차별화 (admin write action vs activity timeline 시맨틱 분리).

**Tech Stack:** Java 21, Spring Boot 3.2 (Spring Security 6.2, Spring Data JPA 3.2, Hibernate 6.4), Flyway 9, JUnit 5, Mockito, AssertJ, ArchUnit, Testcontainers (MySQL 8 + Redis), Awaitility (async test), `@EnableAsync` 신규 도입.

**Spec source (read once, applied throughout):**
- `docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md` — 9 결정사항, 12 risks
- `docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md` §9.1 PR 12 (split a)
- `docs/superpowers/specs/2026-04-19-admin-platform-features.md` §6.A
- `docs/superpowers/specs/2026-04-19-admin-platform-schema.md` §4.7 V10 DDL

**Branching:** Continue on `feature/admin-auth-iam-schema`. Spec commit: `e0e6f0a2`. PR 12a builds on PR 11 HEAD `b180d3a8`.

**Out of scope (defer)** — spec §2.2:
- WITHDREW / TIER_CHANGED / ADMIN_ACTED_ON listener handler (PR 12b)
- Member 어드민 API 4개 (PR 12b)
- last_activity_desc projection (PR 12b)
- Partition 자동 생성 / 아카이브 배치 (future)
- PR 8/9 admin actions backfill to user_activity_log (PR 12b 또는 future)

---

## Atomic commit groupings

Per-task commits are the default. The following groups MUST land as a single commit so the tree stays green:

| Group | Tasks (chunks) | Reason |
|---|---|---|
| **G1: V10 SQL + entity + composite PK + enum (10종) + repository** | Chunk 1 (Tasks 1-5) | DDL ↔ JPA entity 매핑 boot-or-die. enum이 listener에 의존되므로 미리 10종 전체 정의. |
| **G2: AsyncConfig + UserActivityLogListener (skeleton + SIGNED_UP/PROFILE_UPDATED handlers)** | Chunk 2 (Tasks 6-8) | listener `@Async("userActivityLogExecutor")` qualifier가 bean 이름 직접 참조 — bean 부재 시 startup fail. 의존성 평탄한 2 핸들러 먼저. |
| **G3: AdminCrewPenalizedEvent evolution + PR 9 publisher 갱신 + PR 9 spec catch-up + listener handler 추가 + PR 9 test 갱신** | Chunk 3 (Tasks 9-12) | event payload 변경 ↔ 모든 publisher / consumer 동시 적용 + PR 9 design spec backfill. |
| **G6: CrewPenalizedEvent evolution + party publisher 갱신 + listener handler 추가** | Chunk 4 (Tasks 13-15) | 동일 사유. CrewPenaltyCommandService는 본 PR에서 처음 evolution. |
| **G4: PartyroomCreatedEvent + PartyroomCommandService publish + listener handler 추가** | Chunk 5 (Tasks 16-18) | publish-consume pair atomic, missing handler 시 dead event. |
| **G5: UserAccountSignedInEvent + AdminLoginService/AuthService publish + `@Transactional(readOnly = true)` 보강 + listener handler 추가** | Chunk 6 (Tasks 19-22) | publish-consume pair + TX context 보강 한 묶음. listener active TX 부재 시 silent drop. |

기타 task 별 독립 commit: ArchUnit, integration tests, concurrency tests, spec catch-up §12. 

Within each group:
- Per-task step lists remain a checklist.
- **Skip the `git commit` step at the end of each task in the group.**
- Single combined commit at the end of the group's last task with the message specified there.

---

## Hard precondition (verify BEFORE Chunk 1)

- [ ] **Step 1: Confirm spec commit on HEAD ancestry**

```bash
git log --oneline -3
```

Expected: HEAD includes `e0e6f0a2 docs(spec): PR 12a design — V10 user_activity_log ...`. Working tree clean.

- [ ] **Step 2: Working tree clean**

```bash
git status -s
```

Expected: empty.

- [ ] **Step 3: V10 slot open in `db/migration/`**

```bash
ls app/src/main/resources/db/migration/ | grep -E '^V[0-9]+__'
```

Expected: V1, V2, V3, V4, V5, V6, V7, V8, V9, V13, V14 present. **V10 must NOT exist.** (V13/V14는 후속 PR이 미리 잡아둔 slot이라 손대지 않음.)

- [ ] **Step 4: JDK 21 환경**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew --version
```

Expected: Gradle ~8.10, JVM 21.0.x.

- [ ] **Step 5: Baseline build + test pass**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest
```

Expected: BUILD SUCCESSFUL. **예상 5-15분 (cold)** — Testcontainers MySQL boot 포함, Docker daemon 가동 필요. PR 11 HEAD 위에서 회귀 0 보장 필요.

- [ ] **Step 6: Inventory of existing domain events + API ground truth (Task 0 — review advisory #1)**

**Ground truth (verified during plan writing — do not deviate):**
- `JsonMetadata` API: `JsonMetadata.of(Map<String,Object>)` + `JsonMetadata.empty()` + `data()` accessor (returns `Map<String,Object>`). **No `asMap()` method exists.** `JsonMetadata.of(null)` 또는 `Map.of()`은 모두 `EMPTY` 싱글턴 반환. `JsonMetadataConverter`가 빈 map을 SQL NULL로 직렬화 (확인됨, `JsonMetadata.java` Javadoc).
- `UserId` API: `UserId.create(Long)` 또는 `new UserId(Long)`. `getUid()` returns `Long`. **No `UserId.of(UUID)` or UUID-based factory.**
- `DomainEvent` 베이스: `getOccurredAt()` (Lombok `@Getter` from `LocalDateTime occurredAt`), `getEventId()` (UUID), `getEventType()` (`getClass().getSimpleName()`).

이제 기존 이벤트 시그니처 재확인:

```bash
for f in MemberRegisteredEvent UserProfileChangedEvent CrewAccessedEvent CrewPenalizedEvent AdminCrewPenalizedEvent; do
    echo "=== $f ==="
    find . -name "$f.java" -not -path "*/build/*" -not -path "*/test/*" -exec grep -E '^public class|private final|public.*\(' {} \;
done
```

Expected (verified during plan writing):
- `MemberRegisteredEvent`: `UserId userId`, `String email`, `ProviderType providerType` ✓ (충분)
- `UserProfileChangedEvent`: `UserId userId`, `ProfileChangeType changeType` ✓ (충분)
- `CrewAccessedEvent`: `PartyroomId partyroomId`, `CrewId crewId`, `UserId userId`, `AccessType accessType` ✓ (stage_type / duration_sec **부재** — listener metadata에서 단순화 처리; spec §4.3 listener 코드 정정)
- `CrewPenalizedEvent`: `PartyroomId partyroomId`, `CrewId punisherCrewId`, `CrewId punishedCrewId`, `String detail`, `PenaltyType penaltyType` ✓ (`punishedUserAccountId` **부재** — G6 evolution 필요)
- `AdminCrewPenalizedEvent` (PR 9): `PartyroomId partyroomId`, `Long administratorId`, `CrewId punishedCrewId`, `PenaltyType penaltyType`, `Long crewPenaltyHistoryId`, `String reason` ✓ (`punishedUserAccountId` **부재** — G3 evolution 필요)

- [ ] **Step 7: Inventory of `AuthService` OAuth login success path (Task 0 — review advisory #1)**

```bash
grep -n "ApplicationEventPublisher\|publishEvent\|public.*verify\|public.*authenticate\|public.*signIn\|public.*login" app/src/main/java/com/pfplaybackend/api/auth/application/service/AuthService.java
cat app/src/main/java/com/pfplaybackend/api/auth/application/service/AuthService.java | head -100
```

Expected: OAuth login success path 메서드 식별. 본 plan Chunk 6에서 publish 위치 정확히 박음.

- [ ] **Step 8: Inventory of `PartyroomData.create` + service publish path (Task 0)**

```bash
grep -n "registerDomainEvent\|pollDomainEvents\|publishEvent" \
    app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/PartyroomData.java \
    app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomCommandService.java
```

Expected (verified during plan writing): `PartyroomData.create` (line 125)는 도메인 이벤트 register 안 함. `PartyroomCommandService.createPartyroom` (private, line 64-74)도 publish 없음. → spec §5.2 가정 정정. Chunk 5에서 `PartyroomCommandService.createPartyroom` 끝에 `eventPublisher.publishEvent(new PartyroomCreatedEvent(...))` 한 줄 추가. spec §12 catch-up에 명시.

---

## Chunk 1: G1 — V10 SQL + UserActivityLogData entity + composite PK + UserActivityEventType enum + repository

**Goal of chunk:** V10 SQL 적용 시점에 동시에 JPA entity / composite PK / enum / repository 모두 boot 가능. 단일 G1 commit.

**End state of chunk:** V10 마이그까지 적용된 DB에서 어플리케이션 부팅 성공, `UserActivityLogRepository.save(UserActivityLogData.of(...))` 호출 가능. 단위 테스트 그린.

### Task 1: V10 Flyway 마이그레이션 SQL

**Files:**
- Create: `app/src/main/resources/db/migration/V10__create_user_activity_log.sql`

- [ ] **Step 1: V10 SQL 작성**

```sql
-- =====================================================
-- V10: Administration context — user_activity_log
-- Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.7
-- Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §3
-- Plan: docs/superpowers/plans/2026-04-28-admin-platform-pr12a.md Task 1
--
-- Append-only audit timeline. 월별 RANGE 파티셔닝.
-- 모든 user_account 참조는 loose ref (cross-context, no FK).
-- p_future MAXVALUE는 partition 자동 생성 배치 부재 시 안전망.
-- =====================================================

CREATE TABLE user_activity_log (
    log_id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_account_id   BIGINT       NOT NULL,
    event_type        VARCHAR(64)  NOT NULL,
    partyroom_id      BIGINT       NULL,
    metadata          JSON         NULL,
    occurred_at       DATETIME     NOT NULL,
    PRIMARY KEY (log_id, occurred_at),
    INDEX idx_ual_user_time (user_account_id, occurred_at DESC),
    INDEX idx_ual_event_time (event_type, occurred_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
PARTITION BY RANGE (TO_DAYS(occurred_at)) (
    PARTITION p202604 VALUES LESS THAN (TO_DAYS('2026-05-01')),
    PARTITION p202605 VALUES LESS THAN (TO_DAYS('2026-06-01')),
    PARTITION p202606 VALUES LESS THAN (TO_DAYS('2026-07-01')),
    PARTITION p202607 VALUES LESS THAN (TO_DAYS('2026-08-01')),
    PARTITION p202608 VALUES LESS THAN (TO_DAYS('2026-09-01')),
    PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
    PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
    PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);
```

- [ ] **Step 2: SQL sanity check**

```bash
ls app/src/main/resources/db/migration/V10__create_user_activity_log.sql && \
grep -E 'CREATE TABLE|PARTITION BY|p_future' app/src/main/resources/db/migration/V10__create_user_activity_log.sql | wc -l
```

Expected: 파일 존재, 출력 `3` (각 키워드 1번씩).

⚠️ **Skip commit** — G1 묶음.

### Task 2: `UserActivityEventType` enum (Administration domain, 10종 전체)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/enums/UserActivityEventType.java`

- [ ] **Step 1: enum 작성**

```java
package com.pfplaybackend.api.administration.domain.enums;

/**
 * user_activity_log.event_type 컬럼(V10) 매핑.
 *
 * 10종 catalog 전체 미리 정의 — PR 12b가 wiring만 추가하면 됨, enum 재배포 회피.
 *
 * Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.7.2
 *
 * PR 12a 시점 listener 핸들러 7종:
 *   SIGNED_UP, SIGNED_IN, PROFILE_UPDATED, PARTYROOM_CREATED,
 *   PARTYROOM_ENTERED, PARTYROOM_EXITED, PENALIZED_IN_PARTYROOM
 *
 * PR 12b 시점 listener 핸들러 3종 (현 시점 미사용 enum 값):
 *   WITHDREW, TIER_CHANGED, ADMIN_ACTED_ON
 */
public enum UserActivityEventType {
    SIGNED_UP, SIGNED_IN, WITHDREW, PROFILE_UPDATED, TIER_CHANGED,
    PARTYROOM_CREATED, PARTYROOM_ENTERED, PARTYROOM_EXITED,
    PENALIZED_IN_PARTYROOM, ADMIN_ACTED_ON
}
```

⚠️ **Skip commit** — G1 묶음.

### Task 3: `UserActivityLogId` composite PK class (Administration domain)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogId.java`

- [ ] **Step 1: 단위 테스트 먼저 작성 — `equals/hashCode` 동작 검증**

`app/src/test/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogIdTest.java`:

```java
package com.pfplaybackend.api.administration.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class UserActivityLogIdTest {

    @Test
    @DisplayName("같은 logId + occurredAt이면 equals true + hashCode 일치")
    void equalsAndHashCode_match_when_same_components() {
        LocalDateTime ts = LocalDateTime.of(2026, 4, 28, 12, 0);
        UserActivityLogId a = new UserActivityLogId(1L, ts);
        UserActivityLogId b = new UserActivityLogId(1L, ts);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("다른 logId면 equals false")
    void notEqual_when_different_logId() {
        LocalDateTime ts = LocalDateTime.of(2026, 4, 28, 12, 0);
        assertThat(new UserActivityLogId(1L, ts)).isNotEqualTo(new UserActivityLogId(2L, ts));
    }

    @Test
    @DisplayName("noargs constructor + getter 동작 (JPA 요구)")
    void noargs_constructor_and_getters() {
        UserActivityLogId id = new UserActivityLogId();
        assertThat(id.getLogId()).isNull();
        assertThat(id.getOccurredAt()).isNull();
    }
}
```

- [ ] **Step 2: 테스트 컴파일 실패 확인 (`UserActivityLogId` 미존재)**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 에러 (`UserActivityLogId` symbol not found).

- [ ] **Step 3: composite PK class 작성**

```java
package com.pfplaybackend.api.administration.domain.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * user_activity_log composite PK — (log_id, occurred_at).
 * MySQL partitioned table은 partition key를 PK에 포함해야 함 → occurred_at 필수.
 *
 * JPA 요구: implements Serializable, no-args constructor, equals/hashCode.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UserActivityLogId implements Serializable {
    private Long logId;
    private LocalDateTime occurredAt;
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogIdTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G1 묶음.

### Task 4: `UserActivityLogData` JPA entity (Administration domain)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogData.java`

- [ ] **Step 1: 단위 테스트 먼저 작성 — `of(...)` 정적 팩토리 검증**

`app/src/test/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogDataTest.java`:

```java
package com.pfplaybackend.api.administration.domain.entity;

import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserActivityLogDataTest {

    @Test
    @DisplayName("of() 정적 팩토리: 모든 필드 매핑 + eventType.name() 직렬화")
    void of_factory_maps_all_fields() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 28, 12, 0);
        JsonMetadata meta = JsonMetadata.of(Map.of("provider", "GOOGLE"));

        UserActivityLogData d = UserActivityLogData.of(
                100L, UserActivityEventType.SIGNED_IN, 5L, meta, now);

        assertThat(d.getUserAccountId()).isEqualTo(100L);
        assertThat(d.getEventType()).isEqualTo("SIGNED_IN");      // String 직렬화
        assertThat(d.getPartyroomId()).isEqualTo(5L);
        assertThat(d.getMetadata()).isEqualTo(meta);
        assertThat(d.getOccurredAt()).isEqualTo(now);
        assertThat(d.getLogId()).isNull();                        // IDENTITY 미발급
    }

    @Test
    @DisplayName("of() 정적 팩토리: partyroomId/metadata null 허용")
    void of_factory_allows_null_partyroomId_and_metadata() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 28, 12, 0);

        UserActivityLogData d = UserActivityLogData.of(
                100L, UserActivityEventType.SIGNED_UP, null, null, now);

        assertThat(d.getPartyroomId()).isNull();
        assertThat(d.getMetadata()).isNull();
    }
}
```

- [ ] **Step 2: 테스트 컴파일 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 에러 (`UserActivityLogData` 미존재).

- [ ] **Step 3: JPA entity 작성**

```java
package com.pfplaybackend.api.administration.domain.entity;

import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.administration.domain.value.JsonMetadataConverter;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * user_activity_log row mapping (V10).
 *
 * Append-only audit timeline. listener는 `UserActivityLogListener`(Administration BC).
 *
 * Composite PK = (log_id, occurred_at) — partitioned table 요구.
 * eventType은 VARCHAR(64) 컬럼이라 enum.name() 직렬화로 저장 (도메인측 enum은 listener에서 변환).
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §4.1
 */
@Entity
@Table(name = "user_activity_log")
@IdClass(UserActivityLogId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserActivityLogData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Id
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "user_account_id", nullable = false)
    private Long userAccountId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "partyroom_id")
    private Long partyroomId;

    @Convert(converter = JsonMetadataConverter.class)
    @Column(name = "metadata", columnDefinition = "json")
    private JsonMetadata metadata;

    public static UserActivityLogData of(Long userAccountId,
                                         UserActivityEventType eventType,
                                         Long partyroomId,
                                         JsonMetadata metadata,
                                         LocalDateTime occurredAt) {
        UserActivityLogData d = new UserActivityLogData();
        d.userAccountId = userAccountId;
        d.eventType = eventType.name();
        d.partyroomId = partyroomId;
        d.metadata = metadata;
        d.occurredAt = occurredAt;
        return d;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogDataTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G1 묶음.

### Task 5: `UserActivityLogRepository` (Administration adapter)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/UserActivityLogRepository.java`

- [ ] **Step 1: repository interface 작성**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * user_activity_log JPA repository.
 *
 * PR 12a는 save만으로 충분.
 * PR 12b A-2 `recentActivityLog` projection 메서드는 PR 12b에서 추가.
 */
public interface UserActivityLogRepository extends JpaRepository<UserActivityLogData, UserActivityLogId> {
}
```

- [ ] **Step 2: 빌드 + V10 마이그레이션 + entity 매핑 IT**

`app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/out/persistence/UserActivityLogRepositoryIT.java`:

```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class UserActivityLogRepositoryIT {

    @Autowired UserActivityLogRepository repository;

    @Test
    @DisplayName("V10 마이그레이션 후 row 저장 + 읽기 round-trip")
    void save_and_findAll_roundtrip() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 28, 12, 0);
        UserActivityLogData saved = repository.save(UserActivityLogData.of(
                100L, UserActivityEventType.SIGNED_IN, null,
                JsonMetadata.of(Map.of("provider", "GOOGLE", "actor_type", "USER")),
                now));

        assertThat(saved.getLogId()).isNotNull();
        assertThat(saved.getOccurredAt()).isEqualTo(now);

        List<UserActivityLogData> all = repository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getEventType()).isEqualTo("SIGNED_IN");
        assertThat(all.get(0).getMetadata().data())
                .containsEntry("provider", "GOOGLE")
                .containsEntry("actor_type", "USER");
    }

    @Test
    @DisplayName("partyroom_id nullable 허용")
    void save_allows_null_partyroomId() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 28, 12, 0);
        repository.save(UserActivityLogData.of(
                100L, UserActivityEventType.SIGNED_UP, null,
                JsonMetadata.of(Map.of("provider", "LOCAL")), now));

        assertThat(repository.findAll().get(0).getPartyroomId()).isNull();
    }
}
```

> Note: `JsonMetadata.data()`가 정확한 API (verified, Hard precondition Step 6 ground truth). `asMap()`이 아님.

- [ ] **Step 3: PR 8 컨벤션과 일치 확인 (sanity)**

```bash
grep -rn "getMetadata().data()\|getMetadata().asMap()" app/src/test app/src/integration-test --include="*.java" | head -5
```

Expected: `getMetadata().data()` 호출만 매칭, `asMap()` 0건. 본 IT의 단언과 일치.

- [ ] **Step 4: V10 + entity + repo 통합 IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*UserActivityLogRepositoryIT*"
```

Expected: PASS. V10 partition 9개 + p_future 적용 시 `findAll`에서 해당 partition row 읽기 정상.

- [ ] **Step 5: G1 단일 commit**

```bash
git add app/src/main/resources/db/migration/V10__create_user_activity_log.sql \
    app/src/main/java/com/pfplaybackend/api/administration/domain/enums/UserActivityEventType.java \
    app/src/main/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogId.java \
    app/src/main/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogData.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/UserActivityLogRepository.java \
    app/src/test/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogIdTest.java \
    app/src/test/java/com/pfplaybackend/api/administration/domain/entity/UserActivityLogDataTest.java \
    app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/out/persistence/UserActivityLogRepositoryIT.java
```

```bash
git commit -m "$(cat <<'EOF'
feat(administration): V10 user_activity_log + entity + enum + repository (PR 12a G1)

- V10 마이그레이션 (월별 RANGE partition + p_future MAXVALUE).
- UserActivityLogData JPA entity (composite PK = log_id + occurred_at).
- UserActivityEventType enum 10종 (PR 12b 미사용 3종 포함, enum 재배포 회피).
- UserActivityLogRepository (PR 12a는 save만 사용).

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §3, §4.1, §4.2

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: 단일 commit `feat(administration): V10 user_activity_log + entity + enum + repository (PR 12a G1)`.

---

## Chunk 2: G2 — AsyncConfig + UserActivityLogListener skeleton + 2 evolution-free handlers

**Goal of chunk:** `@EnableAsync` 도입 + `userActivityLogExecutor` `ThreadPoolTaskExecutor` bean + listener 클래스 + evolution 불필요한 2 핸들러(`MemberRegisteredEvent` → SIGNED_UP, `UserProfileChangedEvent` → PROFILE_UPDATED). 단일 G2 commit.

**End state of chunk:** 회원가입(`MemberSignService.register*`) / 프로필 변경(`UserBioCommandService` / `UserAvatarCommandService`) 시 `user_activity_log`에 row 자동 INSERT (async). 단위 + IT 그린.

### Task 6: `AsyncConfig` (`@EnableAsync` + executor bean)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/common/config/AsyncConfig.java`

- [ ] **Step 1: 단위 테스트 먼저 작성 — bean 등록 + executor 사이징 검증**

`app/src/test/java/com/pfplaybackend/api/common/config/AsyncConfigTest.java`:

```java
package com.pfplaybackend.api.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AsyncConfig.class)
class AsyncConfigTest {

    @Autowired
    org.springframework.context.ApplicationContext ctx;

    @Test
    @DisplayName("userActivityLogExecutor bean 등록 + sizing")
    void userActivityLogExecutor_registered_with_expected_sizing() {
        ThreadPoolTaskExecutor exec = (ThreadPoolTaskExecutor) ctx.getBean("userActivityLogExecutor");

        assertThat(exec.getCorePoolSize()).isEqualTo(2);
        assertThat(exec.getMaxPoolSize()).isEqualTo(4);
        assertThat(exec.getQueueCapacity()).isEqualTo(200);
        assertThat(exec.getThreadNamePrefix()).isEqualTo("ual-");
        assertThat(exec.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }
}
```

- [ ] **Step 2: 테스트 컴파일 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 에러 (`AsyncConfig` 미존재).

- [ ] **Step 3: `AsyncConfig` 작성**

```java
package com.pfplaybackend.api.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async 인프라.
 *
 * `userActivityLogExecutor`:
 * - UserActivityLogListener (audit timeline) 전용
 * - core=2, max=4, queue=200, CallerRunsPolicy
 * - drop-가능 정책 (saturation 시 producer thread가 직접 INSERT)
 * - 그레이스풀 종료: 큐 비울 때까지 10초 대기 (잔여 task drop 허용)
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §6
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "userActivityLogExecutor")
    public ThreadPoolTaskExecutor userActivityLogExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("ual-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(10);
        exec.initialize();
        return exec;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AsyncConfigTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G2 묶음.

### Task 7: `UserActivityLogListener` skeleton + SIGNED_UP / PROFILE_UPDATED 핸들러

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java`

- [ ] **Step 1: 단위 테스트 먼저 작성 — Mockito로 핸들러 매핑 검증**

`app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java`:

```java
package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.domain.enums.ProfileChangeType;
import com.pfplaybackend.api.user.domain.event.MemberRegisteredEvent;
import com.pfplaybackend.api.user.domain.event.UserProfileChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserActivityLogListenerTest {

    @Mock UserActivityLogRepository repository;
    UserActivityLogListener listener;

    @BeforeEach
    void setUp() {
        listener = new UserActivityLogListener(repository);
    }

    @Test
    @DisplayName("MemberRegisteredEvent → SIGNED_UP row INSERT (provider metadata)")
    void on_MemberRegisteredEvent_inserts_SIGNED_UP_row() {
        UserId userId = UserId.create(100L);
        MemberRegisteredEvent event = new MemberRegisteredEvent(userId, "user@example.com", ProviderType.GOOGLE);

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository).save(cap.capture());
        UserActivityLogData saved = cap.getValue();

        assertThat(saved.getUserAccountId()).isEqualTo(100L);
        assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.SIGNED_UP.name());
        assertThat(saved.getPartyroomId()).isNull();
        assertThat(saved.getOccurredAt()).isEqualTo(event.getOccurredAt());
        assertThat(saved.getMetadata().data()).containsEntry("provider", "GOOGLE");
    }

    @Test
    @DisplayName("UserProfileChangedEvent → PROFILE_UPDATED row INSERT (change_type metadata)")
    void on_UserProfileChangedEvent_inserts_PROFILE_UPDATED_row() {
        UserId userId = UserId.create(100L);
        UserProfileChangedEvent event = new UserProfileChangedEvent(userId, ProfileChangeType.AVATAR);

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository).save(cap.capture());
        UserActivityLogData saved = cap.getValue();

        assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.PROFILE_UPDATED.name());
        assertThat(saved.getMetadata().data()).containsEntry("change_type", "AVATAR");
    }

    @Test
    @DisplayName("repository.save 실패해도 throw 없이 swallow (drop-가능)")
    void on_save_failure_swallows() {
        doThrow(new RuntimeException("db down")).when(repository).save(any());

        UserId userId = UserId.create(100L);
        MemberRegisteredEvent event = new MemberRegisteredEvent(userId, "user@example.com", ProviderType.LOCAL);

        listener.on(event);   // throw 안 함

        verify(repository).save(any());
    }
}
```

- [ ] **Step 2: 테스트 컴파일 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 에러 (`UserActivityLogListener` 미존재).

- [ ] **Step 3: listener 클래스 + 2 핸들러 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.user.domain.event.MemberRegisteredEvent;
import com.pfplaybackend.api.user.domain.event.UserProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * user_activity_log audit timeline writer.
 *
 * 핵심 정책:
 * - `@TransactionalEventListener(AFTER_COMMIT)` — 비즈니스 TX commit 후 실행 (audit 실패 ≠ 비즈니스 실패).
 * - `@Async("userActivityLogExecutor")` — 핫패스 비동기화. spec §6 executor.
 * - drop-가능 — repository.save throw 시 ERROR 로그 + swallow. 비즈니스 흐름 안 막음.
 *
 * PR 8/9 PartyroomAdminActionListener의 sync atomic 패턴과 의도적 차별화.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §4.3, §7
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserActivityLogListener {

    private final UserActivityLogRepository repository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("userActivityLogExecutor")
    public void on(MemberRegisteredEvent e) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("provider", e.getProviderType().name());
        log(e.getUserId().getUid(), UserActivityEventType.SIGNED_UP, null,
            JsonMetadata.of(meta), e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("userActivityLogExecutor")
    public void on(UserProfileChangedEvent e) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("change_type", e.getChangeType().name());
        log(e.getUserId().getUid(), UserActivityEventType.PROFILE_UPDATED, null,
            JsonMetadata.of(meta), e.getOccurredAt());
    }

    /**
     * 공통 INSERT 헬퍼 — drop-가능 정책 (try/catch swallow).
     * `@Async` thread context이므로 throw해도 publisher에 전파 안 됨 — 명시적 swallow.
     */
    private void log(Long userAccountId, UserActivityEventType type,
                     Long partyroomId, JsonMetadata meta, LocalDateTime occurredAt) {
        try {
            repository.save(UserActivityLogData.of(
                    userAccountId, type, partyroomId, meta, occurredAt));
        } catch (Exception ex) {
            log.error("[UAL] failed to insert: type={}, userAccountId={}, partyroomId={}",
                    type, userAccountId, partyroomId, ex);
        }
    }
}
```

> Note (review advisory #2): 모든 핸들러가 `HashMap` 사용 — `Map.of`는 null value를 허용하지 않으므로 일관 패턴. SIGNED_UP / PROFILE_UPDATED는 현재 null 위험 없지만, 일관성을 위해 동일 패턴 적용.

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G2 묶음.

### Task 8: G2 통합 IT — 회원가입 / 프로필 변경 → user_activity_log row 자동 누적

**Files:**
- Create: `app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerSignedUpAndProfileIT.java`

- [ ] **Step 1: 통합 IT 작성 (Awaitility로 async poll)**

```java
package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.support.IntegrationTest;
import com.pfplaybackend.api.user.application.service.MemberSignService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2 end-to-end: 회원가입 / 프로필 변경 → user_activity_log row 누적 검증.
 *
 * @TransactionalEventListener(AFTER_COMMIT) + @Async ⇒ 비즈니스 TX commit 후 별 thread INSERT.
 * Awaitility로 최대 5초 poll (CI 부하 고려).
 */
@IntegrationTest
class UserActivityLogListenerSignedUpAndProfileIT {

    @Autowired MemberSignService memberSignService;
    @Autowired UserActivityLogRepository repository;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("회원가입 시 SIGNED_UP row 1건 INSERT (async, ≤5s)")
    void registerMember_inserts_SIGNED_UP_row() {
        memberSignService.getMemberOrCreate("ual-test@example.com", ProviderType.GOOGLE);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<UserActivityLogData> rows = repository.findAll();
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).getEventType())
                            .isEqualTo(UserActivityEventType.SIGNED_UP.name());
                    assertThat(rows.get(0).getMetadata().data())
                            .containsEntry("provider", "GOOGLE");
                });
    }

    // PROFILE_UPDATED 검증은 UserBio/UserAvatarCommandService 호출 fixture가 기존 IT에 있으면 그 패턴 재사용.
    // 본 IT는 G2 minimal — SIGNED_UP만으로 listener async path 검증 완료.
    // PROFILE_UPDATED end-to-end는 UserBio/UserAvatarCommandServiceIT 갱신 또는 별 IT로 추가 가능 (필요 시).
}
```

> Note: `MemberSignService.getMemberOrCreate(email, provider)`는 PR 4에서 도입된 메서드 (기존 코드). 호출 시 새 `Member` + `UserAccount` 생성 + `MemberRegisteredEvent` publish.

- [ ] **Step 2: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*UserActivityLogListenerSignedUpAndProfileIT*"
```

Expected: PASS (5초 이내).

⚠️ **Failure mode 점검**: Awaitility timeout 발생 시:
- (a) `MemberSignService.getMemberOrCreate`가 `@Transactional` 안에 있는지 확인 (commit 후 listener 호출).
- (b) `MemberRegisteredEvent` publish가 실제 호출되는지 (`MemberSignServiceTest`에서 검증된 동작).
- (c) `userActivityLogExecutor` bean이 active한지.
- (d) test profile의 Hibernate ddl 설정이 V10 적용 후 entity와 매핑 일치하는지.

- [ ] **Step 3: G2 단일 commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/common/config/AsyncConfig.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java \
    app/src/test/java/com/pfplaybackend/api/common/config/AsyncConfigTest.java \
    app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java \
    app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerSignedUpAndProfileIT.java
```

```bash
git commit -m "$(cat <<'EOF'
feat(administration): UserActivityLogListener async + SIGNED_UP/PROFILE_UPDATED handlers (PR 12a G2)

- AsyncConfig: @EnableAsync + userActivityLogExecutor (core=2, max=4, queue=200, CallerRunsPolicy).
- UserActivityLogListener: @TransactionalEventListener(AFTER_COMMIT) + @Async, drop-가능 swallow.
- 핸들러 2종 — MemberRegisteredEvent → SIGNED_UP, UserProfileChangedEvent → PROFILE_UPDATED.
- 나머지 5 핸들러는 후속 G3-G6에서 의존 이벤트 evolution과 함께 추가.

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §4.3, §6, §7

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: 단일 commit `feat(administration): UserActivityLogListener async + SIGNED_UP/PROFILE_UPDATED handlers (PR 12a G2)`.

---

## Chunk 3: G3 — AdminCrewPenalizedEvent evolution + listener handler + PR 9 publisher / IT / spec catch-up

**Goal of chunk:** PR 9 `AdminCrewPenalizedEvent`에 `punishedUserAccountId` 필드 추가 + `AdminCrewPenaltyCommandService.apply` publisher 갱신 + listener 핸들러 추가 (`PENALIZED_IN_PARTYROOM` with `by=ADMIN`) + PR 9 단위/IT 갱신 + PR 9 design spec §11에 forward-evolution 사유 backfill. 단일 G3 commit.

**End state of chunk:** 어드민이 PERMANENT_EXPULSION을 부과하면 `partyroom_admin_action`(PR 8/9) + `user_activity_log`(PR 12a)에 동시 INSERT.

### Task 9: `AdminCrewPenalizedEvent` 필드 추가 + 생성자 갱신

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/event/AdminCrewPenalizedEvent.java`

- [ ] **Step 1: 단위 테스트 먼저 갱신 — `punishedUserAccountId` getter 검증**

`app/src/test/java/com/pfplaybackend/api/party/domain/event/AdminCrewPenalizedEventTest.java` (기존 파일이 있으면 갱신, 없으면 신규):

```java
package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCrewPenalizedEventTest {

    @Test
    @DisplayName("punishedUserAccountId 포함 — listener cross-BC lookup 회피")
    void event_carries_punishedUserAccountId() {
        AdminCrewPenalizedEvent event = new AdminCrewPenalizedEvent(
                new PartyroomId(1L), 100L, new CrewId(50L),
                999L,                                // 🆕 punishedUserAccountId
                PenaltyType.PERMANENT_EXPULSION, 200L, "abuse");

        assertThat(event.getPartyroomId().getId()).isEqualTo(1L);
        assertThat(event.getAdministratorId()).isEqualTo(100L);
        assertThat(event.getPunishedCrewId().getId()).isEqualTo(50L);
        assertThat(event.getPunishedUserAccountId()).isEqualTo(999L);
        assertThat(event.getPenaltyType()).isEqualTo(PenaltyType.PERMANENT_EXPULSION);
        assertThat(event.getCrewPenaltyHistoryId()).isEqualTo(200L);
        assertThat(event.getReason()).isEqualTo("abuse");
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getAggregateId()).isEqualTo("1");
    }
}
```

- [ ] **Step 2: 테스트 컴파일 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 에러 (생성자 시그니처 불일치 + `getPunishedUserAccountId` 없음).

- [ ] **Step 3: 이벤트 클래스 evolution**

기존 파일 전체 교체:

```java
package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 어드민이 부과한 크루 페널티 이벤트.
 * - PartyroomAdminActionListener listen → admin_action PENALIZE_CREW (PR 9)
 * - UserActivityLogListener listen → user_activity_log PENALIZED_IN_PARTYROOM (PR 12a)
 *
 * `punishedUserAccountId`는 PR 12a에서 추가 — administration BC가 user_account_id 기준 audit row를
 * 만들 때 cross-BC lookup race를 회피하기 위해 이벤트가 self-contain.
 *
 * occurredAt은 DomainEvent 기반 클래스가 LocalDateTime.now()로 자동 설정.
 */
@Getter
public class AdminCrewPenalizedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;
    private final CrewId punishedCrewId;
    private final Long punishedUserAccountId;        // 🆕 PR 12a 추가
    private final PenaltyType penaltyType;
    private final Long crewPenaltyHistoryId;
    private final String reason;

    public AdminCrewPenalizedEvent(PartyroomId partyroomId, Long administratorId,
                                   CrewId punishedCrewId, Long punishedUserAccountId,
                                   PenaltyType penaltyType, Long crewPenaltyHistoryId, String reason) {
        super();
        this.partyroomId = partyroomId;
        this.administratorId = administratorId;
        this.punishedCrewId = punishedCrewId;
        this.punishedUserAccountId = punishedUserAccountId;
        this.penaltyType = penaltyType;
        this.crewPenaltyHistoryId = crewPenaltyHistoryId;
        this.reason = reason;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
```

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminCrewPenalizedEventTest*"
```

Expected: PASS.

⚠️ **컴파일 에러 in PR 9 코드** — 다음 task에서 수정.

⚠️ **Skip commit** — G3 묶음.

### Task 10: PR 9 `AdminCrewPenaltyCommandService.apply` publisher 갱신

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandService.java`

- [ ] **Step 1: publisher 호출 인자 갱신**

기존 `apply` 메서드의 `eventPublisher.publishEvent(new AdminCrewPenalizedEvent(...))` 라인 갱신:

```java
// PR 9 원래:
// eventPublisher.publishEvent(new AdminCrewPenalizedEvent(
//         pid, administratorId, new CrewId(crew.getId()),
//         partyEnum, historyId, cmd.reason()));

// PR 12a evolution:
eventPublisher.publishEvent(new AdminCrewPenalizedEvent(
        pid, administratorId, new CrewId(crew.getId()),
        crew.getUserId().getUid(),                        // 🆕 punishedUserAccountId
        partyEnum, historyId, cmd.reason()));
```

- [ ] **Step 2: 컴파일 + 기존 PR 9 단위 테스트 영향 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminCrewPenaltyCommandServiceTest*"
```

Expected: 일부 FAIL — `verify(eventPublisher).publishEvent(any(AdminCrewPenalizedEvent.class))` 단언이 captor로 페이로드 검증하는 case에 영향. captor expected 값 갱신 필요.

- [ ] **Step 3: 영향 받는 단위 테스트 갱신 (PR 9 service test)**

`AdminCrewPenaltyCommandServiceTest`의 publish ArgumentCaptor 검증 부분:

```java
// 추가 단언:
ArgumentCaptor<AdminCrewPenalizedEvent> cap = ArgumentCaptor.forClass(AdminCrewPenalizedEvent.class);
verify(eventPublisher).publishEvent(cap.capture());
assertThat(cap.getValue().getPunishedUserAccountId()).isEqualTo(crew.getUserId().getUid());
```

(정확한 fixture 값은 기존 stub mock의 `crew.getUserId()` return에 맞춤.)

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminCrewPenaltyCommandServiceTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G3 묶음.

### Task 11: `UserActivityLogListener.on(AdminCrewPenalizedEvent)` 핸들러 추가

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java`

- [ ] **Step 1: 단위 테스트 추가 (TDD red)**

`UserActivityLogListenerTest`에 추가:

```java
@Test
@DisplayName("AdminCrewPenalizedEvent → PENALIZED_IN_PARTYROOM row INSERT (by=ADMIN)")
void on_AdminCrewPenalizedEvent_inserts_PENALIZED_IN_PARTYROOM_row() {
    AdminCrewPenalizedEvent event = new AdminCrewPenalizedEvent(
            new PartyroomId(1L), 100L, new CrewId(50L),
            999L, PenaltyType.PERMANENT_EXPULSION, 200L, "abuse");

    listener.on(event);

    ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
    verify(repository).save(cap.capture());
    UserActivityLogData saved = cap.getValue();

    assertThat(saved.getUserAccountId()).isEqualTo(999L);
    assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.PENALIZED_IN_PARTYROOM.name());
    assertThat(saved.getPartyroomId()).isEqualTo(1L);
    assertThat(saved.getMetadata().data())
            .containsEntry("penalty_type", "PERMANENT_EXPULSION")
            .containsEntry("by", "ADMIN")
            .containsEntry("by_administrator_id", 100L);
}
```

import 추가: `com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent`, `com.pfplaybackend.api.party.domain.enums.PenaltyType`, `com.pfplaybackend.api.party.domain.value.{CrewId, PartyroomId}`.

- [ ] **Step 2: 테스트 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerTest*"
```

Expected: 1 FAIL (`on(AdminCrewPenalizedEvent)` 메서드 미존재 또는 호출 미발생).

- [ ] **Step 3: listener에 핸들러 추가**

`UserActivityLogListener`에 메서드 추가 (Task 7 클래스에 append):

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async("userActivityLogExecutor")
public void on(AdminCrewPenalizedEvent e) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("penalty_type", e.getPenaltyType().name());
    meta.put("by", "ADMIN");
    meta.put("by_administrator_id", e.getAdministratorId());
    log(e.getPunishedUserAccountId(), UserActivityEventType.PENALIZED_IN_PARTYROOM,
            e.getPartyroomId().getId(), JsonMetadata.of(meta), e.getOccurredAt());
}
```

import 추가: `com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent`.

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G3 묶음.

### Task 12: PR 9 IT 갱신 + PR 9 spec catch-up + G3 commit

**Files:**
- Modify: `app/src/integration-test/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandServiceIT.java`
- Modify: `app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/PartyroomAdminActionListenerIT.java`
- Create: `app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerAdminPenaltyIT.java`
- Modify: `docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md`

- [ ] **Step 1: PR 9 IT 갱신 — `punishedUserAccountId` payload 검증 추가**

`AdminCrewPenaltyCommandServiceIT.apply_persists_*` 테스트의 `eventPublisher` 검증에 `getPunishedUserAccountId()` 단언 추가. fixture에서 `crew.userId`로 SEED된 `userAccountId`와 일치 검증.

`PartyroomAdminActionListenerIT`의 admin penalty event fixture 시그니처 갱신 (`AdminCrewPenalizedEvent` 생성 시 `punishedUserAccountId` 인자 추가).

- [ ] **Step 2: 신규 IT — admin penalty → user_activity_log row 누적 검증**

**SEED 패턴**: PR 9 `AdminCrewPenaltyCommandServiceIT`의 fixture 패턴 그대로 — 즉 (1) `UserAccountData` SEED with known `userAccountId` (e.g., 999L via `TsidGenerator`), (2) `PartyroomData.create` + 저장, (3) host `CrewData` + target `CrewData` (target의 userId = 999L) + 저장, (4) `AdminContext` mock 또는 SecurityContext setup. 본 IT 작성 전 `Read` `app/src/integration-test/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandServiceIT.java`로 정확한 helper 메서드명/SEED 상수 추출 후 재사용.

```java
package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.application.service.AdminCrewPenaltyCommandService;
import com.pfplaybackend.api.administration.application.dto.command.AdminApplyPenaltyCommand;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminPenaltyType;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.support.IntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class UserActivityLogListenerAdminPenaltyIT {

    private static final Long SEED_TARGET_USER_ACCOUNT_ID = 999L;

    @Autowired AdminCrewPenaltyCommandService service;
    @Autowired UserActivityLogRepository repository;
    // SEED helper들 — PR 9 IT의 fixture 그대로 (Step 2 본문 SEED 패턴 참조).
    // - administrator (id=1, ADMIN role)
    // - partyroom (ACTIVE)
    // - target crew (userId=SEED_TARGET_USER_ACCOUNT_ID)
    // - AdminContext set (administratorId=1)

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("admin PERMANENT_EXPULSION → user_activity_log PENALIZED_IN_PARTYROOM (by=ADMIN, ≤5s)")
    void admin_permanent_expulsion_inserts_user_activity_log_row() {
        Long partyroomId = /* SEED helper로 생성된 partyroom id */;
        Long targetCrewId = /* SEED helper로 생성된 target crew id (userAccountId=SEED_TARGET_USER_ACCOUNT_ID) */;

        service.apply(partyroomId, new AdminApplyPenaltyCommand(
                targetCrewId, AdminPenaltyType.PERMANENT_EXPULSION, "abuse"));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var rows = repository.findAll().stream()
                            .filter(r -> r.getEventType().equals(UserActivityEventType.PENALIZED_IN_PARTYROOM.name()))
                            .toList();
                    assertThat(rows).hasSize(1);
                    UserActivityLogData saved = rows.get(0);
                    assertThat(saved.getUserAccountId()).isEqualTo(SEED_TARGET_USER_ACCOUNT_ID);
                    assertThat(saved.getMetadata().data())
                            .containsEntry("by", "ADMIN")
                            .containsEntry("penalty_type", "PERMANENT_EXPULSION");
                });
    }
}
```

> Note: 정확한 SEED helper 메서드 이름은 PR 9 IT 코드 read 후 그대로 재사용. 본 plan은 placeholder 두 곳(`partyroomId`, `targetCrewId`)을 명시 — 실행 시 PR 9 IT의 helper 호출로 대체.

- [ ] **Step 3: PR 9 design spec catch-up — §11에 forward-evolution backfill**

`docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md` §11 (Open Items / Implementation Reality)에 항목 추가:

```markdown
- **PR 12a forward-evolution: `punishedUserAccountId` 필드 추가** (commit `<G3 sha>`): `AdminCrewPenalizedEvent`에 `punishedUserAccountId` 필드 추가. `UserActivityLogListener`(PR 12a)가 `user_account_id` 기준으로 `user_activity_log` row를 만들 때 cross-BC `crewId → user_account_id` lookup race를 회피하기 위해 이벤트 self-contain. `AdminCrewPenaltyCommandService.apply` publisher가 `crew.getUserId().getUid()`를 채워 publish. PR 9 단위/IT 테스트 fixture는 PR 12a G3에서 동시 갱신.
```

- [ ] **Step 4: 통합 IT 실행 + 회귀**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*UserActivityLogListenerAdminPenaltyIT*" --tests "*AdminCrewPenaltyCommandServiceIT*" --tests "*PartyroomAdminActionListenerIT*"
```

Expected: PASS.

- [ ] **Step 5: G3 단일 commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/event/AdminCrewPenalizedEvent.java \
    app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandService.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java \
    app/src/test/java/com/pfplaybackend/api/party/domain/event/AdminCrewPenalizedEventTest.java \
    app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java \
    app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandServiceTest.java \
    app/src/integration-test/java/com/pfplaybackend/api/administration/application/service/AdminCrewPenaltyCommandServiceIT.java \
    app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/PartyroomAdminActionListenerIT.java \
    app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerAdminPenaltyIT.java \
    docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md
```

```bash
git commit -m "$(cat <<'EOF'
feat(party): AdminCrewPenalizedEvent + listener handler — punishedUserAccountId (PR 12a G3)

- AdminCrewPenalizedEvent에 punishedUserAccountId 필드 추가 (PR 9 forward-evolution).
- AdminCrewPenaltyCommandService.apply publisher가 crew.getUserId().getUid() 채움.
- UserActivityLogListener.on(AdminCrewPenalizedEvent) → PENALIZED_IN_PARTYROOM (by=ADMIN).
- PR 9 단위/IT 테스트 fixture punishedUserAccountId 단언 추가.
- PR 9 design spec §11에 forward-evolution backfill.

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §4.3.1, §5.3, §10 risk #11

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: 단일 commit `feat(party): AdminCrewPenalizedEvent + listener handler — punishedUserAccountId (PR 12a G3)`.

---

## Chunk 4: G6 — CrewPenalizedEvent evolution + party publisher 갱신 + listener handler

**Goal of chunk:** PR 1~2 시점부터 존재하는 `CrewPenalizedEvent`에 `punishedUserAccountId` 필드 추가 + `CrewPenaltyCommandService.addPenalty` publisher 갱신 + listener 핸들러 추가 (`PENALIZED_IN_PARTYROOM` with `by=CREW`). 단일 G6 commit.

**End state of chunk:** crew(host/moderator)가 PERMANENT_EXPULSION을 부과하면 `crew_penalty_history`(party) + `user_activity_log`(administration) 동시 INSERT. 단, audit row INSERT는 async drop-가능.

### Task 13: `CrewPenalizedEvent` 필드 추가 + party publisher 갱신

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/event/CrewPenalizedEvent.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/CrewPenaltyCommandService.java` (또는 publisher 호출 위치)

- [ ] **Step 1: CrewPenalizedEvent publisher 위치 정확히 식별**

```bash
grep -rn "new CrewPenalizedEvent\|CrewPenalizedEvent(" app/src/main/java --include="*.java"
```

Expected: 1~2개 호출 위치. CrewPenaltyCommandService가 가장 유력.

- [ ] **Step 2: 단위 테스트 갱신 — `punishedUserAccountId` getter 검증**

`app/src/test/java/com/pfplaybackend/api/party/domain/event/CrewPenalizedEventTest.java` (기존 또는 신규):

```java
@Test
@DisplayName("punishedUserAccountId 포함 — listener cross-BC lookup 회피")
void event_carries_punishedUserAccountId() {
    CrewPenalizedEvent event = new CrewPenalizedEvent(
            new PartyroomId(1L), new CrewId(10L), new CrewId(50L),
            999L,                                      // 🆕 punishedUserAccountId
            "abuse", PenaltyType.PERMANENT_EXPULSION);

    assertThat(event.getPunishedUserAccountId()).isEqualTo(999L);
}
```

- [ ] **Step 3: 이벤트 클래스 evolution**

```java
package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 크루(host/moderator)가 부과한 페널티 이벤트.
 * - UserActivityLogListener listen → user_activity_log PENALIZED_IN_PARTYROOM (PR 12a)
 *
 * `punishedUserAccountId`는 PR 12a에서 추가 (G6) — administration BC가 user_account_id 기준
 * audit row를 만들 때 cross-BC lookup race 회피.
 */
@Getter
public class CrewPenalizedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final CrewId punisherCrewId;
    private final CrewId punishedCrewId;
    private final Long punishedUserAccountId;        // 🆕 PR 12a 추가
    private final String detail;
    private final PenaltyType penaltyType;

    public CrewPenalizedEvent(PartyroomId partyroomId, CrewId punisherCrewId, CrewId punishedCrewId,
                               Long punishedUserAccountId, String detail, PenaltyType penaltyType) {
        this.partyroomId = partyroomId;
        this.punisherCrewId = punisherCrewId;
        this.punishedCrewId = punishedCrewId;
        this.punishedUserAccountId = punishedUserAccountId;
        this.detail = detail;
        this.penaltyType = penaltyType;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
```

- [ ] **Step 4: publisher 위치 갱신**

`CrewPenaltyCommandService.addPenalty` (또는 동등 위치)의 `eventPublisher.publishEvent(new CrewPenalizedEvent(...))` 호출에 `punishedUserAccountId` 인자 추가:

```java
// PR 12a evolution:
eventPublisher.publishEvent(new CrewPenalizedEvent(
        partyroomId, punisherCrew.getCrewId(), punishedCrew.getCrewId(),
        punishedCrew.getUserId().getUid(),                        // 🆕
        detail, penaltyType));
```

(정확한 변수 이름은 기존 service 코드 참고 — `punishedCrew` / `punishedCrewData` / `targetCrew` 등.)

- [ ] **Step 5: 컴파일 + 기존 단위 테스트 영향**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*CrewPenaltyCommandServiceTest*" --tests "*CrewPenalizedEventTest*"
```

Expected: 일부 FAIL — `CrewPenalizedEvent` 시그니처 변경으로 service test fixture 영향. 갱신 필요.

- [ ] **Step 6: 영향 받는 단위 테스트 갱신**

`CrewPenaltyCommandServiceTest`의 publish ArgumentCaptor 검증 부분 + fixture 갱신.

- [ ] **Step 7: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*CrewPenaltyCommandServiceTest*" --tests "*CrewPenalizedEventTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G6 묶음.

### Task 14: `UserActivityLogListener.on(CrewPenalizedEvent)` 핸들러 추가

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java`

- [ ] **Step 1: 단위 테스트 추가 (TDD red)**

```java
@Test
@DisplayName("CrewPenalizedEvent → PENALIZED_IN_PARTYROOM row INSERT (by=CREW)")
void on_CrewPenalizedEvent_inserts_PENALIZED_IN_PARTYROOM_row() {
    CrewPenalizedEvent event = new CrewPenalizedEvent(
            new PartyroomId(1L), new CrewId(10L), new CrewId(50L),
            999L, "abuse", PenaltyType.PERMANENT_EXPULSION);

    listener.on(event);

    ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
    verify(repository).save(cap.capture());
    UserActivityLogData saved = cap.getValue();

    assertThat(saved.getUserAccountId()).isEqualTo(999L);
    assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.PENALIZED_IN_PARTYROOM.name());
    assertThat(saved.getPartyroomId()).isEqualTo(1L);
    assertThat(saved.getMetadata().data())
            .containsEntry("penalty_type", "PERMANENT_EXPULSION")
            .containsEntry("by", "CREW");
    assertThat(saved.getMetadata().data()).doesNotContainKey("by_administrator_id");
}
```

import 추가: `com.pfplaybackend.api.party.domain.event.CrewPenalizedEvent`.

- [ ] **Step 2: 테스트 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerTest*"
```

Expected: 1 FAIL.

- [ ] **Step 3: listener에 핸들러 추가**

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async("userActivityLogExecutor")
public void on(CrewPenalizedEvent e) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("penalty_type", e.getPenaltyType().name());
    meta.put("by", "CREW");
    log(e.getPunishedUserAccountId(), UserActivityEventType.PENALIZED_IN_PARTYROOM,
            e.getPartyroomId().getId(), JsonMetadata.of(meta), e.getOccurredAt());
}
```

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerTest*"
```

Expected: PASS (모든 핸들러 테스트).

⚠️ **Skip commit** — G6 묶음.

### Task 15: 통합 IT — crew penalty → user_activity_log + G6 commit

**Files:**
- Create: `app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerCrewPenaltyIT.java`

- [ ] **Step 1: IT 작성 (Awaitility)**

```java
@IntegrationTest
class UserActivityLogListenerCrewPenaltyIT {

    @Autowired CrewPenaltyCommandService crewPenaltyCommandService;
    @Autowired UserActivityLogRepository repository;
    // SEED fixture: partyroom + host crew + target crew (userAccountId=999)

    @Test
    @DisplayName("crew PERMANENT_EXPULSION → user_activity_log PENALIZED_IN_PARTYROOM (by=CREW, ≤5s)")
    void crew_permanent_expulsion_inserts_user_activity_log_row() {
        // ... fixture setup with ThreadLocalContext for host crew ...
        // crewPenaltyCommandService.addPenalty(partyroomId, addPenaltyCommand);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var rows = repository.findAll();
                    assertThat(rows).hasSize(1);
                    UserActivityLogData saved = rows.get(0);
                    assertThat(saved.getEventType())
                            .isEqualTo(UserActivityEventType.PENALIZED_IN_PARTYROOM.name());
                    assertThat(saved.getMetadata().data()).containsEntry("by", "CREW");
                });
    }
}
```

- [ ] **Step 2: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*UserActivityLogListenerCrewPenaltyIT*"
```

Expected: PASS.

- [ ] **Step 3: G6 단일 commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/event/CrewPenalizedEvent.java \
    app/src/main/java/com/pfplaybackend/api/party/application/service/CrewPenaltyCommandService.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java \
    app/src/test/java/com/pfplaybackend/api/party/domain/event/CrewPenalizedEventTest.java \
    app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java \
    app/src/test/java/com/pfplaybackend/api/party/application/service/CrewPenaltyCommandServiceTest.java \
    app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerCrewPenaltyIT.java
```

```bash
git commit -m "$(cat <<'EOF'
feat(party): CrewPenalizedEvent + listener handler — punishedUserAccountId (PR 12a G6)

- CrewPenalizedEvent에 punishedUserAccountId 필드 추가 (party domain evolution).
- CrewPenaltyCommandService publisher가 punishedCrew.getUserId().getUid() 채움.
- UserActivityLogListener.on(CrewPenalizedEvent) → PENALIZED_IN_PARTYROOM (by=CREW).
- 기존 CrewPenaltyCommandServiceTest fixture punishedUserAccountId 단언 갱신.

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §5.4, §5.5

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: 단일 commit `feat(party): CrewPenalizedEvent + listener handler — punishedUserAccountId (PR 12a G6)`.

---

## Chunk 5: G4 — PartyroomCreatedEvent + PartyroomCommandService publish + listener handler

**Goal of chunk:** 신규 도메인 이벤트 `PartyroomCreatedEvent` (party BC) + `PartyroomCommandService.createPartyroom` private 메서드 끝에 직접 publish 라인 추가 + listener 핸들러 (`PARTYROOM_CREATED`). 단일 G4 commit.

**End state of chunk:** 룸 생성(MAIN/GENERAL) 시 host의 user_activity_log에 `PARTYROOM_CREATED` row 자동 INSERT. 

**Spec catch-up needed:** spec §5.2가 "service 코드 변경 0"이라 했으나 실제 inventory 결과 `PartyroomCommandService.createPartyroom`은 publish 호출 없음 — 한 줄 추가 필요. spec §12에 backfill (Chunk 7).

### Task 16: `PartyroomCreatedEvent` 신규

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomCreatedEvent.java`

- [ ] **Step 1: 단위 테스트 작성**

`app/src/test/java/com/pfplaybackend/api/party/domain/event/PartyroomCreatedEventTest.java`:

```java
package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PartyroomCreatedEventTest {

    @Test
    @DisplayName("필드 전달 + DomainEvent 자동 stamp")
    void event_carries_fields_and_auto_stamp() {
        PartyroomCreatedEvent event = new PartyroomCreatedEvent(
                new PartyroomId(1L), 100L, StageType.GENERAL);

        assertThat(event.getPartyroomId().getId()).isEqualTo(1L);
        assertThat(event.getHostUserAccountId()).isEqualTo(100L);
        assertThat(event.getStageType()).isEqualTo(StageType.GENERAL);
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getEventType()).isEqualTo("PartyroomCreatedEvent");
        assertThat(event.getAggregateId()).isEqualTo("1");
    }
}
```

- [ ] **Step 2: 테스트 컴파일 실패 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 에러.

- [ ] **Step 3: 이벤트 클래스 작성**

```java
package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 파티룸 생성 이벤트.
 * - UserActivityLogListener listen → user_activity_log PARTYROOM_CREATED (PR 12a)
 *
 * `hostUserAccountId`는 host의 user_account_id (loose ref). user_activity_log row의
 * subject가 host이므로 audit timeline에서 자연스럽게 노출됨.
 */
@Getter
public class PartyroomCreatedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long hostUserAccountId;
    private final StageType stageType;

    public PartyroomCreatedEvent(PartyroomId partyroomId, Long hostUserAccountId, StageType stageType) {
        super();
        this.partyroomId = partyroomId;
        this.hostUserAccountId = hostUserAccountId;
        this.stageType = stageType;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
```

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*PartyroomCreatedEventTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G4 묶음.

### Task 17: `PartyroomCommandService.createPartyroom` publish 라인 추가

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomCommandService.java`

- [ ] **Step 1: publish 라인 추가 (line 73 부근, `createPartyroom` private 끝)**

기존 `createPartyroom` private 메서드 (line 64-74) 끝부분 갱신:

```java
private PartyroomData createPartyroom(CreatePartyroomCommand command, StageType stageType, UserId hostId) {
    PartyroomData partyroom = PartyroomData.create(
            command.title(), command.introduction(),
            LinkDomain.of(command.linkDomain()),
            PlaybackTimeLimit.ofMinutes(command.playbackTimeLimit()),
            stageType, hostId);
    PartyroomData saved = aggregatePort.savePartyroom(partyroom);
    aggregatePort.savePlaybackState(PartyroomPlaybackData.createFor(saved.getPartyroomId()));
    aggregatePort.saveDjQueueState(DjQueueData.createFor(saved.getPartyroomId()));

    // 🆕 PR 12a — UserActivityLogListener consumes this for PARTYROOM_CREATED row.
    // spec §5.2 "service 코드 변경 0" 가정 정정 (PR 12a §12 catch-up).
    eventPublisher.publishEvent(new PartyroomCreatedEvent(
            saved.getPartyroomId(), hostId.getUid(), saved.getStageType()));

    return saved;
}
```

import 추가: `com.pfplaybackend.api.party.domain.event.PartyroomCreatedEvent`.

> Note: `saved.getPartyroomId()`가 `PartyroomId` 또는 `Long` 리턴인지 확인. `PartyroomId` 리턴이면 그대로, `Long` 이면 `new PartyroomId(saved.getPartyroomId())`.

- [ ] **Step 2: 기존 `PartyroomCommandServiceTest` 영향 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*PartyroomCommandServiceTest*"
```

Expected: 일부 FAIL — `verifyNoInteractions(eventPublisher)` 또는 `verify(eventPublisher, never())` 단언이 있으면 깨짐.

- [ ] **Step 3: 영향 받는 단위 테스트 갱신**

`PartyroomCommandServiceTest`의 `createMainStage` / `createGeneralPartyRoom` 테스트에:

```java
verify(eventPublisher).publishEvent(any(PartyroomCreatedEvent.class));
```

또는 ArgumentCaptor로 페이로드 검증.

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*PartyroomCommandServiceTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G4 묶음.

### Task 18: `UserActivityLogListener.on(PartyroomCreatedEvent)` + IT + G4 commit

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java`
- Create: `app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerPartyroomCreatedIT.java`

- [ ] **Step 1: 단위 테스트 추가 (TDD red)**

```java
@Test
@DisplayName("PartyroomCreatedEvent → PARTYROOM_CREATED row INSERT (host metadata)")
void on_PartyroomCreatedEvent_inserts_PARTYROOM_CREATED_row() {
    PartyroomCreatedEvent event = new PartyroomCreatedEvent(
            new PartyroomId(1L), 100L, StageType.GENERAL);

    listener.on(event);

    ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
    verify(repository).save(cap.capture());
    UserActivityLogData saved = cap.getValue();

    assertThat(saved.getUserAccountId()).isEqualTo(100L);    // host
    assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.PARTYROOM_CREATED.name());
    assertThat(saved.getPartyroomId()).isEqualTo(1L);
    assertThat(saved.getMetadata().data()).containsEntry("stage_type", "GENERAL");
}
```

import 추가: `com.pfplaybackend.api.party.domain.event.PartyroomCreatedEvent`, `com.pfplaybackend.api.party.domain.enums.StageType`.

- [ ] **Step 2: listener에 핸들러 추가**

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async("userActivityLogExecutor")
public void on(PartyroomCreatedEvent e) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("stage_type", e.getStageType().name());
    log(e.getHostUserAccountId(), UserActivityEventType.PARTYROOM_CREATED,
            e.getPartyroomId().getId(), JsonMetadata.of(meta), e.getOccurredAt());
}
```

- [ ] **Step 3: 통합 IT — `createGeneralPartyRoom` → user_activity_log row**

```java
@IntegrationTest
class UserActivityLogListenerPartyroomCreatedIT {

    @Autowired PartyroomCommandService service;
    @Autowired UserActivityLogRepository repository;
    // SEED fixture: ThreadLocalContext for host user (userAccountId=100, FM tier)

    @Test
    @DisplayName("createGeneralPartyRoom → user_activity_log PARTYROOM_CREATED (≤5s)")
    void create_general_partyroom_inserts_PARTYROOM_CREATED_row() {
        // ... ThreadLocalContext setup (host) ...
        // service.createGeneralPartyRoom(new CreatePartyroomCommand("title", "intro", "link", 30));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var rows = repository.findAll().stream()
                            .filter(r -> r.getEventType().equals(UserActivityEventType.PARTYROOM_CREATED.name()))
                            .toList();
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).getMetadata().data()).containsEntry("stage_type", "GENERAL");
                });
    }
}
```

- [ ] **Step 4: 단위 + IT 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerTest*" :app:integrationTest --tests "*UserActivityLogListenerPartyroomCreatedIT*"
```

Expected: PASS.

- [ ] **Step 5: G4 단일 commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomCreatedEvent.java \
    app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomCommandService.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java \
    app/src/test/java/com/pfplaybackend/api/party/domain/event/PartyroomCreatedEventTest.java \
    app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java \
    app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomCommandServiceTest.java \
    app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerPartyroomCreatedIT.java
```

```bash
git commit -m "$(cat <<'EOF'
feat(party): PartyroomCreatedEvent + listener handler — PARTYROOM_CREATED audit (PR 12a G4)

- PartyroomCreatedEvent (party domain, partyroomId/hostUserAccountId/stageType).
- PartyroomCommandService.createPartyroom 끝에 직접 publish (createMainStage/createGeneralPartyRoom 모두 cover).
- UserActivityLogListener.on(PartyroomCreatedEvent) → PARTYROOM_CREATED row.
- spec §5.2 "service 코드 변경 0" 가정은 부정확 — §12 catch-up.

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §5.2

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: 단일 commit.

---

## Chunk 6: G5 — UserAccountSignedInEvent + AdminLoginService/AuthService publish + readOnly TX 보강 + listener handler. 추가로 CrewAccessedEvent listener handler

**Goal of chunk:** 신규 이벤트 `UserAccountSignedInEvent` (auth domain, ProviderType + ActorType USER/ADMINISTRATOR) + `AdminLoginService.login` 성공 path publish + `@Transactional(readOnly = true)` 보강 + `AuthService` OAuth 성공 path publish + readOnly 보강 + listener 핸들러 (`SIGNED_IN`). 추가로 같은 G5 묶음에 `CrewAccessedEvent` listener 핸들러 (`PARTYROOM_ENTERED` / `_EXITED`) — 이벤트 evolution 불필요(현재 시그니처 충분), metadata 단순화 (stage_type/duration_sec 누락 OK, listener는 partyroom_id만 기록).

**End state of chunk:** 모든 7 event_type wiring 완료. 회원가입/로그인/프로필/룸 생성/입장/퇴장/페널티 시 user_activity_log row 자동 누적.

### Task 19: `UserAccountSignedInEvent` (auth domain)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/auth/domain/event/UserAccountSignedInEvent.java`

- [ ] **Step 1: 단위 테스트 작성**

```java
package com.pfplaybackend.api.auth.domain.event;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserAccountSignedInEventTest {

    @Test
    @DisplayName("USER actor type")
    void event_user_actor() {
        UserAccountSignedInEvent event = new UserAccountSignedInEvent(
                100L, ProviderType.GOOGLE, UserAccountSignedInEvent.ActorType.USER);

        assertThat(event.getUserAccountId()).isEqualTo(100L);
        assertThat(event.getProvider()).isEqualTo(ProviderType.GOOGLE);
        assertThat(event.getActorType()).isEqualTo(UserAccountSignedInEvent.ActorType.USER);
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getAggregateId()).isEqualTo("100");
    }

    @Test
    @DisplayName("ADMINISTRATOR actor type — 어드민 로그인도 같은 이벤트 사용")
    void event_admin_actor() {
        UserAccountSignedInEvent event = new UserAccountSignedInEvent(
                100L, ProviderType.LOCAL, UserAccountSignedInEvent.ActorType.ADMINISTRATOR);

        assertThat(event.getActorType()).isEqualTo(UserAccountSignedInEvent.ActorType.ADMINISTRATOR);
    }
}
```

- [ ] **Step 2: 테스트 컴파일 실패 확인 (TDD red)**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava 2>&1 | tail -10
```

Expected: 컴파일 에러 (`UserAccountSignedInEvent`, `ActorType` symbol not found).

- [ ] **Step 3: 이벤트 클래스 작성**

```java
package com.pfplaybackend.api.auth.domain.event;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.event.DomainEvent;
import lombok.Getter;

/**
 * user_account 로그인 성공 이벤트.
 * - UserActivityLogListener listen → user_activity_log SIGNED_IN (PR 12a)
 *
 * actorType:
 *  - USER: 일반 사용자 OAuth 로그인 (AuthService publish)
 *  - ADMINISTRATOR: 어드민 LOCAL 로그인 (AdminLoginService publish)
 *
 * 어드민/유저 timeline을 같은 테이블에 통합. metadata.actor_type으로 구분.
 */
@Getter
public class UserAccountSignedInEvent extends DomainEvent {
    private final Long userAccountId;
    private final ProviderType provider;
    private final ActorType actorType;

    public UserAccountSignedInEvent(Long userAccountId, ProviderType provider, ActorType actorType) {
        super();
        this.userAccountId = userAccountId;
        this.provider = provider;
        this.actorType = actorType;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(userAccountId);
    }

    public enum ActorType { USER, ADMINISTRATOR }
}
```

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserAccountSignedInEventTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G5 묶음.

### Task 20: `AdminLoginService.login` + `AuthService` publish + `@Transactional(readOnly=true)` 보강

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/auth/application/service/AdminLoginService.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/auth/application/service/AuthService.java`

- [ ] **Step 1: `AdminLoginService` — `ApplicationEventPublisher` 주입 + publish + `@Transactional(readOnly = true)`**

```java
// 의존성 추가
private final ApplicationEventPublisher eventPublisher;

@Transactional(readOnly = true)        // 🆕 PR 12a — @TransactionalEventListener(AFTER_COMMIT) 동작 보장
public AdminAuthResult login(AdminLoginCommand cmd) {
    try {
        rateLimiter.checkOrThrow(cmd.clientIp(), cmd.email());
    } catch (...) { ... }

    // ... UA / admin / role 검증 (기존 코드) ...

    // 🆕 PR 12a — JWT mint 직전, 모든 검증 통과 후 publish
    eventPublisher.publishEvent(new UserAccountSignedInEvent(
            ua.getUserId().getUid(), ProviderType.LOCAL,
            UserAccountSignedInEvent.ActorType.ADMINISTRATOR));

    String adminToken = jwtService.mintAdminAccessToken(...);
    // ... 나머지 (기존 코드) ...
}
```

- [ ] **Step 2: `AuthService` OAuth 성공 path 식별 후 publish + readOnly 보강**

Hard precondition Step 7에서 식별한 OAuth 성공 메서드에:

```java
// (메서드에 @Transactional 부재 시 추가)
@Transactional(readOnly = true)

// (성공 path 끝, JWT 발급 직전 또는 이후)
eventPublisher.publishEvent(new UserAccountSignedInEvent(
        userAccount.getUserId().getUid(),
        userAccount.getProviderType(),
        UserAccountSignedInEvent.ActorType.USER));
```

> Note: AuthService 코드 패턴에 따라 publish 위치 + readOnly TX 추가 정확 위치 결정 필요. plan 실행 시점에 다시 확인.

- [ ] **Step 3: `AdminLoginService` 단위 테스트 갱신**

`AdminLoginServiceTest`에 publishEvent 호출 검증 추가:

```java
@Test
@DisplayName("login 성공 시 UserAccountSignedInEvent publish (ADMINISTRATOR)")
void login_publishes_UserAccountSignedInEvent() {
    // ... 기존 fixture ...
    AdminAuthResult result = service.login(cmd);

    ArgumentCaptor<UserAccountSignedInEvent> cap =
            ArgumentCaptor.forClass(UserAccountSignedInEvent.class);
    verify(eventPublisher).publishEvent(cap.capture());
    assertThat(cap.getValue().getActorType()).isEqualTo(UserAccountSignedInEvent.ActorType.ADMINISTRATOR);
    assertThat(cap.getValue().getProvider()).isEqualTo(ProviderType.LOCAL);
}

@Test
@DisplayName("login 실패 (rate limit / 자격 / revoked) 시 publish 호출 없음")
void login_failure_does_not_publish() {
    // 각 실패 시나리오마다
    verifyNoInteractions(eventPublisher);   // 또는 verify(...).publishEvent(any()) never
}
```

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminLoginServiceTest*" --tests "*AuthServiceTest*"
```

Expected: PASS.

⚠️ **Skip commit** — G5 묶음.

### Task 21: `UserActivityLogListener.on(UserAccountSignedInEvent)` + `on(CrewAccessedEvent)` 핸들러

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java`

- [ ] **Step 1: SIGNED_IN 단위 테스트 추가**

```java
@Test
@DisplayName("UserAccountSignedInEvent (USER) → SIGNED_IN row INSERT")
void on_UserAccountSignedInEvent_user_inserts_SIGNED_IN_row() {
    UserAccountSignedInEvent event = new UserAccountSignedInEvent(
            100L, ProviderType.GOOGLE, UserAccountSignedInEvent.ActorType.USER);

    listener.on(event);

    ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
    verify(repository).save(cap.capture());
    UserActivityLogData saved = cap.getValue();

    assertThat(saved.getUserAccountId()).isEqualTo(100L);
    assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.SIGNED_IN.name());
    assertThat(saved.getPartyroomId()).isNull();
    assertThat(saved.getMetadata().data())
            .containsEntry("provider", "GOOGLE")
            .containsEntry("actor_type", "USER");
}

@Test
@DisplayName("UserAccountSignedInEvent (ADMINISTRATOR) → SIGNED_IN row INSERT")
void on_UserAccountSignedInEvent_admin_inserts_SIGNED_IN_row() {
    UserAccountSignedInEvent event = new UserAccountSignedInEvent(
            100L, ProviderType.LOCAL, UserAccountSignedInEvent.ActorType.ADMINISTRATOR);

    listener.on(event);

    ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
    verify(repository).save(cap.capture());
    UserActivityLogData saved = cap.getValue();

    assertThat(saved.getMetadata().data()).containsEntry("actor_type", "ADMINISTRATOR");
}
```

- [ ] **Step 2: CrewAccessedEvent ENTER/EXIT 단위 테스트 추가**

```java
@Test
@DisplayName("CrewAccessedEvent ENTER → PARTYROOM_ENTERED row INSERT")
void on_CrewAccessedEvent_enter_inserts_PARTYROOM_ENTERED_row() {
    UserId userId = UserId.create(100L);
    CrewAccessedEvent event = new CrewAccessedEvent(
            new PartyroomId(1L), new CrewId(50L), userId, AccessType.ENTER);

    listener.on(event);

    ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
    verify(repository).save(cap.capture());
    UserActivityLogData saved = cap.getValue();

    assertThat(saved.getUserAccountId()).isEqualTo(100L);
    assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.PARTYROOM_ENTERED.name());
    assertThat(saved.getPartyroomId()).isEqualTo(1L);
    // metadata 단순화 — CrewAccessedEvent에 stage_type/duration_sec 부재.
    // listener는 JsonMetadata.empty() 사용 (converter가 빈 map → SQL NULL 직렬화).
    assertThat(saved.getMetadata().isEmpty()).isTrue();
}

@Test
@DisplayName("CrewAccessedEvent EXIT → PARTYROOM_EXITED row INSERT")
void on_CrewAccessedEvent_exit_inserts_PARTYROOM_EXITED_row() {
    UserId userId = UserId.create(100L);
    CrewAccessedEvent event = new CrewAccessedEvent(
            new PartyroomId(1L), new CrewId(50L), userId, AccessType.EXIT);

    listener.on(event);

    ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
    verify(repository).save(cap.capture());
    assertThat(cap.getValue().getEventType()).isEqualTo(UserActivityEventType.PARTYROOM_EXITED.name());
}
```

- [ ] **Step 3: listener에 두 핸들러 추가**

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async("userActivityLogExecutor")
public void on(UserAccountSignedInEvent e) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("provider", e.getProvider().name());
    meta.put("actor_type", e.getActorType().name());
    log(e.getUserAccountId(), UserActivityEventType.SIGNED_IN, null,
            JsonMetadata.of(meta), e.getOccurredAt());
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async("userActivityLogExecutor")
public void on(CrewAccessedEvent e) {
    UserActivityEventType type = (e.getAccessType() == AccessType.ENTER)
            ? UserActivityEventType.PARTYROOM_ENTERED
            : UserActivityEventType.PARTYROOM_EXITED;
    // metadata 단순화 — CrewAccessedEvent에 stage_type/duration_sec 부재
    // (spec §4.7.2의 metadata 키는 예시; future evolution으로 보강 가능).
    // JsonMetadata.empty() — converter가 빈 map을 SQL NULL로 직렬화.
    log(e.getUserId().getUid(), type, e.getPartyroomId().getId(),
            JsonMetadata.empty(), e.getOccurredAt());
}
```

import 추가: `com.pfplaybackend.api.auth.domain.event.UserAccountSignedInEvent`, `com.pfplaybackend.api.party.domain.event.CrewAccessedEvent`, `com.pfplaybackend.api.party.domain.enums.AccessType`.

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerTest*"
```

Expected: PASS — 7 핸들러 모두 그린.

⚠️ **Skip commit** — G5 묶음.

### Task 22: 통합 IT (admin login → SIGNED_IN, crew enter → PARTYROOM_ENTERED) + G5 commit

**Files:**
- Create: `app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerSignedInIT.java`
- Create: `app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerCrewAccessIT.java`

- [ ] **Step 1: SIGNED_IN IT (admin login)**

```java
@IntegrationTest
class UserActivityLogListenerSignedInIT {

    @Autowired AdminLoginService adminLoginService;
    @Autowired UserActivityLogRepository repository;
    // SEED: AdministratorData(active) + linked UserAccountData(LOCAL, hashed pwd)

    @Test
    @DisplayName("admin login 성공 → SIGNED_IN (actor_type=ADMINISTRATOR, ≤5s)")
    void admin_login_inserts_SIGNED_IN_row() {
        // adminLoginService.login(new AdminLoginCommand(email, password, ip));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var rows = repository.findAll();
                    assertThat(rows).hasSize(1);
                    UserActivityLogData saved = rows.get(0);
                    assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.SIGNED_IN.name());
                    assertThat(saved.getMetadata().data())
                            .containsEntry("actor_type", "ADMINISTRATOR")
                            .containsEntry("provider", "LOCAL");
                });
    }
}
```

- [ ] **Step 2: PARTYROOM_ENTERED IT (crew enter)**

```java
@IntegrationTest
class UserActivityLogListenerCrewAccessIT {

    @Autowired PartyroomAccessCommandService partyroomAccessCommandService;
    @Autowired UserActivityLogRepository repository;
    // SEED: partyroom + user

    @Test
    @DisplayName("crew enter → PARTYROOM_ENTERED row (≤5s)")
    void crew_enter_inserts_PARTYROOM_ENTERED_row() {
        // partyroomAccessCommandService.enter(...);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var rows = repository.findAll().stream()
                            .filter(r -> r.getEventType().equals(UserActivityEventType.PARTYROOM_ENTERED.name()))
                            .toList();
                    assertThat(rows).hasSize(1);
                });
    }
}
```

- [ ] **Step 3: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*UserActivityLogListenerSignedInIT*" --tests "*UserActivityLogListenerCrewAccessIT*"
```

Expected: PASS.

- [ ] **Step 4: G5 단일 commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/auth/domain/event/UserAccountSignedInEvent.java \
    app/src/main/java/com/pfplaybackend/api/auth/application/service/AdminLoginService.java \
    app/src/main/java/com/pfplaybackend/api/auth/application/service/AuthService.java \
    app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java \
    app/src/test/java/com/pfplaybackend/api/auth/domain/event/UserAccountSignedInEventTest.java \
    app/src/test/java/com/pfplaybackend/api/auth/application/service/AdminLoginServiceTest.java \
    app/src/test/java/com/pfplaybackend/api/auth/application/service/AuthServiceTest.java \
    app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerTest.java \
    app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerSignedInIT.java \
    app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerCrewAccessIT.java
```

```bash
git commit -m "$(cat <<'EOF'
feat(auth+administration): SIGNED_IN + ENTER/EXIT audit (PR 12a G5)

- UserAccountSignedInEvent (auth domain, ProviderType + ActorType USER/ADMINISTRATOR).
- AdminLoginService.login: @Transactional(readOnly=true) + publish on success.
- AuthService OAuth 성공 path: @Transactional(readOnly=true) + publish.
- UserActivityLogListener: on(UserAccountSignedInEvent) → SIGNED_IN.
- UserActivityLogListener: on(CrewAccessedEvent) → PARTYROOM_ENTERED/_EXITED.
  CrewAccessedEvent는 evolution 안 함 — metadata 단순화 (stage_type/duration_sec OOS).

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §5.1, §5.1.1, §5.4

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: 단일 commit.

---

## Chunk 7: 회귀 테스트 + ArchUnit + concurrency + spec catch-up §12

**Goal of chunk:** 7 핸들러 모두 wired 후 전체 회귀 보장 + ArchUnit 가드 + concurrency 검증 + spec §12 backfill.

### Task 23: ArchUnit — listener 어노테이션 가드

**Files:**
- Modify: `app/src/test/java/com/pfplaybackend/api/architecture/CrossContextDependencyTest.java` (또는 신규 파일)

- [ ] **Step 1: ArchUnit rule 작성**

> ⚠️ 본 코드는 illustrative — 실행 시 기존 `app/src/test/java/com/pfplaybackend/api/architecture/CrossContextDependencyTest.java`의 컨벤션(import classes, JavaClasses 변수명, `@AnalyzeClasses` annotation 사용 여부)을 read하여 그 패턴 그대로 추가할 것. 아래는 의도 표현용.

```java
// 기존 CrossContextDependencyTest.java에 두 rule 추가 (또는 신규 ArchUnit 테스트 클래스)

@Test
@DisplayName("UserActivityLogListener의 모든 on(...) public 메서드는 @TransactionalEventListener + @Async 보유")
void user_activity_log_listener_methods_have_required_annotations() {
    methods()
            .that().areDeclaredInClassesThat().haveSimpleName("UserActivityLogListener")
            .and().arePublic()
            .and().haveNameStartingWith("on")
            .should().beAnnotatedWith(org.springframework.transaction.event.TransactionalEventListener.class)
            .andShould().beAnnotatedWith(org.springframework.scheduling.annotation.Async.class)
            .check(classes);   // `classes`는 기존 테스트의 JavaClasses 인스턴스명
}

@Test
@DisplayName("auth.domain.event는 administration에 의존하지 않음 (단방향)")
void auth_event_does_not_depend_on_administration() {
    noClasses()
            .that().resideInAPackage("..auth.domain.event..")
            .should().dependOnClassesThat().resideInAPackage("..administration..")
            .check(classes);
}
```

- [ ] **Step 2: ArchUnit 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*CrossContextDependencyTest*"
```

Expected: PASS.

- [ ] **Step 3: commit**

```bash
git add app/src/test/java/com/pfplaybackend/api/architecture/CrossContextDependencyTest.java
git commit -m "$(cat <<'EOF'
test(architecture): ArchUnit guard — UserActivityLogListener handler annotations + BC deps (PR 12a)

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §8.3

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 24: Concurrency IT — 동시 SIGNED_IN

**Files:**
- Create: `app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerConcurrencyIT.java`

- [ ] **Step 1: 동시 SIGNED_IN IT**

```java
@IntegrationTest
class UserActivityLogListenerConcurrencyIT {

    @Autowired AdminLoginService adminLoginService;
    @Autowired UserActivityLogRepository repository;
    // SEED: 같은 administrator 동시 2 디바이스 시나리오

    @Test
    @DisplayName("동시 admin login 2회 → user_activity_log 2 row INSERT (PK 충돌 없음, ≤5s)")
    void concurrent_admin_login_inserts_two_rows() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try { adminLoginService.login(...); }
                finally { latch.countDown(); }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(repository.findAll()).hasSize(2));
    }
}
```

- [ ] **Step 2: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*UserActivityLogListenerConcurrencyIT*"
```

Expected: PASS.

- [ ] **Step 3: commit**

```bash
git add app/src/integration-test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerConcurrencyIT.java
git commit -m "$(cat <<'EOF'
test(administration): UserActivityLogListener concurrent SIGNED_IN IT (PR 12a)

Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §7.2, §8.4

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 25: 전체 회귀 빌드 + spec §12 catch-up

**Files:**
- Modify: `docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md`

- [ ] **Step 1: 전체 회귀 빌드**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest
```

Expected: BUILD SUCCESSFUL. 회귀 0.

- [ ] **Step 2: spec §12 catch-up 작성**

design spec §12 항목 채우기:

```markdown
## 12. Open Items / Implementation Reality (post-build catch-up)

- **§5.2 정정**: `PartyroomCommandService.createPartyroom` (private, line 64-74)는 PR 12a 시점 기준으로 도메인 이벤트 publish 호출 부재였음. spec 가정 "service 코드 변경 0"는 부정확 — Chunk 5 G4에서 `eventPublisher.publishEvent(new PartyroomCreatedEvent(...))` 한 줄 추가. `PartyroomData.create`는 손대지 않음.
- **§4.3 listener 코드 정정**: `CrewAccessedEvent`가 `stage_type` / `duration_sec` 필드를 보유하지 않음 → listener handler에서 metadata 단순화. PR 12a는 `JsonMetadata.empty()` 사용 (converter가 빈 map → SQL NULL 직렬화). spec §4.7.2 metadata catalog의 해당 키들은 future evolution 대상.
- **`MemberSignService.getMemberOrCreate`** — Step 8 IT가 활용한 entry point. 호출 시 새 `Member` 생성 path에서만 `MemberRegisteredEvent` publish.
- **`JsonMetadata` API ground truth (Hard precondition Step 6 확정)** — `JsonMetadata.data()` 정확한 accessor (asMap이 아님). `JsonMetadata.empty()` 정적 팩토리. `JsonMetadata.of(null)` / `Map.of()`은 모두 `EMPTY` 싱글턴 반환. converter가 빈 map → SQL NULL.
- **CrewAccessedEvent / `CrewPenaltyCommandService` publisher 정확한 변수 이름** — Task 13/15에서 inventory 후 정확한 fixture 코드 결정. plan은 일반화 형태.
- **AuthService OAuth 성공 path** — Task 19/20에서 grep 후 정확한 메서드 + readOnly TX 추가 위치 결정. plan은 placeholder 표시.
```

- [ ] **Step 3: spec catch-up commit**

```bash
git add docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md
git commit -m "$(cat <<'EOF'
docs(spec): catch up PR 12a design to implementation reality

Spec §12 backfill — §5.2 service publish 정정, §4.3 listener metadata 단순화,
inventory 결과 반영.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: 최종 git log 확인**

```bash
git log --oneline -15
```

Expected (top down): spec catch-up, concurrency IT, ArchUnit, G5, G4, G6, G3, G2, G1, ... (PR 11 HEAD), ...

총 9 commits (G1, G2, G3, G6, G4, G5 단일 commits + ArchUnit + Concurrency IT + spec catch-up task별 3 commits).

---

**다음 단계:** plan 실행은 `superpowers:subagent-driven-development`로 진행. fresh subagent가 각 chunk별 task 실행 + two-stage review.
