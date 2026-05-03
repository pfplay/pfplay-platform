# 시스템 공지 + 점검 모드 fallback (V14) — Design

> Brainstormed design spec. Implementation plan is generated separately via `superpowers:writing-plans`.

**Date:** 2026-05-03
**Branch:** `feature/admin-auth-iam-schema` (admin platform 시리즈 위에 누적)
**Migration:** V14 — `system_announcement`
**Bounded Context:** administration (신규 BC 만들지 않고 attach)

---

## 1. Goal & Scope

### 1.1 Goal

운영자가 사용자에게 두 종류의 메시지를 송출할 수 있게 한다:

1. **즉시 공지** (이벤트/긴급) — admin 콘솔 발사 → 활성 세션에 ws push로 즉시 도달
2. **예약 점검 공지** (`MAINTENANCE_NOTICE`) — admin이 예약 시각 + 종료 시각으로 등록 → 예약 시각 도달 시 자동으로 점검 모드 phase ACTIVE 토글 → 종료는 admin이 명시적으로 DELETE 호출

점검 모드 ACTIVE 동안에도 신규 사용자가 사이트에 접속할 수 있어야 하며 (= ws 끊긴 사용자도 부팅 시점에 점검 상태를 알 수 있어야 함), 이를 위해 backend 가 Vercel Edge Config 에 점검 상태를 write 하고 frontend 는 부팅 시 Edge Config 를 먼저 읽는 fallback 경로를 갖는다.

### 1.2 In Scope (이 PR 시리즈)

- V14 `system_announcement` 테이블 마이그레이션
- 도메인: `SystemAnnouncement` aggregate + 4개 type enum + 3개 severity enum + i18n 4컬럼 (title_ko/title_en/message_ko/message_en)
- Admin REST: `POST /api/v1/admin/announcements` (발사) / `GET /api/v1/admin/announcements` (이력) / `DELETE /api/v1/admin/announcements/{id}` (철회/조기종료)
- Public REST: `GET /api/v1/system/status` (인증 불필요, 부팅 fallback용)
- WebSocket broadcast: `/sub/system/announcements` (인증된 모든 세션)
- Scheduler: `@Scheduled(cron = "0 * * * * *")` 1분 정밀도로 예약 시각 도달한 `MAINTENANCE_NOTICE` 를 phase ACTIVE 토글
- Vercel Edge Config 연동: backend 가 announcement publish/scheduler tick/DELETE 시점에 PATCH
- Frontend pfplay-web: 부팅 시 Edge Config 읽기 + WS 구독 + maintenance overlay
- Frontend pfplay-admin: 공지 발사/이력/취소 UI (G21~ chunk)

### 1.3 Out of Scope

- **인프라 graceful shutdown / scale-to-zero** — 본 PR 은 backend 가 점검 시작 시점에 자동으로 인프라를 끄지 않음. 점검 모드는 application-level signal 일 뿐, GCE VM 은 그대로 가동 유지
- **사용자 inbox / 공지 보관함** — broadcast 1회성. 공지 도착 후 close 되면 사라짐. DB 에는 audit/이력으로만 보관
- **사용자별 read/unread 상태 관리** — 위와 같은 이유
- **공지 modify (수정)** — POST/DELETE 만. UPDATE 없음. 잘못 발사한 공지는 DELETE 후 재발사
- **공지 audience 분기** (특정 partyroom/특정 tier 만) — broadcast 는 인증된 모든 세션. anonymous 진입 경로 자체 없음
- **점검 모드 종료 자동화** — admin 이 명시 DELETE 호출. backend 는 `scheduled_end_at` 도달 시점에 자동 종료 안 함 (운영자 의도적 결정 — 점검이 늘어질 가능성에 대비)

### 1.4 Milestone 위치

admin platform M5/M6 시리즈가 develop/stg 까지 ship 된 상태에서 그 위에 V14 가 누적되는 흐름. **prod 미배포 admin 시리즈가 먼저 prod ship 되어야 본 PR 도 prod 로 갈 수 있음** (announcement REST가 `@adminAuth.isAdmin()` 의존). `project_admin_platform_prod_ship_pending.md` 참조.

---

## 2. Architecture

### 2.1 전체 흐름

```
┌────────────┐  POST /admin/announcements  ┌─────────┐
│ pfplay-    │ ──────────────────────────▶ │ backend │
│ admin (UI) │                              │  (GCE)  │
└────────────┘                              └────┬────┘
                                                 │
                                  ┌──────────────┼──────────────┐
                                  │              │              │
                                  ▼              ▼              ▼
                          ┌──────────┐  ┌──────────────┐  ┌────────────┐
                          │ DB       │  │ Redis pub/   │  │ Vercel     │
                          │ V14 row  │  │ sub          │  │ Edge       │
                          │          │  │              │  │ Config     │
                          └──────────┘  └──────┬───────┘  │ (write)    │
                                               │          └─────┬──────┘
                                               ▼                │
                                        ┌─────────────┐         │
                                        │ STOMP /topic│         │
                                        │ /system/    │         │
                                        │ announcements│        │
                                        └──────┬──────┘         │
                                               │                │
                              ┌────────────────┴────┐           │
                              ▼                     ▼           │
                       ┌───────────┐         ┌───────────┐      │
                       │ pfplay-web│         │ pfplay-   │      │
                       │ (active   │         │ admin     │      │
                       │ session)  │         │ (active)  │      │
                       └───────────┘         └───────────┘      │
                                                                │
                                                                ▼
                                                        ┌───────────────┐
                                                        │ pfplay-web    │
                                                        │ (boot — read  │
                                                        │  Edge Config  │
                                                        │  → maintenance│
                                                        │  overlay)     │
                                                        └───────────────┘
```

### 2.2 두 경로의 보완 관계

| 경로 | 대상 | trigger | latency |
|---|---|---|---|
| **WS broadcast** | 활성 세션 보유자 | 즉시 | 수백 ms |
| **Edge Config fallback** | 부팅 중인 새 세션 (또는 ws 끊긴 사용자가 새로고침) | 부팅 시점 | 수십 ms (Edge runtime) |

ws 만으로는 새 사용자 / ws 끊긴 사용자가 점검 모드를 인지 못 함. Edge Config 만으로는 활성 세션이 즉시 알 수 없음. 둘 다 필요.

### 2.3 BC 위치

신규 BC 만들지 않고 **administration BC** 에 attach. 이유:
- 공지 발사 권한이 admin 전용 (announcement aggregate 의 lifecycle 이 admin action 과 묶임)
- audit 정보 (`sent_by_administrator_id`, `cancelled_by_administrator_id`) 가 administrator FK
- 별도 BC 만들면 cross-BC reference 만 늘어남

---

## 3. Data Model

### 3.1 V14 마이그레이션

`flyway/migrations/V14__create_system_announcement.sql`:

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
            scheduled_start_at IS NOT NULL
            AND scheduled_end_at IS NOT NULL
            AND scheduled_end_at > scheduled_start_at
        )
    ),
    CONSTRAINT fk_announcement_sent_by
        FOREIGN KEY (sent_by_administrator_id) REFERENCES administrator(administrator_id),
    CONSTRAINT fk_announcement_cancelled_by
        FOREIGN KEY (cancelled_by_administrator_id) REFERENCES administrator(administrator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 3.2 컬럼 의미

| 컬럼 | 의미 |
|---|---|
| `type` | `MAINTENANCE_NOTICE` / `EVENT` / `EMERGENCY` |
| `severity` | `INFO` / `WARN` / `CRITICAL` (frontend UI 가 색상/위치 분기에 사용) |
| `title_*` / `message_*` | i18n 4컬럼 분리 (key+params 방식 아님 — 컨텐츠 운영자가 자유 작성) |
| `scheduled_start_at` | `MAINTENANCE_NOTICE` 만 사용. 점검 시작 예약 시각. 도달 시 scheduler 가 phase ACTIVE 토글 |
| `scheduled_end_at` | `MAINTENANCE_NOTICE` 만 사용. 점검 종료 예정 시각 (UI 표시용). backend 자동 종료 안 함 |
| `expires_at` | `EVENT`/`EMERGENCY` 의 노출 만료 시각 (선택). frontend toast 자동 dismiss 시점 |
| `sent_at` | DB INSERT 시점 (= 공지 발사 시점) |
| `sent_by_administrator_id` | 발사자 audit |
| `maintenance_started_at` | scheduler 가 phase ACTIVE 토글한 시각. NULL = 아직 시작 안 됨 / 시작 후엔 timestamp |
| `cancelled_at` | admin 이 DELETE 호출한 시각 (철회/조기종료). NULL = 활성 |
| `cancelled_by_administrator_id` | 취소자 audit |

### 3.3 도메인 enum

```java
// administration/domain/value/AnnouncementType.java
public enum AnnouncementType {
    MAINTENANCE_NOTICE,   // 점검 공지 (scheduled_*at 필수)
    EVENT,                // 이벤트 공지
    EMERGENCY;            // 긴급 공지

    public boolean requiresSchedule() {
        return this == MAINTENANCE_NOTICE;
    }
}

// administration/domain/value/AnnouncementSeverity.java
public enum AnnouncementSeverity {
    INFO, WARN, CRITICAL
}
```

### 3.4 Aggregate

`administration/domain/entity/data/SystemAnnouncementData` (BaseEntity 상속):

```java
@Entity
@Table(name = "system_announcement")
@Getter
public class SystemAnnouncementData extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    private AnnouncementType type;
    @Enumerated(EnumType.STRING)
    private AnnouncementSeverity severity;
    private String titleKo;
    private String titleEn;
    private String messageKo;
    private String messageEn;
    private LocalDateTime scheduledStartAt;
    private LocalDateTime scheduledEndAt;
    private LocalDateTime expiresAt;
    private LocalDateTime sentAt;
    private Long sentByAdministratorId;
    private LocalDateTime maintenanceStartedAt;
    private LocalDateTime cancelledAt;
    private Long cancelledByAdministratorId;

    // 상태 전이 메서드 (모두 invariant guard 선행)
    public void markMaintenanceStarted(Clock clock) { ... }   // scheduler 가 호출
    public void cancel(Long administratorId, Clock clock) { ... }   // admin DELETE 가 호출

    public boolean isActive(LocalDateTime now) {
        if (cancelledAt != null) return false;
        if (expiresAt != null && expiresAt.isBefore(now)) return false;
        return true;
    }

    public boolean isMaintenancePhaseActive() {
        return type == AnnouncementType.MAINTENANCE_NOTICE
            && maintenanceStartedAt != null
            && cancelledAt == null;
    }
}
```

### 3.5 컬럼 invariant 표 (type 별 NULL 강제)

DB CHECK 제약 (§3.1) + service-layer 검증 (§4.1) + 도메인 메서드 guard 가 일관되게 강제하는 컬럼 상태:

| 컬럼 | `MAINTENANCE_NOTICE` | `EVENT` / `EMERGENCY` |
|---|---|---|
| `scheduled_start_at` | **NOT NULL** + 미래 시각 | **NULL 강제** |
| `scheduled_end_at` | **NOT NULL** + `> scheduled_start_at` | **NULL 강제** |
| `expires_at` | **NULL 강제** (점검은 admin DELETE 가 종료, expires_at 자동 종료 의미론과 충돌) | NULL 또는 미래 시각 |
| `maintenance_started_at` | NULL → scheduler 가 채움 | **NULL 강제** (영구) |
| `cancelled_at` | NULL → admin DELETE 가 채움 | NULL → admin DELETE 가 채움 |

`scheduled_start_at`/`scheduled_end_at` 의 **NULL 강제** (`type != MAINTENANCE_NOTICE` 일 때) 는 DB CHECK 제약에 없고 service-layer 에서만 보장 — 실수 방지. 도메인 factory 메서드가 type 분기로 강제.

---

## 4. REST API

### 4.1 `POST /api/v1/admin/announcements` — 발사

- **Auth**: `@PreAuthorize("@adminAuth.isAdmin()")`
- **Body**: `AnnouncementCreateRequest`:
  ```java
  public record AnnouncementCreateRequest(
      @NotNull AnnouncementType type,
      @NotNull AnnouncementSeverity severity,
      @NotBlank @Size(max=200) String titleKo,
      @NotBlank @Size(max=200) String titleEn,
      @NotBlank @Size(max=2000) String messageKo,
      @NotBlank @Size(max=2000) String messageEn,
      LocalDateTime scheduledStartAt,    // MAINTENANCE_NOTICE 만 필수
      LocalDateTime scheduledEndAt,      // MAINTENANCE_NOTICE 만 필수
      LocalDateTime expiresAt            // EVENT/EMERGENCY 옵션
  ) {}
  ```
- **검증** (service-layer):
  - `type == MAINTENANCE_NOTICE` → `scheduledStartAt`/`scheduledEndAt` non-null + `scheduledEndAt > scheduledStartAt` + `scheduledStartAt > now` (과거 예약 거부)
  - `type != MAINTENANCE_NOTICE` → `scheduledStartAt`/`scheduledEndAt` null 강제 (실수 방지)
  - **overlap 검증 안 함** — last-write-wins 허용. Edge Config 단일 슬롯이라 두 점검 동시 예약 시 두번째가 덮어씀. 운영 정책: "한 번에 한 점검만" — 운영자 책임. (first cut 단순화, 결함 발생 시 evolve)
- **Response**: `201 Created`, `ApiCommonResponse<AnnouncementCreateResponse(announcementId)>`
- **부수 효과**:
  - V14 INSERT
  - WS broadcast `/sub/system/announcements` (event type: `ANNOUNCEMENT_PUBLISHED`)
  - Edge Config write (해당 type 이 `MAINTENANCE_NOTICE` 인 경우만 — `maintenance.phase = "PLANNED"`)

### 4.2 `GET /api/v1/admin/announcements` — 이력

- **Auth**: admin
- **QueryParams**: `page` (`@Min(0)`), `size` (`@Min(1) @Max(200)`), `type` (List, optional), `status` (`active`/`cancelled`/`all`, optional, 기본 `all`), `sort` (`sent_at_desc`/`sent_at_asc`)
- **Response**: `200 OK`, `ApiCommonResponse<Page<AnnouncementSummaryResponse>>`
- `AnnouncementSummaryResponse` 는 i18n 4컬럼 모두 포함 (admin UI 가 두 언어 동시 표시 가능)

### 4.3 `DELETE /api/v1/admin/announcements/{id}` — 철회/조기종료

- **Auth**: admin
- **Response**: `200 OK`, `ApiCommonResponse<Void>`
- **처리**:
  - 이미 `cancelled_at != null` → `409 ALREADY_CANCELLED`
  - 그 외 → `cancelled_at = now`, `cancelled_by_administrator_id = currentAdmin`
  - WS broadcast `/sub/system/announcements` (event type: `ANNOUNCEMENT_CANCELLED`)
  - 해당 announcement 가 `MAINTENANCE_NOTICE` + `maintenance_started_at != null` (즉 점검 모드 ACTIVE 였음) → Edge Config write (`maintenance: null` — 즉 점검 종료 신호)

### 4.4 `GET /api/v1/system/status` — 부팅 fallback

- **Auth**: 불필요 (anonymous 허용)
- **Response**: `200 OK`, `ApiCommonResponse<SystemStatusResponse>`:
  ```java
  public record SystemStatusResponse(
      MaintenanceInfo maintenance,                  // null = 점검 모드 아님
      List<ActiveAnnouncementSummary> activeAnnouncements,    // 활성 EVENT/EMERGENCY
      List<PlannedMaintenanceSummary> plannedMaintenance      // 예약된 MAINTENANCE_NOTICE
  ) {}

  public record MaintenanceInfo(
      MaintenancePhase phase,    // PLANNED | ACTIVE
      LocalDateTime startAt,
      LocalDateTime endAt,
      String messageKo,
      String messageEn
  ) {}
  ```
- frontend 가 부팅 시 Edge Config 먼저 읽고, Edge Config 가 unreachable / stale 의심되면 본 endpoint 로 source of truth 재확인
- **Cache-Control**: `public, max-age=10` (10초). 부팅 fallback 트래픽이 page-load 마다 backend 를 hit 하지 않도록. `public` 명시 — anonymous endpoint 라 CDN/edge intermediary 가 캐시 가능해야 함 (`private` 기본값으로 떨어지면 intermediary 우회). Edge Config eventual consistency (§9.5) 가 ~10초 윈도우라 동일 윈도우로 정렬. ETag 는 도입 안 함 (응답 자체가 작아서 condition GET 부담 < 그 자체 응답 부담)

---

## 5. Domain & Application

### 5.1 Layered 구조

```
administration/
├── domain/
│   ├── entity/data/SystemAnnouncementData.java
│   ├── value/AnnouncementType.java
│   ├── value/AnnouncementSeverity.java
│   ├── value/MaintenancePhase.java
│   ├── exception/AnnouncementException.java
│   └── port/EdgeConfigPort.java               ← outbound port (interface)
├── application/
│   ├── service/SystemAnnouncementCommandService.java   ← @Transactional
│   ├── service/SystemAnnouncementQueryService.java     ← @Transactional(readOnly=true)
│   └── service/MaintenanceSchedulerService.java        ← @Scheduled
├── adapter/
│   ├── in/web/AdminAnnouncementController.java
│   ├── in/web/SystemStatusController.java              ← public endpoint
│   ├── out/persistence/SystemAnnouncementRepository.java
│   ├── out/event/AnnouncementBroadcaster.java          ← WS publish
│   └── out/edge/VercelEdgeConfigAdapter.java           ← EdgeConfigPort 구현
```

### 5.2 EdgeConfigPort 인터페이스

```java
// domain/port/EdgeConfigPort.java
public interface EdgeConfigPort {
    /**
     * 점검 상태를 Edge Config 에 write.
     * @param entity null = 점검 종료 (Edge Config maintenance 키를 null 로 set).
     *               non-null + phase = MAINTENANCE_NOTICE entity 면 그 데이터로 upsert.
     * @param phase  null entity 일 때 무시. non-null entity 의 PLANNED/ACTIVE 표시.
     * 실패 시 RuntimeException throw — listener 가 swallow + ERROR log.
     * 재시도 안 함 — scheduler 가 매분 다시 evaluate 하므로 transient fail 은 자연 복구.
     */
    void writeMaintenance(SystemAnnouncementData entity, MaintenancePhase phase);
}
```

VercelEdgeConfigAdapter 구현 (first cut 단순화):
- 기존 공용 `RestTemplate` (`RestTemplateConfig`) 직접 사용 — timeout 적용 안 함 (Vercel API 가 안정적, 추후 결함 발생 시 timeout 추가)
- `PATCH https://api.vercel.com/v1/edge-config/{configId}/items`
- Header: `Authorization: Bearer {VERCEL_API_TOKEN}`
- Team account 면 query param `?teamId={VERCEL_TEAM_ID}` 추가
- 1회 호출 + 실패 시 RuntimeException throw — adapter 내부 retry 없음. listener 가 잡아서 ERROR log 만. 다음 cron tick 이 자연 재시도

### 5.3 SystemAnnouncementCommandService

```java
@Transactional
public Long publish(AnnouncementCreateRequest req, Long administratorId) {
    AnnouncementType.requiresSchedule()/ severity 등 검증
    SystemAnnouncementData entity = SystemAnnouncementData.create(...);
    entity = repository.save(entity);
    eventPublisher.publishEvent(new AnnouncementPublishedEvent(entity));
    return entity.getId();
}

@Transactional
public void cancel(Long announcementId, Long administratorId) {
    SystemAnnouncementData entity = repository.findById(...).orElseThrow(...);
    entity.cancel(administratorId, clock);
    eventPublisher.publishEvent(new AnnouncementCancelledEvent(entity));
}
```

`@TransactionalEventListener(AFTER_COMMIT)` 이 두 outbound 액션을 일괄 처리:
- WS broadcast (AnnouncementBroadcaster)
- Edge Config write (조건부 — `MAINTENANCE_NOTICE` 만)

### 5.4 MaintenanceSchedulerService

```java
@Service
@RequiredArgsConstructor
public class MaintenanceSchedulerService {

    private final SystemAnnouncementRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * 1분 정밀도. 매분 0초마다 도달한 점검 예약을 phase ACTIVE 로 토글.
     */
    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void promoteScheduledMaintenance() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<SystemAnnouncementData> due = repository
            .findDueForMaintenanceActivation(now);   // type=MAINTENANCE_NOTICE
                                                      // AND maintenance_started_at IS NULL
                                                      // AND scheduled_start_at <= now
                                                      // AND scheduled_end_at > now
                                                      // AND cancelled_at IS NULL
        for (var entity : due) {
            entity.markMaintenanceStarted(clock);
            eventPublisher.publishEvent(new MaintenanceStartedEvent(entity));
        }
    }
}
```

`AFTER_COMMIT` listener 가:
- WS broadcast (`MAINTENANCE_STARTED` event)
- Edge Config write (`maintenance.phase = "ACTIVE"`)

### 5.5 Repository 메서드 표

`SystemAnnouncementRepository extends JpaRepository<SystemAnnouncementData, Long>`:

| 메서드 | 용도 | 호출자 |
|---|---|---|
| `findById(Long)` | 단건 lookup (DELETE 분기) | CommandService |
| `save(...)` (JPA 기본) | INSERT (publish) / UPDATE (cancel, markMaintenanceStarted) | CommandService, SchedulerService |
| `findDueForMaintenanceActivation(LocalDateTime now)` | scheduler tick 의 "예약 시각 도달" 후보 — `type='MAINTENANCE_NOTICE' AND maintenance_started_at IS NULL AND scheduled_start_at <= :now AND scheduled_end_at > :now AND cancelled_at IS NULL` | SchedulerService |
| `findActivePublic(LocalDateTime now)` | `/system/status` 의 `activeAnnouncements` — `cancelled_at IS NULL AND (expires_at IS NULL OR expires_at > :now) AND type IN ('EVENT','EMERGENCY')` ORDER BY `sent_at DESC` | QueryService |
| `findCurrentMaintenance(LocalDateTime now)` | `/system/status` 의 `maintenance` field — `type='MAINTENANCE_NOTICE' AND maintenance_started_at IS NOT NULL AND cancelled_at IS NULL` LIMIT 1 (단일 활성 보장 §4.1) | QueryService |
| `findPlannedMaintenance(LocalDateTime now)` | `/system/status` 의 `plannedMaintenance` — `type='MAINTENANCE_NOTICE' AND maintenance_started_at IS NULL AND cancelled_at IS NULL AND scheduled_start_at > :now` ORDER BY `scheduled_start_at ASC` | QueryService |
| `findOverlappingMaintenance(LocalDateTime start, LocalDateTime end)` | publish 시 overlap 검증 (§4.1) — `type='MAINTENANCE_NOTICE' AND cancelled_at IS NULL AND scheduled_start_at < :end AND scheduled_end_at > :start` | CommandService |
| `findAllForAdmin(Pageable, type filter, status filter)` | admin GET 이력 페이지 | QueryService (admin) |

모두 Spring Data method query 또는 `@Query` JPQL — native SQL 불필요.

---

## 6. WebSocket

### 6.1 Topic

`/sub/system/announcements` — 단일 토픽 (partyroom 단위 분기 없음)

### 6.2 Audience

인증된 모든 STOMP 세션 (anonymous 진입 경로 자체 없음 — GUEST + MEMBER 자동 포함)

### 6.3 Event types

| eventType | 발생 시점 | payload 핵심 필드 |
|---|---|---|
| `ANNOUNCEMENT_PUBLISHED` | POST /admin/announcements 직후 (AFTER_COMMIT) | `announcementId, type, severity, titleKo, titleEn, messageKo, messageEn, scheduledStartAt, scheduledEndAt, expiresAt, sentAt` |
| `ANNOUNCEMENT_CANCELLED` | DELETE /admin/announcements/{id} 직후 | `announcementId, cancelledAt` |
| `MAINTENANCE_STARTED` | scheduler tick 으로 phase ACTIVE 토글 시 | `announcementId, severity, scheduledStartAt, scheduledEndAt, messageKo, messageEn` |

### 6.4 Payload 의미론

`ANNOUNCEMENT_PUBLISHED` 의 payload 는 **이벤트 발행 시점 snapshot**. 즉 `DjQueueChangedEvent` 에서 발견된 late-binding 함정 (`reference_first_dj_silent_deactivate.md`) 을 본 spec 에서는 회피:

```java
// AnnouncementBroadcaster.java
@TransactionalEventListener(phase = AFTER_COMMIT)
public void on(AnnouncementPublishedEvent event) {
    // ★ payload 를 entity snapshot 으로 직접 구성 — late-lookup query 안 함
    AnnouncementBroadcastPayload payload = AnnouncementBroadcastPayload.from(event.entity());
    simpMessagingTemplate.convertAndSend("/sub/system/announcements", payload);
}
```

이렇게 하면 발행 후 즉시 DELETE 가 일어나도 PUBLISHED 이벤트는 발사 시점 그대로 도착 (DELETE 는 별도 CANCELLED 이벤트로 도착).

### 6.5 Reuse vs 신규 — 결정

기존 `realtime/sender/SimpMessageSender.sendToGroup` 는 partyroom 단위 broadcast (group ID 기반 토픽) 라 시스템 공지에 부적합. **§5.1 의 `AnnouncementBroadcaster` 가 `SimpMessagingTemplate` 을 직접 주입받아 `/sub/system/announcements` 로 convertAndSend** — `SimpMessageSender` 확장 안 함.

근거:
- `SimpMessageSender` 는 partyroom-scoped 추상화. 시스템 공지 토픽은 그 추상화 밖
- administration BC 가 자체 outbound adapter 를 갖는 게 BC 경계와 일치
- realtime 모듈 코드 변경 0

---

## 7. Vercel Edge Config + 환경별 Secret 매트릭스

### 7.1 Edge Config 스키마 — 환경별 key 분리 (단일 인스턴스 안)

Vercel Hobby plan 은 account 당 Edge Config 인스턴스 1개 제한. 단일 인스턴스 안에서 환경별 다른 key 로 격리:

```json
{
  "maintenance": null | { ...prod... },                      // prod backend write / Production frontend read
  "maintenance_preview": null | { ...stg... },               // stg backend write / Preview frontend read
  "maintenance_development": null | { ...dev... }            // dev backend write / Development frontend read
}
```

각 key 의 value 형태 (동일 schema):

```json
{
  "phase": "PLANNED" | "ACTIVE",
  "startAt": "2026-05-04T03:00:00+09:00",
  "endAt":   "2026-05-04T04:00:00+09:00",
  "messageKo": "...",
  "messageEn": "..."
}
```

`null` 또는 key 부재 = 점검 모드 아님 (frontend 정상 부팅). Vercel Edge Config Items API 의 `operation: "upsert"` 가 key 부재 시 자동 생성 — 사전 등록 불필요.

**환경 ↔ key 매핑** (frontend 의 `process.env.VERCEL_ENV` 와 정확히 일치):

| 환경 | backend env `VERCEL_EDGE_CONFIG_KEY` | frontend `VERCEL_ENV` | Edge Config key |
|---|---|---|---|
| prod | `maintenance` (또는 미설정 default) | `production` | `maintenance` |
| stg | `maintenance_preview` | `preview` | `maintenance_preview` |
| dev | `maintenance_development` | `development` | `maintenance_development` |
| 로컬 (`next dev`) | — | undefined → fallback | skip |

### 7.2 backend 가 Edge Config 를 write 하는 시점

| trigger | payload | 비고 |
|---|---|---|
| `POST /admin/announcements` (type=MAINTENANCE_NOTICE) | `{ phase: "PLANNED", ... }` | 예약 등록 시 |
| Scheduler `promoteScheduledMaintenance` (예약 시각 도달) | `{ phase: "ACTIVE", ... }` | phase 전환 |
| `DELETE /admin/announcements/{id}` (cancel) | `null` | 점검 종료 |

다른 announcement (EVENT/EMERGENCY) 는 Edge Config 안 만짐 — ws broadcast 만.

### 7.3 Token 주입 매트릭스

배포 인프라는 **GCE VM + IAP + DOT_ENV append** (Cloud Run 아님). 정정된 사실 ↔ `project_system_announcement_design.md` Token 주입 행 참조.

| 환경 | 액션 (4 키: ID/TOKEN/TEAM_ID/KEY) |
|---|---|
| `.env.example` (tracked) | 4 placeholder 추가 (✅ ID/TOKEN ea2e3b47 회복, KEY 후속 commit) |
| `.env.local` (사용자 PC) | `VERCEL_EDGE_CONFIG_ID` / `VERCEL_API_TOKEN` / (선택)`VERCEL_TEAM_ID`. KEY 는 default `maintenance` 또는 `maintenance_development` |
| `.env.dev` (self-hosted runner 머신) | 위 + `VERCEL_EDGE_CONFIG_KEY=maintenance_development` |
| `.env.stg` (위 동일 경로) | 위 + `VERCEL_EDGE_CONFIG_KEY=maintenance_preview` |
| GH Secret `DOT_ENV` (prod) | 위 + `VERCEL_EDGE_CONFIG_KEY=maintenance` (또는 미포함 → default) |
| `application.yml` | `vercel.edge-config.{id, api-token, team-id, base-url, maintenance-key}` placeholder 추가 (코드 작업) |
| `docker-compose.*.yml` | 변경 없음 — `env_file: .env.{env}` 디렉티브로 키 무관 통째 주입 중. 신규 키 자동 전파 |
| GH Workflow YAML | 변경 없음 (DOT_ENV 본문 append 만) |

(Team account 인 경우 `VERCEL_TEAM_ID` 도 동일 매트릭스로 추가 — 본 PR 작업자가 Team 여부 확인 후 결정)

### 7.4 토큰 발급 절차 (사용자 액션)

1. **Edge Config 인스턴스 1개 생성** — Vercel Dashboard → Storage → Create Database → Edge Config → 이름 `pfplay-maintenance` → 생성 후 ID(`ecfg_...`) 복사 → `VERCEL_EDGE_CONFIG_ID`. (Hobby plan 1개 제한 — 환경 격리는 §7.1 의 환경별 key 분리로 해결)
2. **pfplay-web 프로젝트와 Connect** — Edge Config 화면 → Projects 탭 → Connect Project → `pfplay-web` 선택 → **Production / Preview / Development 모두 체크**. `EDGE_CONFIG` 연결 문자열이 세 환경에 자동 주입 (frontend 가 `process.env.VERCEL_ENV` 로 환경 분기 + 다른 key read)
3. **API 토큰 발급** — Account Settings → Tokens → Create Token → scope/expiration 결정 → `VERCEL_API_TOKEN`
4. (Team) `VERCEL_TEAM_ID` — Team Settings → General

---

## 8. Frontend

### 8.1 pfplay-admin (G21~)

**G21**: 공지 발사 form
- 4개 필드 (titleKo/En, messageKo/En) + type 라디오 + severity 라디오
- type=MAINTENANCE_NOTICE 시 datetime picker 두 개 (start/end) 활성화
- type=EVENT/EMERGENCY 시 expiresAt picker 활성화
- 발사 버튼 → POST /admin/announcements → 성공 시 토스트 + 이력 페이지로 이동

**G22**: 공지 이력 페이지
- 페이지네이션 + status 필터 + type 필터
- 각 row 에 "취소" 버튼 (cancelled_at IS NULL 일 때만 활성)
- 취소 → DELETE /admin/announcements/{id} → confirm 모달

**G23**: i18n 관리 (선택, 시간 남으면)
- 두 언어 동시 입력 강제 (한쪽만 작성하면 발사 차단)

### 8.2 pfplay-web

**부팅 fallback**:
- `next.config` 에 `@vercel/edge-config` 신규 추가
- `middleware.ts` (또는 `app/layout.tsx` 의 server component) 에서 Edge Config 의 `maintenance` 키 읽기
- `maintenance != null && maintenance.phase === "ACTIVE"` 면 maintenance overlay 페이지로 redirect (CSS-level overlay or 별도 route `/maintenance`)
- "다시 시도" 버튼만 노출 — 재로드 시 다시 Edge Config 체크

**활성 세션 ws 구독**:
- 기존 STOMP client 가 `/sub/system/announcements` 추가 구독
- `ANNOUNCEMENT_PUBLISHED` 수신 → severity 별 toast/banner 표시
- `MAINTENANCE_STARTED` 수신 → 즉시 maintenance overlay 표시 (Edge Config 까지 안 기다림)
- `ANNOUNCEMENT_CANCELLED` 수신 → 해당 announcement 의 toast/overlay dismiss

**Edge Config 읽기 권한**:
- Vercel Dashboard 에서 Edge Config 를 pfplay-web 프로젝트에 Connect 하면 `EDGE_CONFIG` 연결 문자열이 자동 주입 (수동 secret 추가 0)
- `import { get } from '@vercel/edge-config'` server-side 사용
- **`NEXT_PUBLIC_*` 접두사 절대 금지** — 토큰 노출 위험

---

## 9. Failure Modes

### 9.1 Edge Config write 실패 (backend → Vercel)

- **시나리오**: Vercel API 5xx, 네트워크 timeout, 토큰 만료
- **현재 효과**: announcement 자체는 DB INSERT 됐고 ws broadcast 도 갔지만 Edge Config 만 stale
- **대응**:
  - 1회 호출 + 실패 시 RuntimeException
  - listener 가 잡아서 ERROR log
  - **재시도는 별도 메커니즘 없음** — scheduler 가 매분 phase 를 다시 evaluate 하므로 maintenance 케이스는 자연 복구 (다음 tick 에 다시 write 시도). 즉시 발사하는 EVENT/EMERGENCY 는 ws broadcast 자체는 갔으니 활성 세션은 영향 없음
  - 신규 부팅 사용자는 `/api/v1/system/status` 로 fallback (Edge Config 가 stale 이어도 backend DB 가 source of truth)
  - 만성적 실패 시 admin 콘솔 alert — 본 PR 외 별도 모니터링 작업

### 9.2 Redis pub 실패 (backend → Redis)

- **시나리오**: Redis 다운, 연결 끊김
- **현재 효과**: ws broadcast 0
- **대응**:
  - `messagePublisher.publish` 가 throw — listener 가 swallow (log.error)
  - DB 는 INSERT 됨. 사용자는 새로고침 시 `/api/v1/system/status` 로 인지
  - 운영 모니터링 (Redis healthcheck) 으로 detect

### 9.3 Scheduler 누락 (backend down 중에 scheduled_start_at 도달)

- **시나리오**: backend 가 down 중에 예약 시각 통과 → 복구 후 scheduler tick
- **현재 효과**: scheduler 가 `scheduled_start_at <= now AND scheduled_end_at > now AND maintenance_started_at IS NULL` 조건으로 query → **놓친 예약도 다음 tick 에 포착됨**
- **`scheduled_end_at` 까지 이미 지났으면**: query 에서 빠짐 → 그 점검은 영원히 미발사. **이는 의도된 동작 (lock-in)** — 점검 종료 시각이 지났으면 발사 의미 없음. 운영 정책으로 확정 (writing-plans 에서 재논의 안 함)

### 9.4 Race — scheduler tick 중에 admin 이 DELETE

- **시나리오**: scheduler 가 phase ACTIVE 로 토글하는 트랜잭션 중에 admin 이 DELETE 호출
- **현재 효과**: DB row lock 으로 직렬화 (둘 중 먼저 commit 한 쪽이 이김)
- **대응**: 도메인 메서드 `markMaintenanceStarted` / `cancel` 가 invariant guard 로 후속 작업 거부

### 9.5 Edge Config eventual consistency

- **시나리오**: Vercel Edge Config 는 글로벌 propagation 에 수 초 ~ 수십 초 소요 (지역 PoP 별)
- **현재 효과**: PATCH 직후 read 시 stale 값 가능
- **대응**: ws 가 즉시 broadcast 라 활성 세션은 영향 없음. 신규 부팅 사용자는 stale 보다 일관된 값 (~10초 늦은 값) — 점검 모드 진입/종료 즈음 짧은 transient 만 영향. 허용 가능

---

## 10. Testing

### 10.1 Unit (도메인)

- `SystemAnnouncementDataTest` — 상태 전이 메서드 (markMaintenanceStarted, cancel) 의 invariant guard
- `AnnouncementTypeTest` — `requiresSchedule()` 분기
- `MaintenancePhaseTest` — phase 전이 valid/invalid

### 10.2 Service

- `SystemAnnouncementCommandServiceTest` — Mock repo, eventPublisher → publish/cancel 흐름 검증
- `MaintenanceSchedulerServiceTest` — Clock fixed, due query mock → markMaintenanceStarted 호출 검증
- `VercelEdgeConfigAdapterTest` — RestTemplate mock, write 성공/실패/timeout 분기

### 10.3 Integration

- `SystemAnnouncementCommandServiceIT` — H2 + 실제 트랜잭션 + AFTER_COMMIT listener 가 호출되는지 검증
- `MaintenanceSchedulerIT` — 실제 cron 안 돌리고 메서드 직접 호출 + 도메인 상태 변경 검증
- `AdminAnnouncementControllerIT` — `@WebMvcTest` (PR 12b2 G1 표준 패턴) — auth + validation + 응답 형식
- `SystemStatusControllerIT` — anonymous 호출 가능 + 응답 스키마 검증

### 10.4 WS Broadcast 검증

- `AnnouncementBroadcasterIT` — `@SpringBootTest` + STOMP test client 로 `/sub/system/announcements` 구독 → publish 호출 → 1초 안에 메시지 수신 검증
- payload snapshot 검증 (late-binding 회피 확인) — 구체적 어설션: "publish → DELETE 가 같은 트랜잭션 내 빠르게 일어나는 시나리오에서 PUBLISHED 이벤트 payload 가 발사 시점 entity snapshot 그대로 도착 (DELETE 가 발사 payload 를 corrupt 하지 않음). DELETE 는 별도 CANCELLED 이벤트로 도착". `reference_first_dj_silent_deactivate.md` 의 late-binding 함정 회피를 명시 검증

### 10.5 Edge Config 통합 테스트는 안 함

- Vercel API 호출은 mock 으로만 (실제 Vercel staging 안 만듦). 운영 검증은 manual smoke test (admin이 staging 에서 발사 → Vercel Dashboard 에서 Edge Config 확인)

---

## 11. Out of Scope (재명시)

- 사용자 inbox / read 상태 / 공지 보관함
- 공지 modify (UPDATE) — POST/DELETE 만
- 공지 audience 분기 (전체 broadcast 만)
- 점검 모드 종료 자동화 (admin DELETE 만)
- 인프라 graceful shutdown / scale-to-zero
- multilingual 자동 번역 (운영자 직접 작성)
- 공지 templates / 미리보기

---

## 12. Open Items

writing-plans 단계에서 결정 또는 구현 중 결정:

1. **Commit chunk 전략** — (i) 단일 PR / (ii) 2~3 chunk (DB+Entity+Repo+EdgeConfigPort → Service+Scheduler+Broadcaster → REST+WS+테스트) / (iii) forward-evolution 3단. 사용자 (ii) 권장 의향 표명 (`project_system_announcement_design.md` 미해결 항목)

2. **Vercel Team 여부** — `VERCEL_TEAM_ID` 필요 여부 — 사용자가 Vercel 계정 타입 확인 후 spec §7.3 매트릭스 추가 결정

3. **EdgeConfigWriteException 시 admin alert 채널** — §9.1: log+metric 으로 detect 한다고 했지만 구체적 alert 경로 (Slack? GH issue? 별도 admin 콘솔 banner?) 미정

4. **WS 구독 시 backlog 재전송** — 현재 spec: 세션 끊긴 사용자가 재연결 시 그 사이 ws 이벤트는 못 받음 (REST `/system/status` 로 catch up 만). backlog 메커니즘 도입은 별도 작업

5. **admin frontend G21~ chunk 분리** — spec §8.1 의 G21/G22/G23 가 한 PR 인지 chunk 인지 — `feedback_pr_series_workflow.md` 의 chunk 패턴 따를지 결정

6. **본 PR 진입 시점에 admin platform prod ship 선행 작업과 충돌 검토** — `project_admin_platform_prod_ship_pending.md` 5단계 절차와 본 PR 의 prod ship 순서 정렬 필요 (admin 먼저 prod → 본 PR 그 위에)
