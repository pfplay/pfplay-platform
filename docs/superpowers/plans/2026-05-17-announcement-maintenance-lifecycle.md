# 점검 공지 라이프사이클 보강 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 점검 공지에 cron 자동 정상종료 + ACTIVE 한정 종료시각 조정 + 철회(cancelled)/정상완료(completed) 구분을 추가한다.

**Architecture:** 기존 nullable-timestamp 도메인 모델을 점진 확장(접근법 A). V17로 `completed_at` 컬럼 추가, 도메인 메서드 2개(`markCompleted`/`adjustScheduledEndTime`) + 신규 `MaintenanceEndedEvent` + 스케줄러 완료 패스 + REST 2개(PATCH `/schedule`, POST `/complete`). pfplay-admin은 상태배지 + 조정/완료 다이얼로그. pfplay-web 무변경.

**Tech Stack:** Spring Boot (Java 21, Gradle), Flyway, JPA/QueryDSL, JUnit5/Mockito/MockMvc · React+Vite+TS, react-query, zod, vitest/RTL/MSW.

**Spec:** `docs/superpowers/specs/2026-05-17-announcement-maintenance-lifecycle-design.md` · **Issue:** pfplay-platform#218 · **Branch:** `feature/announcement-maintenance-lifecycle`

**빌드/테스트 전제:**
- 백엔드 gradle 호출은 **반드시** `JAVA_HOME` prefix: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew ...` (memory `reference_pfplay_platform_jdk`).
- 백엔드 단위 테스트: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "FQCN"` (`pfplay-platform/` 에서).
- admin 테스트: `pfplay-admin/` 에서 `yarn vitest run <path>`.
- 커밋 메시지·이슈·PR 전부 **한글** (memory `feedback_korean_issue_commit_pr`). 커밋 trailer 유지.
- TDD 엄수: RED(테스트 작성→실패 확인)→GREEN(최소 구현→통과)→commit. @superpowers:test-driven-development

---

## File Structure

**pfplay-platform (`app/src/main/java/com/pfplaybackend/api/administration/`)**
- `domain/exception/AnnouncementException.java` — ANN-006/007/008 추가
- `domain/entity/data/SystemAnnouncementData.java` — `completedAt` 필드 + `markCompleted` + `adjustScheduledEndTime` + `isMaintenancePhaseActive` 보강
- `domain/event/MaintenanceEndedEvent.java` — 신규 record
- `adapter/out/persistence/SystemAnnouncementRepository.java` — `findCurrentMaintenance` 보강 + `findDueForMaintenanceCompletion`
- `application/service/SystemAnnouncementCommandService.java` — `adjustEndTime` + `complete`
- `application/service/MaintenanceSchedulerService.java` — `completeExpiredMaintenance` cron
- `adapter/out/event/AnnouncementBroadcaster.java` — `on(MaintenanceEndedEvent)`
- `adapter/in/web/AdminAnnouncementController.java` — PATCH `/{id}/schedule`, POST `/{id}/complete`
- `adapter/in/web/payload/request/` — `AdjustScheduleRequest.java` 신규
- `adapter/in/web/payload/response/AnnouncementSummaryResponse.java` — `completedAt` 필드
- `app/src/main/resources/db/migration/V17__add_completed_at_to_system_announcement.sql` — 신규

**pfplay-admin (`src/`)**
- `entities/announcement/model/types.ts` — `completedAt` + 헤더 주석 갱신
- `shared/lib/labels.ts` — `ANNOUNCEMENT_DERIVED_STATUS` 매핑 신규
- `features/announcements/api/announcements-api.ts` — `adjustAnnouncementSchedule`, `completeAnnouncement`
- `features/announcements/api/use-adjust-schedule.ts`, `use-complete-announcement.ts` — 신규 hook
- `features/announcements/ui/announcements-table.tsx` — 상태배지 + 액션 분기
- `features/announcements/ui/adjust-schedule-dialog.tsx`, `complete-announcement-dialog.tsx` — 신규
- `features/announcements/ui/cancel-announcement-dialog.tsx` — 문구 갱신
- `widgets/announcements-history.tsx` — 신규 다이얼로그/상태 wiring (**`AnnouncementsHistoryContent` 가 실제 `AnnouncementsTable`+`CancelAnnouncementDialog`+`cancelTarget` state 보유**. `pages/announcements-history-page.tsx` 는 5줄 shell — 건드리지 말 것)
- test mocks: `test/mocks/handlers/announcements.ts`, `test/mocks/fixtures/announcements.ts`
- `widgets/__tests__/announcements-history.test.tsx` — 기존 위젯 테스트(있으면 신규 target/onCancelClick 변경 반영)

각 chunk 끝에서 plan-document-reviewer 디스패치 → ✅ 까지 수정 후 다음 chunk.

---

## Chunk 1: 백엔드 도메인 + 스키마 + 리포지토리

### Task 1: V17 마이그레이션 — `completed_at` 컬럼

**Files:**
- Create: `app/src/main/resources/db/migration/V17__add_completed_at_to_system_announcement.sql`

- [ ] **Step 1: 마이그레이션 작성**

```sql
ALTER TABLE system_announcement
  ADD COLUMN completed_at DATETIME NULL AFTER cancelled_at;
```

- [ ] **Step 2: 슬롯 확인** — `app/src/main/resources/db/migration/` 에 V16 가 최신, V17 없음 확인 (`ls`/Glob). 슬롯 점프 시 memory `feedback_flyway_slot_renumber` 정책.

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/resources/db/migration/V17__add_completed_at_to_system_announcement.sql
git commit -m "feat(announcement): V17 completed_at 컬럼 추가 (#218)"
```

### Task 2: `AnnouncementException` — ANN-006/007/008

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/domain/exception/AnnouncementException.java`

- [ ] **Step 1: enum 상수 추가** — 기존 ANN-005 줄 끝 `;` 를 `,` 로 바꾸고 아래 3개 추가 (`ErrorType` import 이미 존재):

```java
    SCHEDULED_START_IN_PAST("ANN-005", "예약 시작 시각은 미래여야 합니다.", ErrorType.BAD_REQUEST),
    INVALID_END_ADJUSTMENT("ANN-006", "조정할 종료 시각은 현재 이후여야 합니다.", ErrorType.BAD_REQUEST),
    NOT_ACTIVE_MAINTENANCE("ANN-007", "진행 중인 점검 공지가 아닙니다.", ErrorType.CONFLICT),
    ALREADY_COMPLETED("ANN-008", "이미 정상 종료된 점검 공지입니다.", ErrorType.CONFLICT);
```

- [ ] **Step 2: 컴파일 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/domain/exception/AnnouncementException.java
git commit -m "feat(announcement): ANN-006/007/008 예외 코드 추가 (#218)"
```

### Task 3: 도메인 — `markCompleted` / `adjustScheduledEndTime` / `isMaintenancePhaseActive` 보강

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/SystemAnnouncementData.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/domain/entity/data/SystemAnnouncementDataTest.java` (없으면 생성)

- [ ] **Step 1: 실패 테스트 작성** — 신규 메서드 동작. `create()` 후 리플렉션 없이 도메인 메서드만으로 ACTIVE 상태를 만들 수 없으므로(=`markMaintenanceStarted` 가 ACTIVE 진입 유일 경로) `markMaintenanceStarted(clock)` 로 ACTIVE 만든 뒤 검증. 고정 `Clock` 사용.

테스트 파일에 추가(없으면 신규, 패키지 `...domain.entity.data`):

```java
package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.assertj.core.api.Assertions.*;

class SystemAnnouncementDataTest {

    private static final ZoneId Z = ZoneId.of("Asia/Seoul");

    private Clock clockAt(LocalDateTime t) {
        return Clock.fixed(t.atZone(Z).toInstant(), Z);
    }

    private SystemAnnouncementData activeMaintenance(LocalDateTime start, LocalDateTime end, Clock startedClock) {
        SystemAnnouncementData e = SystemAnnouncementData.create(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검", "maint", "본문", "body",
                start, end, null, start.minusHours(1), 1L);
        e.markMaintenanceStarted(startedClock);
        return e;
    }

    @Test
    @DisplayName("markCompleted — ACTIVE면 completedAt set")
    void markCompleted_active_setsCompletedAt() {
        LocalDateTime start = LocalDateTime.of(2026, 5, 4, 3, 0);
        LocalDateTime end = LocalDateTime.of(2026, 5, 4, 4, 0);
        SystemAnnouncementData e = activeMaintenance(start, end, clockAt(start));

        e.markCompleted(clockAt(end));

        assertThat(e.getCompletedAt()).isEqualTo(end);
        assertThat(e.isMaintenancePhaseActive()).isFalse();
    }

    @Test
    @DisplayName("markCompleted — 이미 completed면 ANN-008(Conflict)")
    void markCompleted_alreadyCompleted_throwsConflict() {
        LocalDateTime start = LocalDateTime.of(2026, 5, 4, 3, 0);
        SystemAnnouncementData e = activeMaintenance(start, start.plusHours(1), clockAt(start));
        e.markCompleted(clockAt(start.plusMinutes(30)));

        assertThatThrownBy(() -> e.markCompleted(clockAt(start.plusMinutes(40))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ANN-008");
    }

    @Test
    @DisplayName("markCompleted — PLANNED(미시작)면 ANN-007(Conflict)")
    void markCompleted_planned_throwsConflict() {
        SystemAnnouncementData e = SystemAnnouncementData.create(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검", "m", "b", "b",
                LocalDateTime.of(2026, 5, 4, 3, 0), LocalDateTime.of(2026, 5, 4, 4, 0),
                null, LocalDateTime.of(2026, 5, 4, 2, 0), 1L);

        assertThatThrownBy(() -> e.markCompleted(clockAt(LocalDateTime.of(2026, 5, 4, 2, 30))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ANN-007");
    }

    @Test
    @DisplayName("adjustScheduledEndTime — ACTIVE & newEnd>now면 갱신")
    void adjust_active_futureEnd_updates() {
        LocalDateTime start = LocalDateTime.of(2026, 5, 4, 3, 0);
        SystemAnnouncementData e = activeMaintenance(start, start.plusHours(1), clockAt(start));
        LocalDateTime now = start.plusMinutes(50);
        LocalDateTime newEnd = start.plusHours(2);

        e.adjustScheduledEndTime(newEnd, clockAt(now));

        assertThat(e.getScheduledEndAt()).isEqualTo(newEnd);
    }

    @Test
    @DisplayName("adjustScheduledEndTime — newEnd<=now면 ANN-006(BadRequest)")
    void adjust_pastEnd_throwsBadRequest() {
        LocalDateTime start = LocalDateTime.of(2026, 5, 4, 3, 0);
        SystemAnnouncementData e = activeMaintenance(start, start.plusHours(1), clockAt(start));
        LocalDateTime now = start.plusMinutes(50);

        assertThatThrownBy(() -> e.adjustScheduledEndTime(now, clockAt(now)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ANN-006");
    }

    @Test
    @DisplayName("adjustScheduledEndTime — PLANNED면 ANN-007(Conflict)")
    void adjust_planned_throwsConflict() {
        SystemAnnouncementData e = SystemAnnouncementData.create(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검", "m", "b", "b",
                LocalDateTime.of(2026, 5, 4, 3, 0), LocalDateTime.of(2026, 5, 4, 4, 0),
                null, LocalDateTime.of(2026, 5, 4, 2, 0), 1L);

        assertThatThrownBy(() -> e.adjustScheduledEndTime(
                LocalDateTime.of(2026, 5, 4, 5, 0), clockAt(LocalDateTime.of(2026, 5, 4, 2, 30))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ANN-007");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementDataTest"`
Expected: FAIL — `markCompleted`/`adjustScheduledEndTime`/`getCompletedAt` 미정의 컴파일 에러

- [ ] **Step 3: 도메인 구현** — `SystemAnnouncementData.java` 에 `cancelledByAdministratorId` 필드 다음 줄에 추가:

```java
    @Column(name = "completed_at") private LocalDateTime completedAt;
```

`isMaintenancePhaseActive()` 를 교체:

```java
    public boolean isMaintenancePhaseActive() {
        return type == AnnouncementType.MAINTENANCE_NOTICE
                && maintenanceStartedAt != null && cancelledAt == null && completedAt == null;
    }
```

`isMaintenancePhaseActive()` 아래에 추가 (import `com.pfplaybackend.api.administration.domain.exception.AnnouncementException;` 와 `ExceptionCreator` 는 파일 상단에 이미 있음):

```java
    public void markCompleted(Clock clock) {
        if (completedAt != null)
            throw ExceptionCreator.create(AnnouncementException.ALREADY_COMPLETED);
        if (!isMaintenancePhaseActive())
            throw ExceptionCreator.create(AnnouncementException.NOT_ACTIVE_MAINTENANCE);
        this.completedAt = LocalDateTime.now(clock);
    }

    public void adjustScheduledEndTime(LocalDateTime newEnd, Clock clock) {
        if (!isMaintenancePhaseActive())
            throw ExceptionCreator.create(AnnouncementException.NOT_ACTIVE_MAINTENANCE);
        if (newEnd == null || !newEnd.isAfter(LocalDateTime.now(clock)))
            throw ExceptionCreator.create(AnnouncementException.INVALID_END_ADJUSTMENT);
        this.scheduledEndAt = newEnd;
    }
```

> 가드 순서 주의: `markCompleted` 는 completed 먼저(중복→ANN-008), 그다음 ACTIVE 여부(→ANN-007). raw `IllegalStateException` 금지 — REST 도달 경로라 500 됨(spec §2).

- [ ] **Step 4: 테스트 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementDataTest"`
Expected: PASS (6/6), 출력 클린

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/SystemAnnouncementData.java app/src/test/java/com/pfplaybackend/api/administration/domain/entity/data/SystemAnnouncementDataTest.java
git commit -m "feat(announcement): markCompleted/adjustScheduledEndTime 도메인 메서드 (#218)"
```

### Task 4: 리포지토리 — `findCurrentMaintenance` 보강 + `findDueForMaintenanceCompletion`

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/SystemAnnouncementRepository.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/SystemAnnouncementRepositoryTest.java` (없으면 생성; `@DataJpaTest` 패턴 — 기존 repo 테스트가 있으면 그 패턴 mirror, 없으면 신규 `@DataJpaTest`)

- [ ] **Step 1: 실패 테스트 작성** — `findDueForMaintenanceCompletion` 가 (a) ACTIVE & end<=now 만 반환, (b) cancelled/completed/미시작 제외. `findCurrentMaintenance` 가 completed row 제외.

```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class SystemAnnouncementRepositoryTest {

    @Autowired SystemAnnouncementRepository repository;

    private static final ZoneId Z = ZoneId.of("Asia/Seoul");
    private Clock clockAt(LocalDateTime t) { return Clock.fixed(t.atZone(Z).toInstant(), Z); }

    private SystemAnnouncementData maintenance(LocalDateTime start, LocalDateTime end) {
        return SystemAnnouncementData.create(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검", "m", "b", "b", start, end, null, start.minusHours(1), 1L);
    }

    @Test
    @DisplayName("findDueForMaintenanceCompletion — ACTIVE & end<=now 만 반환")
    void completion_returnsActiveExpiredOnly() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 4, 4, 0);

        SystemAnnouncementData due = maintenance(now.minusHours(1), now.minusMinutes(1));
        due.markMaintenanceStarted(clockAt(now.minusHours(1)));
        SystemAnnouncementData notExpired = maintenance(now.minusHours(1), now.plusHours(1));
        notExpired.markMaintenanceStarted(clockAt(now.minusHours(1)));
        SystemAnnouncementData notStarted = maintenance(now.plusHours(1), now.plusHours(2));
        repository.saveAll(List.of(due, notExpired, notStarted));

        List<SystemAnnouncementData> result = repository.findDueForMaintenanceCompletion(now);

        assertThat(result).extracting(SystemAnnouncementData::getId).containsExactly(due.getId());
    }

    @Test
    @DisplayName("findDueForMaintenanceCompletion — cancelled/completed 제외")
    void completion_excludesCancelledCompleted() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 4, 4, 0);

        SystemAnnouncementData cancelled = maintenance(now.minusHours(1), now.minusMinutes(1));
        cancelled.markMaintenanceStarted(clockAt(now.minusHours(1)));
        cancelled.cancel(1L, clockAt(now.minusMinutes(30)));
        SystemAnnouncementData completed = maintenance(now.minusHours(1), now.minusMinutes(1));
        completed.markMaintenanceStarted(clockAt(now.minusHours(1)));
        completed.markCompleted(clockAt(now.minusMinutes(20)));
        repository.saveAll(List.of(cancelled, completed));

        assertThat(repository.findDueForMaintenanceCompletion(now)).isEmpty();
    }

    @Test
    @DisplayName("findCurrentMaintenance — completed row 제외")
    void current_excludesCompleted() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 4, 3, 0);
        SystemAnnouncementData completed = maintenance(base, base.plusHours(1));
        completed.markMaintenanceStarted(clockAt(base));
        completed.markCompleted(clockAt(base.plusMinutes(30)));
        repository.save(completed);

        assertThat(repository.findCurrentMaintenance()).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepositoryTest"`
Expected: FAIL — `findDueForMaintenanceCompletion` 미정의 / `findCurrentMaintenance` 가 completed 반환

- [ ] **Step 3: 리포지토리 구현** — `findCurrentMaintenance` 쿼리에 `AND a.completedAt IS NULL` 추가:

```java
    @Query("""
        SELECT a FROM SystemAnnouncementData a
        WHERE a.type = com.pfplaybackend.api.administration.domain.value.AnnouncementType.MAINTENANCE_NOTICE
          AND a.maintenanceStartedAt IS NOT NULL AND a.cancelledAt IS NULL AND a.completedAt IS NULL
        """)
    Optional<SystemAnnouncementData> findCurrentMaintenance();
```

`findCurrentMaintenance` 아래에 신규 추가:

```java
    @Query("""
        SELECT a FROM SystemAnnouncementData a
        WHERE a.type = com.pfplaybackend.api.administration.domain.value.AnnouncementType.MAINTENANCE_NOTICE
          AND a.maintenanceStartedAt IS NOT NULL
          AND a.cancelledAt IS NULL AND a.completedAt IS NULL
          AND a.scheduledEndAt <= :now
        """)
    List<SystemAnnouncementData> findDueForMaintenanceCompletion(@Param("now") LocalDateTime now);
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepositoryTest"`
Expected: PASS (3/3)

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/SystemAnnouncementRepository.java app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/SystemAnnouncementRepositoryTest.java
git commit -m "feat(announcement): findDueForMaintenanceCompletion + findCurrentMaintenance completed 제외 (#218)"
```

---

## Chunk 2: 백엔드 이벤트 + 스케줄러 + CommandService

### Task 5: `MaintenanceEndedEvent` record

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/event/MaintenanceEndedEvent.java`

- [ ] **Step 1: 작성** (`MaintenanceStartedEvent` mirror):

```java
package com.pfplaybackend.api.administration.domain.event;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;

public record MaintenanceEndedEvent(SystemAnnouncementData entity) {}
```

- [ ] **Step 2: 컴파일 확인** — `JAVA_HOME=... ./gradlew :app:compileJava` → SUCCESSFUL
- [ ] **Step 3: 커밋** — `git add ...event/MaintenanceEndedEvent.java && git commit -m "feat(announcement): MaintenanceEndedEvent (#218)"`

### Task 6: `AnnouncementBroadcaster` — `on(MaintenanceEndedEvent)`

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/event/AnnouncementBroadcaster.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/adapter/out/event/AnnouncementBroadcasterTest.java` (있으면 확장, 없으면 신규 — Mockito `SimpMessagingTemplate`/`EdgeConfigPort` mock)

- [ ] **Step 1: 실패 테스트 작성** — `on(MaintenanceEndedEvent)` 가 (a) `MAINTENANCE_ENDED` payload 를 `/sub/system/announcements` 로 발사, (b) `edgeConfigPort.writeMaintenance(null, null)` 호출.

```java
package com.pfplaybackend.api.administration.adapter.out.event;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.MaintenanceEndedEvent;
import com.pfplaybackend.api.administration.domain.port.EdgeConfigPort;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.*;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnnouncementBroadcasterTest {

    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock EdgeConfigPort edgeConfigPort;
    @InjectMocks AnnouncementBroadcaster broadcaster;

    @Test
    void maintenanceEnded_broadcastsDismissAndDeletesEdgeConfig() {
        ZoneId z = ZoneId.of("Asia/Seoul");
        Clock clock = Clock.fixed(LocalDateTime.of(2026,5,4,3,0).atZone(z).toInstant(), z);
        SystemAnnouncementData e = SystemAnnouncementData.create(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검","m","b","b",
                LocalDateTime.of(2026,5,4,3,0), LocalDateTime.of(2026,5,4,4,0),
                null, LocalDateTime.of(2026,5,4,2,0), 1L);
        e.markMaintenanceStarted(clock);
        e.markCompleted(Clock.fixed(LocalDateTime.of(2026,5,4,4,0).atZone(z).toInstant(), z));

        broadcaster.on(new MaintenanceEndedEvent(e));

        ArgumentCaptor<Map<String,Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/sub/system/announcements"), cap.capture());
        assertThat(cap.getValue().get("eventType")).isEqualTo("MAINTENANCE_ENDED");
        verify(edgeConfigPort).writeMaintenance(null, null);
    }
}
```

- [ ] **Step 2: 실패 확인** — `JAVA_HOME=... ./gradlew :app:test --tests "...AnnouncementBroadcasterTest"` → FAIL (`on(MaintenanceEndedEvent)` 없음)

- [ ] **Step 3: 구현** — `AnnouncementBroadcaster.java` 의 `on(MaintenanceStartedEvent)` 다음에 추가, import `MaintenanceEndedEvent`:

```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(MaintenanceEndedEvent event) {
        SystemAnnouncementData e = event.entity();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "MAINTENANCE_ENDED");
        payload.put("announcementId", e.getId());
        payload.put("completedAt", e.getCompletedAt());
        messagingTemplate.convertAndSend(TOPIC, payload);
        tryWriteEdgeConfig(null, null);
    }
```

- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: 커밋** — `git commit -m "feat(announcement): MaintenanceEndedEvent 브로드캐스트 + Edge Config 삭제 (#218)"`

### Task 7: 스케줄러 — `completeExpiredMaintenance`

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/application/service/MaintenanceSchedulerService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/application/service/MaintenanceSchedulerServiceTest.java` (있으면 확장)

- [ ] **Step 1: 실패 테스트** — due 있으면 각 entity `markCompleted` + `MaintenanceEndedEvent` 발행, 없으면 no-op. (`promoteScheduledMaintenance` 기존 테스트 패턴 mirror — repository/eventPublisher mock + fixed Clock.)

```java
@Test
void completeExpiredMaintenance_due_marksAndPublishes() {
    LocalDateTime now = LocalDateTime.of(2026,5,4,4,0);
    // service 의 Clock 이 now 를 가리키도록 테스트 셋업(기존 promote 테스트의 clock stub 패턴 재사용)
    SystemAnnouncementData e = Mockito.spy(/* ACTIVE & end<=now maintenance, 기존 헬퍼 재사용 */);
    given(repository.findDueForMaintenanceCompletion(now)).willReturn(List.of(e));

    service.completeExpiredMaintenance();

    verify(e).markCompleted(any(Clock.class));
    verify(eventPublisher).publishEvent(any(MaintenanceEndedEvent.class));
}

@Test
void completeExpiredMaintenance_none_noop() {
    given(repository.findDueForMaintenanceCompletion(any())).willReturn(List.of());
    service.completeExpiredMaintenance();
    verifyNoInteractions(eventPublisher);
}
```
> 기존 `MaintenanceSchedulerServiceTest` 의 Clock/엔티티 생성 헬퍼를 그대로 재사용할 것. 없으면 `SystemAnnouncementDataTest` 의 `activeMaintenance` 패턴 복제.

- [ ] **Step 2: 실패 확인** → FAIL (`completeExpiredMaintenance` 없음)

- [ ] **Step 3: 구현** — `promoteScheduledMaintenance` 아래 추가 (import `MaintenanceEndedEvent`):

```java
    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void completeExpiredMaintenance() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<SystemAnnouncementData> due = repository.findDueForMaintenanceCompletion(now);
        if (due.isEmpty()) {
            return;
        }
        for (SystemAnnouncementData entity : due) {
            entity.markCompleted(clock);
            eventPublisher.publishEvent(new MaintenanceEndedEvent(entity));
        }
    }
```
> per-row try/catch 안 함 — 기존 `promoteScheduledMaintenance` 컨벤션 유지. race 패자면 도메인 예외가 해당 tick `@Transactional` 롤백, 다음 tick 재시도(쿼리 필터가 남은 row 만 반환). spec §에러.

- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: 커밋** — `git commit -m "feat(announcement): scheduled_end_at 도래 자동 정상종료 cron (#218)"`

### Task 8: `SystemAnnouncementCommandService` — `adjustEndTime` / `complete`

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/application/service/SystemAnnouncementCommandService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/application/service/SystemAnnouncementCommandServiceTest.java` (있으면 확장, 없으면 신규 — repository/eventPublisher/edgeConfigPort? CommandService 는 현재 edgeConfigPort 의존 없음 → `adjustEndTime` 만 edgeConfigPort 직접 호출 필요하므로 **EdgeConfigPort 를 CommandService 에 주입 추가**)

- [ ] **Step 1: 실패 테스트** — `complete(id, adminId)`: entity 조회→`markCompleted`→`MaintenanceEndedEvent` 발행. `adjustEndTime(id, newEnd, adminId)`: 조회→`adjustScheduledEndTime`→`edgeConfigPort.writeMaintenance(entity, ACTIVE)` 호출 & **이벤트 미발행**(조용). 미존재 시 ANN-001.

```java
@Test
void complete_publishesMaintenanceEndedEvent() {
    SystemAnnouncementData e = spy(activeMaintenanceFixture());
    given(repository.findById(10L)).willReturn(Optional.of(e));
    service.complete(10L, 1L);
    verify(e).markCompleted(clock);
    verify(eventPublisher).publishEvent(any(MaintenanceEndedEvent.class));
}

@Test
void adjustEndTime_writesEdgeConfigActive_noEvent() {
    SystemAnnouncementData e = spy(activeMaintenanceFixture());
    given(repository.findById(10L)).willReturn(Optional.of(e));
    LocalDateTime newEnd = LocalDateTime.of(2026,5,4,5,0);
    service.adjustEndTime(10L, newEnd, 1L);
    verify(e).adjustScheduledEndTime(newEnd, clock);
    verify(edgeConfigPort).writeMaintenance(e, MaintenancePhase.ACTIVE);
    verifyNoInteractions(eventPublisher);
}

@Test
void complete_notFound_throwsAnn001() {
    given(repository.findById(99L)).willReturn(Optional.empty());
    assertThatThrownBy(() -> service.complete(99L, 1L))
        .isInstanceOf(NotFoundException.class).hasMessageContaining("ANN-001");
}
```

- [ ] **Step 2: 실패 확인** → FAIL

- [ ] **Step 3: 구현** — `SystemAnnouncementCommandService` 에 `EdgeConfigPort edgeConfigPort` 필드 추가(생성자 주입 — `@RequiredArgsConstructor` 라 final 필드만 추가), import `EdgeConfigPort`/`MaintenancePhase`/`MaintenanceEndedEvent`. `cancel` 아래 추가:

```java
    @Transactional
    public void complete(Long id, Long administratorId) {
        SystemAnnouncementData entity = repository.findById(id)
                .orElseThrow(() -> ExceptionCreator.create(AnnouncementException.ANNOUNCEMENT_NOT_FOUND));
        entity.markCompleted(clock);
        eventPublisher.publishEvent(new MaintenanceEndedEvent(entity));
    }

    @Transactional
    public void adjustEndTime(Long id, LocalDateTime newEnd, Long administratorId) {
        SystemAnnouncementData entity = repository.findById(id)
                .orElseThrow(() -> ExceptionCreator.create(AnnouncementException.ANNOUNCEMENT_NOT_FOUND));
        entity.adjustScheduledEndTime(newEnd, clock);
        edgeConfigPort.writeMaintenance(entity, MaintenancePhase.ACTIVE); // 조용 — WS 이벤트 없음
    }
```
> `adjustEndTime` 의 Edge Config 실패는 삼키지 않고 전파(5xx) — 운영자 노출 목적(spec §에러). `complete` 는 이벤트 경로라 broadcaster 의 `tryWriteEdgeConfig` best-effort.

- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: 커밋** — `git commit -m "feat(announcement): CommandService adjustEndTime/complete (#218)"`

---

## Chunk 3: 백엔드 REST + 응답 DTO

### Task 9: `AdjustScheduleRequest` + `AnnouncementSummaryResponse.completedAt`

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/AdjustScheduleRequest.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/AnnouncementSummaryResponse.java`

- [ ] **Step 1: 요청 DTO 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record AdjustScheduleRequest(@NotNull LocalDateTime scheduledEndAt) {}
```

- [ ] **Step 2: 응답 DTO 에 `completedAt` 추가** — `cancelledAt` 다음에 `LocalDateTime completedAt` 추가, `from()` 에 `e.getCompletedAt()` 추가, Javadoc `14 fields` → `15 fields` 동기화 (spec §7).

- [ ] **Step 3: 컴파일 확인** → `JAVA_HOME=... ./gradlew :app:compileJava` SUCCESSFUL
- [ ] **Step 4: 커밋** — `git commit -m "feat(announcement): AdjustScheduleRequest + 응답 completedAt (#218)"`

### Task 10: 컨트롤러 — PATCH `/{id}/schedule` + POST `/{id}/complete`

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminAnnouncementController.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminAnnouncementControllerTest.java`

- [ ] **Step 1: 실패 테스트 추가** — 기존 `AdminAnnouncementControllerTest` 에 추가 (`patch`/`post` import 추가, `import static ...MockMvcRequestBuilders.patch;`):

```java
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /{id}/schedule 200 — happy")
    void adjustSchedule_returns200() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/announcements/{id}/schedule", ANNOUNCEMENT_ID)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledEndAt\":\"2026-05-04T05:00:00\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /{id}/schedule 409 — ANN-007 비-ACTIVE")
    void adjustSchedule_notActive_returns409() throws Exception {
        willThrow(ExceptionCreator.create(AnnouncementException.NOT_ACTIVE_MAINTENANCE))
                .given(systemAnnouncementCommandService).adjustEndTime(eq(ANNOUNCEMENT_ID), any(), anyLong());
        mockMvc.perform(patch("/api/v1/admin/announcements/{id}/schedule", ANNOUNCEMENT_ID)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledEndAt\":\"2026-05-04T05:00:00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ANN-007"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /{id}/schedule 400 — scheduledEndAt 누락(@NotNull)")
    void adjustSchedule_missingBody_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/announcements/{id}/schedule", ANNOUNCEMENT_ID)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /{id}/complete 200 — happy")
    void complete_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/admin/announcements/{id}/complete", ANNOUNCEMENT_ID)
                        .with(csrf())).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /{id}/complete 409 — ANN-008 이미 완료")
    void complete_alreadyCompleted_returns409() throws Exception {
        willThrow(ExceptionCreator.create(AnnouncementException.ALREADY_COMPLETED))
                .given(systemAnnouncementCommandService).complete(eq(ANNOUNCEMENT_ID), anyLong());
        mockMvc.perform(post("/api/v1/admin/announcements/{id}/complete", ANNOUNCEMENT_ID)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ANN-008"));
    }
```

- [ ] **Step 2: 실패 확인** → FAIL (엔드포인트 없음 → 404/메서드 미존재 컴파일 에러)

- [ ] **Step 3: 컨트롤러 구현** — `cancel` 메서드 아래 추가 (import `AdjustScheduleRequest`, `org.springframework.web.bind.annotation.PatchMapping`):

```java
    @Operation(summary = "점검 종료시각 조정", description = "ACTIVE 한정. ANN-007(비-ACTIVE) 409, ANN-006(과거시각) 400.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PatchMapping("/{id}/schedule")
    public ResponseEntity<ApiCommonResponse<Void>> adjustSchedule(
            @PathVariable @Min(1) Long id, @Valid @RequestBody AdjustScheduleRequest req) {
        Long administratorId = adminContext.currentAdministratorId();
        commandService.adjustEndTime(id, req.scheduledEndAt(), administratorId);
        return ResponseEntity.ok(ApiCommonResponse.ok());
    }

    @Operation(summary = "점검 즉시 정상종료", description = "ACTIVE 한정. ANN-008(이미 완료) 409, ANN-007 409.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiCommonResponse<Void>> complete(@PathVariable @Min(1) Long id) {
        Long administratorId = adminContext.currentAdministratorId();
        commandService.complete(id, administratorId);
        return ResponseEntity.ok(ApiCommonResponse.ok());
    }
```

- [ ] **Step 3b: 클래스 Javadoc 동기화** — `AdminAnnouncementController` 클래스 Javadoc 의 "3 endpoint" 목록(`POST`/`GET`/`DELETE`)에 `PATCH /{id}/schedule — 종료시각 조정 (ACTIVE)` + `POST /{id}/complete — 즉시 정상종료 (ACTIVE)` 2줄 추가, "3 endpoint"→"5 endpoint". 함께: `@Tag(... description="시스템 공지 발행/조회/철회")` → `"시스템 공지 발행/조회/철회/종료시각 조정/정상종료"`, `Spec:` 라인에 `docs/superpowers/specs/2026-05-17-announcement-maintenance-lifecycle-design.md` 교차참조 추가.

- [ ] **Step 4: 통과 확인** — `JAVA_HOME=... ./gradlew :app:test --tests "...AdminAnnouncementControllerTest"` → PASS (기존 6 + 신규 5)

- [ ] **Step 5: 백엔드 전체 테스트 회귀**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test`
Expected: BUILD SUCCESSFUL (전체 그린)

- [ ] **Step 6: 커밋** — `git commit -m "feat(announcement): PATCH /schedule + POST /complete 엔드포인트 (#218)"`

---

## Chunk 4: pfplay-admin

> 작업 디렉터리: `pfplay-admin/`. 테스트: `yarn vitest run <path>`. **pfplay-admin 은 별 git 레포** (memory `reference_pfplay_admin_no_gha`) — 커밋은 pfplay-admin 레포에. 브랜치는 pfplay-admin `develop` 기준 신규 `feature/announcement-maintenance-lifecycle` 생성 후 작업.

### Task 11: 타입 + 라벨

**Files:**
- Modify: `pfplay-admin/src/entities/announcement/model/types.ts`
- Modify: `pfplay-admin/src/shared/lib/labels.ts`
- Modify: `pfplay-admin/src/test/mocks/fixtures/announcements.ts`

- [ ] **Step 1: 브랜치 생성** — `cd pfplay-admin && git fetch origin && git checkout -b feature/announcement-maintenance-lifecycle origin/develop`

- [ ] **Step 2: 타입에 `completedAt` 추가** — `Announcement` 인터페이스 `cancelledAt` 아래:

```ts
  /** scheduler 자동 또는 admin /complete 로 정상 종료된 시각. NULL = 정상완료된 적 없음. */
  completedAt: string | null
```
헤더 주석(types.ts L9-10) 의 정확한 2줄 블록을 Edit 으로 교체 (trailing period 포함, old_string 정확히):
```
 * - admin 콘솔에서는 신규 송출 (POST), 이력 조회 (GET), 취소 (DELETE) 만 사용.
 *   modify 없음 — 잘못 송출하면 DELETE 후 재송출.
```
→
```
 * - admin 콘솔: 송출(POST), 이력(GET), 철회(DELETE), 종료시각 조정(PATCH /schedule, ACTIVE 한정),
 *   즉시 정상종료(POST /complete).
```
(spec §7. `Announcement.completedAt` 추가는 Task 13/14 의 spread fixture·deriveAnnouncementStatus 보다 **반드시 먼저** — 이 Task 11 이 그 선행조건.)

- [ ] **Step 3: 파생 상태 라벨 추가** — `labels.ts` 의 `ANNOUNCEMENT_SEVERITY` 아래에:

```ts
export type AnnouncementDerivedStatus =
  | "PLANNED" | "ACTIVE" | "COMPLETED" | "CANCELLED" | "SENT"

export const ANNOUNCEMENT_DERIVED_STATUS: Mapping<AnnouncementDerivedStatus> = {
  label: {
    PLANNED: "예정",
    ACTIVE: "진행중",
    COMPLETED: "정상완료",
    CANCELLED: "철회",
    SENT: "송출됨",
  },
  variant: {
    PLANNED: "warning",
    ACTIVE: "success",
    COMPLETED: "muted",
    CANCELLED: "muted",
    SENT: "default",
  },
}

// 우선순위: 철회 > 완료 > 진행중 > (점검이면 예정 / 그 외 송출됨)
export function deriveAnnouncementStatus(a: {
  type: "MAINTENANCE_NOTICE" | "EVENT" | "EMERGENCY"
  maintenanceStartedAt: string | null
  cancelledAt: string | null
  completedAt: string | null
}): AnnouncementDerivedStatus {
  if (a.cancelledAt) return "CANCELLED"
  if (a.completedAt) return "COMPLETED"
  if (a.maintenanceStartedAt) return "ACTIVE"
  return a.type === "MAINTENANCE_NOTICE" ? "PLANNED" : "SENT"
}
```

- [ ] **Step 4: fixture 업데이트** — `fixtures/announcements.ts` 의 기존 3 fixture 에 `completedAt: null` 추가. 신규 fixture 2개 추가:

```ts
export const activeMaintenanceFixture: Announcement = {
  ...maintenanceNoticeFixture,
  id: 104,
  maintenanceStartedAt: "2026-05-04T03:00:00",
  cancelledAt: null,
  completedAt: null,
}

export const completedMaintenanceFixture: Announcement = {
  ...maintenanceNoticeFixture,
  id: 105,
  maintenanceStartedAt: "2026-05-04T03:00:00",
  completedAt: "2026-05-04T04:00:00",
  cancelledAt: null,
}
```
신규 ANN 에러 fixture 추가:
```ts
export const annNotActiveError = { status: 409, errorCode: "ANN-007", message: "진행 중인 점검 공지가 아닙니다." }
export const annAlreadyCompletedError = { status: 409, errorCode: "ANN-008", message: "이미 정상 종료된 점검 공지입니다." }
export const annInvalidEndAdjustmentError = { status: 400, errorCode: "ANN-006", message: "조정할 종료 시각은 현재 이후여야 합니다." }
```

- [ ] **Step 5: 타입체크** — `cd pfplay-admin && yarn tsc --noEmit` → 에러 0
- [ ] **Step 6: 커밋** — `git commit -m "feat(announcements): completedAt 타입 + 파생 상태 라벨 (#218)"`

### Task 12: API + hooks

**Files:**
- Modify: `pfplay-admin/src/features/announcements/api/announcements-api.ts`
- Create: `pfplay-admin/src/features/announcements/api/use-adjust-schedule.ts`, `use-complete-announcement.ts`
- Modify: `pfplay-admin/src/test/mocks/handlers/announcements.ts`
- Test: `pfplay-admin/src/features/announcements/api/__tests__/announcements-api.test.ts` (있으면 확장)

- [ ] **Step 1: 실패 테스트** — `announcements-api.test.ts` 에 추가: `adjustAnnouncementSchedule(id, iso)` 가 `PATCH /api/v1/admin/announcements/{id}/schedule` 바디 `{scheduledEndAt}` 로 호출, `completeAnnouncement(id)` 가 `POST .../{id}/complete`. (기존 cancel 테스트 패턴 mirror.)

- [ ] **Step 2: 실패 확인** — `yarn vitest run src/features/announcements/api/__tests__/announcements-api.test.ts` → FAIL

- [ ] **Step 3: API 함수 추가** — `announcements-api.ts` 끝에:

```ts
export async function adjustAnnouncementSchedule(
  id: number,
  scheduledEndAt: string,
): Promise<void> {
  await http<void>(`${API}/${id}/schedule`, {
    method: "PATCH",
    body: { scheduledEndAt },
  })
}

export async function completeAnnouncement(id: number): Promise<void> {
  await http<void>(`${API}/${id}/complete`, { method: "POST" })
}
```
hook 2개 (`use-cancel-announcement.ts` mirror):

```ts
// use-adjust-schedule.ts
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { adjustAnnouncementSchedule } from "./announcements-api"
import { mutationSuccessToast, mutationErrorToast } from "@/shared/lib/mutation-toast"

export function useAdjustSchedule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, scheduledEndAt }: { id: number; scheduledEndAt: string }) =>
      adjustAnnouncementSchedule(id, scheduledEndAt),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["announcements"] })
      mutationSuccessToast("종료 시각을 조정했습니다")
    },
    onError: mutationErrorToast,
  })
}
```
```ts
// use-complete-announcement.ts  (mutationFn: (id:number)=>completeAnnouncement(id), 성공 토스트 "점검을 정상 종료했습니다")
```

- [ ] **Step 4: MSW 핸들러 추가** — `handlers/announcements.ts` 의 배열에:

```ts
  http.patch(`${API}/:id/schedule`, () => HttpResponse.json({ data: null })),
  http.post(`${API}/:id/complete`, () => HttpResponse.json({ data: null })),
```

- [ ] **Step 5: 통과 확인** → PASS
- [ ] **Step 6: 커밋** — `git commit -m "feat(announcements): adjust-schedule/complete API + hooks (#218)"`

### Task 13: 테이블 상태배지 + 액션 분기

**Files:**
- Modify: `pfplay-admin/src/features/announcements/ui/announcements-table.tsx`
- Test: `pfplay-admin/src/features/announcements/ui/__tests__/announcements-table.test.tsx`

- [ ] **Step 1: 실패 테스트** — 기존 `announcements-table.test.tsx` 의 **비-skeleton 테스트 4개를 전부 재작성**(loading/empty 2개는 유지). 그대로 두면 aria-label `공지 #{id} 취소`→`철회` 변경·컬럼 `취소 시각`→`상태`로 2개가 red 로 남음:
  1. `활성 공지 — 취소 버튼 활성`(L24): fixture 가 `maintenanceNoticeFixture`(=`maintenanceStartedAt:null` → `deriveAnnouncementStatus`=**PLANNED**, ACTIVE 아님)이라 제목이 오해. → 테스트명 `PLANNED 점검 — 철회 버튼만`으로 변경, aria-label `공지 #{id} 철회`, `onCancelClick` 호출 검증
  2. `취소된 공지 — 버튼 비활성`(L42): `emergencyCancelledFixture` → "철회" 배지 표시 + 액션 버튼 없음(또는 비활성) 검증으로 교체
  3. `type / severity 라벨 humanize`(L58): 그대로 유지하되 `onCancelClick`만 넘기던 props 에 신규 optional 콜백 영향 없음 확인(컴파일만 깨지면 props 추가가 optional 이라 무방 — 그래도 import 정리)
  4. 신규: `activeMaintenanceFixture` → "진행중" 배지 + `종료시각 조정`/`지금 종료`/`철회` 3버튼 + 각 `onAdjustClick`/`onCompleteClick`/`onCancelClick` 호출; `completedMaintenanceFixture` → "정상완료" 배지 + 액션 없음; `eventFixture` → "송출됨" 배지 + `철회`만
  Props 에 `onAdjustClick?`/`onCompleteClick?` 추가(optional). aria-label 규칙: `공지 #{id} 철회`/`공지 #{id} 종료시각 조정`/`공지 #{id} 지금 종료`.

- [ ] **Step 2: 실패 확인** → FAIL

- [ ] **Step 3: 구현** — `announcements-table.tsx`:
  - Props 에 `onAdjustClick?: (a:Announcement)=>void`, `onCompleteClick?: (a:Announcement)=>void` 추가
  - `import { ANNOUNCEMENT_DERIVED_STATUS, deriveAnnouncementStatus } from "@/shared/lib/labels"`
  - '취소 시각' 컬럼 헤더 → '상태', 셀을 `deriveAnnouncementStatus(row)` → `<Badge variant={ANNOUNCEMENT_DERIVED_STATUS.variant[s]}>{ANNOUNCEMENT_DERIVED_STATUS.label[s]}</Badge>`
  - 작업 셀: `const status = deriveAnnouncementStatus(row)`; `status==="ACTIVE"` 면 `종료시각 조정`+`지금 종료`+`철회` 3버튼, 그 외 `CANCELLED`/`COMPLETED` 면 액션 없음(상태 텍스트), `PLANNED`/`SENT` 면 `철회`만. aria-label 유지(`공지 #${id} 철회` 등, 기존 테스트 호환 위해 기존 `취소`→`철회` 라벨 변경은 테스트도 같이 갱신).

- [ ] **Step 4: 통과 확인** — `yarn vitest run src/features/announcements/ui/__tests__/announcements-table.test.tsx` → PASS
- [ ] **Step 5: 커밋** — `git commit -m "feat(announcements): 테이블 파생 상태배지 + ACTIVE 액션 분기 (#218)"`

### Task 14: 조정/완료 다이얼로그 + 페이지 wiring + 문구

**Files:**
- Create: `pfplay-admin/src/features/announcements/ui/adjust-schedule-dialog.tsx`, `complete-announcement-dialog.tsx`
- Modify: `pfplay-admin/src/features/announcements/ui/cancel-announcement-dialog.tsx`
- Modify: `pfplay-admin/src/widgets/announcements-history.tsx` (**`AnnouncementsHistoryContent`** 가 테이블+다이얼로그+state 보유. `pages/announcements-history-page.tsx` 는 shell — 건드리지 말 것)
- Modify (있으면): `pfplay-admin/src/widgets/__tests__/announcements-history.test.tsx` — 신규 콜백/다이얼로그 반영
- Test: `pfplay-admin/src/features/announcements/ui/__tests__/adjust-schedule-dialog.test.tsx` (신규)

- [ ] **Step 1: 실패 테스트 — adjust 다이얼로그** — 퀵버튼 `+30분` 클릭 시 입력값이 `target.scheduledEndAt + 30분`(현재 종료시각 누적 기준)으로 prefill, `확정` 클릭 시 `useAdjustSchedule().mutate({id, scheduledEndAt})` 호출. (RTL + `useAdjustSchedule` mock.)

- [ ] **Step 2: 실패 확인** → FAIL

- [ ] **Step 3: 구현**
  - `adjust-schedule-dialog.tsx`: `cancel-announcement-dialog.tsx` 구조 mirror. `target: Announcement | null` props. `<input type="datetime-local">` 기본값 = `target.scheduledEndAt` 의 `YYYY-MM-DDTHH:mm`. 퀵버튼 `+10분/+30분/+1시간` → `현재 입력값(없으면 scheduledEndAt) + N` 으로 setState(누적). `확정` → `useAdjustSchedule().mutate({ id: target.id, scheduledEndAt: <input+":00" 정규화> })`, onSuccess 닫기. mutation-schema 의 `ISO_LOCAL` 정규화 규칙 재사용(초 보정).
  - `complete-announcement-dialog.tsx`: `cancel` 다이얼로그 mirror. 문구 "지금 점검을 정상 종료합니다. 사용자가 즉시 서비스로 복귀합니다. 되돌릴 수 없습니다." `확정` → `useCompleteAnnouncement().mutate(target.id)`.
  - `cancel-announcement-dialog.tsx`: 본문의 `잘못 송출한 경우 취소 후 새로 송출하세요. modify 는 지원되지 않습니다.` → `종료시각 조정은 별도 기능입니다. 본 작업은 공지 철회(취소)입니다.` 로 교체.
  - `widgets/announcements-history.tsx` 의 `AnnouncementsHistoryContent`: 기존 `const [cancelTarget, setCancelTarget] = useState<Announcement|null>(null)` 패턴 그대로 mirror 해 `adjustTarget`/`completeTarget` 2개 state 추가. `<AnnouncementsTable>` 에 `onAdjustClick={setAdjustTarget}`/`onCompleteClick={setCompleteTarget}` 추가(기존 `onCancelClick={setCancelTarget}` 유지). 기존 `<CancelAnnouncementDialog>` 옆에 `<AdjustScheduleDialog target={adjustTarget} onOpenChange=.../>` + `<CompleteAnnouncementDialog target={completeTarget} onOpenChange=.../>` 추가(동일 `onOpenChange` 닫기 패턴 L66-68).

- [ ] **Step 4: 통과 확인** — `yarn vitest run src/features/announcements` → 전체 PASS
- [ ] **Step 5: admin 전체 회귀**

Run: `cd pfplay-admin && yarn vitest run && yarn tsc --noEmit`
Expected: 전체 PASS, 타입에러 0

- [ ] **Step 6: 커밋** — `git commit -m "feat(announcements): 조정/완료 다이얼로그 + 페이지 wiring + 문구 갱신 (#218)"`

### Task 15: 수동 통합 검증 (dev 서버)

- [ ] **Step 1** — `cd pfplay-admin && yarn dev`. 공지 송출 이력 페이지 진입. (백엔드 로컬: `reference_local_docker_compose` 의 `docker-compose.local.yml` + admin seed 로그인.)
- [ ] **Step 2** — MAINTENANCE 공지 송출(가까운 미래) → 시작 도래 후 "진행중" 배지 확인 → `종료시각 조정` 다이얼로그에서 `+30분` 퀵버튼 동작/확정 → 목록 반영. `지금 종료` → "정상완료" 배지. 별도 공지 `철회` → "철회" 배지. EVENT 공지는 `철회`만.
- [ ] **Step 3** — UI로 검증 불가한 부분(자동 cron 완료, Edge Config 조용한 갱신)은 그 사실을 명시 보고(테스트로 커버됨, 수동은 backend 시계 의존이라 생략 가능).

---

## Execution Notes

- 백엔드(pfplay-platform)와 admin(pfplay-admin)은 **별 레포 / 별 PR**. 백엔드 PR: `feature/announcement-maintenance-lifecycle` → `develop` (이슈 #218 연결, 한글). admin PR: pfplay-admin `feature/...` → `develop` (한글).
- 승격 흐름: 백엔드 develop→release(stg)→main(prod) 일반 git flow (`reference_branch_env_mapping`). prod 승격 전 spec §"다음 액션"·자동완료 self-healing 인지(사용자 이미 인지 — runbook 노트 생략 결정).
- 완료 후 memory `project_system_announcement_design` 에 lock 반전 기록(자동종료 도입 / completed_at / modify=end조정 한정 / WS `MAINTENANCE_ENDED` 추가).
- 각 chunk 종료 시 plan-document-reviewer 디스패치(spec 경로 + chunk 내용 제공, 세션 히스토리 미전달). ✅ 까지 수정 후 다음 chunk.
