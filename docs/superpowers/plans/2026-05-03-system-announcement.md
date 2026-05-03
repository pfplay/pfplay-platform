# 시스템 공지 + 점검 모드 fallback (V14) Backend Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** administration BC 에 V14 `system_announcement` aggregate 신설 + admin REST 발사/취소/이력 + public `/system/status` fallback + WebSocket `/sub/system/announcements` broadcast + 1분 cron `MaintenanceSchedulerService` + Vercel Edge Config write adapter. **Backend (`pfplay-platform`) 단일 PR, 단일 commit.**

**Architecture:** administration BC attach. Controller(`@PreAuthorize @Validated @Valid`) → Service(`@Transactional`) → Repository → Domain method → `eventPublisher.publishEvent` → `@TransactionalEventListener(AFTER_COMMIT)` listener (WS broadcast + Edge Config write 묶음). `AnnouncementBroadcaster` 가 `SimpMessagingTemplate` 직접 사용. Edge Config 1회 호출 + 실패 시 ERROR log (cron 자연 재시도). DjQueueChangedEvent late-binding 함정(`reference_first_dj_silent_deactivate.md`) 회피 — payload 는 발사 시점 entity snapshot 직접 구성.

**Tech Stack:** Java 21, Spring Boot 3.2 (Spring Security 6.2, Spring Data JPA 3.2, Spring WebSocket/STOMP), Jakarta Bean Validation 3.0, Flyway 9, RestTemplate, JUnit 5, Mockito, AssertJ, Testcontainers (MySQL 8 + Redis), STOMP test client.

**Spec source:** `docs/superpowers/specs/2026-05-03-system-announcement-design.md` (700 라인 — V14 DDL §3, REST §4, Domain/Service §5, WS §6, Edge Config §7, Failure modes §9, Testing §10)

**Branching:** `feature/admin-auth-iam-schema` 위에 누적. HEAD = `ea2e3b47` (`.env.example` 회복 chore).

**Out of scope (defer)** — spec §1.3 / §11:
- frontend (admin G21~ + web 부팅 fallback) — 별도 plan
- 사용자 inbox / read-state, 공지 modify, audience 분기, graceful shutdown, multilingual 자동번역
- WS backlog 재전송 (REST `/system/status` catch up 만)
- Overlap MAINTENANCE_NOTICE 검증 (last-write-wins 허용)
- Edge Config retry layer (1회 호출만, scheduler 매분 자연 재시도)
- `MaintenanceSnapshot` 별 record (adapter 가 entity 직접 받음)
- `EdgeConfigWriteException` 별 class (RuntimeException 직접)
- Qualified `RestTemplate` bean (timeout 적용 안 함, 기존 공용 bean)

**Open items deferred to during-implementation:**
- `VERCEL_TEAM_ID` 필요 여부 — 사용자가 Vercel 계정 타입 확인 후 Task 3 적용

---

## File map (단일 PR)

**Production code (administration BC):**
- `app/src/main/resources/db/migration/V14__create_system_announcement.sql`
- `app/src/main/resources/application.yml` — line 86~88 사이에 `vercel:` 블록 추가
- `app/src/main/java/com/pfplaybackend/api/administration/`
  - `domain/value/AnnouncementType.java`
  - `domain/value/AnnouncementSeverity.java`
  - `domain/value/MaintenancePhase.java`
  - `domain/entity/data/SystemAnnouncementData.java`
  - `domain/exception/AnnouncementException.java`
  - `domain/event/AnnouncementPublishedEvent.java`
  - `domain/event/AnnouncementCancelledEvent.java`
  - `domain/event/MaintenanceStartedEvent.java`
  - `domain/port/EdgeConfigPort.java`
  - `application/service/SystemAnnouncementCommandService.java`
  - `application/service/SystemAnnouncementQueryService.java`
  - `application/service/MaintenanceSchedulerService.java`
  - `adapter/in/web/AdminAnnouncementController.java`
  - `adapter/in/web/SystemStatusController.java`
  - `adapter/in/web/payload/request/AnnouncementCreateRequest.java`
  - `adapter/in/web/payload/response/AnnouncementSummaryResponse.java`
  - `adapter/in/web/payload/response/SystemStatusResponse.java`
  - `adapter/out/persistence/SystemAnnouncementRepository.java`
  - `adapter/out/event/AnnouncementBroadcaster.java`
  - `adapter/out/edge/VercelEdgeConfigAdapter.java`
  - `adapter/out/edge/properties/VercelEdgeConfigProperties.java`

**Tests:**
- `app/src/test/java/com/pfplaybackend/api/administration/`
  - `domain/entity/data/SystemAnnouncementDataTest.java` — factory invariant (5-7 cases)
  - `adapter/out/persistence/SystemAnnouncementRepositoryIntegrationTest.java` — 4 query 메서드
  - `adapter/out/edge/VercelEdgeConfigAdapterTest.java` — success/failure (mock RestTemplate)
  - `application/service/SystemAnnouncementCommandServiceTest.java` — publish/cancel mock
  - `application/service/MaintenanceSchedulerServiceTest.java` — due query → markStarted
  - `adapter/in/web/AdminAnnouncementControllerTest.java` — `@WebMvcTest` POST/GET/DELETE
  - `adapter/in/web/SystemStatusControllerTest.java` — `@WebMvcTest` 익명 호출
  - `adapter/out/event/AnnouncementBroadcasterIT.java` — `@SpringBootTest` + STOMP test client end-to-end (publish → broadcast 수신)

---

## Hard precondition (verify BEFORE Task 1)

- [ ] **Step 1: HEAD 확인**

```bash
git -C "C:/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-platform" log --oneline -3
```
Expected: HEAD `ea2e3b47 chore(env): document missing keys in .env.example ...`

- [ ] **Step 2: tracked working tree clean**

```bash
git -C "C:/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-platform" status -s -uno
```
Expected: empty.

- [ ] **Step 3: JDK 21 + baseline test pass**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Read spec 1회**

`docs/superpowers/specs/2026-05-03-system-announcement-design.md` 전 12 섹션.

---

## Task 1: V14 마이그레이션 + 도메인 enum + entity + factory test

**Files:**
- Create V14 SQL, 3 enums, entity, exception, factory test

- [ ] **Step 1: V14 SQL** — spec §3.1 그대로

`app/src/main/resources/db/migration/V14__create_system_announcement.sql`:
```sql
CREATE TABLE system_announcement (
    id                              BIGINT       NOT NULL AUTO_INCREMENT,
    type                            VARCHAR(32)  NOT NULL,
    severity                        VARCHAR(16)  NOT NULL,
    title_ko                        VARCHAR(200) NOT NULL,
    title_en                        VARCHAR(200) NOT NULL,
    message_ko                      VARCHAR(2000) NOT NULL,
    message_en                      VARCHAR(2000) NOT NULL,
    scheduled_start_at              DATETIME     NULL,
    scheduled_end_at                DATETIME     NULL,
    expires_at                      DATETIME     NULL,
    sent_at                         DATETIME     NOT NULL,
    sent_by_administrator_id        BIGINT       NOT NULL,
    maintenance_started_at          DATETIME     NULL,
    cancelled_at                    DATETIME     NULL,
    cancelled_by_administrator_id   BIGINT       NULL,
    created_at                      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_active_maintenance (type, cancelled_at, scheduled_start_at, maintenance_started_at),
    KEY idx_sent_at_desc (sent_at DESC),
    CONSTRAINT chk_maintenance_window CHECK (
        type != 'MAINTENANCE_NOTICE' OR (
            scheduled_start_at IS NOT NULL AND scheduled_end_at IS NOT NULL
            AND scheduled_end_at > scheduled_start_at)),
    CONSTRAINT fk_announcement_sent_by FOREIGN KEY (sent_by_administrator_id) REFERENCES administrator(administrator_id),
    CONSTRAINT fk_announcement_cancelled_by FOREIGN KEY (cancelled_by_administrator_id) REFERENCES administrator(administrator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **Step 2: Enums**

```java
// administration/domain/value/AnnouncementType.java
public enum AnnouncementType { MAINTENANCE_NOTICE, EVENT, EMERGENCY }

// administration/domain/value/AnnouncementSeverity.java
public enum AnnouncementSeverity { INFO, WARN, CRITICAL }

// administration/domain/value/MaintenancePhase.java
public enum MaintenancePhase { PLANNED, ACTIVE }
```

- [ ] **Step 3: AnnouncementException** — `AdminReportException` 패턴 일치

```java
package com.pfplaybackend.api.administration.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum AnnouncementException implements DomainException {
    ANNOUNCEMENT_NOT_FOUND("ANN-001", "공지를 찾을 수 없습니다.", ErrorType.NOT_FOUND),
    ALREADY_CANCELLED("ANN-002", "이미 철회된 공지입니다.", ErrorType.CONFLICT),
    INVALID_SCHEDULE_FOR_TYPE("ANN-003", "공지 타입과 일정 정보가 일치하지 않습니다.", ErrorType.BAD_REQUEST),
    INVALID_SCHEDULE_WINDOW("ANN-004", "예약 종료 시각은 시작 시각보다 이후여야 합니다.", ErrorType.BAD_REQUEST),
    SCHEDULED_START_IN_PAST("ANN-005", "예약 시작 시각은 미래여야 합니다.", ErrorType.BAD_REQUEST);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    AnnouncementException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode; this.message = message; this.errorType = errorType;
    }
}
```

- [ ] **Step 4: SystemAnnouncementDataTest (RED)**

```java
package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.administration.domain.value.*;
import com.pfplaybackend.api.common.exception.http.*;
import org.junit.jupiter.api.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class SystemAnnouncementDataTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.now(clock);

    @Test
    @DisplayName("create EVENT — 스케줄 NULL 강제")
    void createEvent() {
        SystemAnnouncementData a = SystemAnnouncementData.create(
            AnnouncementType.EVENT, AnnouncementSeverity.INFO,
            "이벤트", "Event", "본문", "Body", null, null, now.plusDays(1), now, 1L);
        assertThat(a.getScheduledStartAt()).isNull();
    }

    @Test
    @DisplayName("create MAINTENANCE_NOTICE — 스케줄 필수 (BadRequest ANN-003)")
    void maintenanceRequiresSchedule() {
        assertThatThrownBy(() -> SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", null, null, null, now, 1L))
            .isInstanceOf(BadRequestException.class)
            .hasFieldOrPropertyWithValue("errorCode", "ANN-003");
    }

    @Test
    @DisplayName("create MAINTENANCE_NOTICE — end <= start 거부 (ANN-004)")
    void invertedWindow() {
        LocalDateTime s = now.plusHours(1);
        assertThatThrownBy(() -> SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", s, s, null, now, 1L))
            .isInstanceOf(BadRequestException.class)
            .hasFieldOrPropertyWithValue("errorCode", "ANN-004");
    }

    @Test
    @DisplayName("create MAINTENANCE_NOTICE — expires_at NULL 강제")
    void maintenanceRejectsExpiresAt() {
        LocalDateTime s = now.plusHours(1), e = s.plusHours(1);
        assertThatThrownBy(() -> SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", s, e, now.plusDays(1), now, 1L))
            .isInstanceOf(BadRequestException.class)
            .hasFieldOrPropertyWithValue("errorCode", "ANN-003");
    }

    @Test
    @DisplayName("markMaintenanceStarted — type guard")
    void markStartedTypeGuard() {
        SystemAnnouncementData event = SystemAnnouncementData.create(
            AnnouncementType.EVENT, AnnouncementSeverity.INFO,
            "이벤트", "E", "본문", "B", null, null, null, now, 1L);
        assertThatThrownBy(() -> event.markMaintenanceStarted(clock))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("cancel — already cancelled CONFLICT")
    void cancelIdempotent() {
        SystemAnnouncementData a = SystemAnnouncementData.create(
            AnnouncementType.EVENT, AnnouncementSeverity.INFO,
            "이벤트", "E", "본문", "B", null, null, null, now, 1L);
        a.cancel(2L, clock);
        assertThatThrownBy(() -> a.cancel(3L, clock))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("errorCode", "ANN-002");
    }

    @Test
    @DisplayName("isMaintenancePhaseActive — started + not cancelled")
    void phaseActive() {
        LocalDateTime s = now.plusHours(1), e = s.plusHours(1);
        SystemAnnouncementData a = SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", s, e, null, now, 1L);
        assertThat(a.isMaintenancePhaseActive()).isFalse();
        a.markMaintenanceStarted(clock);
        assertThat(a.isMaintenancePhaseActive()).isTrue();
        a.cancel(2L, clock);
        assertThat(a.isMaintenancePhaseActive()).isFalse();
    }
}
```

- [ ] **Step 5: SystemAnnouncementData (GREEN)**

```java
package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.administration.domain.exception.AnnouncementException;
import com.pfplaybackend.api.administration.domain.value.*;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;

@Entity
@Table(name = "system_announcement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemAnnouncementData extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AnnouncementType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private AnnouncementSeverity severity;
    @Column(name = "title_ko", nullable = false, length = 200) private String titleKo;
    @Column(name = "title_en", nullable = false, length = 200) private String titleEn;
    @Column(name = "message_ko", nullable = false, length = 2000) private String messageKo;
    @Column(name = "message_en", nullable = false, length = 2000) private String messageEn;
    @Column(name = "scheduled_start_at") private LocalDateTime scheduledStartAt;
    @Column(name = "scheduled_end_at") private LocalDateTime scheduledEndAt;
    @Column(name = "expires_at") private LocalDateTime expiresAt;
    @Column(name = "sent_at", nullable = false) private LocalDateTime sentAt;
    @Column(name = "sent_by_administrator_id", nullable = false) private Long sentByAdministratorId;
    @Column(name = "maintenance_started_at") private LocalDateTime maintenanceStartedAt;
    @Column(name = "cancelled_at") private LocalDateTime cancelledAt;
    @Column(name = "cancelled_by_administrator_id") private Long cancelledByAdministratorId;

    public static SystemAnnouncementData create(
            AnnouncementType type, AnnouncementSeverity severity,
            String titleKo, String titleEn, String messageKo, String messageEn,
            LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt, LocalDateTime expiresAt,
            LocalDateTime sentAt, Long sentByAdministratorId) {
        if (type == AnnouncementType.MAINTENANCE_NOTICE) {
            if (scheduledStartAt == null || scheduledEndAt == null || expiresAt != null)
                throw ExceptionCreator.create(AnnouncementException.INVALID_SCHEDULE_FOR_TYPE);
            if (!scheduledEndAt.isAfter(scheduledStartAt))
                throw ExceptionCreator.create(AnnouncementException.INVALID_SCHEDULE_WINDOW);
        } else {
            if (scheduledStartAt != null || scheduledEndAt != null)
                throw ExceptionCreator.create(AnnouncementException.INVALID_SCHEDULE_FOR_TYPE);
        }
        SystemAnnouncementData e = new SystemAnnouncementData();
        e.type = type; e.severity = severity;
        e.titleKo = titleKo; e.titleEn = titleEn; e.messageKo = messageKo; e.messageEn = messageEn;
        e.scheduledStartAt = scheduledStartAt; e.scheduledEndAt = scheduledEndAt; e.expiresAt = expiresAt;
        e.sentAt = sentAt; e.sentByAdministratorId = sentByAdministratorId;
        return e;
    }

    public void markMaintenanceStarted(Clock clock) {
        if (type != AnnouncementType.MAINTENANCE_NOTICE)
            throw new IllegalStateException("non-MAINTENANCE_NOTICE: " + type);
        if (maintenanceStartedAt != null) throw new IllegalStateException("already started: " + id);
        if (cancelledAt != null) throw new IllegalStateException("cancelled: " + id);
        this.maintenanceStartedAt = LocalDateTime.now(clock);
    }

    public void cancel(Long administratorId, Clock clock) {
        if (cancelledAt != null) throw ExceptionCreator.create(AnnouncementException.ALREADY_CANCELLED);
        this.cancelledAt = LocalDateTime.now(clock);
        this.cancelledByAdministratorId = administratorId;
    }

    public boolean isMaintenancePhaseActive() {
        return type == AnnouncementType.MAINTENANCE_NOTICE && maintenanceStartedAt != null && cancelledAt == null;
    }
}
```

- [ ] **Step 6: Run test (PASS)**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests SystemAnnouncementDataTest
```
Expected: 7 tests passed.

---

## Task 2: Repository + IntegrationTest

**Files:** `SystemAnnouncementRepository.java` + `SystemAnnouncementRepositoryIntegrationTest.java`

- [ ] **Step 1: Repository — 5 메서드만**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.*;

public interface SystemAnnouncementRepository extends JpaRepository<SystemAnnouncementData, Long> {

    @Query("""
        SELECT a FROM SystemAnnouncementData a
        WHERE a.type = com.pfplaybackend.api.administration.domain.value.AnnouncementType.MAINTENANCE_NOTICE
          AND a.maintenanceStartedAt IS NULL AND a.cancelledAt IS NULL
          AND a.scheduledStartAt <= :now AND a.scheduledEndAt > :now
        """)
    List<SystemAnnouncementData> findDueForMaintenanceActivation(@Param("now") LocalDateTime now);

    @Query("""
        SELECT a FROM SystemAnnouncementData a
        WHERE a.type IN (com.pfplaybackend.api.administration.domain.value.AnnouncementType.EVENT,
                         com.pfplaybackend.api.administration.domain.value.AnnouncementType.EMERGENCY)
          AND a.cancelledAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
        ORDER BY a.sentAt DESC
        """)
    List<SystemAnnouncementData> findActivePublic(@Param("now") LocalDateTime now);

    @Query("""
        SELECT a FROM SystemAnnouncementData a
        WHERE a.type = com.pfplaybackend.api.administration.domain.value.AnnouncementType.MAINTENANCE_NOTICE
          AND a.maintenanceStartedAt IS NOT NULL AND a.cancelledAt IS NULL
        """)
    Optional<SystemAnnouncementData> findCurrentMaintenance();

    @Query("""
        SELECT a FROM SystemAnnouncementData a
        WHERE a.type = com.pfplaybackend.api.administration.domain.value.AnnouncementType.MAINTENANCE_NOTICE
          AND a.maintenanceStartedAt IS NULL AND a.cancelledAt IS NULL AND a.scheduledStartAt > :now
        ORDER BY a.scheduledStartAt ASC
        """)
    List<SystemAnnouncementData> findPlannedMaintenance(@Param("now") LocalDateTime now);

    Page<SystemAnnouncementData> findAll(Pageable pageable);
}
```

- [ ] **Step 2: IntegrationTest (5 메서드 검증)**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.value.*;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class SystemAnnouncementRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired SystemAnnouncementRepository repo;
    private final LocalDateTime now = LocalDateTime.of(2026, 5, 4, 0, 0);

    @Test
    @DisplayName("findDueForMaintenanceActivation — start≤now, end>now, started=null, cancelled=null")
    void due() {
        repo.save(maintenance(now.minusMinutes(5), now.plusMinutes(55), null, null));   // ✓
        repo.save(maintenance(now.plusMinutes(5), now.plusMinutes(65), null, null));     // future start
        repo.save(maintenance(now.minusMinutes(5), now.plusMinutes(55), now.minusMinutes(1), null));   // started
        repo.save(maintenance(now.minusMinutes(5), now.plusMinutes(55), null, now.minusMinutes(1)));   // cancelled
        repo.save(event());                                                                              // wrong type
        assertThat(repo.findDueForMaintenanceActivation(now)).hasSize(1);
    }

    @Test
    @DisplayName("findActivePublic — type IN (EVENT,EMERGENCY), cancelled=null, expiresAt null OR > now")
    void activePublic() {
        repo.save(event());
        repo.save(eventWithExpiry(now.plusMinutes(10)));
        repo.save(eventWithExpiry(now.minusMinutes(1)));   // expired
        repo.save(eventCancelled());
        repo.save(maintenance(now.plusMinutes(5), now.plusMinutes(65), null, null));   // wrong type
        assertThat(repo.findActivePublic(now)).hasSize(2);
    }

    @Test
    @DisplayName("findCurrentMaintenance — phase ACTIVE 단일 row")
    void current() {
        repo.save(maintenance(now.minusMinutes(5), now.plusMinutes(55), now.minusMinutes(1), null));
        assertThat(repo.findCurrentMaintenance()).isPresent();
    }

    @Test
    @DisplayName("findPlannedMaintenance — start>now, started=null, cancelled=null, ASC")
    void planned() {
        repo.save(maintenance(now.plusMinutes(15), now.plusMinutes(75), null, null));
        repo.save(maintenance(now.plusMinutes(5), now.plusMinutes(65), null, null));
        List<SystemAnnouncementData> p = repo.findPlannedMaintenance(now);
        assertThat(p).hasSize(2);
        assertThat(p.get(0).getScheduledStartAt()).isBefore(p.get(1).getScheduledStartAt());
    }

    private SystemAnnouncementData maintenance(LocalDateTime s, LocalDateTime e,
                                                LocalDateTime started, LocalDateTime cancelled) {
        SystemAnnouncementData d = SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "k", "e", "ko", "en", s, e, null, now, 1L);
        if (started != null) ReflectionTestUtils.setField(d, "maintenanceStartedAt", started);
        if (cancelled != null) {
            ReflectionTestUtils.setField(d, "cancelledAt", cancelled);
            ReflectionTestUtils.setField(d, "cancelledByAdministratorId", 2L);
        }
        return d;
    }
    private SystemAnnouncementData event() {
        return SystemAnnouncementData.create(AnnouncementType.EVENT, AnnouncementSeverity.INFO,
            "k", "e", "ko", "en", null, null, null, now, 1L);
    }
    private SystemAnnouncementData eventWithExpiry(LocalDateTime exp) {
        return SystemAnnouncementData.create(AnnouncementType.EVENT, AnnouncementSeverity.INFO,
            "k", "e", "ko", "en", null, null, exp, now, 1L);
    }
    private SystemAnnouncementData eventCancelled() {
        SystemAnnouncementData d = event();
        d.cancel(2L, Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        return d;
    }
}
```

- [ ] **Step 3: Run** — `--tests SystemAnnouncementRepositoryIntegrationTest`. 4 tests PASS.

---

## Task 3: EdgeConfigPort + VercelEdgeConfigAdapter + properties + application.yml

**Files:** EdgeConfigPort, VercelEdgeConfigAdapter, VercelEdgeConfigProperties, application.yml 수정, adapter test

- [ ] **Step 1: VercelEdgeConfigProperties**

```java
package com.pfplaybackend.api.administration.adapter.out.edge.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "vercel.edge-config")
public class VercelEdgeConfigProperties {
    private String id;
    private String apiToken;
    private String teamId;
    private String baseUrl = "https://api.vercel.com";
}
```

- [ ] **Step 2: application.yml** — line 86 `service-api:` 블록 다음, line 88 `# 🔐 커스텀 앱 설정` 위에 root level `vercel:` 블록:

```yaml
vercel:
  edge-config:
    id: ${VERCEL_EDGE_CONFIG_ID:}
    api-token: ${VERCEL_API_TOKEN:}
    team-id: ${VERCEL_TEAM_ID:}
    base-url: https://api.vercel.com
```

- [ ] **Step 3: EdgeConfigPort**

```java
package com.pfplaybackend.api.administration.domain.port;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.value.MaintenancePhase;

public interface EdgeConfigPort {
    /**
     * @param entity null = 점검 종료 (Edge Config maintenance 키를 null 로). non-null + phase 면 upsert.
     * 실패 시 RuntimeException — listener 가 swallow + ERROR log. 재시도 없음.
     */
    void writeMaintenance(SystemAnnouncementData entity, MaintenancePhase phase);
}
```

- [ ] **Step 4: VercelEdgeConfigAdapterTest (RED)**

```java
package com.pfplaybackend.api.administration.adapter.out.edge;

import com.pfplaybackend.api.administration.adapter.out.edge.properties.VercelEdgeConfigProperties;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.value.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.web.client.*;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VercelEdgeConfigAdapterTest {
    private RestTemplate rt;
    private VercelEdgeConfigProperties props;
    private VercelEdgeConfigAdapter adapter;

    @BeforeEach void setUp() {
        rt = mock(RestTemplate.class);
        props = new VercelEdgeConfigProperties();
        props.setId("ecfg_test"); props.setApiToken("token");
        adapter = new VercelEdgeConfigAdapter(rt, props);
    }

    @Test
    @DisplayName("write success — single PATCH")
    void success() {
        when(rt.exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class)))
            .thenReturn(ResponseEntity.ok().build());
        adapter.writeMaintenance(maintenance(), MaintenancePhase.PLANNED);
        verify(rt, times(1)).exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("write null entity — maintenance 키 null 로 set")
    void writeNull() {
        when(rt.exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class)))
            .thenReturn(ResponseEntity.ok().build());
        adapter.writeMaintenance(null, null);
        verify(rt, times(1)).exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("API 실패 → RuntimeException (재시도 없음)")
    void failNoRetry() {
        when(rt.exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class)))
            .thenThrow(new RestClientException("timeout"));
        assertThatThrownBy(() -> adapter.writeMaintenance(maintenance(), MaintenancePhase.PLANNED))
            .isInstanceOf(RuntimeException.class);
        verify(rt, times(1)).exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("teamId 설정 시 query param 추가")
    void teamId() {
        props.setTeamId("team_xyz");
        when(rt.exchange(contains("teamId=team_xyz"), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class)))
            .thenReturn(ResponseEntity.ok().build());
        adapter.writeMaintenance(maintenance(), MaintenancePhase.PLANNED);
        verify(rt, times(1)).exchange(contains("teamId=team_xyz"), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("id blank 면 skip (warn log)")
    void skipWhenIdBlank() {
        props.setId("");
        adapter.writeMaintenance(maintenance(), MaintenancePhase.PLANNED);
        verify(rt, never()).exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class));
    }

    private SystemAnnouncementData maintenance() {
        LocalDateTime s = LocalDateTime.parse("2026-05-04T03:00:00");
        return SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", s, s.plusHours(1), null, s.minusDays(1), 1L);
    }
}
```

- [ ] **Step 5: VercelEdgeConfigAdapter (GREEN)**

```java
package com.pfplaybackend.api.administration.adapter.out.edge;

import com.pfplaybackend.api.administration.adapter.out.edge.properties.VercelEdgeConfigProperties;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.port.EdgeConfigPort;
import com.pfplaybackend.api.administration.domain.value.MaintenancePhase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class VercelEdgeConfigAdapter implements EdgeConfigPort {

    private final RestTemplate restTemplate;
    private final VercelEdgeConfigProperties properties;

    @Override
    public void writeMaintenance(SystemAnnouncementData entity, MaintenancePhase phase) {
        if (properties.getId() == null || properties.getId().isBlank()) {
            log.warn("[EdgeConfig] VERCEL_EDGE_CONFIG_ID not set — skip write.");
            return;
        }
        String url = UriComponentsBuilder.fromHttpUrl(properties.getBaseUrl())
            .pathSegment("v1", "edge-config", properties.getId(), "items")
            .queryParamIfPresent("teamId",
                properties.getTeamId() != null && !properties.getTeamId().isBlank()
                    ? Optional.of(properties.getTeamId()) : Optional.<String>empty())
            .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getApiToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Object value = entity == null ? null : Map.of(
            "phase", phase.name(),
            "startAt", entity.getScheduledStartAt().toString(),
            "endAt", entity.getScheduledEndAt().toString(),
            "messageKo", entity.getMessageKo(),
            "messageEn", entity.getMessageEn());

        Map<String, Object> body = Map.of("items",
            List.of(Map.of("operation", "upsert", "key", "maintenance", "value", value)));

        try {
            restTemplate.exchange(url, HttpMethod.PATCH, new HttpEntity<>(body, headers), Void.class);
        } catch (RestClientException e) {
            log.error("[EdgeConfig] write failed (no retry — scheduler will re-attempt)", e);
            throw new RuntimeException("Vercel Edge Config write failed: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 6: Run** — `--tests VercelEdgeConfigAdapterTest`. 5 tests PASS.

---

## Task 4: 도메인 이벤트 + AnnouncementBroadcaster (listener)

**Files:** 3 events + broadcaster

- [ ] **Step 1: 3 도메인 이벤트** (immutable record 또는 Getter class)

```java
// administration/domain/event/AnnouncementPublishedEvent.java
public record AnnouncementPublishedEvent(SystemAnnouncementData entity) {}

// administration/domain/event/AnnouncementCancelledEvent.java
public record AnnouncementCancelledEvent(SystemAnnouncementData entity) {}

// administration/domain/event/MaintenanceStartedEvent.java
public record MaintenanceStartedEvent(SystemAnnouncementData entity) {}
```

(SystemAnnouncementData 가 entity 라 record 에 박아도 직렬화 안 됨 — `ApplicationEventPublisher` 만 거치는 in-process 이벤트라 OK)

- [ ] **Step 2: AnnouncementBroadcaster — WS broadcast + Edge Config write 묶음**

```java
package com.pfplaybackend.api.administration.adapter.out.event;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.*;
import com.pfplaybackend.api.administration.domain.port.EdgeConfigPort;
import com.pfplaybackend.api.administration.domain.value.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementBroadcaster {

    private static final String TOPIC = "/sub/system/announcements";

    private final SimpMessagingTemplate messagingTemplate;
    private final EdgeConfigPort edgeConfigPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(AnnouncementPublishedEvent event) {
        SystemAnnouncementData e = event.entity();
        broadcast("ANNOUNCEMENT_PUBLISHED", e, null);
        if (e.getType() == AnnouncementType.MAINTENANCE_NOTICE) {
            tryWriteEdgeConfig(e, MaintenancePhase.PLANNED);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(AnnouncementCancelledEvent event) {
        SystemAnnouncementData e = event.entity();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "ANNOUNCEMENT_CANCELLED");
        payload.put("announcementId", e.getId());
        payload.put("cancelledAt", e.getCancelledAt());
        messagingTemplate.convertAndSend(TOPIC, payload);
        // 점검이 ACTIVE 였으면 Edge Config maintenance 키도 종료
        if (e.getType() == AnnouncementType.MAINTENANCE_NOTICE && e.getMaintenanceStartedAt() != null) {
            tryWriteEdgeConfig(null, null);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(MaintenanceStartedEvent event) {
        broadcast("MAINTENANCE_STARTED", event.entity(), null);
        tryWriteEdgeConfig(event.entity(), MaintenancePhase.ACTIVE);
    }

    private void broadcast(String eventType, SystemAnnouncementData e, Object extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("announcementId", e.getId());
        payload.put("type", e.getType().name());
        payload.put("severity", e.getSeverity().name());
        payload.put("titleKo", e.getTitleKo());
        payload.put("titleEn", e.getTitleEn());
        payload.put("messageKo", e.getMessageKo());
        payload.put("messageEn", e.getMessageEn());
        payload.put("scheduledStartAt", e.getScheduledStartAt());
        payload.put("scheduledEndAt", e.getScheduledEndAt());
        payload.put("expiresAt", e.getExpiresAt());
        payload.put("sentAt", e.getSentAt());
        messagingTemplate.convertAndSend(TOPIC, payload);
    }

    private void tryWriteEdgeConfig(SystemAnnouncementData entity, MaintenancePhase phase) {
        try {
            edgeConfigPort.writeMaintenance(entity, phase);
        } catch (RuntimeException ex) {
            log.error("[Announcement] Edge Config write failed — DB state authoritative, " +
                      "scheduler will re-attempt next tick.", ex);
        }
    }
}
```

---

## Task 5: SystemAnnouncementCommandService + MaintenanceSchedulerService + service test

**Files:** CommandService, SchedulerService, 두 test

- [ ] **Step 1: SystemAnnouncementCommandService**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.*;
import com.pfplaybackend.api.administration.domain.exception.AnnouncementException;
import com.pfplaybackend.api.administration.domain.value.*;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

@Service
@RequiredArgsConstructor
public class SystemAnnouncementCommandService {

    private final SystemAnnouncementRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public Long publish(AnnouncementType type, AnnouncementSeverity severity,
                         String titleKo, String titleEn, String messageKo, String messageEn,
                         LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt, LocalDateTime expiresAt,
                         Long administratorId) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (type == AnnouncementType.MAINTENANCE_NOTICE && scheduledStartAt != null && !scheduledStartAt.isAfter(now)) {
            throw ExceptionCreator.create(AnnouncementException.SCHEDULED_START_IN_PAST);
        }
        SystemAnnouncementData entity = SystemAnnouncementData.create(
            type, severity, titleKo, titleEn, messageKo, messageEn,
            scheduledStartAt, scheduledEndAt, expiresAt, now, administratorId);
        SystemAnnouncementData saved = repository.save(entity);
        eventPublisher.publishEvent(new AnnouncementPublishedEvent(saved));
        return saved.getId();
    }

    @Transactional
    public void cancel(Long id, Long administratorId) {
        SystemAnnouncementData entity = repository.findById(id)
            .orElseThrow(() -> ExceptionCreator.create(AnnouncementException.ANNOUNCEMENT_NOT_FOUND));
        entity.cancel(administratorId, clock);
        eventPublisher.publishEvent(new AnnouncementCancelledEvent(entity));
    }
}
```

- [ ] **Step 2: MaintenanceSchedulerService**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.MaintenanceStartedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceSchedulerService {

    private final SystemAnnouncementRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Scheduled(cron = "0 * * * * *")   // 매분 0초
    @Transactional
    public void promoteScheduledMaintenance() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<SystemAnnouncementData> due = repository.findDueForMaintenanceActivation(now);
        for (SystemAnnouncementData entity : due) {
            entity.markMaintenanceStarted(clock);
            eventPublisher.publishEvent(new MaintenanceStartedEvent(entity));
        }
    }
}
```

- [ ] **Step 3: `@EnableScheduling` 확인** — 이미 main `Application.java` 또는 별 `@Configuration` 에 있는지 확인. 없으면 `application/service/` 옆 또는 boot config 에 한 줄 추가:
```java
@EnableScheduling
@Configuration
public class SchedulingConfig {}
```
(기존 코드에 이미 있을 가능성 높음 — `grep -r "@EnableScheduling" app/src/main/java` 로 확인)

- [ ] **Step 4: Service test** (mock 기반, 둘 다 같은 파일 또는 분리)

```java
// SystemAnnouncementCommandServiceTest — Mock repo + eventPublisher
@Test void publish_publishesEvent() { ... }
@Test void publish_pastSchedule_throws() { ... }
@Test void cancel_emitsCancelledEvent() { ... }
@Test void cancel_notFound_throws() { ... }

// MaintenanceSchedulerServiceTest
@Test void due_marksAndEmits() { ... }
@Test void none_due_noOp() { ... }
```

(테스트 코드 자세히는 작성하면서 채움 — Mockito 표준 패턴, 기존 `AdminReportCommandServiceTest` 등 참조)

---

## Task 6: AdminAnnouncementController + DTOs + WebMvc test

**Files:** controller, 3 DTO, WebMvc test

- [ ] **Step 1: AnnouncementCreateRequest**

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.pfplaybackend.api.administration.domain.value.*;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

public record AnnouncementCreateRequest(
    @NotNull AnnouncementType type,
    @NotNull AnnouncementSeverity severity,
    @NotBlank @Size(max=200) String titleKo,
    @NotBlank @Size(max=200) String titleEn,
    @NotBlank @Size(max=2000) String messageKo,
    @NotBlank @Size(max=2000) String messageEn,
    LocalDateTime scheduledStartAt,
    LocalDateTime scheduledEndAt,
    LocalDateTime expiresAt
) {}
```

- [ ] **Step 2: AnnouncementSummaryResponse**

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.value.*;
import java.time.LocalDateTime;

public record AnnouncementSummaryResponse(
    Long id, AnnouncementType type, AnnouncementSeverity severity,
    String titleKo, String titleEn, String messageKo, String messageEn,
    LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt, LocalDateTime expiresAt,
    LocalDateTime sentAt, Long sentByAdministratorId,
    LocalDateTime maintenanceStartedAt, LocalDateTime cancelledAt
) {
    public static AnnouncementSummaryResponse from(SystemAnnouncementData e) {
        return new AnnouncementSummaryResponse(
            e.getId(), e.getType(), e.getSeverity(),
            e.getTitleKo(), e.getTitleEn(), e.getMessageKo(), e.getMessageEn(),
            e.getScheduledStartAt(), e.getScheduledEndAt(), e.getExpiresAt(),
            e.getSentAt(), e.getSentByAdministratorId(),
            e.getMaintenanceStartedAt(), e.getCancelledAt());
    }
}
```

- [ ] **Step 3: AdminAnnouncementController**

```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.AnnouncementCreateRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AnnouncementSummaryResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.application.service.SystemAnnouncementCommandService;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.ThreadLocalContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/announcements")
@Validated
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final SystemAnnouncementCommandService commandService;
    private final SystemAnnouncementRepository repository;

    @PostMapping
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<Map<String, Long>>> publish(
            @Valid @RequestBody AnnouncementCreateRequest req) {
        Long administratorId = ThreadLocalContext.getAuthContext().getAdministratorId();
        Long id = commandService.publish(req.type(), req.severity(),
            req.titleKo(), req.titleEn(), req.messageKo(), req.messageEn(),
            req.scheduledStartAt(), req.scheduledEndAt(), req.expiresAt(), administratorId);
        return ResponseEntity.status(201).body(ApiCommonResponse.ok(Map.of("announcementId", id)));
    }

    @GetMapping
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<Page<AnnouncementSummaryResponse>>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        Page<AnnouncementSummaryResponse> result = repository
            .findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt")))
            .map(AnnouncementSummaryResponse::from);
        return ResponseEntity.ok(ApiCommonResponse.ok(result));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<Void>> cancel(@PathVariable @Min(1) Long id) {
        Long administratorId = ThreadLocalContext.getAuthContext().getAdministratorId();
        commandService.cancel(id, administratorId);
        return ResponseEntity.ok(ApiCommonResponse.ok());
    }
}
```

- [ ] **Step 4: AdminAnnouncementControllerTest** — `@WebMvcTest` (기존 admin controller test 패턴, e.g. `AdminReportCommandControllerTest`)

```java
// 5-6 case: publish 201 / publish bad payload 400 / publish unauthorized 401 (or 403) /
//           list 200 / delete 200 / delete not_found 404
```

(상세 코드는 기존 패턴 참조하여 작성)

---

## Task 7: SystemStatusController + QueryService + WebMvc test

**Files:** controller, query service, response DTO, test

- [ ] **Step 1: SystemStatusResponse**

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import java.time.LocalDateTime;
import java.util.List;

public record SystemStatusResponse(
    MaintenanceInfo maintenance,
    List<AnnouncementSummaryResponse> activeAnnouncements,
    List<MaintenanceInfo> plannedMaintenance
) {
    public record MaintenanceInfo(
        String phase, LocalDateTime startAt, LocalDateTime endAt,
        String messageKo, String messageEn) {}
}
```

- [ ] **Step 2: SystemAnnouncementQueryService**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.*;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.SystemStatusResponse.MaintenanceInfo;
import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SystemAnnouncementQueryService {

    private final SystemAnnouncementRepository repository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public SystemStatusResponse getSystemStatus() {
        LocalDateTime now = LocalDateTime.now(clock);
        MaintenanceInfo current = repository.findCurrentMaintenance()
            .map(e -> toInfo(e, "ACTIVE")).orElse(null);
        List<AnnouncementSummaryResponse> active = repository.findActivePublic(now).stream()
            .map(AnnouncementSummaryResponse::from).toList();
        List<MaintenanceInfo> planned = repository.findPlannedMaintenance(now).stream()
            .map(e -> toInfo(e, "PLANNED")).toList();
        return new SystemStatusResponse(current, active, planned);
    }

    private MaintenanceInfo toInfo(SystemAnnouncementData e, String phase) {
        return new MaintenanceInfo(phase, e.getScheduledStartAt(), e.getScheduledEndAt(),
            e.getMessageKo(), e.getMessageEn());
    }
}
```

- [ ] **Step 3: SystemStatusController**

```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.SystemStatusResponse;
import com.pfplaybackend.api.administration.application.service.SystemAnnouncementQueryService;
import com.pfplaybackend.api.common.ApiCommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemStatusController {

    private final SystemAnnouncementQueryService queryService;

    @GetMapping("/status")
    public ResponseEntity<ApiCommonResponse<SystemStatusResponse>> status() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(10)).cachePublic())
            .body(ApiCommonResponse.ok(queryService.getSystemStatus()));
    }
}
```

- [ ] **Step 4: SecurityConfig 확인 — `/api/v1/system/status` 익명 허용**

기존 `SecurityConfig` 의 `permitAll()` matcher 목록에 추가 필요 (만약 default deny 면). `grep -n "permitAll\|/api/v1/" common/src/main/java/.../SecurityConfig.java` 로 확인 후 적절히.

- [ ] **Step 5: SystemStatusControllerTest** — `@WebMvcTest` 익명 호출 200 응답

---

## Task 8: 통합 IT (publish → ws broadcast end-to-end) + 빌드 검증 + 단일 commit

**Files:** `AnnouncementBroadcasterIT.java`, 마지막 빌드 + commit

- [ ] **Step 1: AnnouncementBroadcasterIT** — `@SpringBootTest` + STOMP test client (기존 ws IT 패턴 참조 — 없으면 본 IT 가 첫 ws IT)

```java
// publish 호출 → ws subscriber 가 1초 안에 ANNOUNCEMENT_PUBLISHED payload 수신 검증
// payload snapshot 검증: publish 직후 cancel 호출해도 PUBLISHED 페이로드는 발사 시점 그대로
```

(STOMP test client setup 은 spring-messaging StompSession + StandardWebSocketClient 패턴 — 자세한 코드는 작성 시 결정)

- [ ] **Step 2: 전체 빌드 + 모든 테스트 PASS**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: ArchUnit 검증** — annotation-driven rule 이 자동 cover. 별도 작업 0.

- [ ] **Step 4: 사용자 작업 — `.env.local` 에 Vercel 키 추가**

```
VERCEL_EDGE_CONFIG_ID=ecfg_xxxxxxxx
VERCEL_API_TOKEN=xxxxxxxx
# VERCEL_TEAM_ID=team_xxx       # Team account 인 경우만
```

(만약 사용자가 아직 토큰 발급 안 했으면 — adapter id blank 시 warn-skip 하므로 boot/test 자체는 가능)

- [ ] **Step 5: 단일 commit**

```bash
git -C "C:/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-platform" add \
  app/src/main/resources/db/migration/V14__create_system_announcement.sql \
  app/src/main/resources/application.yml \
  app/src/main/java/com/pfplaybackend/api/administration/ \
  app/src/test/java/com/pfplaybackend/api/administration/

git -C "C:/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-platform" commit -m "$(cat <<'EOF'
feat(announcement): V14 system announcement + maintenance fallback

Adds the system-announcement + maintenance-mode fallback feature
(spec docs/superpowers/specs/2026-05-03-system-announcement-design.md):

- V14 migration (system_announcement table) with i18n 4-column split,
  CHECK constraint enforcing scheduled_*at non-null for MAINTENANCE_NOTICE,
  FK to administrator for audit, indexes for active-maintenance + sent_at DESC.

- administration BC additions:
  - 3 enums (AnnouncementType, AnnouncementSeverity, MaintenancePhase)
  - SystemAnnouncementData aggregate with factory invariants per spec §3.5
    + state-transition methods (markMaintenanceStarted, cancel)
  - 3 in-process domain events (Published/Cancelled/MaintenanceStarted)
  - SystemAnnouncementCommandService (publish, cancel) — @Transactional
  - SystemAnnouncementQueryService (system status) — @Transactional(readOnly)
  - MaintenanceSchedulerService — @Scheduled cron("0 * * * * *") promotes
    PLANNED → ACTIVE for due maintenance windows
  - AnnouncementBroadcaster — @TransactionalEventListener(AFTER_COMMIT)
    fans out to STOMP /sub/system/announcements + Edge Config write
    (single attempt, scheduler re-attempts on next tick if Vercel fails)

- Outbound adapters:
  - SystemAnnouncementRepository (5 query methods covering admin list +
    public status + scheduler due query)
  - VercelEdgeConfigAdapter implementing EdgeConfigPort — PATCH
    /v1/edge-config/{id}/items via the existing shared RestTemplate

- REST endpoints:
  - POST   /api/v1/admin/announcements    (admin, @PreAuthorize)
  - GET    /api/v1/admin/announcements    (admin paged history)
  - DELETE /api/v1/admin/announcements/{id}  (admin cancel)
  - GET    /api/v1/system/status            (public, Cache-Control 10s)

- Tests: factory invariants, repository IT (5 query methods), adapter
  unit tests (Vercel API mock), command/scheduler service unit tests,
  WebMvc tests for both controllers, end-to-end broadcaster IT verifying
  publish → STOMP delivery with snapshot-payload semantics (avoids the
  late-binding trap documented in reference_first_dj_silent_deactivate.md).

frontend (admin G21~ + web boot fallback) lands in separate plans.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: HEAD 검증**

```bash
git -C "C:/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-platform" log --oneline -3
```
Expected: 새 commit on top of `ea2e3b47`.
