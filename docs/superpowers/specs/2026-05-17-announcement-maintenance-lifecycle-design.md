# 점검 공지 라이프사이클 보강 설계 (자동 정상종료 + 종료시각 조정 + 철회/완료 구분)

**상태**: 초안
**브랜치**: `feature/announcement-maintenance-lifecycle` (base `origin/develop` `1d524b13`)
**이슈**: pfplay-platform#218
**범위**: pfplay-platform(도메인/스케줄러/이벤트/REST/V17) + pfplay-admin(공지 화면). pfplay-web 무변경.

## 배경

점검 공지(`MAINTENANCE_NOTICE`)의 종료 레버가 `DELETE`(철회) 하나뿐이다. `scheduled_end_at`은 자동 종료 트리거가 아니라 cron *시작* 조건(`findDueForMaintenanceActivation`)의 상한일 뿐 — 일단 `maintenance_started_at`이 박혀 ACTIVE가 되면 운영자가 수동 철회할 때까지 **무기한 ACTIVE**로 남는다(`bugs/2026-05-15-vercel-edge-config-patch-method.md` Fix 4 = 점검 영구 잠금 risk). 또 "잘못 송출(철회)"과 "점검 정상 완료"가 이력상 구분되지 않는다(단일 `cancelled_at`).

## 목적 / 비목적

**목적**: (1) `scheduled_end_at` 도래 시 cron 자동 정상종료, (2) ACTIVE 한정 종료시각 조정(연장/단축), (3) 철회와 정상완료를 DB·이력에서 구분.

기존 design-lock 결정 2건을 **의식적으로 반전**한다 (`project_system_announcement_design`, 구현 후 메모리 갱신):
- 「점검 종료 = admin 명시 DELETE, backend 자동 종료 안 함」 → 자동 정상종료 도입
- 「modify 미지원, 취소 후 재송출」 → ACTIVE 종료시각 조정 도입 (start 변경은 여전히 재공지로 갈음 — 미반전)

**비목적**:

- 백엔드 phase/status enum 상태기계 재구성(접근법 B) — 다형 테이블(`MAINTENANCE_NOTICE`/`EVENT`/`EMERGENCY` 공유) + prod-live 위험으로 기각. 별 스코프 리팩터로 분리 가능.
- start 시각 modify — 점검 전(PLANNED)이라 사용자가 서비스 이용 중 → 재공지(취소+재송출)로 갈음. modify 미지원 lock 유지.
- pfplay-web 변경 — middleware/`getEdgeConfigMaintenance`가 Edge Config `endAt`을 이미 read. 조용한 갱신은 새 요청 시 자동 반영. (검증만)
- 이중 Edge Config write 재시도/트랜잭셔널 보강 — 기존 `tryWriteEdgeConfig` best-effort + cron 재시도 패턴 그대로.

## 핵심 사실 (코드 확정)

- 도메인 `SystemAnnouncementData`는 nullable-timestamp 모델: `maintenanceStartedAt`/`cancelledAt`. `markMaintenanceStarted`/`cancel`/`isMaintenancePhaseActive()`. status enum 없음.
- `EdgeConfigPort.writeMaintenance(entity, phase)`는 `entity.getScheduledEndAt()`을 `endAt`으로 직렬화. `entity==null` → `{"value": null}` 삭제. → end만 고치고 `writeMaintenance(entity, ACTIVE)` 재호출하면 **조용한 endAt 갱신** 그대로 됨.
- 브로드캐스터는 `@TransactionalEventListener(AFTER_COMMIT)` — `AnnouncementCancelledEvent` → WS dismiss + (ACTIVE였으면) Edge Config 삭제. `MaintenanceStartedEvent` → WS + Edge Config ACTIVE write.
- 마이그레이션 최신 = V16(add_presence). 슬롯 점프 없음 → **다음 V17**.
- 리포 쿼리 4개 중 `findCurrentMaintenance`만 `completed_at` 영향. `findDueForMaintenanceActivation`/`findPlannedMaintenance`는 `maintenanceStartedAt IS NULL`이라 완료건 자연 배제. `findActivePublic`은 EVENT/EMERGENCY.

## 변경 단위

### 1. 스키마 — V17

`app/src/main/resources/db/migration/V17__add_completed_at_to_system_announcement.sql`:

```sql
ALTER TABLE system_announcement
  ADD COLUMN completed_at DATETIME NULL AFTER cancelled_at;
```

백필 없음. 기존 row `completed_at = NULL` = "정상완료된 적 없음" 의미 보존. `cancelled_at`/`maintenance_started_at` 무변경. (퀵 연장이 *현재 종료시각 누적* 기준이라 `original_scheduled_end_at` 컬럼 불필요.)

### 2. 도메인 (`SystemAnnouncementData`)

- `+ @Column(name = "completed_at") private LocalDateTime completedAt;`
- `markCompleted(Clock clock)`: ACTIVE 아니면 `ExceptionCreator.create(...)`로 도메인 예외(아래 표). `this.completedAt = LocalDateTime.now(clock)`.
- `adjustScheduledEndTime(LocalDateTime newEnd, Clock clock)`: ACTIVE 한정. 불변식 `newEnd.isAfter(LocalDateTime.now(clock))`. `this.scheduledEndAt = newEnd`. (단축도 허용 — newEnd가 now보다 뒤이기만 하면 됨. now 이하로 당기려면 `/complete` 사용.)
- `isMaintenancePhaseActive()` → `... && cancelledAt == null && completedAt == null`로 보강.

**예외 코드 확정** (기존 ANN-001~005, ANN-002=`ALREADY_CANCELLED`/409). 신규 도메인 메서드는 REST 경로에서 도달하므로 **raw `IllegalStateException` 금지** — 반드시 `ExceptionCreator.create(AnnouncementException.X)` (그래야 `GlobalExceptionHandler`가 약속한 4xx로 매핑; raw `IllegalStateException`은 500). 기존 `markMaintenanceStarted`/`cancel`의 raw `IllegalStateException` 가드는 스케줄러 내부 race 전용이라 무변경:

| 신규 코드 | 의미 | ErrorType |
|---|---|---|
| `INVALID_END_ADJUSTMENT` ("ANN-006") | `adjustScheduledEndTime` 의 `newEnd <= now` | BAD_REQUEST (400) |
| `NOT_ACTIVE_MAINTENANCE` ("ANN-007") | `adjustScheduledEndTime`/`markCompleted` 가 ACTIVE 아닌 row(PLANNED·완료·철회·비-MAINTENANCE) | CONFLICT (409) |
| `ALREADY_COMPLETED` ("ANN-008") | `markCompleted` 중복(이미 completed) | CONFLICT (409) |

`AnnouncementException` enum에 위 3개 추가. `markCompleted`: 비-ACTIVE → ANN-007, 이미 completed면 ANN-008(가드 순서: completed 먼저 검사). `adjustScheduledEndTime`: 비-ACTIVE → ANN-007, newEnd<=now → ANN-006.

### 3. 리포지토리 (`SystemAnnouncementRepository`)

- `findCurrentMaintenance` 쿼리에 `AND a.completedAt IS NULL` 추가 (완료건이 "현재 점검"으로 안 잡히게).
- 신규 `findDueForMaintenanceCompletion(now)`:

```java
@Query("""
    SELECT a FROM SystemAnnouncementData a
    WHERE a.type = ...MAINTENANCE_NOTICE
      AND a.maintenanceStartedAt IS NOT NULL
      AND a.cancelledAt IS NULL AND a.completedAt IS NULL
      AND a.scheduledEndAt <= :now
    """)
List<SystemAnnouncementData> findDueForMaintenanceCompletion(@Param("now") LocalDateTime now);
```

- `findDueForMaintenanceActivation`/`findPlannedMaintenance`/`findActivePublic` 무변경.

### 4. 스케줄러 (`MaintenanceSchedulerService`)

기존 `@Scheduled(cron = "0 * * * * *") promoteScheduledMaintenance`와 별개로 동일 1분 정밀도 완료 패스 추가(트랜잭션·실패 격리 위해 메서드 분리):

```java
@Scheduled(cron = "0 * * * * *")
@Transactional
public void completeExpiredMaintenance() {
    LocalDateTime now = LocalDateTime.now(clock);
    for (SystemAnnouncementData e : repository.findDueForMaintenanceCompletion(now)) {
        e.markCompleted(clock);
        eventPublisher.publishEvent(new MaintenanceEndedEvent(e));
    }
}
```

### 5. 이벤트 / 브로드캐스터

- 신규 `MaintenanceEndedEvent(SystemAnnouncementData entity)` (도메인 event 패키지, `AnnouncementCancelledEvent` 미러).
- `AnnouncementBroadcaster`에 핸들러 추가:

```java
@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
public void on(MaintenanceEndedEvent event) {
    SystemAnnouncementData e = event.entity();
    Map<String,Object> payload = new LinkedHashMap<>();
    payload.put("eventType", "MAINTENANCE_ENDED");
    payload.put("announcementId", e.getId());
    payload.put("completedAt", e.getCompletedAt());
    messagingTemplate.convertAndSend(TOPIC, payload);
    tryWriteEdgeConfig(null, null); // 점검 실제 종료 → 사용자 풀어줌
}
```

  → 점검이 *실제로 끝날 때*(자동완료 OR 수동 `/complete`)는 사용자를 풀어줘야 하므로 WS dismiss + Edge Config 삭제. "조용히"는 종료시각 *조정* 한정이지 종료 자체가 아님.
- **종료시각 조정(조용)**: 신규 WS 이벤트 없음. CommandService가 end edit 후 `edgeConfigPort.writeMaintenance(entity, MaintenancePhase.ACTIVE)`를 **직접 호출**(이벤트 미발행) → endAt만 갱신. Edge Config write 실패는 기존처럼 로깅 후 삼킴(다음 변경/cron 시 정합) — 단, 조정은 cron 재시도 대상이 아니므로 실패 시 운영자에게 4xx/5xx 노출 여부는 §에러 참고.
- `MAINTENANCE_ENDED` WS 이벤트 타입은 신규지만 design-lock의 WS event types(`ANNOUNCEMENT_PUBLISHED`/`ANNOUNCEMENT_CANCELLED`/`MAINTENANCE_STARTED`)에 1개 추가 — lock 반전의 일부로 명시.

### 6. REST API (`AdminAnnouncementController`)

모두 기존 `@PreAuthorize("@adminAuth.isAdmin()")` 게이팅.

- `DELETE /api/v1/admin/announcements/{id}` — **불변**. 철회(`cancelled_at`). PLANNED·ACTIVE 모두. ANN-001/002 그대로.
- `PATCH /api/v1/admin/announcements/{id}/schedule` `{ "scheduledEndAt": "<ISO-8601>" }` — **ACTIVE 한정**. `commandService.adjustEndTime(id, newEnd, adminId)` → 도메인 `adjustScheduledEndTime` + Edge Config ACTIVE 재기록(조용). 비-ACTIVE/미존재 시 도메인 예외 → 4xx. 응답 200 `ok()`.
- `POST /api/v1/admin/announcements/{id}/complete` — **ACTIVE 한정**. `commandService.complete(id, adminId)` → 도메인 `markCompleted` + `MaintenanceEndedEvent`(WS dismiss + Edge Config 삭제). 응답 200 `ok()`.

`SystemAnnouncementCommandService` 에 `adjustEndTime`, `complete` 추가. 스케줄러 자동완료도 동일 도메인 `markCompleted` + `MaintenanceEndedEvent` 경로 공유.

`AnnouncementSummaryResponse`에 `completedAt` 필드 추가 (record 의 "N fields" Javadoc 도 동기화). admin `types.ts` 헤더 주석의 "modify 없음 — 잘못 송출하면 DELETE 후 재송출" 문구도 end 조정 도입 반영해 갱신(plan 에서 같이 처리).

### 7. pfplay-admin UI

- `entities/announcement/model/types.ts`: `Announcement`에 `completedAt: string | null` 추가. (`maintenanceStartedAt`/`cancelledAt`은 **이미 존재** — admin 타입·`AnnouncementSummaryResponse` 둘 다 노출 중. 상태배지 파생에 필요한 필드는 이미 있음. net-new DTO 필드는 `completedAt` 단 1개.)
- `announcements-api.ts`: `adjustAnnouncementSchedule(id, scheduledEndAt)` (PATCH), `completeAnnouncement(id)` (POST). 각 react-query mutation hook(`use-adjust-schedule`, `use-complete-announcement`) — 기존 `use-cancel-announcement` 패턴 미러, 성공 시 list invalidate.
- `announcements-table.tsx`: '취소 시각' 컬럼 → **상태 배지**(예정 / 진행중 / 정상완료 / 철회) 파생:
  - `cancelledAt != null` → 철회
  - `completedAt != null` → 정상완료
  - `maintenanceStartedAt != null` → 진행중
  - else (MAINTENANCE, 시작 전) → 예정
  - (EVENT/EMERGENCY는 송출/철회만)
  액션: 진행중(ACTIVE) 행에 `종료시각 조정` + `지금 종료` + `철회`. 그 외 행은 `철회`만.
- 신규 `adjust-schedule-dialog.tsx`: datetime 입력 + 퀵 버튼 `+10분` `+30분` `+1시간` — 클릭 시 **현재 적용 종료시각(`scheduledEndAt`) 기준 누적 +N** 으로 입력값 prefill(확정 버튼으로 PATCH). 누적이라 별도 원본 보존 불필요.
- `complete-announcement-dialog.tsx`: 확인 후 POST /complete. 문구 = "지금 점검을 정상 종료합니다. 사용자가 즉시 서비스로 복귀합니다."
- 기존 `cancel-announcement-dialog.tsx`: "modify 는 지원되지 않습니다" 문구 → end 조정 도입 반영해 갱신("종료시각 조정은 별도 기능, 본 작업은 철회").

### 8. pfplay-web

무변경. `middleware.ts` → `getEdgeConfigMaintenance()`가 Edge Config `maintenance.endAt`을 이미 read. 조용한 endAt 갱신은 다음 요청 시 반영. `MAINTENANCE_ENDED` WS는 기존 dismiss 처리(cancel과 동형)로 흡수되는지 **검증만**(필요 시 별 task — 본 spec 비목적).

## 데이터 흐름

```
[자동 정상종료] cron(0 * * * * *) completeExpiredMaintenance
  → findDueForMaintenanceCompletion(now)
  → markCompleted → MaintenanceEndedEvent
  → AFTER_COMMIT: WS "MAINTENANCE_ENDED" + Edge Config delete(null)

[종료시각 조정] PATCH /{id}/schedule
  → adjustScheduledEndTime(newEnd) (ACTIVE & newEnd>now)
  → edgeConfigPort.writeMaintenance(entity, ACTIVE)  (조용, WS 없음)

[즉시 정상종료] POST /{id}/complete
  → markCompleted → MaintenanceEndedEvent  (자동종료와 동일 경로)

[철회] DELETE /{id}  (불변)
  → cancel → AnnouncementCancelledEvent → WS + (ACTIVE였으면) Edge Config delete
```

## 에러 / 엣지

- `adjustScheduledEndTime`/`markCompleted` 를 비-ACTIVE(PLANNED·이미 완료·이미 철회·비-MAINTENANCE) row에 호출 → ANN-007(409). `/complete` 중복(이미 completed) → ANN-008(409). `adjust` 의 `newEnd<=now` → ANN-006(400). 모두 `ExceptionCreator.create` → `GlobalExceptionHandler` 4xx (§2 표 — raw `IllegalStateException` 사용 시 500 되므로 금지).
- `newEnd <= now`: ANN-006 거부. "지금 끝내려면 `/complete`" 안내. 단축은 `now < newEnd < 현재 end` 면 허용(자동완료 cron이 새 end 도래 시 처리).
- 종료시각 조정 시 Edge Config write 실패: cron 재시도 대상 아님(조정은 이벤트리스 직접 호출). 운영자에게 실패 노출 위해 adjust 경로의 Edge Config write 실패는 삼키지 않고 5xx 전파(철회/시작 경로의 best-effort 와 의도적으로 다름). 단 DB는 이미 commit됨 → 운영자 재시도 가능. **재시도 시 주의**: `writeMaintenance`가 entity의 현재 `scheduledEndAt`을 재직렬화하므로 같은 row 대상 동일 의도면 재PATCH로 복구되나, 도메인 가드가 `newEnd>now`라 시간 경과로 원래 입력값이 과거가 됐으면 운영자는 *새 미래값*으로 재요청해야 함(같은 리터럴 값 아님).
- 자동완료 cron tick과 운영자 `/complete` 동시: 도메인 `markCompleted` 가드(completed 먼저 검사)로 후속 호출이 ANN-008(409) → 한쪽만 성공. REST 호출자는 깔끔한 409 수신(raw `IllegalStateException`→500 아님 — §2 예외 확정의 이유). 스케줄러가 race 패자면 `ExceptionCreator` 예외가 해당 tick `@Transactional` 롤백(기존 `promoteScheduledMaintenance`도 동일 속성 — per-row try 안 함, 컨벤션 유지). 쿼리 필터가 다음 tick에 남은 row만 반환하므로 무해.
- 자동완료 cron tick과 운영자 `DELETE`(철회) 동시: 둘 다 ACTIVE 가드. 먼저 commit된 쪽 승. cancelled 후 completion 쿼리는 `cancelledAt IS NULL`이라 다음 tick에 자연 제외. 반대 순서도 `completedAt IS NULL` 대칭. 무해.
- EVENT/EMERGENCY: `completed_at`/스케줄러 완료패스 무관(쿼리가 type 필터). 상태배지에서 송출/철회만.

## 테스트 전략

TDD 단위 분해 (RED→GREEN):

- 도메인: `markCompleted`(ACTIVE 성공 / PLANNED·완료·철회 시 예외), `adjustScheduledEndTime`(ACTIVE & newEnd>now 성공 / newEnd<=now 예외 / 비-ACTIVE 예외), `isMaintenancePhaseActive` completed 보강.
- 리포: `findDueForMaintenanceCompletion`(end<=now & ACTIVE만 / cancelled·completed 제외 / 미시작 제외), `findCurrentMaintenance` completed 제외 회귀.
- 스케줄러: `completeExpiredMaintenance` due → markCompleted + 이벤트 발행, 없음 → no-op.
- 브로드캐스터: `MaintenanceEndedEvent` → WS payload(`MAINTENANCE_ENDED`) + Edge Config delete 호출.
- 컨트롤러(MockMvc): `/schedule` PATCH(200 / ACTIVE아님 4xx / newEnd<=now 400 / non-admin 403 / anon 401), `/complete` POST(200 / 중복 409 / 권한). `DELETE` 회귀(불변).
- CommandService: `adjustEndTime`이 Edge Config ACTIVE 재기록 호출 & WS 미발사, `complete`가 이벤트 경로.
- pfplay-admin: 상태배지 파생 4-state, adjust 다이얼로그 퀵버튼이 현재 endAt 누적 +N prefill, complete/adjust mutation 성공 시 invalidate, ACTIVE 외 행 액션 노출 규칙. (RTL/MSW, 기존 announcements-table 테스트 확장.)

## 회귀 체크

- 기존 발행/철회/시작(`MaintenanceStartedEvent`) 흐름 무변경. `DELETE` 의미·응답 불변.
- `findActivePublic`/`findPlannedMaintenance`/`findDueForMaintenanceActivation`·`/api/v1/system/status` 부팅 fallback 영향 0(쿼리 무변경, completed는 started 전제라 planned/activation 결과집합에 원천 부재).
- V17은 add-column NULL → 기존 prod row·진행 중 점검 무영향.
- `tsc`/lint/test 그린(양 레포). pfplay-web 빌드 무영향.

## 관련

- 이슈: pfplay-platform#218
- 노트: `bugs/2026-05-15-vercel-edge-config-patch-method.md`(Fix 4 = 자동종료 부재 해소), `reference_maintenance_system_paths`(Path A/C)
- 메모리: `project_system_announcement_design`(lock 반전 — 구현 후 갱신: 자동종료 도입 / completed_at / modify=end조정 한정 / WS `MAINTENANCE_ENDED` 추가), `feedback_korean_issue_commit_pr`, `feedback_flyway_slot_renumber`(V17 슬롯 확인), `feedback_pr_series_workflow`
- 별 task(범위 밖): 백엔드 phase enum 리팩터(접근법 B), pfplay-web `MAINTENANCE_ENDED` 처리 검증, `AuthService` 외부에러 마스킹(무관)
