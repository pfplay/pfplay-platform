# PR 7: V6 Partyroom 상태 진화 + Atomic Counter 패턴 도입 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** V6 Flyway 마이그레이션으로 `partyroom`의 상태 모델을 3-상태 ENUM으로 진화시키고 denormalized 카운터/플래그 컬럼을 도입한다. 카운터 무결성을 native atomic UPDATE로 보장하고, 동일 패턴을 `playback_aggregation`(좋아요/싫어요/그랩)에도 적용한다. `crew` 재입장 race(B-3) 및 spurious ENTER 발행 버그를 함께 차단한다.

**Architecture:** entity 필드 변경 + `@Modifying @Query` 기반 atomic UPDATE 메서드 + `@TransactionalEventListener(AFTER_COMMIT)` listener 분리. 멀티 인스턴스 환경에서도 DB row lock + intra-JVM 이벤트 모델 조합으로 분산락 없이 정합성 보장. 어드민 상태 전이 API(`suspend`/`restore`/`displayFlag` setter) 및 이벤트 publish는 PR 8로 deferral.

**Tech Stack:** Java 21, Spring Boot 3.2, Flyway 9, JPA/Hibernate, QueryDSL 5, JUnit 5, Mockito, Testcontainers (MySQL 8).

**Spec source (read once, applied throughout):**
- `docs/superpowers/specs/2026-04-27-admin-platform-pr7-design.md` — 전체 설계, 11개 결정사항, 위험 분석
- `docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md` §9.1 PR 7 row, §9.4 M3
- `docs/superpowers/specs/2026-04-19-admin-platform-schema.md` §4.3 V6 — DDL 원본 (spec doc §3에서 redundant 제거)

**Decisions taken (see spec §13 for rationale):** brevity를 위해 본 plan에는 옮기지 않음. 의문이 생기면 spec §13의 11개 결정 항목 참조.

**Branching:** Continue on `feature/admin-auth-iam-schema`. PR 6 HEAD: `5bcfc9c9`. Spec commit: `5f9ae8d3` (`docs(spec): PR 7 design — ...`). PR 7 builds on top.

**Out of scope (defer)** — spec §2.2 참조. 본 plan에서 다루지 않음:
- `displayFlag` setter / 상태 전이 호출자 (PR 8)
- `PartyroomSuspendedEvent`/`PartyroomRestoredEvent` publish (PR 8)
- ArchUnit 규칙 (PR 8)
- drift 검증 배치 (운영 trigger 시)
- 분산락 도입 (본 PR scope 아님 — spec §7.4)

---

## Atomic commit groupings

Per-task commits are the default. The following groups MUST land as a single commit so the tree stays green:

| Group | Tasks | Reason |
|---|---|---|
| **G1: V6 + enums + 엔티티** | Tasks 1 + 2 + 3 + 4 | 컬럼 ↔ enum ↔ 엔티티 필드가 boot-or-die 의존. 컬럼만 추가 시 엔티티 컴파일 깨짐, 엔티티만 변경 시 부팅 실패. 단일 commit으로 동시 land. **배포 순서도 동일 — V6 SQL 적용과 새 jar 배포는 분리 불가.** |
| **G2: Repository 시그니처 일관 변경** | Tasks 6 + 7 + 8 | `PartyroomRepository`(JPQL `findActiveHostRoom`) + `PartyroomRepositoryImpl`(QueryDSL `findAllUnusedPartyroomDataByDay` + getCrewDataByPartyroomId의 `isTerminated.eq(false)`) + atomic UPDATE 3개 — status 시맨틱 일관성 보장 |

기타 task들은 task별 독립 commit (default).

Within each group:
- Per-task step lists remain a checklist.
- **Skip the `git commit` step at the end of each task in the group.**
- Single combined commit at the end of the group's last task with the message specified there.

---

## Hard precondition (verify BEFORE Task 1)

PR 7 builds on PR 6 (HEAD `5bcfc9c9`) + spec commit (`5f9ae8d3`). Before Task 1:

- [ ] **Step 1: Confirm spec commit is on HEAD**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-platform"
git log --oneline -2
```

Expected:
```
5f9ae8d3 docs(spec): PR 7 design — V6 partyroom state evolution + atomic counter pattern
5bcfc9c9 docs+test: catch up specs to PR 6 reality + URL-gate rows for new paths (PR 6)
```

(Subsequent unrelated commits on `feature/admin-auth-iam-schema` are fine, but file paths/line numbers in this plan are anchored at the PR 7 spec commit `5f9ae8d3`.)

- [ ] **Step 2: Confirm working tree is clean**

```bash
git status -s
```

Expected: empty output. Any dirty file → STOP and ask.

- [ ] **Step 3: Confirm V6 slot is open in `db/migration/`**

```bash
ls app/src/main/resources/db/migration/ | grep -E '^V[0-9]'
```

Expected list contains: `V1__init_schema.sql`, `V2__...`, ..., `V5__create_administrator.sql`, `V9__create_system_config.sql`, `V13__add_must_change_password_to_user_account.sql`. **V6/V7/V8/V10/V11/V12 must NOT exist.**

- [ ] **Step 4: Confirm Java/Gradle environment**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew --version
```

Expected: Gradle ~8.x, JVM 21.0.x. (Per project memory `reference_pfplay_platform_jdk.md` — Gradle 호출 시 JAVA_HOME prefix 필수.)

- [ ] **Step 5: Baseline build + test pass**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test
```

Expected: BUILD SUCCESSFUL. **예상 5-15분 (cold) — Testcontainers MySQL boot 포함, Docker daemon 가동 필요.** Any failing test → STOP, fix baseline first.

---

## Chunk 1: G1 — V6 마이그레이션 + enums + PartyroomData 리팩토링

**Goal of chunk:** boot 가능한 새 상태 모델 land. 신규 enum 2개 + V6 마이그레이션 + `PartyroomData` 필드/메서드 리팩토링이 단일 commit으로 묶임. 모든 기존 호출부는 시그니처 유지된 `isTerminated()` 덕에 변경 없이 컴파일/동작 OK.

**End state of chunk:** 빈 DB clean apply 시 V6까지 마이그레이션 통과, 어플리케이션 부팅 성공, 기존 테스트 100% 그린, 신규 단위 테스트(상태 전이 매트릭스) 그린.

### Task 1: V6 Flyway 마이그레이션 SQL 작성

**Files:**
- Create: `app/src/main/resources/db/migration/V6__evolve_partyroom_state.sql`

- [ ] **Step 1: 마이그레이션 SQL 작성** — spec §3 그대로

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

- [ ] **Step 2: ENUM downgrade trap 수동 점검**

ENUM 컬럼은 MySQL에서 한 번 정의 후 값 추가 가능하지만 제거는 row 데이터에 의존. 수동 확인:
- `status` 정의: ACTIVE, SUSPENDED, TERMINATED — 향후 추가 가능, 본 PR scope 내 제거 없음
- `display_flag` 정의: NORMAL, FEATURED, HIDDEN — 동일

문제 없음. (자동화 도구는 본 프로젝트에 없음 — `fastapi:migrate-check`는 Alembic 전용. spec §3 참조.)

- [ ] **Step 3: SQL syntax 검증**

```bash
# Testcontainers는 G1 commit 후 Task 4 단위 테스트로 자동 검증.
# 여기선 syntax sanity만:
grep -E 'CREATE INDEX|ALTER TABLE|UPDATE' app/src/main/resources/db/migration/V6__evolve_partyroom_state.sql | wc -l
```

Expected: `7` (ALTER ×2 + UPDATE ×3 + CREATE INDEX ×2).

⚠️ **Skip commit** — G1 묶음. Task 4 마무리에서 단일 commit.

---

### Task 2: `PartyroomStatus` enum 신설

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/enums/PartyroomStatus.java`

- [ ] **Step 1: enum 작성**

```java
package com.pfplaybackend.api.party.domain.enums;

public enum PartyroomStatus {
    ACTIVE,
    SUSPENDED,
    TERMINATED
}
```

⚠️ **Skip commit** — G1 묶음.

---

### Task 3: `DisplayFlag` enum 신설

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/enums/DisplayFlag.java`

- [ ] **Step 1: enum 작성**

```java
package com.pfplaybackend.api.party.domain.enums;

public enum DisplayFlag {
    NORMAL,
    FEATURED,
    HIDDEN
}
```

⚠️ **Skip commit** — G1 묶음.

---

### Task 4: `PartyroomData` 엔티티 리팩토링 + 단위 테스트 + G1 commit

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/PartyroomData.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/PartyroomDataTest.java` (full replace — 기존 7개 테스트 → 신규 매트릭스 + 기존 커버리지 보존)
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/exception/PartyroomException.java` (신규 예외 코드 1개 추가)

**Note: spec 차이.** spec §4.2는 신규 예외를 `IllegalPartyroomStateException` (별도 클래스)로 명명했지만, 본 plan은 기존 `PartyroomException` enum 패턴(`DomainException` 구현)을 재사용 — 코드베이스 컨벤션 정합성 우선. 의미 동일.

**Note: 기존 테스트 7개의 커버리지를 신규 매트릭스가 흡수해야 함:**
- `createDefaultState` — 신규 매트릭스 `FactoryDefaults` 노드가 흡수
- `validateHostNotHostThrows` / `validateHostHostNoException` — 신규 `ValidateHost` 노드 추가 필요
- `validateNotTerminatedTerminatedThrows` — 신규 `ValidateNotTerminated` 노드가 흡수
- `terminate` — 신규 `Terminate.fromActive` 노드가 흡수
- `terminateRegistersPartyroomClosedEvent` — 신규 `TerminateEventRegistration` 노드 추가 필요 (이벤트 등록 회귀 위험)
- `pollDomainEventsClearsAfterPoll` — 신규 `PollDomainEvents` 노드 추가 필요

#### 4.1 단위 테스트 먼저 작성 (TDD red)

- [ ] **Step 1: 기존 테스트 파일 전체 교체** — 상태 전이 매트릭스 + isActive/isSuspended/isTerminated 매트릭스 + 기존 7개 테스트 커버리지 모두 보존

`app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/PartyroomDataTest.java`:

```java
package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.PartyroomClosedEvent;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartyroomDataTest {

    private PartyroomData newPartyroom() {
        return PartyroomData.create(
                "Test Room", "intro",
                LinkDomain.of("youtube.com"),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL,
                new UserId(1L)
        );
    }

    @Nested
    @DisplayName("팩토리 — 신규 생성 시 기본 상태")
    class FactoryDefaults {
        @Test
        @DisplayName("status=ACTIVE, displayFlag=NORMAL, crewCount=0, lastActivityAt=null")
        void defaults() {
            PartyroomData p = newPartyroom();
            assertThat(p.getStatus()).isEqualTo(PartyroomStatus.ACTIVE);
            assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.NORMAL);
            assertThat(p.getCrewCount()).isZero();
            assertThat(p.getLastActivityAt()).isNull();
            assertThat(p.isActive()).isTrue();
            assertThat(p.isSuspended()).isFalse();
            assertThat(p.isTerminated()).isFalse();
            assertThat(p.getNoticeContent()).isEmpty();
            assertThat(p.getTitle()).isEqualTo("Test Room");
            assertThat(p.getStageType()).isEqualTo(StageType.GENERAL);
        }
    }

    @Nested
    @DisplayName("suspend()")
    class Suspend {
        @Test @DisplayName("ACTIVE → SUSPENDED 성공")
        void fromActive() {
            PartyroomData p = newPartyroom();
            p.suspend();
            assertThat(p.getStatus()).isEqualTo(PartyroomStatus.SUSPENDED);
            assertThat(p.isSuspended()).isTrue();
            assertThat(p.isActive()).isFalse();
        }

        @Test @DisplayName("이미 SUSPENDED → ConflictException")
        void fromSuspended() {
            PartyroomData p = newPartyroom();
            p.suspend();
            assertThatThrownBy(p::suspend).isInstanceOf(ConflictException.class);
        }

        @Test @DisplayName("TERMINATED → ConflictException")
        void fromTerminated() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThatThrownBy(p::suspend).isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("restore()")
    class Restore {
        @Test @DisplayName("SUSPENDED → ACTIVE 성공")
        void fromSuspended() {
            PartyroomData p = newPartyroom();
            p.suspend();
            p.restore();
            assertThat(p.isActive()).isTrue();
        }

        @Test @DisplayName("ACTIVE에서 호출 → ConflictException")
        void fromActive() {
            PartyroomData p = newPartyroom();
            assertThatThrownBy(p::restore).isInstanceOf(ConflictException.class);
        }

        @Test @DisplayName("TERMINATED → ConflictException")
        void fromTerminated() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThatThrownBy(p::restore).isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("terminate()")
    class Terminate {
        @Test @DisplayName("ACTIVE → TERMINATED 성공")
        void fromActive() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThat(p.isTerminated()).isTrue();
        }

        @Test @DisplayName("SUSPENDED → TERMINATED 성공")
        void fromSuspended() {
            PartyroomData p = newPartyroom();
            p.suspend();
            p.terminate();
            assertThat(p.isTerminated()).isTrue();
        }

        @Test @DisplayName("이중 terminate → ConflictException (TERMINATED는 terminal)")
        void fromTerminated() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThatThrownBy(p::terminate).isInstanceOf(ConflictException.class);
        }

        @Test @DisplayName("종료 시 PartyroomClosedEvent가 도메인 이벤트로 등록된다")
        void registersPartyroomClosedEvent() {
            PartyroomData p = newPartyroom();
            p.terminate();
            List<DomainEvent> events = p.pollDomainEvents();
            assertThat(events).hasSize(1);
            assertThat(events.get(0)).isInstanceOf(PartyroomClosedEvent.class);
            PartyroomClosedEvent event = (PartyroomClosedEvent) events.get(0);
            assertThat(event.getHostId()).isEqualTo(new UserId(1L));
            assertThat(event.getTitle()).isEqualTo("Test Room");
        }
    }

    @Nested
    @DisplayName("pollDomainEvents()")
    class PollDomainEvents {
        @Test @DisplayName("호출 후 이벤트 목록이 비워진다")
        void clearsAfterPoll() {
            PartyroomData p = newPartyroom();
            p.terminate();
            p.pollDomainEvents();
            assertThat(p.pollDomainEvents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("validateHost()")
    class ValidateHost {
        @Test @DisplayName("호스트가 아닌 사용자 → ForbiddenException")
        void notHost() {
            PartyroomData p = newPartyroom();
            assertThatThrownBy(() -> p.validateHost(new UserId(999L)))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test @DisplayName("호스트 본인 → 예외 없음")
        void host() {
            PartyroomData p = newPartyroom();
            assertThatNoException().isThrownBy(() -> p.validateHost(p.getHostId()));
        }
    }

    @Nested
    @DisplayName("validateNotTerminated() — 기존 시맨틱 유지 (TERMINATED만 거부)")
    class ValidateNotTerminated {
        @Test @DisplayName("ACTIVE 통과")
        void active() {
            assertThatNoException().isThrownBy(() -> newPartyroom().validateNotTerminated());
        }

        @Test @DisplayName("SUSPENDED도 통과 (TERMINATED만 거부 — SUSPENDED 입장 거부는 PartyroomEntrySpecification 책임)")
        void suspended() {
            PartyroomData p = newPartyroom();
            p.suspend();
            assertThatNoException().isThrownBy(p::validateNotTerminated);
        }

        @Test @DisplayName("TERMINATED → ForbiddenException (기존 ALREADY_TERMINATED 코드)")
        void terminated() {
            PartyroomData p = newPartyroom();
            p.terminate();
            assertThatThrownBy(p::validateNotTerminated).isInstanceOf(ForbiddenException.class);
        }
    }
}
```

**Exception 매핑 노트:**
- `ILLEGAL_STATE_TRANSITION` (신규) → `ErrorType.CONFLICT` → `ConflictException`
- `ALREADY_TERMINATED` (기존) → `ErrorType.FORBIDDEN` → `ForbiddenException`
- `validateHost`의 `GRADE_INSUFFICIENT_FOR_OPERATION` → `ErrorType.FORBIDDEN` → `ForbiddenException` (기존 그대로)

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 / RED 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.entity.data.PartyroomDataTest"
```

Expected: `compileTestJava` FAILED — `PartyroomData.suspend()`, `restore()`, `getStatus()`, `getDisplayFlag()`, `getCrewCount()`, `isActive()`, `isSuspended()`, `getLastActivityAt()` 메서드/필드 미존재 + 신규 enum import 미존재. (기존 `terminate()`/`isTerminated()`/`pollDomainEvents()`/`validateHost()`/`validateNotTerminated()`는 시그니처 유지로 OK.)

#### 4.2 `PartyroomException` 신규 예외 추가

- [ ] **Step 3: 신규 예외 코드 추가** — `PTR-007`

`app/src/main/java/com/pfplaybackend/api/party/domain/exception/PartyroomException.java` 마지막 enum 값(`ALREADY_HOST("PTR-006", ...)`) 뒤에 행 추가, 세미콜론 위치 조정:

```java
@Getter
public enum PartyroomException implements DomainException {
    NOT_FOUND_ROOM("PTR-001", "파티룸을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    ALREADY_TERMINATED("PTR-002", "이미 종료된 파티룸입니다", ErrorType.FORBIDDEN),
    EXCEEDED_LIMIT("PTR-003", "입장 인원 제한을 초과했습니다", ErrorType.FORBIDDEN),
    ACTIVE_ANOTHER_ROOM("PTR-004", "이미 다른 파티룸에 입장 중입니다", ErrorType.FORBIDDEN),
    RESTRICTED_AUTHORITY("PTR-005", "권한이 부족합니다", ErrorType.FORBIDDEN),
    ALREADY_HOST("PTR-006", "이미 다른 파티룸의 호스트입니다", ErrorType.FORBIDDEN),
    ILLEGAL_STATE_TRANSITION("PTR-007", "허용되지 않은 파티룸 상태 전이입니다", ErrorType.CONFLICT);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    PartyroomException(String errorCode, String message, ErrorType errorType) {
        this.message = message;
        this.errorCode = errorCode;
        this.errorType = errorType;
    }
}
```

생성자 인자 순서: `(errorCode, message, errorType)` — 기존 컨벤션 그대로. `ErrorType.CONFLICT`는 `GlobalExceptionHandler`가 `ConflictException`(HTTP 409)으로 매핑.

#### 4.3 `PartyroomData` 본체 변경

- [ ] **Step 4: 필드/메서드 변경**

`app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/PartyroomData.java` 전체:

```java
package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.common.domain.annotation.AggregateRoot;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.PartyroomClosedEvent;
import com.pfplaybackend.api.party.domain.exception.GradeException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.value.*;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@AggregateRoot
@Getter
@DynamicInsert
@DynamicUpdate
@Table(
        name = "PARTYROOM",
        indexes = {
                @Index(name = "partyroom_host_id_IDX", columnList = "host_id")
        }
)
@Entity
public class PartyroomData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "partyroom_id")
    private Long id;

    @Transient
    private PartyroomId partyroomId;

    @Enumerated(EnumType.STRING)
    private StageType stageType;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "uid", column = @Column(name = "host_id")),
    })
    private UserId hostId;

    private String title;
    private String introduction;
    @Convert(converter = LinkDomainConverter.class)
    private LinkDomain linkDomain;
    @Convert(converter = PlaybackTimeLimitConverter.class)
    private PlaybackTimeLimit playbackTimeLimit;
    private String noticeContent;

    // V6: 상태 모델 (is_terminated → status ENUM 3-state)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PartyroomStatus status;

    // V6: denormalized counter — atomic UPDATE만 갱신 (PartyroomRepository.incrementCrewCount/decrementCrewCount)
    @Column(name = "crew_count", nullable = false)
    private int crewCount;

    // V6: 최근 활동 시각 — atomic UPDATE만 갱신 (PartyroomRepository.touchLastActivity)
    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    // V6: 표시 플래그 — getter only. setter는 PR 8 (Administration BC).
    @Enumerated(EnumType.STRING)
    @Column(name = "display_flag", nullable = false, length = 16)
    private DisplayFlag displayFlag;

    @PostPersist
    public void updatePartyroomId() {
        initializePartyroomId();
    }

    @PostLoad
    private void postLoad() {
        initializePartyroomId();
    }

    private void initializePartyroomId() {
        this.partyroomId = new PartyroomId(this.id);
    }

    protected PartyroomData() {}

    @Builder
    public PartyroomData(Long id, PartyroomId partyroomId, UserId hostId, StageType stageType,
                         String title, String introduction, LinkDomain linkDomain, PlaybackTimeLimit playbackTimeLimit,
                         String noticeContent, PartyroomStatus status, int crewCount,
                         LocalDateTime lastActivityAt, DisplayFlag displayFlag,
                         LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.partyroomId = partyroomId;
        this.hostId = hostId;
        this.stageType = stageType;
        this.title = title;
        this.introduction = introduction;
        this.linkDomain = linkDomain;
        this.playbackTimeLimit = playbackTimeLimit;
        this.noticeContent = noticeContent;
        this.status = status != null ? status : PartyroomStatus.ACTIVE;
        this.crewCount = crewCount;
        this.lastActivityAt = lastActivityAt;
        this.displayFlag = displayFlag != null ? displayFlag : DisplayFlag.NORMAL;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ── Factory Method ──

    public static PartyroomData create(String title, String introduction, LinkDomain linkDomain,
                                        PlaybackTimeLimit timeLimit, StageType stageType, UserId hostId) {
        return PartyroomData.builder()
                .stageType(stageType)
                .hostId(hostId)
                .title(title)
                .introduction(introduction)
                .linkDomain(linkDomain)
                .playbackTimeLimit(timeLimit)
                .noticeContent("")
                .status(PartyroomStatus.ACTIVE)
                .crewCount(0)
                .displayFlag(DisplayFlag.NORMAL)
                .build();
    }

    // ── Business Methods ──

    public PartyroomData updateBaseInfo(String title, String introduction, LinkDomain linkDomain, PlaybackTimeLimit timeLimit) {
        this.title = title;
        this.introduction = introduction;
        this.linkDomain = linkDomain;
        this.playbackTimeLimit = timeLimit;
        return this;
    }

    // ── State Inspection ──

    public boolean isActive() {
        return this.status == PartyroomStatus.ACTIVE;
    }

    public boolean isSuspended() {
        return this.status == PartyroomStatus.SUSPENDED;
    }

    /** TERMINATED 여부. 시그니처 유지 — 기존 호출자 무수정 작동. */
    public boolean isTerminated() {
        return this.status == PartyroomStatus.TERMINATED;
    }

    // ── State Transitions ──
    // 매트릭스 (spec §4.2):
    //   ACTIVE     -- suspend() --> SUSPENDED
    //   SUSPENDED  -- restore() --> ACTIVE
    //   ACTIVE/SUSPENDED -- terminate() --> TERMINATED  (terminal)
    // 비허용 전이는 ILLEGAL_STATE_TRANSITION 예외.

    public void suspend() {
        if (this.status != PartyroomStatus.ACTIVE) {
            throw ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION);
        }
        this.status = PartyroomStatus.SUSPENDED;
    }

    public void restore() {
        if (this.status != PartyroomStatus.SUSPENDED) {
            throw ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION);
        }
        this.status = PartyroomStatus.ACTIVE;
    }

    public void terminate() {
        if (this.status == PartyroomStatus.TERMINATED) {
            throw ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION);
        }
        this.status = PartyroomStatus.TERMINATED;
        registerEvent(new PartyroomClosedEvent(this.partyroomId, this.hostId, this.title));
    }

    // ── Validation ──

    public void validateHost(UserId userId) {
        if (!this.hostId.equals(userId)) {
            throw ExceptionCreator.create(GradeException.GRADE_INSUFFICIENT_FOR_OPERATION);
        }
    }

    /** TERMINATED 룸 가드. 시맨틱 유지 — SUSPENDED는 이 가드 통과 (입장 거부는 PartyroomEntrySpecification에서 처리). */
    public void validateNotTerminated() {
        if (isTerminated()) {
            throw ExceptionCreator.create(PartyroomException.ALREADY_TERMINATED);
        }
    }

    public PartyroomData assignPartyroomId(PartyroomId partyroomId) {
        this.partyroomId = partyroomId;
        return this;
    }
}
```

주요 변경점:
- `boolean isTerminated` 필드 → `PartyroomStatus status` enum
- `DisplayFlag displayFlag`, `int crewCount`, `LocalDateTime lastActivityAt` 추가
- 빌더 인자 `boolean isTerminated` → `PartyroomStatus status` + 신규 3개
- `terminate()` 가드 추가 (이중 호출 시 예외) — `PartyroomClosedEvent` registerEvent는 그대로 유지
- `isActive()`, `isSuspended()`, `suspend()`, `restore()` 신규
- `isTerminated()` 시그니처 유지

- [ ] **Step 5: 단위 테스트 재실행 — 모두 GREEN**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.entity.data.PartyroomDataTest"
```

Expected: BUILD SUCCESSFUL, all tests passed.

- [ ] **Step 6: 전체 컴파일 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava :app:compileTestJava
```

Expected: BUILD SUCCESSFUL. `isTerminated` 시그니처 유지로 기존 호출자 모두 컴파일 성공해야 함. 만약 `isTerminated` field 직접 참조가 어딘가 있다면 그게 컴파일 깨짐 → grep으로 추가 사이트 발견 후 보정 (PartyroomData 내부 외 기대 0건).

#### 4.4 마이그레이션 + 부팅 검증 (통합)

- [ ] **Step 7: 기존 통합 테스트 — V6 마이그레이션 자동 적용 + 새 entity 매핑 검증**

기존 `PartyroomRepositoryIntegrationTest.saveAndFindById`가 자동으로 V6까지 적용된 DB에서 PartyroomData persist를 시도하므로, 이게 그린이면 V6 SQL + 엔티티 매핑이 정합한다.

**단, 기존 테스트의 `assertThat(loaded.isTerminated()).isFalse();`는 시그니처 유지로 그대로 통과한다.** 추가 검증 (status/crewCount/displayFlag default) 보강:

`app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomRepositoryIntegrationTest.java`의 `saveAndFindById` 메서드에 assertion 추가:

```java
assertThat(loaded.getStatus()).isEqualTo(PartyroomStatus.ACTIVE);
assertThat(loaded.getCrewCount()).isZero();
assertThat(loaded.getDisplayFlag()).isEqualTo(DisplayFlag.NORMAL);
assertThat(loaded.getLastActivityAt()).isNull();
```

import 추가:
```java
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
```

- [ ] **Step 8: 통합 테스트 실행 — V6 마이그레이션 통과 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepositoryIntegrationTest"
```

Expected: BUILD SUCCESSFUL. Testcontainers MySQL이 V1→V6 마이그레이션 모두 통과 + 신규 컬럼 default 값 검증 통과.

만약 실패:
- `crew_count` 기본값 검증 실패 시: `INT NOT NULL DEFAULT 0` 매핑이 Hibernate ↔ DDL 불일치인지 확인
- ENUM 매핑 에러: `@Enumerated(EnumType.STRING)` 누락 여부 + `length=16` 충분한지

- [ ] **Step 9: 전체 테스트 — 회귀 없음 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test
```

Expected: BUILD SUCCESSFUL. 기존 모든 테스트 그린 — `isTerminated()` 시그니처 유지 덕에 호출부 무변경으로 통과. 만약 어딘가 `is_terminated` 컬럼 직접 참조가 있다면 SQL 에러 → 발견 즉시 grep으로 위치 식별.

#### 4.5 G1 단일 commit

- [ ] **Step 10: G1 묶음 commit**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-platform"
git add \
  app/src/main/resources/db/migration/V6__evolve_partyroom_state.sql \
  app/src/main/java/com/pfplaybackend/api/party/domain/enums/PartyroomStatus.java \
  app/src/main/java/com/pfplaybackend/api/party/domain/enums/DisplayFlag.java \
  app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/PartyroomData.java \
  app/src/main/java/com/pfplaybackend/api/party/domain/exception/PartyroomException.java \
  app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/PartyroomDataTest.java \
  app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomRepositoryIntegrationTest.java
git commit -m "$(cat <<'EOF'
feat(party): V6 partyroom state model + entity refactor (PR 7 G1)

- V6 migration: is_terminated BOOLEAN → status ENUM(ACTIVE/SUSPENDED/TERMINATED),
  + crew_count, last_activity_at, display_flag denormalized columns;
  data migration UPDATE TERMINATED rows; new indexes for list queries.
- PartyroomStatus / DisplayFlag enums.
- PartyroomData: status field + isActive/isSuspended (new), isTerminated
  (signature kept), suspend/restore/terminate (state transition matrix
  enforced — TERMINATED is terminal, illegal transitions throw
  ILLEGAL_STATE_TRANSITION).
- displayFlag/crewCount/lastActivityAt: getter only — setters intentionally
  omitted; PR 7 listener uses native atomic UPDATE only (next chunk).
- PartyroomData unit tests cover full state-transition matrix.
- PartyroomRepository integration test extended: V6 migration applied,
  new column defaults verified.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git status
```

Expected: `nothing to commit, working tree clean`. Single commit lands G1.

- [ ] **Step 11: HEAD 및 변경 파일 수 검증**

```bash
git log --oneline -1
git diff --stat HEAD~1
```

Expected:
- 마지막 commit이 위 G1 메시지
- `git diff --stat HEAD~1` 출력에 정확히 7 files changed (V6 sql + 2 enum + PartyroomData + PartyroomException + PartyroomDataTest + PartyroomRepositoryIntegrationTest). 8개 이상 → 다른 dirty file이 섞임 → revert 후 다시.

---

## Chunk 2: G2 — Repository status 시맨틱 일관 변경 + isTerminated 호출부 마이그레이션 + atomic UPDATE 통합 테스트

**Goal of chunk:** 1) `PartyroomRepository`에 atomic UPDATE 메서드 3개를 추가하고 모든 `is_terminated` 기반 쿼리(`findActiveHostRoom`, QueryDSL 2개)를 `status` 기반으로 일관 변경한다. 2) `findAllUnusedPartyroomDataByDay`에 `status <> TERMINATED` 필터를 추가하여 G1에서 도입된 strict `terminate()` 가드와 양립 가능하게 만든다. 3) `!isTerminated()` 호출부 5개를 `isActive()`로 의미론적 정정한다. 4) atomic UPDATE 메서드의 통합 테스트를 추가한다.

**End state of chunk:** 모든 partyroom 관련 쿼리/호출부가 `status` 시맨틱으로 일관, atomic UPDATE 3개가 통합 테스트로 검증, cleanup 잡이 G1의 strict `terminate()`와 양립.

### Task 5: `PartyroomRepository` — atomic UPDATE 메서드 + JPQL `findActiveHostRoom` 변경

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomRepository.java`

- [ ] **Step 1: 파일 전체 교체**

```java
package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PartyroomRepository extends JpaRepository<PartyroomData, Long>,
        com.pfplaybackend.api.party.adapter.out.persistence.custom.PartyroomRepositoryCustom {

    Optional<PartyroomData> findByLinkDomain(LinkDomain linkDomain);

    /**
     * Host가 보유한 비-종료 룸 1개 조회. SUSPENDED 룸도 포함 (호스트가 SUSPENDED 룸 보유 중일 때
     * 신규 룸 생성을 막는 용도 — spec §6.4 결정 (a)).
     */
    @Query("SELECT p FROM PartyroomData p WHERE p.hostId = :userId " +
           "AND p.status <> com.pfplaybackend.api.party.domain.enums.PartyroomStatus.TERMINATED")
    Optional<PartyroomData> findActiveHostRoom(@Param("userId") UserId userId);

    /**
     * crew_count +1 + lastActivityAt 갱신. TERMINATED 룸은 거부 (반환 0).
     * Race A 차단: DB row lock이 동시 호출을 직렬화.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PartyroomData p " +
           "SET p.crewCount = p.crewCount + 1, p.lastActivityAt = :now " +
           "WHERE p.id = :id " +
           "AND p.status <> com.pfplaybackend.api.party.domain.enums.PartyroomStatus.TERMINATED")
    int incrementCrewCount(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * crew_count -1 + lastActivityAt 갱신. 음수 방지 (CASE WHEN). TERMINATED 룸 거부.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PartyroomData p " +
           "SET p.crewCount = CASE WHEN p.crewCount > 0 THEN p.crewCount - 1 ELSE 0 END, " +
           "    p.lastActivityAt = :now " +
           "WHERE p.id = :id " +
           "AND p.status <> com.pfplaybackend.api.party.domain.enums.PartyroomStatus.TERMINATED")
    int decrementCrewCount(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * lastActivityAt만 갱신. ACTIVE 룸만 (SUSPENDED/TERMINATED 룸은 거부).
     * Playback 이벤트가 SUSPENDED/TERMINATED 룸 lastActivity 갱신하는 건 의미 없음.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PartyroomData p SET p.lastActivityAt = :now " +
           "WHERE p.id = :id " +
           "AND p.status = com.pfplaybackend.api.party.domain.enums.PartyroomStatus.ACTIVE")
    int touchLastActivity(@Param("id") Long id, @Param("now") LocalDateTime now);
}
```

⚠️ **Skip commit** — G2 묶음. Task 7에서 단일 commit.

---

### Task 6: `PartyroomRepositoryImpl` — QueryDSL 2개 위치 status 시맨틱 변경

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/impl/PartyroomRepositoryImpl.java`

#### 6.1 `getCrewDataByPartyroomId`의 isTerminated 필터 (line 110)

- [ ] **Step 1: line 110 변경**

```java
// before:
.where(qPartyroomData.isTerminated.eq(false))

// after:
.where(qPartyroomData.status.ne(com.pfplaybackend.api.party.domain.enums.PartyroomStatus.TERMINATED))
```

(`PartyroomStatus` import 상단에 추가하면 fully-qualified 표기 불필요.)

#### 6.2 `findAllUnusedPartyroomDataByDay`에 status 필터 추가 (line 162-169)

⚠️ **중요:** 현재 이 메서드는 `is_terminated` 필터가 없어 이미 종료된 룸도 결과에 포함됨. G1의 strict `terminate()`(이중 호출 시 예외) 도입 후엔 `PartyroomCommandService.deleteUnusedPartyroom`이 이미 종료된 30일 이전 룸을 다시 terminate 시도하다 `ILLEGAL_STATE_TRANSITION` 예외 발생 → 운영 잡 깨짐.

- [ ] **Step 2: 메서드 본문에 status 가드 추가**

```java
@Override
public List<PartyroomData> findAllUnusedPartyroomDataByDay(int days) {
    QPartyroomData qPartyroomData = QPartyroomData.partyroomData;

    return queryFactory.select(qPartyroomData)
            .from(qPartyroomData)
            .where(
                qPartyroomData.updatedAt.before(LocalDateTime.now(clock).minusDays(days)),
                qPartyroomData.status.ne(com.pfplaybackend.api.party.domain.enums.PartyroomStatus.TERMINATED)
            )
            .fetch();
}
```

(`,` 로 두 조건 연결 — QueryDSL은 다중 인자 `.where`를 AND로 결합.)

- [ ] **Step 3: import 정리**

파일 상단에 import 추가 (없으면):
```java
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
```

그리고 위 두 변경에서 fully-qualified 표기를 단순 `PartyroomStatus.TERMINATED`로 정리.

⚠️ **Skip commit** — G2 묶음. Task 7에서 단일 commit.

---

### Task 7: G2 단일 commit

- [ ] **Step 1: 컴파일 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava
```

Expected: BUILD SUCCESSFUL. QueryDSL Q-class(`QPartyroomData`)의 `status` 필드가 자동 생성됐는지 확인 — G1에서 entity에 `status` 추가 후 `compileJava`가 한 번이라도 실행됐다면 자동 재생성됨. 안 되면 `./gradlew :app:clean :app:compileJava`.

- [ ] **Step 2: 단위 테스트로 회귀 없음 확인 (빠른 피드백)**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.*"
```

Expected: BUILD SUCCESSFUL. 도메인 단위 테스트가 그린이면 entity ↔ enum mapping 무결.

- [ ] **Step 3: G2 묶음 commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomRepository.java \
  app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/impl/PartyroomRepositoryImpl.java
git commit -m "$(cat <<'EOF'
feat(party): repository status semantics + atomic counter UPDATE methods (PR 7 G2)

PartyroomRepository:
- findActiveHostRoom JPQL: is_terminated=false → status<>TERMINATED
  (intent: SUSPENDED room ownership also blocks new room creation per
  spec decision §6.4(a))
- new atomic UPDATEs: incrementCrewCount, decrementCrewCount (with
  underflow guard via CASE WHEN), touchLastActivity (ACTIVE only).
  All reject TERMINATED rooms (return 0 affected → listener WARN).

PartyroomRepositoryImpl (QueryDSL):
- getCrewDataByPartyroomId where: isTerminated.eq(false) → status.ne(TERMINATED)
- findAllUnusedPartyroomDataByDay: ADD status<>TERMINATED filter to
  prevent the cleanup job from re-terminating already-TERMINATED rows
  (would throw ILLEGAL_STATE_TRANSITION under PR 7 G1's strict guard).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: 변경 파일 수 검증**

```bash
git diff --stat HEAD~1
```

Expected: 정확히 2 files changed.

---

### Task 8: `!isTerminated()` 호출부 5개 의미론적 정정 (`isActive()`)

**Background:** `isTerminated()` 시그니처는 G1에서 유지됐으므로 컴파일은 OK. 그러나 `!isTerminated()`는 PR 8에서 SUSPENDED 진입 시 의미가 변함 (active vs not-terminated). PR 7에서 의미를 명시화 — `isActive()`로 정정.

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/payload/response/CreateAdminPartyroomResponse.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/admin/application/dto/result/AdminPartyroomResult.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/admin/application/service/AdminDemoService.java`
- (`PartyroomAccessCommandService.java:58`은 단순 로깅이라 변경 없음)

#### 8.1 grep으로 모든 호출부 식별

- [ ] **Step 1: grep 실행하여 spec와 매칭 확인**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-platform"
grep -rn "!.*isTerminated()" app/src/main/java/ app/src/test/java/
```

Expected: **정확히 4건** — `!`가 붙은 사이트만 매치:
1. `admin/.../CreateAdminPartyroomResponse.java:37` `.isActive(!partyroom.isTerminated())`
2. `admin/.../AdminPartyroomResult.java:27` `!partyroom.isTerminated(),`
3. `admin/.../AdminDemoService.java:400` `.filter(p -> !p.isTerminated() && ...)`
4. `admin/.../AdminDemoService.java:411` `.filter(p -> !p.isTerminated())`

spec §8.1 표 5번째 행(`PartyroomAccessCommandService.java:58`)은 `partyroom.isTerminated()`(부정 없음, 단순 로깅)이라 이 grep에 잡히지 않음 — 의도된 변경 없음 사이트.

4건 미만 → 누락 의심. 4건 초과 → 신규 사이트 발생 (PR 6 이후 추가 코드), 모두 spec 결정 동일하게 처리.

#### 8.2 `CreateAdminPartyroomResponse.java:37` 변경

- [ ] **Step 2: line 37 변경**

```java
// before:
.isActive(!partyroom.isTerminated())

// after:
.isActive(partyroom.isActive())
```

#### 8.3 `AdminPartyroomResult.java:27` 변경

- [ ] **Step 3: line 27 변경**

```java
// before:
!partyroom.isTerminated(),

// after:
partyroom.isActive(),
```

#### 8.4 `AdminDemoService.java:400` 및 `:411` 변경

- [ ] **Step 4: 두 line 동시 변경**

```java
// before (400):
.filter(p -> !p.isTerminated() && p.getStageType() == StageType.GENERAL)

// after:
.filter(p -> p.isActive() && p.getStageType() == StageType.GENERAL)

// before (411):
.filter(p -> !p.isTerminated())

// after:
.filter(p -> p.isActive())
```

#### 8.5 검증 + commit

- [ ] **Step 5: grep으로 잔여 `!isTerminated()` 호출 확인**

```bash
grep -rn "!.*isTerminated()" app/src/main/java/ app/src/test/java/
```

Expected: 0건. 만약 추가 사이트가 발견되면 동일 원칙(`isActive()`)으로 정정.

- [ ] **Step 6: 컴파일 + 단위 테스트**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava :app:test --tests "com.pfplaybackend.api.admin.*"
```

Expected: BUILD SUCCESSFUL. admin 모듈 테스트가 그린.

- [ ] **Step 7: commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/payload/response/CreateAdminPartyroomResponse.java \
  app/src/main/java/com/pfplaybackend/api/admin/application/dto/result/AdminPartyroomResult.java \
  app/src/main/java/com/pfplaybackend/api/admin/application/service/AdminDemoService.java
git commit -m "$(cat <<'EOF'
refactor(admin): !isTerminated() → isActive() to disambiguate from SUSPENDED (PR 7)

PR 7 introduces SUSPENDED state. !isTerminated() == ACTIVE today but will
mean ACTIVE OR SUSPENDED once PR 8 wires the suspend endpoint. All five
admin-facing call sites mean "operable room" (ACTIVE only) — make that
intent explicit now to prevent silent regression at PR 8 land.

PartyroomAccessCommandService.java:58 (log statement) intentionally not
changed — pure observability, not a behavioral predicate.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Atomic UPDATE 메서드 통합 테스트

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomRepositoryAtomicUpdateIT.java`

테스트 분리 이유: 기존 `PartyroomRepositoryIntegrationTest`는 단일 트랜잭션 (`@Transactional` 클래스 레벨, line 18) — flush/clear 패턴. atomic UPDATE는 `@Modifying` semantic + 트랜잭션 컨텍스트 다르므로 별 파일 권장.

#### 9.1 테스트 파일 작성

- [ ] **Step 1: 새 통합 테스트 파일 작성**

```java
package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PartyroomRepositoryAtomicUpdateIT extends AbstractIntegrationTest {

    @Autowired
    private PartyroomRepository partyroomRepository;

    private PartyroomData createAndSaveActive(long hostUid) {
        PartyroomData p = PartyroomData.create(
                "atomic", "intro", LinkDomain.of("link-" + hostUid),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(hostUid));
        return partyroomRepository.saveAndFlush(p);
    }

    private PartyroomData createAndSaveTerminated(long hostUid) {
        PartyroomData p = createAndSaveActive(hostUid);
        p.terminate();
        return partyroomRepository.saveAndFlush(p);
    }

    // ── incrementCrewCount ────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("incrementCrewCount — ACTIVE 룸에서 +1, lastActivityAt 갱신, 1 affected")
    void increment_active() {
        PartyroomData p = createAndSaveActive(1001L);
        LocalDateTime now = LocalDateTime.now();

        int affected = partyroomRepository.incrementCrewCount(p.getId(), now);

        assertThat(affected).isEqualTo(1);
        PartyroomData reloaded = partyroomRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getCrewCount()).isEqualTo(1);
        assertThat(reloaded.getLastActivityAt()).isEqualToIgnoringNanos(now);
    }

    @Test
    @Transactional
    @DisplayName("incrementCrewCount — TERMINATED 룸 거부 (0 affected)")
    void increment_terminated() {
        PartyroomData p = createAndSaveTerminated(1002L);

        int affected = partyroomRepository.incrementCrewCount(p.getId(), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    @Test
    @Transactional
    @DisplayName("incrementCrewCount — 존재하지 않는 id 거부 (0 affected)")
    void increment_missing() {
        int affected = partyroomRepository.incrementCrewCount(999_999_999L, LocalDateTime.now());
        assertThat(affected).isZero();
    }

    // ── decrementCrewCount ────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("decrementCrewCount — 정상 -1")
    void decrement_normal() {
        PartyroomData p = createAndSaveActive(1003L);
        partyroomRepository.incrementCrewCount(p.getId(), LocalDateTime.now());
        partyroomRepository.incrementCrewCount(p.getId(), LocalDateTime.now());

        int affected = partyroomRepository.decrementCrewCount(p.getId(), LocalDateTime.now());

        assertThat(affected).isEqualTo(1);
        PartyroomData reloaded = partyroomRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getCrewCount()).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("decrementCrewCount — crewCount=0에서 호출하면 음수 안 되고 0 유지 (1 affected — UPDATE 자체는 실행)")
    void decrement_underflow_guard() {
        PartyroomData p = createAndSaveActive(1004L);

        int affected = partyroomRepository.decrementCrewCount(p.getId(), LocalDateTime.now());

        assertThat(affected).isEqualTo(1);
        PartyroomData reloaded = partyroomRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getCrewCount()).isZero();
    }

    @Test
    @Transactional
    @DisplayName("decrementCrewCount — TERMINATED 룸 거부")
    void decrement_terminated() {
        PartyroomData p = createAndSaveTerminated(1005L);

        int affected = partyroomRepository.decrementCrewCount(p.getId(), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    // ── touchLastActivity ─────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("touchLastActivity — ACTIVE 룸 갱신")
    void touch_active() {
        PartyroomData p = createAndSaveActive(1006L);
        LocalDateTime now = LocalDateTime.now();

        int affected = partyroomRepository.touchLastActivity(p.getId(), now);

        assertThat(affected).isEqualTo(1);
        PartyroomData reloaded = partyroomRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getLastActivityAt()).isEqualToIgnoringNanos(now);
    }

    @Test
    @Transactional
    @DisplayName("touchLastActivity — SUSPENDED 룸 거부 (ACTIVE-only)")
    void touch_suspended() {
        PartyroomData p = createAndSaveActive(1007L);
        p.suspend();
        partyroomRepository.saveAndFlush(p);

        int affected = partyroomRepository.touchLastActivity(p.getId(), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    @Test
    @Transactional
    @DisplayName("touchLastActivity — TERMINATED 룸 거부")
    void touch_terminated() {
        PartyroomData p = createAndSaveTerminated(1008L);

        int affected = partyroomRepository.touchLastActivity(p.getId(), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    // ── findActiveHostRoom (status 시맨틱 변경 회귀) ────────────────

    @Test
    @Transactional
    @DisplayName("findActiveHostRoom — TERMINATED 룸은 결과에서 제외")
    void findActiveHostRoom_excludes_terminated() {
        UserId hostId = new UserId(2001L);
        PartyroomData p = createAndSaveActive(2001L);
        p.terminate();
        partyroomRepository.saveAndFlush(p);

        Optional<PartyroomData> result = partyroomRepository.findActiveHostRoom(hostId);

        assertThat(result).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("findActiveHostRoom — SUSPENDED 룸은 포함 (호스트의 새 룸 생성 차단 의도, spec §6.4(a))")
    void findActiveHostRoom_includes_suspended() {
        UserId hostId = new UserId(2002L);
        PartyroomData p = createAndSaveActive(2002L);
        p.suspend();
        partyroomRepository.saveAndFlush(p);

        Optional<PartyroomData> result = partyroomRepository.findActiveHostRoom(hostId);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(PartyroomStatus.SUSPENDED);
    }
}
```

- [ ] **Step 2: 테스트 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepositoryAtomicUpdateIT"
```

Expected: 11 tests passed (3 increment + 3 decrement + 3 touch + 2 findActiveHostRoom).

- [ ] **Step 3: commit**

```bash
git add app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomRepositoryAtomicUpdateIT.java
git commit -m "$(cat <<'EOF'
test(party): atomic UPDATE methods integration tests (PR 7)

Cover incrementCrewCount / decrementCrewCount / touchLastActivity:
positive paths, TERMINATED rejection, missing-id, underflow guard,
ACTIVE-only constraint for touchLastActivity. Plus findActiveHostRoom
status-semantic regression: TERMINATED excluded, SUSPENDED included
(per spec §6.4(a) — block re-creation while admin-suspended).

Concurrency tests (100-thread races) live in a dedicated chunk to
avoid bloating this baseline.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: 회귀 테스트**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test
```

Expected: BUILD SUCCESSFUL — 회귀 없음.

---

## Chunk 3: `PartyroomEntrySpecification` SUSPENDED 거부 + `PartyroomCounterListener` 신설

**Goal of chunk:** 1) `PartyroomEntrySpecification.validate(...)`에 SUSPENDED 룸 입장 거부 가드를 추가하고 단위 테스트로 ACTIVE/SUSPENDED/TERMINATED 케이스 전수 검증. 2) `PartyroomCounterListener`를 신설하여 `CrewAccessedEvent(ENTER/EXIT)` → atomic UPDATE 카운터 ±, `PlaybackStartedEvent`/`PlaybackDeactivatedEvent` → `lastActivityAt` 갱신을 연결. listener는 `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 패턴.

**End state of chunk:** 입장 명세가 SUSPENDED 거부, listener가 이벤트 → DB 카운터 갱신을 자동 수행. 이벤트 publish-listen 회로가 통합 테스트로 검증.

### Task 10: `PartyroomEntrySpecification` — SUSPENDED 거부 가드

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/specification/PartyroomEntrySpecification.java`
- Create or Modify: `app/src/test/java/com/pfplaybackend/api/party/domain/specification/PartyroomEntrySpecificationTest.java`

#### 10.1 단위 테스트 먼저 (TDD red)

- [ ] **Step 1: 기존 테스트 파일 존재 여부 확인**

```bash
ls app/src/test/java/com/pfplaybackend/api/party/domain/specification/PartyroomEntrySpecificationTest.java 2>&1
```

존재 시 Modify, 없으면 Create.

- [ ] **Step 2: 테스트 작성/교체**

```java
package com.pfplaybackend.api.party.domain.specification;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartyroomEntrySpecificationTest {

    private PartyroomData newRoom() {
        return PartyroomData.create(
                "Room", "intro", LinkDomain.of("link"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(1L)
        );
    }

    private final PartyroomEntrySpecification spec = new PartyroomEntrySpecification();

    @Nested
    @DisplayName("status 가드")
    class StatusGuard {
        @Test @DisplayName("ACTIVE 입장 허용")
        void active() {
            PartyroomData room = newRoom();
            assertThatNoException().isThrownBy(() -> spec.validate(room, 0L, Optional.empty()));
        }

        @Test
        @DisplayName("SUSPENDED 거부 — ConflictException (어드민이 정지한 룸)")
        void suspended() {
            PartyroomData room = newRoom();
            room.suspend();
            assertThatThrownBy(() -> spec.validate(room, 0L, Optional.empty()))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("TERMINATED 거부 — ForbiddenException (기존 ALREADY_TERMINATED)")
        void terminated() {
            PartyroomData room = newRoom();
            room.terminate();
            assertThatThrownBy(() -> spec.validate(room, 0L, Optional.empty()))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    // 기존 ban / 인원 초과 등 가드 회귀 테스트는 별도 — 본 task 범위 외
}
```

- [ ] **Step 3: 테스트 실행 — RED 확인**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.specification.PartyroomEntrySpecificationTest"
```

Expected: `suspended()` 테스트 FAIL — `validate()`가 SUSPENDED를 거부하지 않고 통과. (`active()`, `terminated()`는 기존 동작으로 PASS.)

#### 10.2 SUSPENDED 거부 가드 추가

- [ ] **Step 4: `PartyroomEntrySpecification` 수정**

`app/src/main/java/com/pfplaybackend/api/party/domain/specification/PartyroomEntrySpecification.java`:

```java
package com.pfplaybackend.api.party.domain.specification;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.exception.PenaltyException;

import java.util.Optional;

public class PartyroomEntrySpecification {

    public void validate(PartyroomData partyroom, long activeCrewCount, Optional<CrewData> existingCrew) {
        partyroom.validateNotTerminated();
        if (partyroom.isSuspended()) {
            // SUSPENDED 룸 입장 거부 — PR 8에서 어드민이 룸을 정지시킨 경우.
            // PR 7 시점엔 SUSPENDED 진입 경로 없지만 픽스처로 직접 SUSPENDED 룸 만들어 가드 검증 가능.
            throw ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION);
        }
        if (activeCrewCount > 49) throw ExceptionCreator.create(PartyroomException.EXCEEDED_LIMIT);
        existingCrew.filter(CrewData::isBanned).ifPresent(c -> {
            throw ExceptionCreator.create(PenaltyException.PERMANENT_EXPULSION);
        });
    }
}
```

설계 노트: SUSPENDED 거부에 신규 `PartyroomException` 코드 추가하지 않고 G1에서 만든 `ILLEGAL_STATE_TRANSITION` 재사용. 의미: "현재 상태에선 입장이 허용되지 않는다." → ConflictException(409). 어드민이 룸을 정지시킨 사용자 응답으로 적합.

- [ ] **Step 5: 테스트 재실행 — GREEN**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.specification.PartyroomEntrySpecificationTest"
```

Expected: 3 tests passed.

- [ ] **Step 6: 회귀 — admin/party 모듈 단위 테스트**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.*" --tests "com.pfplaybackend.api.admin.*"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/party/domain/specification/PartyroomEntrySpecification.java \
  app/src/test/java/com/pfplaybackend/api/party/domain/specification/PartyroomEntrySpecificationTest.java
git commit -m "$(cat <<'EOF'
feat(party): PartyroomEntrySpecification rejects SUSPENDED rooms (PR 7)

Adds an isSuspended() guard between the existing not-terminated check
and capacity check. Reuses ILLEGAL_STATE_TRANSITION (PTR-007 → 409
Conflict) for the user-facing error — semantically "this room is not
currently accepting entries" matches what an admin-paused room means.

PR 7 has no path that creates SUSPENDED rooms (PR 8 ships the suspend
endpoint), but the guard is unit-tested against fixture-built SUSPENDED
state so the protection is in place the moment SUSPENDED becomes reachable.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: `PartyroomCounterListener` 신설 + 통합 테스트

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/PartyroomCounterListener.java`
- Create: `app/src/test/java/com/pfplaybackend/api/party/adapter/in/listener/PartyroomCounterListenerIT.java`

**Note:** 폴더가 `adapter/in/listener`로 존재하지 않을 수 있음 — Step 1에서 신설.

#### 11.1 디렉터리 신설

- [ ] **Step 1: 디렉터리 확인 및 생성**

```bash
ls app/src/main/java/com/pfplaybackend/api/party/adapter/in/ 2>&1
```

`listener/` 없으면:

```bash
mkdir -p app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener
mkdir -p app/src/test/java/com/pfplaybackend/api/party/adapter/in/listener
```

(이미 다른 in/listener 디렉터리가 있는지 확인. `CrewProfilePreCheckTopicListener.java` 등 기존 listener는 동일 위치 사용.)

#### 11.2 listener 본체 작성

- [ ] **Step 2: `PartyroomCounterListener` 작성**

```java
package com.pfplaybackend.api.party.adapter.in.listener;

import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.PlaybackDeactivatedEvent;
import com.pfplaybackend.api.party.domain.event.PlaybackStartedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Partyroom denormalized 카운터/lastActivity 갱신 listener.
 *
 * 모든 listener 메서드는 {@code AFTER_COMMIT} phase에서 새 트랜잭션을 열어
 * native atomic UPDATE를 실행한다 — Race A(multi-instance counter race)는
 * DB row lock이 직렬화하므로 분산락 불필요. spec §7.1 / §7.4 참조.
 *
 * Affected==0 케이스 (룸 missing or TERMINATED)는 WARN/DEBUG 로그.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PartyroomCounterListener {

    private final PartyroomRepository partyroomRepository;
    private final Clock clock;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(CrewAccessedEvent event) {
        Long partyroomId = event.getPartyroomId().getId();
        LocalDateTime now = LocalDateTime.now(clock);
        int affected = switch (event.getAccessType()) {
            case ENTER -> partyroomRepository.incrementCrewCount(partyroomId, now);
            case EXIT  -> partyroomRepository.decrementCrewCount(partyroomId, now);
        };
        if (affected == 0) {
            log.warn("[PartyroomCounterListener] crew_count update skipped (room missing or TERMINATED) " +
                     "partyroomId={}, accessType={}", partyroomId, event.getAccessType());
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
        int affected = partyroomRepository.touchLastActivity(partyroomId, LocalDateTime.now(clock));
        if (affected == 0) {
            log.debug("[PartyroomCounterListener] touchLastActivity skipped (room not ACTIVE) partyroomId={}",
                      partyroomId);
        }
    }
}
```

설계 노트:
- `Clock` 주입 — 기존 컨벤션(`PartyroomAccessCommandService.java:44`도 `private final Clock clock;`) 따름. 테스트에서 시간 mock 가능.
- `DomainEventRedisRelay`도 같은 `CrewAccessedEvent`를 listen 중 — Spring이 두 listener를 독립적으로 dispatch (충돌 없음).

#### 11.3 통합 테스트 작성

- [ ] **Step 3: `PartyroomCounterListenerIT` 작성**

```java
package com.pfplaybackend.api.party.adapter.in.listener;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.PlaybackDeactivatedEvent;
import com.pfplaybackend.api.party.domain.event.PlaybackStartedEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.party.domain.value.PlaybackSnapshot;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class PartyroomCounterListenerIT extends AbstractIntegrationTest {

    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;

    private long createActiveRoom(long hostUid, String linkSuffix) {
        PartyroomData p = PartyroomData.create(
                "listener-test", "intro", LinkDomain.of("link-" + linkSuffix),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(hostUid));
        return partyroomRepository.saveAndFlush(p).getId();
    }

    @Test
    @DisplayName("ENTER 이벤트 → crew_count +1, lastActivityAt 갱신")
    void enter_increments() {
        long roomId = createActiveRoom(3001L, "enter");

        // AFTER_COMMIT phase가 fire되도록 트랜잭션 안에서 publish
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId),
                        new CrewId(7777L), new UserId(3001L), AccessType.ENTER))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getCrewCount()).isEqualTo(1);
        assertThat(reloaded.getLastActivityAt()).isNotNull();
    }

    @Test
    @DisplayName("EXIT 이벤트 → crew_count -1")
    void exit_decrements() {
        long roomId = createActiveRoom(3002L, "exit");
        // 사전 +1
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId),
                        new CrewId(8888L), new UserId(3002L), AccessType.ENTER))
        );

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId),
                        new CrewId(8888L), new UserId(3002L), AccessType.EXIT))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getCrewCount()).isZero();
    }

    @Test
    @DisplayName("PlaybackStartedEvent → lastActivityAt 갱신 (crew_count는 변함 없음)")
    void playback_started_touches() {
        long roomId = createActiveRoom(3003L, "pstart");
        long beforeNanos = System.nanoTime();

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new PlaybackStartedEvent(
                        new PartyroomId(roomId),
                        new CrewId(9999L),
                        new PlaybackSnapshot(0L, "", "", "", "", 0L)
                ))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getLastActivityAt()).isNotNull();
        assertThat(reloaded.getCrewCount()).isZero();
    }

    @Test
    @DisplayName("PlaybackDeactivatedEvent → lastActivityAt 갱신")
    void playback_deactivated_touches() {
        long roomId = createActiveRoom(3004L, "pdeact");

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new PlaybackDeactivatedEvent(
                        new PartyroomId(roomId),
                        new PlaybackId(1111L), new CrewId(2222L)))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getLastActivityAt()).isNotNull();
    }

    @Test
    @DisplayName("ENTER 이벤트가 TERMINATED 룸에 도착하면 crew_count 변화 없음 (WARN 로그)")
    void enter_terminated_room_noop() {
        long roomId = createActiveRoom(3005L, "term");
        // 룸 종료
        transactionTemplate.executeWithoutResult(status -> {
            PartyroomData p = partyroomRepository.findById(roomId).orElseThrow();
            p.terminate();
            partyroomRepository.saveAndFlush(p);
        });

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId),
                        new CrewId(3333L), new UserId(3005L), AccessType.ENTER))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getCrewCount()).isZero();
    }
}
```

**가능한 함정:**
- `PlaybackSnapshot`은 `record(long id, String linkId, String name, String duration, String thumbnailImage, long endTime)` — 팩토리 없음, 생성자 직접 호출. listener 동작 검증이 목적이라 dummy 값 OK.
- `import com.pfplaybackend.api.party.domain.value.PartyroomId;` 위 코드에 `fully-qualified` 표기로 잡음 회피했지만, 정리 시 import 추가 후 단순화 권장.
- `transactionTemplate.executeWithoutResult` — `AFTER_COMMIT` listener phase가 실제 발화되려면 publishEvent가 트랜잭션 컨텍스트 안에 있어야 함. 그렇지 않으면 `fallbackExecution=true`로 즉시 실행되지만 phase 시맨틱이 다름. 안정성을 위해 명시적으로 트랜잭션 wrapping.
- `Clock` bean은 `common/src/main/java/com/pfplaybackend/api/common/config/ClockConfig.java`에서 `Clock.systemDefaultZone()`로 빈 등록됨 — `@Autowired Clock clock` 또는 생성자 주입 가능.

- [ ] **Step 4: 통합 테스트 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.adapter.in.listener.PartyroomCounterListenerIT"
```

Expected: 5 tests passed.

만약 실패:
- listener가 실행되지 않음 → `@Component` scan 범위 확인 (`@SpringBootApplication` 패키지 트리에 listener 위치)
- AFTER_COMMIT phase fire 안 됨 → `transactionTemplate` wrapping 누락 또는 `fallbackExecution=true` 효과로 phase 이전에 실행 — 어느 경우든 listener 실행은 보장됨
- `crew_count` 안 갱신됨 → atomic UPDATE 메서드 자체 문제 (Chunk 2 PartyroomRepositoryAtomicUpdateIT가 그린이면 메서드 자체 OK)

- [ ] **Step 5: 전체 회귀 테스트**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test
```

Expected: BUILD SUCCESSFUL — 회귀 없음. 특히 `DomainEventRedisRelayTest`는 listener 추가에 영향 없음 확인.

- [ ] **Step 6: commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/PartyroomCounterListener.java \
  app/src/test/java/com/pfplaybackend/api/party/adapter/in/listener/PartyroomCounterListenerIT.java
git commit -m "$(cat <<'EOF'
feat(party): PartyroomCounterListener — atomic counter / lastActivity wiring (PR 7)

Single listener bean wires three domain events to native UPDATE methods:

- CrewAccessedEvent(ENTER) → incrementCrewCount + lastActivityAt
- CrewAccessedEvent(EXIT)  → decrementCrewCount + lastActivityAt
- PlaybackStartedEvent     → touchLastActivity
- PlaybackDeactivatedEvent → touchLastActivity

Each handler runs in @TransactionalEventListener(AFTER_COMMIT) +
@Transactional(REQUIRES_NEW), so the publishing tx commits first, then
the counter UPDATE serializes via DB row lock. Multi-instance safe —
no distributed lock required (spec §7.1 / §7.4).

Affected==0 (room missing / TERMINATED / not ACTIVE for touch) → WARN
or DEBUG log; the listener never throws back to the publisher.

DomainEventRedisRelay already listens to the same CrewAccessedEvent
for Redis fanout; Spring dispatches both listeners independently.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Chunk 4: Crew 조건부 toggle UPDATE + `PartyroomAccessCommandService` 리팩토링 + 동시성 테스트

**Goal of chunk:** 1) `CrewRepository`/`PartyroomAggregatePort`/`PartyroomAggregateAdapter`에 `activateCrew`/`deactivateCrew` 조건부 UPDATE 메서드 추가. 2) `PartyroomAccessCommandService.tryEnter()` / `exit()`을 atomic toggle로 리팩토링하여 (i) Race B-reentry 차단 (ii) same-room re-entry spurious ENTER 발행 차단. 3) `PartyroomRepository`/`PlaybackAggregationRepository`/`PartyroomAccessCommandService`에 대한 동시성 회귀 테스트 추가.

**End state of chunk:** 같은 user 100 스레드 동시 enter → counter == 1, 같은 user 100 스레드 동시 like → counter == 100, 모든 race 시나리오가 결정적 테스트로 가드됨.

### Task 12: Crew atomic toggle infrastructure (Repository + Port + Adapter)

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/CrewRepository.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/port/PartyroomAggregatePort.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomAggregateAdapter.java`
- Create: `app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/CrewRepositoryAtomicToggleIT.java`

#### 12.1 `CrewRepository`에 atomic toggle 메서드 추가

- [ ] **Step 1: 파일 전체 교체**

`app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/CrewRepository.java`:

```java
package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CrewRepository extends JpaRepository<CrewData, Long> {
    List<CrewData> findByPartyroomIdAndIsActiveTrue(PartyroomId partyroomId);
    Optional<CrewData> findByPartyroomIdAndUserId(PartyroomId partyroomId, UserId userId);
    long countByPartyroomIdAndIsActiveTrue(PartyroomId partyroomId);

    /**
     * crew row를 active로 조건부 toggle.
     *  - 반환 1: is_active=false → true 전이 발생 (호출자는 ENTER 이벤트 발행 의무)
     *  - 반환 0: row 없거나 이미 active (호출자는 후속 분기 — 새 INSERT 또는 idempotent return)
     * Race B-reentry 차단: 동시 호출 중 정확히 한 호출자만 1 반환.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CrewData c " +
           "SET c.isActive = true, c.enteredAt = :now " +
           "WHERE c.partyroomId = :partyroomId AND c.userId = :userId AND c.isActive = false")
    int activateCrew(@Param("partyroomId") PartyroomId partyroomId,
                     @Param("userId") UserId userId,
                     @Param("now") LocalDateTime now);

    /**
     * crew row를 inactive로 조건부 toggle.
     *  - 반환 1: is_active=true → false 전이 발생 (호출자는 EXIT 이벤트 발행 의무)
     *  - 반환 0: row 없거나 이미 inactive (호출자 idempotent return)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CrewData c " +
           "SET c.isActive = false, c.exitedAt = :now " +
           "WHERE c.partyroomId = :partyroomId AND c.userId = :userId AND c.isActive = true")
    int deactivateCrew(@Param("partyroomId") PartyroomId partyroomId,
                       @Param("userId") UserId userId,
                       @Param("now") LocalDateTime now);
}
```

#### 12.2 `PartyroomAggregatePort`에 메서드 추가

- [ ] **Step 2: port interface에 추가**

`app/src/main/java/com/pfplaybackend/api/party/domain/port/PartyroomAggregatePort.java`의 `// ===== Crew: CrewData =====` 섹션 마지막에 추가:

```java
import java.time.LocalDateTime;   // import 추가

// ... 기존 메서드들 ...

/** 조건부 atomic toggle. 반환값: 1 (전이 발생) / 0 (미전이). 자세한 시맨틱은 CrewRepository javadoc. */
int activateCrew(PartyroomId partyroomId, UserId userId, LocalDateTime now);
int deactivateCrew(PartyroomId partyroomId, UserId userId, LocalDateTime now);
```

#### 12.3 `PartyroomAggregateAdapter` 위임 추가

- [ ] **Step 3: adapter에 위임 메서드 추가**

`app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomAggregateAdapter.java`의 Crew 섹션 끝 (`countActiveCrews` 뒤) 추가:

```java
import java.time.LocalDateTime;   // import 추가

// ... 기존 위임 메서드들 ...

@Override
public int activateCrew(PartyroomId partyroomId, UserId userId, LocalDateTime now) {
    return crewRepository.activateCrew(partyroomId, userId, now);
}

@Override
public int deactivateCrew(PartyroomId partyroomId, UserId userId, LocalDateTime now) {
    return crewRepository.deactivateCrew(partyroomId, userId, now);
}
```

#### 12.4 통합 테스트

- [ ] **Step 4: `CrewRepositoryAtomicToggleIT` 작성**

`app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/CrewRepositoryAtomicToggleIT.java`:

```java
package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class CrewRepositoryAtomicToggleIT extends AbstractIntegrationTest {

    @Autowired private CrewRepository crewRepository;

    private CrewData seedActiveCrew(long roomId, long uid) {
        CrewData crew = CrewData.create(new PartyroomId(roomId), new UserId(uid),
                GradeType.LISTENER, CountryCode.of("KR"), LocalDateTime.now());
        return crewRepository.saveAndFlush(crew);
    }

    private CrewData seedInactiveCrew(long roomId, long uid) {
        CrewData c = seedActiveCrew(roomId, uid);
        c.deactivatePresence(LocalDateTime.now());
        return crewRepository.saveAndFlush(c);
    }

    // ── activateCrew ─────────────────────────────────────────────

    @Test
    @DisplayName("activateCrew — inactive row → 1 반환, isActive=true 전이")
    void activate_inactive() {
        CrewData seeded = seedInactiveCrew(4001L, 4001L);

        int affected = crewRepository.activateCrew(seeded.getPartyroomId(), seeded.getUserId(), LocalDateTime.now());

        assertThat(affected).isEqualTo(1);
        Optional<CrewData> reloaded = crewRepository.findByPartyroomIdAndUserId(
                seeded.getPartyroomId(), seeded.getUserId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().isActive()).isTrue();
    }

    @Test
    @DisplayName("activateCrew — 이미 active → 0 반환 (no-op)")
    void activate_already_active() {
        CrewData seeded = seedActiveCrew(4002L, 4002L);

        int affected = crewRepository.activateCrew(seeded.getPartyroomId(), seeded.getUserId(), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    @Test
    @DisplayName("activateCrew — row 없음 → 0 반환")
    void activate_missing() {
        int affected = crewRepository.activateCrew(new PartyroomId(999_999L),
                new UserId(999_999L), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    // ── deactivateCrew ───────────────────────────────────────────

    @Test
    @DisplayName("deactivateCrew — active row → 1 반환, isActive=false 전이")
    void deactivate_active() {
        CrewData seeded = seedActiveCrew(4003L, 4003L);

        int affected = crewRepository.deactivateCrew(seeded.getPartyroomId(), seeded.getUserId(), LocalDateTime.now());

        assertThat(affected).isEqualTo(1);
        Optional<CrewData> reloaded = crewRepository.findByPartyroomIdAndUserId(
                seeded.getPartyroomId(), seeded.getUserId());
        assertThat(reloaded.get().isActive()).isFalse();
        assertThat(reloaded.get().getExitedAt()).isNotNull();
    }

    @Test
    @DisplayName("deactivateCrew — 이미 inactive → 0 반환")
    void deactivate_already_inactive() {
        CrewData seeded = seedInactiveCrew(4004L, 4004L);

        int affected = crewRepository.deactivateCrew(seeded.getPartyroomId(), seeded.getUserId(), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    @Test
    @DisplayName("deactivateCrew — row 없음 → 0 반환")
    void deactivate_missing() {
        int affected = crewRepository.deactivateCrew(new PartyroomId(999_998L),
                new UserId(999_998L), LocalDateTime.now());

        assertThat(affected).isZero();
    }
}
```

- [ ] **Step 5: 테스트 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.adapter.out.persistence.CrewRepositoryAtomicToggleIT"
```

Expected: 6 tests passed.

- [ ] **Step 6: commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/CrewRepository.java \
  app/src/main/java/com/pfplaybackend/api/party/domain/port/PartyroomAggregatePort.java \
  app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomAggregateAdapter.java \
  app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/CrewRepositoryAtomicToggleIT.java
git commit -m "$(cat <<'EOF'
feat(party): crew is_active conditional UPDATE — race B-3 mitigation (PR 7)

CrewRepository.activateCrew / deactivateCrew: native @Modifying @Query
that only flips is_active when the current value differs from the
target. Returns 1 on actual transition, 0 on no-op (already in target
state, or row missing).

Caller obligation:
  - return == 1 → publish ENTER/EXIT event
  - return == 0 → idempotent (no event publish)

This eliminates the re-entry race: two concurrent enter() requests for
the same (partyroom, user) where the row is already inactive both
attempt activateCrew; DB row lock serializes; exactly one returns 1
and publishes ENTER, the other returns 0 and is silent.

Wired through PartyroomAggregatePort + PartyroomAggregateAdapter to
keep the hexagonal layer boundary intact. PartyroomAccessCommandService
adopts these in the next task.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: `PartyroomAccessCommandService.tryEnter` / `exit` 리팩토링

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandService.java`

**Goals (spec §7.2 cross-ref):**
1. `tryEnter`의 same-room re-entry 분기 (line 75-83)에서 ENTER publish 제거 → spurious ENTER 차단
2. `addOrActivateCrew` (line 92-107)을 atomic toggle 기반으로 재작성
3. `exit` (line 119-140)을 atomic toggle 기반으로 재작성
4. `expel` (line 142-151)는 admin-initiated single-actor flow + `enforceBan` 추가 로직이라 **기존 dirty-checking 유지** (race risk 미미)

#### 13.1 service 본체 교체

- [ ] **Step 1: `PartyroomAccessCommandService.java` 변경 — 메서드 단위 교체**

기존 `addOrActivateCrew` private helper 제거하고 신규 `ensureCrewActive` 도입. 파일 전체를 다음으로 교체 (변경 범위 큼 — diff 잘 검토):

```java
package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.enums.DjChangeType;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.DjQueueChangedEvent;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.service.PartyroomAggregateService;
import com.pfplaybackend.api.party.domain.specification.PartyroomEntrySpecification;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartyroomAccessCommandService {

    private final ApplicationEventPublisher eventPublisher;
    private final PartyroomAggregatePort aggregatePort;
    private final PartyroomAggregateService partyroomAggregateService;
    private final PartyroomQueryService partyroomQueryService;
    private final PlaybackControlPort playbackControlPort;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    private TransactionTemplate requiresNewReadOnlyTx;

    @PostConstruct
    void initTxTemplates() {
        this.requiresNewReadOnlyTx = new TransactionTemplate(transactionManager);
        this.requiresNewReadOnlyTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewReadOnlyTx.setReadOnly(true);
    }

    @Transactional
    public CrewData tryEnter(PartyroomId partyroomId, CountryCode countryCode) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId userId = authContext.getUserId();
        log.info("[tryEnter] START - userId={}, targetPartyroomId={}", userId, partyroomId.getId());

        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);

        long activeCrewCount = aggregatePort.countActiveCrews(partyroomId);
        Optional<CrewData> existingCrew = aggregatePort.findCrew(partyroomId, userId);
        log.debug("[tryEnter] Partyroom found - partyroomId={}, status={}, crewCount={}",
                partyroomId.getId(), partyroom.getStatus(), activeCrewCount);

        new PartyroomEntrySpecification().validate(partyroom, activeCrewCount, existingCrew);

        // Validate Crew Condition
        Optional<ActivePartyroomDto> optActiveRoomInfo = partyroomQueryService.getMyActivePartyroom(userId);
        log.info("[tryEnter] Active room check - userId={}, hasActiveRoom={}, activeRoomId={}",
                userId,
                optActiveRoomInfo.isPresent(),
                optActiveRoomInfo.map(ActivePartyroomDto::id).orElse(null));

        if (optActiveRoomInfo.isPresent()) {
            ActivePartyroomDto activeRoomInfo = optActiveRoomInfo.get();
            if (!partyroomId.equals(new PartyroomId(activeRoomInfo.id()))) {
                // 다른 룸에서 옮겨오는 중 — 기존 룸 exit 후 진입 흐름으로 fall-through
                log.info("[tryEnter] Auto-exit from another room - userId={}, exitingRoomId={}, enteringRoomId={}",
                        userId, activeRoomInfo.id(), partyroomId.getId());
                exit(new PartyroomId(activeRoomInfo.id()));
            } else {
                // 같은 룸 재입장 (websocket 재연결 등) — 이미 active인 경로.
                // ⚠️ 이전 코드는 여기서도 ENTER 이벤트 발행 → counter inflate.
                // PR 7: countryCode만 갱신, 이벤트 발행 금지 (spec §7.2 spurious ENTER 차단).
                log.info("[tryEnter] Same room re-entry — countryCode 갱신만, no ENTER publish. userId={}, partyroomId={}",
                        userId, partyroomId.getId());
                CrewData crew = aggregatePort.findCrew(partyroomId, userId).orElseThrow();
                crew.updateCountryCode(countryCode);
                return aggregatePort.saveCrew(crew);
            }
        }

        // 새 진입 (또는 다른 룸에서 옮겨와 새 진입)
        CrewActivationResult result = ensureCrewActive(partyroom, userId, countryCode);
        if (result.transitioned) {
            log.info("[tryEnter] SUCCESS - userId={}, partyroomId={}, crewId={}",
                    userId, partyroomId.getId(), result.crew.getId());
            publishAccessChangedEvent(partyroom.getPartyroomId(), result.crew, userId);
        } else {
            log.info("[tryEnter] IDEMPOTENT - already active or concurrent insert loser, no event. userId={}, partyroomId={}",
                    userId, partyroomId.getId());
        }
        return result.crew;
    }

    /**
     * Crew를 active 상태로 만든다 (idempotent). 호출자에게 `transitioned` 플래그를 돌려 ENTER 이벤트
     * 발행 여부를 판단하게 한다.
     *
     * 흐름:
     *  1. {@code activateCrew} atomic toggle 시도 → 1이면 inactive→active 전이 성공.
     *  2. 0 (row missing 또는 이미 active) → findCrew 분기:
     *     a. row 없음 → INSERT. 동시 INSERT 패배자는 {@link DataIntegrityViolationException} —
     *        outer 트랜잭션이 rollback-only 상태가 되므로 winner 조회는 별 트랜잭션(REQUIRES_NEW)에서
     *        수행. 본 호출자는 idempotent return.
     *     b. row 있고 active → countryCode만 갱신, idempotent.
     */
    private CrewActivationResult ensureCrewActive(PartyroomData partyroom, UserId userId, CountryCode countryCode) {
        PartyroomId pid = partyroom.getPartyroomId();
        LocalDateTime now = LocalDateTime.now(clock);

        int activated = aggregatePort.activateCrew(pid, userId, now);
        if (activated == 1) {
            CrewData crew = aggregatePort.findCrew(pid, userId).orElseThrow();
            crew.updateCountryCode(countryCode);
            return new CrewActivationResult(aggregatePort.saveCrew(crew), true);
        }

        Optional<CrewData> existing = aggregatePort.findCrew(pid, userId);
        if (existing.isEmpty()) {
            try {
                CrewData newCrew = CrewData.create(pid, userId, GradeType.LISTENER, countryCode, now);
                return new CrewActivationResult(aggregatePort.saveCrew(newCrew), true);
            } catch (DataIntegrityViolationException e) {
                // INSERT race 패배 — outer tx가 rollback-only 상태. winner row 조회는 별 트랜잭션에서.
                log.info("[ensureCrewActive] CONCURRENT_INSERT_LOSER - userId={}, partyroomId={}",
                        userId, pid.getId());
                CrewData winner = findCrewInNewTransaction(pid, userId);
                return new CrewActivationResult(winner, false);
            }
        }

        // 이미 active — countryCode만 갱신
        CrewData crew = existing.get();
        crew.updateCountryCode(countryCode);
        return new CrewActivationResult(aggregatePort.saveCrew(crew), false);
    }

    /**
     * Outer @Transactional이 rollback-only로 진입한 후에도 안전하게 SELECT 가능하도록 별 트랜잭션 사용.
     * Spring AOP self-invocation 우회를 위해 @Transactional(REQUIRES_NEW) 메서드 호출 대신
     * TransactionTemplate 직접 사용 — 같은 클래스 내부 호출은 proxy를 거치지 않아
     * @Transactional 어노테이션이 무효화되기 때문.
     */
    private CrewData findCrewInNewTransaction(PartyroomId partyroomId, UserId userId) {
        return requiresNewReadOnlyTx.execute(status ->
                aggregatePort.findCrew(partyroomId, userId).orElseThrow()
        );
    }

    private record CrewActivationResult(CrewData crew, boolean transitioned) {}

    private void publishAccessChangedEvent(PartyroomId partyroomId, CrewData crew, UserId userId) {
        eventPublisher.publishEvent(new CrewAccessedEvent(partyroomId, new CrewId(crew.getId()), userId, AccessType.ENTER));
    }

    @Transactional
    public void enterByHost(UserId hostId, PartyroomData partyroom) {
        CrewData crew = CrewData.create(partyroom.getPartyroomId(), hostId, GradeType.HOST, null, LocalDateTime.now(clock));
        aggregatePort.saveCrew(crew);
    }

    @Transactional
    public void exit(PartyroomId partyroomId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId userId = authContext.getUserId();
        log.info("[exit] START - userId={}, partyroomId={}", userId, partyroomId.getId());

        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);

        Optional<CrewData> optionalCrew = aggregatePort.findCrew(partyroomId, userId);
        if (optionalCrew.isEmpty()) {
            log.warn("[exit] INVALID_ACTIVE_ROOM - userId={} has no crew row in partyroomId={}",
                    userId, partyroomId.getId());
            throw ExceptionCreator.create(CrewException.INVALID_ACTIVE_ROOM);
        }

        CrewData crew = optionalCrew.get();
        LocalDateTime now = LocalDateTime.now(clock);

        // Atomic toggle. 0 반환 시 이미 inactive — idempotent return.
        int deactivated = aggregatePort.deactivateCrew(partyroomId, userId, now);
        if (deactivated == 0) {
            log.info("[exit] IDEMPOTENT - already inactive, no event published. userId={}, partyroomId={}",
                    userId, partyroomId.getId());
            return;
        }

        handleDjQueueOnLeave(partyroom, new CrewId(crew.getId()));
        eventPublisher.publishEvent(new CrewAccessedEvent(partyroom.getPartyroomId(), new CrewId(crew.getId()),
                userId, AccessType.EXIT));
    }

    /**
     * Admin-initiated expulsion. 기존 JPA dirty-checking 유지 — caller가 이미 entity 보유,
     * concurrency risk 미미, 추가 enforceBan 로직 묶음 변경 필요.
     */
    @Transactional
    public void expel(PartyroomData partyroom, CrewData crew, boolean isPermanent)  {
        crew.deactivatePresence(LocalDateTime.now(clock));
        if(isPermanent) crew.enforceBan();
        aggregatePort.saveCrew(crew);

        handleDjQueueOnLeave(partyroom, new CrewId(crew.getId()));

        eventPublisher.publishEvent(new CrewAccessedEvent(partyroom.getPartyroomId(), new CrewId(crew.getId()), crew.getUserId(), AccessType.EXIT));
    }

    private void handleDjQueueOnLeave(PartyroomData partyroom, CrewId crewId) {
        boolean wasInDjQueue = aggregatePort.findDj(partyroom.getPartyroomId(), crewId)
                .isPresent();
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroom.getPartyroomId());
        boolean wasCurrentDj = playbackState.isActivated() && wasInDjQueue
                && playbackState.isCurrentDj(crewId);

        partyroomAggregateService.removeDjFromQueue(partyroom.getPartyroomId(), crewId);

        if (wasInDjQueue) {
            eventPublisher.publishEvent(new DjQueueChangedEvent(partyroom.getPartyroomId(), DjChangeType.DEQUEUE_EXIT, crewId));
        }
        if (wasCurrentDj) {
            playbackControlPort.skipPlayback(partyroom.getPartyroomId());
        }
    }
}
```

주요 변경 요약:
- `addOrActivateCrew` 제거 → `ensureCrewActive` 도입 (transitioned flag 반환)
- `tryEnter` same-room re-entry 분기에서 `publishAccessChangedEvent` 호출 제거
- `tryEnter` fall-through 경로에서 `ensureCrewActive`의 `transitioned`가 true일 때만 publish
- `exit` 본문을 `aggregatePort.deactivateCrew` atomic UPDATE 기반으로 재작성 — 0 반환 시 idempotent return
- `expel`은 의도적으로 dirty-checking 유지

#### 13.2 빌드 + 회귀 테스트

- [ ] **Step 2: 컴파일**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava :app:compileTestJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 기존 PartyroomAccessCommandService 관련 회귀**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.adapter.in.web.PartyroomAccessCommandControllerTest" --tests "com.pfplaybackend.api.party.adapter.in.web.PartyroomAccessQueryControllerTest"
```

Expected: BUILD SUCCESSFUL. (기존 controller 테스트가 mock 기반이라 service 내부 로직 변경에 무관해야 함. 만약 깨지면 mock setup이 specific behavior에 의존했던 것 — 적절히 조정.)

- [ ] **Step 4: commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandService.java
git commit -m "$(cat <<'EOF'
refactor(party): PartyroomAccessCommandService — atomic toggle + spurious ENTER fix (PR 7)

tryEnter:
  - addOrActivateCrew helper replaced by ensureCrewActive returning a
    {crew, transitioned} pair. transitioned==true → publish ENTER;
    transitioned==false → idempotent (already active OR concurrent
    INSERT loser).
  - same-room re-entry branch (websocket reconnect path): no longer
    publishes ENTER; only updates countryCode. This was the spurious
    ENTER source identified in spec §7.2 — the moment counter listener
    is wired (Chunk 3), each reconnect would have inflated crew_count.
  - addOrActivateCrew removed.

ensureCrewActive flow:
  1. aggregatePort.activateCrew (atomic UPDATE WHERE is_active=false)
  2. == 1 → transition; update countryCode + publish ENTER
  3. == 0 → existing crew row?
     a. missing → INSERT new crew (UNIQUE blocks concurrent INSERT;
        DataIntegrityViolationException → idempotent loser)
     b. present and active → idempotent (countryCode update only)

exit:
  - aggregatePort.deactivateCrew (atomic UPDATE WHERE is_active=true)
  - == 1 → publish EXIT; == 0 → idempotent return (race loser)
  - INVALID_ACTIVE_ROOM precondition preserved (no row at all).

expel: intentionally NOT migrated. Admin-initiated single-actor flow,
race risk negligible, enforceBan side effect kept under JPA dirty-
checking.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: 동시성 회귀 테스트 (single-JVM thread races)

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomCounterConcurrencyIT.java`
- Create: `app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceRaceIT.java`

**핵심 acceptance test:** 같은 user 100 스레드 동시 enter → `crew_count == 1` (spec §9.3 ★ 표시 행).

#### 14.1 카운터 atomic UPDATE 동시성 테스트

- [ ] **Step 1: `PartyroomCounterConcurrencyIT` 작성**

```java
package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PartyroomCounterConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private PartyroomRepository partyroomRepository;

    private static final int THREAD_COUNT = 100;

    private long createActiveRoom(long hostUid) {
        PartyroomData p = PartyroomData.create(
                "concurrent", "intro", LinkDomain.of("link-conc-" + hostUid),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(hostUid));
        return partyroomRepository.saveAndFlush(p).getId();
    }

    @Test
    @DisplayName("incrementCrewCount — 100 스레드 동시 호출 → crew_count == 100")
    void increment_concurrent() throws Exception {
        long roomId = createActiveRoom(5001L);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        AtomicInteger affectedSum = new AtomicInteger(0);

        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        affectedSum.addAndGet(
                                partyroomRepository.incrementCrewCount(roomId, LocalDateTime.now()));
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        assertThat(affectedSum.get()).isEqualTo(THREAD_COUNT);
        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getCrewCount()).isEqualTo(THREAD_COUNT);
    }

    @Test
    @DisplayName("incrementCrewCount + decrementCrewCount mix — 100 inc + 50 dec → crew_count == 50")
    void increment_decrement_mix() throws Exception {
        long roomId = createActiveRoom(5002L);
        int increments = 100;
        int decrements = 50;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(increments + decrements);

        try {
            for (int i = 0; i < increments; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        partyroomRepository.incrementCrewCount(roomId, LocalDateTime.now());
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            // 사전에 50번 increment를 미리 적용해 음수 가드가 발동하지 않게 함
            for (int i = 0; i < 50; i++) {
                partyroomRepository.incrementCrewCount(roomId, LocalDateTime.now());
            }
            for (int i = 0; i < decrements; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        partyroomRepository.decrementCrewCount(roomId, LocalDateTime.now());
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        // 사전 50 + 동시 100 - 동시 50 = 100
        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getCrewCount()).isEqualTo(100);
    }
}
```

- [ ] **Step 2: 테스트 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.adapter.out.persistence.PartyroomCounterConcurrencyIT"
```

Expected: 2 tests passed. **만약 실패하면** atomic UPDATE 가정이 깨진 결정적 증거 — Chunk 2의 `@Modifying @Query` 정의 재확인 필요.

#### 14.2 PartyroomAccessCommandService end-to-end 동시성 테스트 ★

이 테스트가 spec §9.3 ★ 표시 행 — Race B-reentry + spurious ENTER 두 케이스 모두 차단됐다는 결정적 acceptance test.

- [ ] **Step 3: `PartyroomAccessCommandServiceRaceIT` 작성**

```java
package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PartyroomAccessCommandServiceRaceIT extends AbstractIntegrationTest {

    @Autowired private PartyroomAccessCommandService accessCommandService;
    @Autowired private PartyroomRepository partyroomRepository;

    private long createActiveRoom(long hostUid) {
        PartyroomData p = PartyroomData.create(
                "race", "intro", LinkDomain.of("link-race-" + hostUid),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(hostUid));
        return partyroomRepository.saveAndFlush(p).getId();
    }

    /**
     * 같은 user가 같은 룸에 100번 동시 enter → crew_count는 정확히 1.
     * Race B-first(첫 입장 동시 INSERT) + B-reentry(재입장 toggle 동시 호출) +
     * same-room spurious ENTER (ensure*Active idempotent return) 모두 차단 검증.
     */
    @Test
    @DisplayName("같은 user 100 스레드 동시 tryEnter → crew_count == 1 (★ acceptance test)")
    void same_user_concurrent_enter() throws Exception {
        long roomId = createActiveRoom(6001L);
        UserId userId = new UserId(7001L);
        PartyroomId pid = new PartyroomId(roomId);

        int threadCount = 100;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        // 모든 스레드가 같은 user로 인증된 척 (테스트 픽스처)
                        ThreadLocalContext.setContext(authContextOf(userId));
                        start.await();
                        try {
                            accessCommandService.tryEnter(pid, CountryCode.of("KR"));
                        } catch (Exception e) {
                            // 일부 스레드는 PartyroomEntrySpecification 등에서 예외 가능 — 무시
                            // 핵심은 어떤 race도 carry가 inflate되지 않는 것
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        ThreadLocalContext.clearContext();
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        // AFTER_COMMIT listener는 REQUIRES_NEW 동기 dispatch라 done.await() 후 모든 UPDATE 완료 — sleep 불필요
        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getCrewCount())
                .as("100 동시 enter → crew_count는 정확히 1 (race B + spurious ENTER 차단 검증)")
                .isEqualTo(1);
    }

    private AuthContext authContextOf(UserId userId) {
        // AuthContext: @AllArgsConstructor only (no @Builder). 기존 user 테스트 컨벤션 따름.
        return new AuthContext(userId, AuthorityTier.GT);
    }
}
```

**가능한 함정:**
- `AuthContext`/`ThreadLocalContext` API: `new AuthContext(userId, AuthorityTier)` + `ThreadLocalContext.setContext(obj)` / `clearContext()` — `setAuthContext`/`clear` 메서드 없음. 본 plan은 검증된 코드.
- `accessCommandService.tryEnter`가 내부에서 `partyroomQueryService.getMyActivePartyroom(userId)` 호출 — 이게 트랜잭션 내 다른 sequencer를 거치므로 race가 자연스럽게 발생.
- `AFTER_COMMIT` listener는 `REQUIRES_NEW` 동기 dispatch라 publishing thread에서 즉시 실행 완료. `done.await()` 후 모든 commit + 모든 listener UPDATE 종료 보장 — `Thread.sleep` 불필요. 본 테스트는 sleep 제거된 형태.
- 100 스레드 모두 같은 user로 인증된다는 가정은 production에선 발생 안 함 (보통 다른 user) — 이 테스트는 race 시나리오의 worst case 시뮬레이션.

- [ ] **Step 4: 테스트 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceRaceIT"
```

Expected: 1 test passed (★ acceptance test).

만약 `crew_count != 1` 발생:
- 2 이상 → race가 충분히 차단되지 않음. ensureCrewActive 또는 same-room re-entry 분기 검토
- 0 → enter 자체가 실패. AuthContext 셋업 또는 다른 prerequisite 누락

- [ ] **Step 5: 전체 회귀 테스트**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: commit**

```bash
git add \
  app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomCounterConcurrencyIT.java \
  app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceRaceIT.java
git commit -m "$(cat <<'EOF'
test(party): single-JVM concurrency tests — counter atomicity + race B (PR 7)

PartyroomCounterConcurrencyIT:
- 100 concurrent incrementCrewCount → crew_count == 100 (lost-update guard)
- 100 inc + 50 dec mixed → crew_count == 50 (with 50-pre-increment to
  avoid underflow guard masking the test)

PartyroomAccessCommandServiceRaceIT:
- 100 concurrent tryEnter for same user → crew_count == 1.
  Closes spec §9.3 ★ acceptance: validates Race B-first (UNIQUE blocks
  concurrent INSERT), B-reentry (activateCrew conditional UPDATE),
  AND same-room spurious ENTER (ensureCrewActive idempotent path).

Multi-instance (cross-JVM) simulation is in Chunk 5 as a stretch test.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Chunk 5: PlaybackAggregation atomic UPDATE + 다중 인스턴스 시뮬레이션(stretch) + spec 보완

**Goal of chunk:** 1) `PlaybackAggregation`(좋아요/싫어요/그랩 카운터)에도 같은 atomic UPDATE 패턴을 적용하고 기존 dirty-checking 경로를 제거. 2) 100 스레드 동시 like 동시성 테스트 추가. 3) 다중 인스턴스 시뮬레이션 (stretch — 실패 시 skip OK). 4) spec 문서를 PR 7 reality에 맞춰 catch-up.

**End state of chunk:** PR 7 전 범위 land. spec과 코드 100% 정합.

### Task 15: `PlaybackAggregationRepository` atomic delta + 호출부 마이그레이션

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/PlaybackAggregationRepository.java` (현재 `JpaRepository`만 extend, 메서드 0개)
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/PlaybackCommandService.java` (line 150-155 `updatePlaybackAggregation`)
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/PlaybackAggregationData.java` (line 44-49 `updateAggregation` 제거)

#### 15.1 `PlaybackAggregationRepository`에 atomic delta 메서드

- [ ] **Step 1: 파일 확인**

```bash
cat app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/PlaybackAggregationRepository.java
```

Expected: `extends JpaRepository<PlaybackAggregationData, PlaybackId>` 만 있는 짧은 파일.

- [ ] **Step 2: atomic delta 추가**

```java
package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaybackAggregationRepository extends JpaRepository<PlaybackAggregationData, PlaybackId> {

    /**
     * 좋아요/싫어요/그랩 카운터에 delta 적용. native atomic UPDATE.
     *  - 반환 1: 정상 적용
     *  - 반환 0: row 없음 (호출자 WARN 로그 후 무시)
     * 동시 호출 시 DB row lock으로 직렬화 → lost update 차단.
     * 음수 가드 없음 — like/dislike는 history 기준 delta라 정상 흐름에선 음수 발생 불가.
     * 음수 발생 시 WARN 로그가 history vs counter drift 신호.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PlaybackAggregationData a " +
           "SET a.likeCount = a.likeCount + :deltaLike, " +
           "    a.dislikeCount = a.dislikeCount + :deltaDislike, " +
           "    a.grabCount = a.grabCount + :deltaGrab " +
           "WHERE a.playbackId = :playbackId")
    int applyAggregationDelta(@Param("playbackId") PlaybackId playbackId,
                              @Param("deltaLike") int deltaLike,
                              @Param("deltaDislike") int deltaDislike,
                              @Param("deltaGrab") int deltaGrab);
}
```

#### 15.2 `PlaybackCommandService.updatePlaybackAggregation` 리팩토링

- [ ] **Step 3: line 150-155 메서드 교체**

기존 (line 150-155):
```java
@Transactional
public PlaybackAggregationData updatePlaybackAggregation(PlaybackId playbackId, List<Integer> deltaRecord) {
    PlaybackAggregationData aggregation = playbackAggregationRepository.findById(playbackId).orElseThrow();
    aggregation.updateAggregation(deltaRecord.get(0), deltaRecord.get(1), deltaRecord.get(2));
    return playbackAggregationRepository.save(aggregation);
}
```

신규 (반환 타입 유지 — 호출자가 반환값을 이벤트 publish에 사용):
```java
@Transactional
public PlaybackAggregationData updatePlaybackAggregation(PlaybackId playbackId, List<Integer> deltaRecord) {
    int updated = playbackAggregationRepository.applyAggregationDelta(
            playbackId,
            deltaRecord.get(0),
            deltaRecord.get(1),
            deltaRecord.get(2)
    );
    if (updated == 0) {
        log.warn("[updatePlaybackAggregation] row missing for playbackId={}", playbackId);
    }
    // applyAggregationDelta는 @Modifying(clearAutomatically=true)이므로 1차 캐시 비워짐.
    // findById는 fresh SELECT로 atomic UPDATE 후 최신 카운터 값 반환 → 호출자가 이벤트 publish에 사용.
    return playbackAggregationRepository.findById(playbackId).orElseThrow();
}
```

**시그니처 유지.** 핵심 차이는 내부 구현 — atomic UPDATE 후 reload.

- [ ] **Step 4: 호출자 컨텍스트 확인 (정보용)**

`PlaybackReactionPostProcessCommandService.java:41-42` (확인 결과):
```java
PlaybackAggregationData aggregation = playbackCommandService.updatePlaybackAggregation(...);
publishAggregationChangedEvent(partyroomId, aggregation);   // ← 반환값 사용
```

→ 반환 타입 유지로 호출자 무수정 OK. 이 step은 검증만, 코드 변경 없음.

- [ ] **Step 5: import 정리 — `log` 사용 가능한지 확인**

`PlaybackCommandService.java`가 `@Slf4j`(Lombok) annotation을 가졌는지 확인. 없으면 추가:
```java
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackCommandService { ... }
```

#### 15.3 `PlaybackAggregationData.updateAggregation` 제거 + 종속 테스트 정리

- [ ] **Step 6: dirty-checking 메서드 제거**

`app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/PlaybackAggregationData.java`의 line 44-49 `updateAggregation` 메서드 삭제. 다른 호출자 0건 확인:

```bash
grep -rn "\.updateAggregation(" app/src/
```

Expected: `PlaybackCommandService.java:153` (Step 3에서 이미 제거된 호출) + `PlaybackAggregationDataTest.java` 내부 호출(다음 step에서 정리) 외 0건. 만약 다른 호출자 발견 시 atomic delta 호출로 마이그레이션 후 제거.

- [ ] **Step 6.5: `PlaybackAggregationDataTest` 정리**

기존 4개 테스트 중 3개가 제거된 `updateAggregation`을 호출 → 컴파일 깨짐. 다음 3개 테스트 **삭제**:
- `updateAggregationPositiveDelta` (line 24-37)
- `updateAggregationNegativeDelta` (line 39-53)
- `updateAggregationAccumulates` (line 55-70)

`createForDefaultZero` 테스트는 유지. 결과 파일:

```java
package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.party.domain.value.PlaybackId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackAggregationDataTest {

    @Test
    @DisplayName("createFor — 팩토리 메서드로 생성 시 모든 카운트가 0으로 초기화된다")
    void createForDefaultZero() {
        PlaybackAggregationData aggregation = PlaybackAggregationData.createFor(new PlaybackId(1L));

        assertThat(aggregation.getPlaybackId()).isEqualTo(new PlaybackId(1L));
        assertThat(aggregation.getLikeCount()).isZero();
        assertThat(aggregation.getDislikeCount()).isZero();
        assertThat(aggregation.getGrabCount()).isZero();
    }
}
```

증분 카운터 동작 검증은 본 chunk의 `PlaybackAggregationAtomicUpdateIT` (Step 8) 와 `PlaybackAggregationConcurrencyIT` (Task 16) 가 완전히 대체.

- [ ] **Step 6.6: `PlaybackReactionPostProcessCommandServiceTest` 검증**

해당 테스트는 line 115-116에서 `playbackCommandService.updatePlaybackAggregation(...)` 의 반환값 stub:
```java
when(playbackCommandService.updatePlaybackAggregation(playbackId, List.of(1, 0, 0)))
        .thenReturn(aggregation);
```

본 plan의 Step 3은 **반환 타입을 유지**하기로 결정 → 이 stub은 변경 없이 작동. 테스트 수정 불필요. 단, 기존 단위 테스트가 `PlaybackAggregationData mock(PlaybackAggregationData.class)` 패턴을 사용 → entity 자체는 mock이므로 시그니처 변경 영향 없음. 확인만 하고 진행.

#### 15.4 빌드 + 회귀 테스트

- [ ] **Step 7: 컴파일 + 단위 테스트**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava :app:compileTestJava :app:test --tests "com.pfplaybackend.api.party.application.service.*" --tests "com.pfplaybackend.api.party.domain.*"
```

Expected: BUILD SUCCESSFUL. Step 6.5에서 이미 3개 테스트가 제거됐으므로 컴파일 fail 없음.

- [ ] **Step 8: 통합 테스트 — atomic delta**

`app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/PlaybackAggregationAtomicUpdateIT.java` 신설:

```java
package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class PlaybackAggregationAtomicUpdateIT extends AbstractIntegrationTest {

    @Autowired private PlaybackAggregationRepository repository;

    @Test
    @DisplayName("applyAggregationDelta — 정상 +1/-1, 기존 카운터에 누적")
    void apply_normal() {
        PlaybackId pid = new PlaybackId(80001L);
        repository.saveAndFlush(PlaybackAggregationData.createFor(pid));

        int affected = repository.applyAggregationDelta(pid, 5, 2, 3);

        assertThat(affected).isEqualTo(1);
        PlaybackAggregationData reloaded = repository.findById(pid).orElseThrow();
        assertThat(reloaded.getLikeCount()).isEqualTo(5);
        assertThat(reloaded.getDislikeCount()).isEqualTo(2);
        assertThat(reloaded.getGrabCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("applyAggregationDelta — 음수 delta 누적 (정상)")
    void apply_negative() {
        PlaybackId pid = new PlaybackId(80002L);
        repository.saveAndFlush(PlaybackAggregationData.createFor(pid));
        repository.applyAggregationDelta(pid, 10, 0, 0);

        int affected = repository.applyAggregationDelta(pid, -3, 0, 0);

        assertThat(affected).isEqualTo(1);
        assertThat(repository.findById(pid).orElseThrow().getLikeCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("applyAggregationDelta — 존재하지 않는 playbackId → 0 affected")
    void apply_missing() {
        int affected = repository.applyAggregationDelta(new PlaybackId(999_999_999L), 1, 0, 0);
        assertThat(affected).isZero();
    }
}
```

- [ ] **Step 9: 테스트 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.adapter.out.persistence.PlaybackAggregationAtomicUpdateIT"
```

Expected: 3 tests passed.

- [ ] **Step 10: commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/PlaybackAggregationRepository.java \
  app/src/main/java/com/pfplaybackend/api/party/application/service/PlaybackCommandService.java \
  app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/PlaybackAggregationData.java \
  app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/PlaybackAggregationDataTest.java \
  app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/PlaybackAggregationAtomicUpdateIT.java
git commit -m "$(cat <<'EOF'
feat(party): PlaybackAggregation atomic counter delta — same lost-update fix as crew_count (PR 7)

PlaybackAggregationRepository.applyAggregationDelta: native @Modifying
@Query that adds three deltas in one statement. DB row lock serializes
concurrent like/dislike/grab updates — lost-update window closed.

PlaybackCommandService.updatePlaybackAggregation: replaced read-modify-
write (findById + updateAggregation + save) with applyAggregationDelta
+ post-update findById reload. Return signature kept (PlaybackAggregationData)
so the sole caller (PlaybackReactionPostProcessCommandService) can keep
publishing ReactionAggregationChangedEvent with current counter values.

PlaybackAggregationData.updateAggregation removed — no remaining callers.
PlaybackAggregationDataTest pruned to keep only the createFor factory
assertion; counter-mutation behavior is now exclusively verified at
the repository / integration / concurrency layer.

External dirty-checking entry point eliminated, mirroring the
crewCount/lastActivityAt visibility discipline applied in G1.

Integration tests cover positive / negative-delta / missing-row paths.
Concurrency test (100-thread like) lives in next task with the
multi-instance setup.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 16: PlaybackAggregation 동시성 테스트

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/PlaybackAggregationConcurrencyIT.java`

#### 16.1 100 스레드 동시 like 테스트

- [ ] **Step 1: 테스트 작성**

```java
package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackAggregationConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private PlaybackAggregationRepository repository;

    private static final int THREAD_COUNT = 100;

    @Test
    @DisplayName("applyAggregationDelta — 100 스레드 동시 like → likeCount == 100")
    void concurrent_likes() throws Exception {
        PlaybackId pid = new PlaybackId(81001L);
        repository.saveAndFlush(PlaybackAggregationData.createFor(pid));

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);

        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        repository.applyAggregationDelta(pid, 1, 0, 0);
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        PlaybackAggregationData reloaded = repository.findById(pid).orElseThrow();
        assertThat(reloaded.getLikeCount())
                .as("100 동시 +1 → likeCount는 정확히 100 (lost-update 가드 검증)")
                .isEqualTo(THREAD_COUNT);
        assertThat(reloaded.getDislikeCount()).isZero();
        assertThat(reloaded.getGrabCount()).isZero();
    }
}
```

- [ ] **Step 2: 테스트 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.adapter.out.persistence.PlaybackAggregationConcurrencyIT"
```

Expected: 1 test passed. `likeCount < 100` 발생 시 atomic UPDATE 가정이 깨짐 — Step 2 (Task 15)의 `@Modifying @Query` 정의 재확인.

- [ ] **Step 3: commit**

```bash
git add app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/PlaybackAggregationConcurrencyIT.java
git commit -m "$(cat <<'EOF'
test(party): PlaybackAggregation 100-thread like race — lost-update guard (PR 7)

Validates applyAggregationDelta serializes concurrent +1 likes via DB
row lock. Mirrors crew_count concurrency test — same atomic pattern,
same expected invariant.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 17: 다중 인스턴스 시뮬레이션 (stretch) — 실패 시 skip OK

**Goal:** 같은 Testcontainers MySQL을 두 개의 독립 EntityManagerFactory가 가리키게 만들어 cross-JVM race를 부분적으로 시뮬레이션. spec §9.4에 따라 stretch 분류 — 셋업이 flaky이면 단일 컨텍스트 동시성(`Task 14`/`Task 16`)이 핵심 invariant를 이미 커버하므로 skip 허용.

**Files:**
- Create (stretch): `app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/MultiContextCounterIT.java`

#### 17.1 두 EntityManagerFactory + 같은 DB 시뮬레이션

- [ ] **Step 1: feasibility 점검**

본 task는 **stretch**. 시도 전 다음 확인:
- `AbstractIntegrationTest`가 노출하는 Testcontainers MySQL 컨테이너 hostname/port를 가져올 수 있는지
- `LocalContainerEntityManagerFactoryBean` 또는 `EntityManagerFactoryBuilder`로 별 EMF를 같은 datasource에 연결 가능한지

**Note:** 이 시뮬레이션은 진정한 cross-JVM이 아닌 "같은 JVM 내 두 독립 persistence context" 시뮬레이션. DB row lock 검증이라는 1차 가치는 동일하지만 Spring 컨텍스트 분리 셋업이 까다로움. 30분 시도 후 셋업 안 되면 **skip + 본 task 삭제**, 단 §9.4에 다음 노트 남김:
> "Multi-instance simulation skipped — single-context concurrency tests (Task 14, 16) cover core invariants. Production multi-node race coverage relies on operational monitoring."

- [ ] **Step 2: skip 결정 시 — task 17 본 entry 삭제 후 chunk 5 commit history에 노트 추가**

만약 skip 결정 시:
```bash
# 파일 미생성 → commit 없음. 본 plan 문서의 Task 17만 "Skipped" 마킹.
echo "Task 17 stretch skipped — see Step 1 rationale" >&2
```

- [ ] **Step 3: 시도 결정 시 — 셋업 코드 작성** (옵션)

이 단계는 stretch 범위라 상세 코드 sketch만 제공 — 실제 구현 시 trial-and-error 예상:

```java
package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
// ... imports ...

class MultiContextCounterIT extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;   // 같은 Testcontainers MySQL
    @Autowired private EntityManagerFactoryBuilder builder;

    private LocalContainerEntityManagerFactoryBean buildSecondEmf() {
        // 같은 dataSource를 가리키는 두 번째 EMF
        // ... Hibernate properties 설정, packagesToScan ...
    }

    @Test
    @DisplayName("stretch — 두 EMF가 같은 partyroom에 동시 increment → 합산 정확")
    void multi_context_increment() {
        // EMF A의 PartyroomRepository와 EMF B의 PartyroomRepository를 각각 만들어
        // 양쪽에서 50번씩 increment → 최종 crew_count == 100 검증
        // ...
    }
}
```

실패 시 (Spring 셋업 충돌, 트랜잭션 매니저 충돌 등) 즉시 stop. 본 task 미완료가 PR 7 진행에 영향 주지 않음 — Task 14/16의 단일 컨텍스트 테스트가 핵심 race 가드.

---

### Task 18: spec 문서 catch-up — PR 7 reality 반영

**Files:**
- Modify: `docs/superpowers/specs/2026-04-27-admin-platform-pr7-design.md`

본 plan 작성 중 발견된 차이점들을 spec 문서에 반영 — 후속 PR/리뷰가 일관된 출처에서 작업하도록.

#### 18.1 §9.2 contradiction 정정

- [ ] **Step 1: §9.2 `findActiveHostRoom` 행 정정**

현재 spec §9.2 표:
```
| `findActiveHostRoom` | TERMINATED 제외, SUSPENDED 제외 |
```
→
```
| `findActiveHostRoom` | TERMINATED 제외, SUSPENDED는 **포함** (호스트의 새 룸 생성을 차단하기 위한 §6.4(a) 결정 — 의미 정정) |
```

reasoning: 본 plan Task 5/6의 JPQL/QueryDSL 변경은 `status <> TERMINATED` (SUSPENDED 포함). spec §9.2의 "SUSPENDED 제외" 표기는 §5.3 / §6.4(a) 본문과 모순. plan은 §6.4(a) 본문 결정을 따랐고 이게 의도된 결정. spec §9.2 수치를 본문에 맞춰 정정.

#### 18.2 §4.2 신규 예외 클래스명 보강

- [ ] **Step 2: §4.2 보강**

§4.2에서 `IllegalPartyroomStateException` 별 클래스로 명명한 부분 옆에 다음 노트 추가:
> "구현은 기존 `PartyroomException` enum 패턴(`DomainException` 구현)을 재사용하여 `ILLEGAL_STATE_TRANSITION` (코드 PTR-007, ErrorType.CONFLICT) 한 행 추가. 의미 동일, 코드베이스 컨벤션 정합성 우선. (plan §4.2 참조.)"

#### 18.3 §10 deploy 노트

- [ ] **Step 3: §10 G1 행 보강**

§10 표 G1 행 마지막에 추가:
> "**배포 순서: V6 SQL 적용과 새 jar 배포는 분리 불가 — 같은 deploy unit으로 진행.** 컬럼만 적용된 상태에서 구버전 jar가 부팅하면 boot 실패."

#### 18.4 §12 risk 12.3 / 12.4 해소 마킹

- [ ] **Step 4: §12 risk 표 행 12.3/12.4 strikeout 확인**

이미 spec iteration 2 리뷰에서 strikeout 적용됨. plan 작성 중 추가로 확인 — `crew.deactivatePresence()` toggle 시맨틱 / `getPartyroomId()` 노출 모두 plan 코드로 검증됨. 추가 변경 불필요.

#### 18.5 commit

- [ ] **Step 5: spec 문서 commit**

```bash
git add docs/superpowers/specs/2026-04-27-admin-platform-pr7-design.md
git commit -m "$(cat <<'EOF'
docs(spec): catch up PR 7 design to plan reality

Three small corrections discovered while writing the implementation plan:

1. §9.2 findActiveHostRoom row: was "SUSPENDED 제외", actual decision per
   §6.4(a) is "SUSPENDED 포함" (block re-creation while admin-suspended).
   Fix the §9.2 entry to match the §5.3 / §6.4(a) body.

2. §4.2 IllegalPartyroomStateException naming: implementation reuses the
   existing PartyroomException enum pattern with code PTR-007. Add note
   so future readers don't search for a class that doesn't exist.

3. §10 G1 row: explicit deploy ordering — V6 SQL + new jar must land as
   one deploy unit; column-without-jar boots into compile/runtime failure.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final verification — plan 끝, 머지 전 체크

PR 7의 모든 task 완료 후:

- [ ] **Step 1: 전체 회귀 테스트**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test
```

Expected: BUILD SUCCESSFUL. 단 한 개의 테스트도 fail 없음.

- [ ] **Step 2: PR 7 commit count 확인**

```bash
git log --oneline 5bcfc9c9..HEAD
```

Expected commit 시퀀스 (대략 12-14개):
1. spec 작성 (`5f9ae8d3`)
2. G1: V6 + enums + entity (G1 묶음)
3. G2: repository status 시맨틱 + atomic UPDATE 메서드 (G2 묶음)
4. isTerminated → isActive 호출부 정정
5. atomic UPDATE 통합 테스트
6. PartyroomEntrySpecification SUSPENDED 거부
7. PartyroomCounterListener + 통합 테스트
8. crew atomic toggle (CrewRepository + Port + Adapter + IT)
9. PartyroomAccessCommandService 리팩토링 (spurious ENTER 제거)
10. 동시성 테스트 (counter + access service race)
11. PlaybackAggregation atomic delta + 호출부 + 단위/통합 테스트
12. PlaybackAggregation 동시성 테스트
13. (옵션) Multi-instance simulation
14. spec catch-up

- [ ] **Step 3: spec § acceptance — ★ 표시 행 검증**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceRaceIT.same_user_concurrent_enter"
```

Expected: PASS — `crew_count == 1`. **이 테스트가 fail이면 PR 7의 핵심 acceptance가 깨진 것 — 머지 차단.**

- [ ] **Step 4: superpowers:finishing-a-development-branch 스킬로 머지 결정**

PR 머지 / PR 생성 / 추가 작업 등 마무리 옵션 결정.

---

**End of plan.**


---

