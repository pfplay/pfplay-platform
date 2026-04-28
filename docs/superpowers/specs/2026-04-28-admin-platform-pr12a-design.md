# PR 12a: V10 user_activity_log + UserActivityLogListener + 이벤트 source 배선 — Design

> Brainstormed design spec. Implementation plan is generated separately via `superpowers:writing-plans`.

**Date:** 2026-04-28
**Branch:** `feature/admin-auth-iam-schema` (PR 11 HEAD `b180d3a8` 위에 빌드)
**Roadmap row:** §9.1 PR 12 — *V10 user_activity_log (partitioned) + event listeners + member 관리 API (A-1~A-4)* (size: L) — **split (a)**
**Milestone:** M5 (PR 12-13, 유저 관리 + 활동 로그 + 신고 시스템) 시작점
**Split rationale:** roadmap의 PR 12는 size L. 본 PR(12a)는 인프라(V10 + listener + 이벤트 publish 배선) 만 처리하고, Member 어드민 API 4개(A-1~A-4)는 PR 12b로 분리. 인프라/표면 모듈/리스크 프로파일 분리로 리뷰·롤백 용이성 확보.

---

## 1. Goal

V10 마이그레이션으로 `user_activity_log` 테이블(월별 RANGE partition) 도입 + Administration BC에 `UserActivityLogListener`(`@TransactionalEventListener(AFTER_COMMIT) + @Async`, drop-가능) 신설. 기존 도메인 이벤트 4종(`MemberRegisteredEvent` / `UserProfileChangedEvent` / `CrewAccessedEvent` / `CrewPenalizedEvent` + `AdminCrewPenalizedEvent`)을 listener로 소비, 신규 이벤트 2종(`UserAccountSignedInEvent` / `PartyroomCreatedEvent`) 추가. 핸들러 7개로 event_type 7종(SIGNED_UP / _IN / PROFILE_UPDATED / PARTYROOM_CREATED / _ENTERED / _EXITED / PENALIZED_IN_PARTYROOM)을 user_activity_log row로 변환·INSERT.

본 PR이 끝나면:
- 회원가입·로그인·프로필 변경·룸 생성/입장/퇴장·페널티 발생 시 `user_activity_log`에 row가 자동 누적된다.
- 핫패스(로그인/룸 입장 등) 영향 0 (AFTER_COMMIT + @Async).
- audit row INSERT 실패는 drop (비즈니스 흐름 안 막음, ERROR 로그만).
- PR 12b의 Member 어드민 API가 즉시 `recentActivityLog` projection으로 데이터 활용 가능.

---

## 2. Scope

### 2.1 In Scope (PR 12a)

1. **V10 Flyway 마이그레이션** — `user_activity_log` 테이블 (월별 RANGE partition, p202604~p202612 + p_future MAXVALUE, PK=(log_id, occurred_at), idx_ual_user_time, idx_ual_event_time). spec §4.7.1 그대로.
2. **Administration BC 신규 컴포넌트**:
   - `UserActivityLogData` JPA entity (`@IdClass UserActivityLogId` for composite PK).
   - `UserActivityLogRepository` (Spring Data JPA, `save`만; projection 메서드는 PR 12b).
   - `UserActivityLogListener` — 7개 `@TransactionalEventListener(AFTER_COMMIT) + @Async("userActivityLogExecutor")` 핸들러.
   - `UserActivityEventType` enum (**10종 catalog 전체** — PR 12b에서 사용할 WITHDREW / TIER_CHANGED / ADMIN_ACTED_ON 포함).
3. **Async 인프라**:
   - `AsyncConfig` (`common/config/`) — `@EnableAsync` + `userActivityLogExecutor` `ThreadPoolTaskExecutor` bean (core=2, max=4, queue=200, CallerRunsPolicy, name prefix `ual-`).
4. **신규 도메인 이벤트 2종**:
   - `UserAccountSignedInEvent` (auth domain) — `userAccountId`, `provider` (LOCAL / GOOGLE / ...), `actorType` (USER / ADMINISTRATOR).
   - `PartyroomCreatedEvent` (party domain) — `partyroomId`, `hostUserAccountId`, `stageType`.
5. **이벤트 publish 추가**:
   - `AuthService` (OAuth login 성공 path) → `UserAccountSignedInEvent` publish (`actorType=USER`).
   - `AdminLoginService.login` (성공 시) → `UserAccountSignedInEvent` publish (`actorType=ADMINISTRATOR`).
   - `Partyroom.create*` aggregate 팩토리 → `PartyroomCreatedEvent` `registerDomainEvent` (기존 `pollDomainEvents()` 패턴 자동 publish).
6. **`AdminCrewPenalizedEvent` evolution** — `punishedUserAccountId` 필드 추가. PR 9 publisher (`AdminCrewPenaltyCommandService.apply`) 갱신 + PR 9 IT/단위 테스트 갱신.
7. **`@Transactional(readOnly = true)` 보강** — `AdminLoginService.login` + `AuthService` OAuth publish path. `@TransactionalEventListener(AFTER_COMMIT)` 동작을 위한 active TX 보장.
8. **metadata JSON 매핑** — PR 8의 `JsonMetadata` / `JsonMetadataConverter` 재사용.
9. **테스트** — 단위/IT/Listener async/Concurrency/ArchUnit (§8).

### 2.2 Out of Scope (defer)

| 항목 | 위치 | 사유 |
|---|---|---|
| `WITHDREW` listener handler | PR 12b | 이벤트 source(A-4 endpoint)가 PR 12b |
| `TIER_CHANGED` listener handler | PR 12b | 이벤트 source(A-3 endpoint)가 PR 12b |
| `ADMIN_ACTED_ON` listener handler | PR 12b 또는 future | TIER_CHANGED / WITHDREW 또는 PR 8/9 admin actions 부수 효과로 발생 |
| Member 어드민 API 4개 (A-1~A-4) | PR 12b | 본 PR은 인프라만 |
| `last_activity_desc` projection 쿼리 | PR 12b | A-1 sort 옵션과 함께 |
| Partition 자동 생성 배치 (Spring Scheduler) | future | 9개월 + p_future MAXVALUE 여유, spec §4.7.4 "운영 중 결정" |
| Partition 아카이브 + DROP 배치 | future | 동일 |
| PR 8/9 admin actions(`AdminPartyroomCommandService` lifecycle) → user_activity_log `ADMIN_ACTED_ON` backfill | PR 12b 또는 future | 자연스러운 파급 효과 묶음 |
| Realtime / Redis fanout으로 활동 노출 | future | audit-only |
| metadata 인덱싱 / FULLTEXT | future | MVP는 user_account_id + event_type 인덱스로 충분 |

---

## 3. V10 Migration DDL

**파일:** `app/src/main/resources/db/migration/V10__create_user_activity_log.sql`

```sql
-- =====================================================
-- V10: Administration context — user_activity_log
-- Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.7
-- Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §3
--
-- Append-only audit timeline. 월별 RANGE 파티셔닝.
-- 모든 user_account 참조는 loose ref (cross-context, no FK).
-- =====================================================

CREATE TABLE user_activity_log (
    log_id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_account_id   BIGINT       NOT NULL,                -- loose ref (cross-context, no FK)
    event_type        VARCHAR(64)  NOT NULL,                -- SIGNED_IN | PARTYROOM_ENTERED 등
    partyroom_id      BIGINT       NULL,                    -- loose ref, nullable
    metadata          JSON         NULL,                    -- event별 추가 데이터
    occurred_at       DATETIME     NOT NULL,
    PRIMARY KEY (log_id, occurred_at),                      -- 파티션 키 포함 PK
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

**미세 결정:**
- **`event_type` VARCHAR(64) (not ENUM)** — `provider_type`/`role` VARCHAR 정책(PR 4/5)과 일관. event_type 카탈로그가 시간에 따라 늘어나는 것이 본질이라 ENUM 사용 시 DDL 변경 잦음.
- **PK `(log_id, occurred_at)`** — MySQL partitioned table은 partition key를 PK에 포함해야 함. AUTO_INCREMENT는 `log_id`로 유지.
- **인덱스 2개로 한정** — PR 12b A-2 `recentActivityLog` (user별 최근 N건) 쿼리가 주 read path이며 `idx_ual_user_time DESC` cover. event_type 별 통계는 future.
- **`partyroom_id` nullable** — SIGNED_IN / SIGNED_UP / PROFILE_UPDATED는 룸 무관, 나머지는 룸 컨텍스트 보유.
- **MAXVALUE p_future** — 운영 8개월차에 partition 추가 누락되어도 INSERT 차단 안 됨. spec §4.7.4 "운영 중 결정" 시점까지 안전망.

V slot 점유: V1~V11 사용. **V10 신규**. V13/V14는 후속 PR이 미리 잡아둔 slot 그대로.

---

## 4. Administration BC 컴포넌트

기존 administration BC 구조 (확인 완료):
- `administration/adapter/in/listener/` — PR 8 `PartyroomAdminActionListener` 위치.
- `administration/adapter/out/persistence/` — JPA repo.
- `administration/domain/value/JsonMetadata.java` + `JsonMetadataConverter.java` — PR 8 도입 helper.

### 4.1 `UserActivityLogData` (JPA entity)

`administration/domain/entity/UserActivityLogData.java` — PR 8 `PartyroomAdminActionData` 패턴 (정적 팩토리 `of(...)` + `@NoArgsConstructor(PROTECTED)`).

```java
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
    private String eventType;             // String 컬럼 + 도메인측 enum 변환은 listener에서

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

**Composite PK class** (`administration/domain/entity/UserActivityLogId.java`):
```java
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UserActivityLogId implements Serializable {
    private Long logId;
    private LocalDateTime occurredAt;
}
```

**`UserActivityEventType` enum** (`administration/domain/enums/UserActivityEventType.java`):
```java
public enum UserActivityEventType {
    SIGNED_UP, SIGNED_IN, WITHDREW, PROFILE_UPDATED, TIER_CHANGED,
    PARTYROOM_CREATED, PARTYROOM_ENTERED, PARTYROOM_EXITED,
    PENALIZED_IN_PARTYROOM, ADMIN_ACTED_ON
}
```
→ **10종 catalog 전체 미리 정의** — PR 12b가 wiring만 추가하면 됨. enum 재배포 회피.

### 4.2 `UserActivityLogRepository`

`administration/adapter/out/persistence/UserActivityLogRepository.java`:
```java
public interface UserActivityLogRepository extends JpaRepository<UserActivityLogData, UserActivityLogId> {
    // PR 12b A-2 용 projection 쿼리는 PR 12b에서 추가
}
```
PR 12a는 `save`만으로 충분.

### 4.3 `UserActivityLogListener` (Administration BC adapter)

`administration/adapter/in/listener/UserActivityLogListener.java` — PR 8 `PartyroomAdminActionListener`와 같은 패키지, 다른 패턴(async).

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class UserActivityLogListener {

    private final UserActivityLogRepository repository;

    // === 7개 핸들러 (PR 12a 범위) ===

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("userActivityLogExecutor")
    public void on(MemberRegisteredEvent e) {
        log(e.getUserId().getId(), UserActivityEventType.SIGNED_UP, null,
            JsonMetadata.of(Map.of("provider", e.getProviderType().name())),
            e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("userActivityLogExecutor")
    public void on(UserAccountSignedInEvent e) {
        log(e.getUserAccountId(), UserActivityEventType.SIGNED_IN, null,
            JsonMetadata.of(Map.of(
                "provider", e.getProvider().name(),
                "actor_type", e.getActorType().name())),
            e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("userActivityLogExecutor")
    public void on(UserProfileChangedEvent e) {
        log(e.getUserId().getId(), UserActivityEventType.PROFILE_UPDATED, null,
            JsonMetadata.of(Map.of("change_type", e.getChangeType().name())),
            e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("userActivityLogExecutor")
    public void on(PartyroomCreatedEvent e) {
        log(e.getHostUserAccountId(), UserActivityEventType.PARTYROOM_CREATED,
            e.getPartyroomId().getId(),
            JsonMetadata.of(Map.of("stage_type", e.getStageType().name())),
            e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("userActivityLogExecutor")
    public void on(CrewAccessedEvent e) {
        UserActivityEventType type = (e.getAccessType() == CrewAccessType.ENTER)
            ? UserActivityEventType.PARTYROOM_ENTERED
            : UserActivityEventType.PARTYROOM_EXITED;
        Map<String, Object> meta = new HashMap<>();
        meta.put("stage_type", e.getStageType() != null ? e.getStageType().name() : null);
        if (type == UserActivityEventType.PARTYROOM_EXITED && e.getDurationSec() != null) {
            meta.put("duration_sec", e.getDurationSec());   // EXIT만
        }
        log(e.getUserAccountId(), type, e.getPartyroomId().getId(),
            JsonMetadata.of(meta), e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("userActivityLogExecutor")
    public void on(CrewPenalizedEvent e) {
        log(e.getPunishedUserAccountId(), UserActivityEventType.PENALIZED_IN_PARTYROOM,
            e.getPartyroomId().getId(),
            JsonMetadata.of(Map.of(
                "penalty_type", e.getPenaltyType().name(),
                "by", "CREW")),
            e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("userActivityLogExecutor")
    public void on(AdminCrewPenalizedEvent e) {
        // §5.3 evolution으로 punishedUserAccountId가 이벤트 페이로드에 포함됨 (PR 9 evolution)
        log(e.getPunishedUserAccountId(), UserActivityEventType.PENALIZED_IN_PARTYROOM,
            e.getPartyroomId().getId(),
            JsonMetadata.of(Map.of(
                "penalty_type", e.getPenaltyType().name(),
                "by", "ADMIN",
                "by_administrator_id", e.getAdministratorId())),
            e.getOccurredAt());
    }

    private void log(Long userAccountId, UserActivityEventType type,
                     Long partyroomId, JsonMetadata meta, LocalDateTime occurredAt) {
        try {
            repository.save(UserActivityLogData.of(
                userAccountId, type, partyroomId, meta, occurredAt));
        } catch (Exception ex) {
            // drop-가능 정책: ERROR 로그 + swallow. 비즈니스 TX는 이미 commit.
            log.error("[UAL] failed to insert: type={}, userAccountId={}, partyroomId={}",
                      type, userAccountId, partyroomId, ex);
        }
    }
}
```

**미세 결정:**
- **에러 swallow** — `@Async`로 별 thread라 throw해도 publisher에 전파 안 됨. 명시적 try/catch + ERROR 로그 (모니터링 hook). spec §4.7.3 "유실 수용".
- **각 핸들러별 `@TransactionalEventListener + @Async` 명시** — 누락 시 sync 동작이라 일관성 깨짐. ArchUnit으로 "listener 메서드 모두 두 어노테이션 보유" 검증.
- **`event.getOccurredAt()` 사용** — `DomainEvent` 베이스가 자동 stamp. `Clock` 주입 불필요 (PR 8/9 패턴).
- **`CrewPenalizedEvent` vs `AdminCrewPenalizedEvent` 분리 핸들러** — metadata `by` 키로 구별. Single subscribed type 원칙 (PR 8 패턴).

### 4.3.1 Cross-BC lookup 회피 — `AdminCrewPenalizedEvent` evolution

PR 9 `AdminCrewPenalizedEvent`는 `punishedCrewId`만 보유. user_activity_log는 `user_account_id` 기준이라 변환 필요. 옵션 (A) cross-BC port / (B) 이벤트 evolution / (C) 양 이벤트 통일 추가 중 **(B)** 채택:

- **사유:** listener cross-BC lookup의 race(crew row 조회 시점이 admin penalty 발생 후 별 TX이라 간헐적 stale 가능, lookup 미스 시 audit row drop)와 추가 port/adapter 비용을 회피. 이벤트가 audit 소비자에게 필요한 정보를 self-contain하는 게 패턴 일관 (PR 8 lifecycle 이벤트들도 동일).
- **위치:** PR 12a §5.3에 evolution 변경. PR 9는 머지된 상태이므로 본 PR이 forward-evolution.

---

## 5. 이벤트 source publish 추가

### 5.1 `UserAccountSignedInEvent` (신규, auth domain)

**파일:** `app/src/main/java/com/pfplaybackend/api/auth/domain/event/UserAccountSignedInEvent.java`

```java
@Getter
public class UserAccountSignedInEvent extends DomainEvent {
    private final Long userAccountId;
    private final ProviderType provider;     // LOCAL | GOOGLE | KAKAO ...
    private final ActorType actorType;       // USER | ADMINISTRATOR

    public UserAccountSignedInEvent(Long userAccountId, ProviderType provider, ActorType actorType) {
        super();
        this.userAccountId = userAccountId;
        this.provider = provider;
        this.actorType = actorType;
    }

    @Override
    public String getAggregateId() { return String.valueOf(userAccountId); }

    public enum ActorType { USER, ADMINISTRATOR }
}
```

**Publish 위치 1 — `AdminLoginService.login`** (성공 path, JWT mint 직전):
```java
// after rate-limit pass + UA/admin/role 검증 통과 후, JWT mint 직전
eventPublisher.publishEvent(new UserAccountSignedInEvent(
    ua.getUserId().getUid(), ProviderType.LOCAL,
    UserAccountSignedInEvent.ActorType.ADMINISTRATOR));
```
- 신규 의존성: `ApplicationEventPublisher eventPublisher` 주입 추가.

**Publish 위치 2 — `AuthService` (OAuth login 성공 path)**: Task 0(implementation inventory)에서 OAuth success path 정확히 식별 후 동일 패턴 publish (`actorType=USER`).

### 5.1.1 `@Transactional(readOnly = true)` 보강

`AdminLoginService.login`은 현재 `@Transactional` 부재 → `@TransactionalEventListener(AFTER_COMMIT)`이 active TX 부재 시 listener 호출 안 함 (Spring 기본 동작). 대응:

- `AdminLoginService.login`에 `@Transactional(readOnly = true)` 추가. UA/admin 조회는 read-only, JWT mint는 외부 작업이라 TX 영향 없음. publish가 commit 직후 listener 호출 보장.
- `AuthService` OAuth login success path에 동일 보강. Task 0에서 정확한 메서드 식별.

### 5.2 `PartyroomCreatedEvent` (신규, party domain)

**파일:** `app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomCreatedEvent.java`

```java
@Getter
public class PartyroomCreatedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long hostUserAccountId;
    private final StageType stageType;          // MAIN | GENERAL

    public PartyroomCreatedEvent(PartyroomId partyroomId, Long hostUserAccountId, StageType stageType) {
        super();
        this.partyroomId = partyroomId;
        this.hostUserAccountId = hostUserAccountId;
        this.stageType = stageType;
    }

    @Override
    public String getAggregateId() { return String.valueOf(partyroomId.getId()); }
}
```

**Publish 위치 — `Partyroom` aggregate 생성 팩토리 메서드** (도메인 이벤트 register, 기존 `pollDomainEvents()` 자동 publish 활용):
```java
// Partyroom.createMainStage / createGeneralPartyRoom 안에서:
partyroom.registerDomainEvent(new PartyroomCreatedEvent(
    partyroom.getId(), partyroom.getHostId().getUid(), partyroom.getStageType()));
```
→ 기존 `PartyroomCommandService.createMainStage` / `createGeneralPartyRoom` line 98 / 108의 `partyroom.pollDomainEvents().forEach(eventPublisher::publishEvent)`가 자동 publish. service 코드 변경 0.

### 5.3 `AdminCrewPenalizedEvent` evolution (§4.3.1 B)

PR 9에서 도입된 `AdminCrewPenalizedEvent`에 `punishedUserAccountId` 필드 추가:

```java
@Getter
public class AdminCrewPenalizedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;
    private final CrewId punishedCrewId;
    private final Long punishedUserAccountId;       // 🆕 PR 12a 추가
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
    public String getAggregateId() { return String.valueOf(partyroomId.getId()); }
}
```

**Publisher 갱신 — `AdminCrewPenaltyCommandService.apply`** (PR 9):
```java
eventPublisher.publishEvent(new AdminCrewPenalizedEvent(
    pid, administratorId, new CrewId(crew.getId()),
    crew.getUserId().getUid(),                           // 🆕
    partyEnum, historyId, cmd.reason()));
```

**갱신 범위:** PR 9 단위 테스트 (`AdminCrewPenaltyCommandServiceTest`), IT (`AdminCrewPenaltyCommandServiceIT`), `PartyroomAdminActionListenerIT` 의 fixture가 `punishedUserAccountId` 단언 추가. PR 9 spec(`2026-04-28-admin-platform-pr9-design.md` §11)에 forward-evolution 사유 backfill (PR 8 §15 catch-up 패턴).

### 5.4 publish 변경 없음 (재활용)

| 기존 이벤트 | 위치 | listener 매핑 | 변경 |
|---|---|---|---|
| `MemberRegisteredEvent` | user BC, `MemberSignService` | SIGNED_UP | 없음 |
| `UserProfileChangedEvent` | user BC, `UserBioCommandService` / `UserAvatarCommandService` | PROFILE_UPDATED | 없음 |
| `CrewAccessedEvent` | party BC | PARTYROOM_ENTERED / _EXITED | inventory 후 누락 시 evolution (§5.5) |
| `CrewPenalizedEvent` | party BC | PENALIZED_IN_PARTYROOM (`by=CREW`) | inventory 후 누락 시 evolution (§5.5) |
| `AdminCrewPenalizedEvent` | party BC, PR 9 | PENALIZED_IN_PARTYROOM (`by=ADMIN`) | §5.3 evolution |

### 5.5 기존 이벤트 필드 inventory (PR 12a Task 0)

PR 12a 시작 시점에 `CrewAccessedEvent` / `CrewPenalizedEvent`의 현재 필드 점검:
- `CrewAccessedEvent`: listener는 `userAccountId`, `accessType` (ENTER / EXIT), `partyroomId`, `stageType`, `durationSec`(EXIT시 nullable) 필요.
- `CrewPenalizedEvent`: listener는 `punishedUserAccountId`, `partyroomId`, `penaltyType` 필요.

누락 필드는 본 PR에 evolution commit 추가 (G6 새 그룹 잠재적). 현재 시점 inventory는 plan 단계에서 진행.

---

## 6. Async 인프라 (`AsyncConfig`)

**파일:** `app/src/main/java/com/pfplaybackend/api/common/config/AsyncConfig.java`

```java
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

**미세 결정:**
- **위치 `common/config/`** — `@EnableAsync`는 application-level. 향후 다른 BC가 `@Async` 필요 시 같은 config에 bean 추가.
- **`CallerRunsPolicy`** — burst 시 producer thread가 직접 INSERT 실행 → drop 안 함. 단, producer가 `AFTER_COMMIT` listener context이므로 비즈니스 흐름 영향은 listener 동기화 정도. queue 200 saturation은 운영 ALERT 신호.
- **`WaitForTasksToCompleteOnShutdown=true`** — 그레이스풀 종료 시 큐에 남은 INSERT 처리. audit 손실 최소화.
- **`AwaitTerminationSeconds=10`** — 종료 timeout. spec §4.7.3 "drop-가능"이라 강제 drop 허용.
- **`@Async("userActivityLogExecutor")` 명시** — default executor 미사용. 다른 `@Async` 작업과 burst 영향 격리.

---

## 7. Race / Concurrency Analysis

### 7.1 비즈니스 TX vs listener async TX 분리

`@TransactionalEventListener(AFTER_COMMIT) + @Async`로 listener는 별 thread + 별 TX. 비즈니스 TX A commit → 이벤트 publish → executor enqueue → 별 thread에서 listener 실행 (`repository.save`가 자체 TX 시작 — Spring Data JPA 기본). 비즈니스 TX와 listener TX가 분리되어 audit row가 비즈니스 commit 직후 INSERT되지만 **별도 TX**라 atomic 아님 — drop-가능 정책 그대로.

**Listener 메서드 자체에는 `@Transactional` 명시 안 함** — `repository.save` 한 번만 호출하므로 Spring Data JPA의 implicit TX로 충분. 명시 시 `@Async + @Transactional` 인터셉터 순서 이슈 가능성.

### 7.2 동시 활동 (다중 디바이스 SIGNED_IN, 동시 룸 입장 등)

- 동일 user가 두 디바이스에서 SIGNED_IN 동시 → 두 publishEvent → 두 listener async task → 두 row INSERT (PK auto increment, UNIQUE 충돌 없음). 정상 시맨틱.
- 동일 user가 룸 A 빠르게 ENTER / EXIT 반복 → 4 이벤트 → 4 row, 시간순 정렬 정상.

### 7.3 Executor saturation

burst 시 queue (200) full → `CallerRunsPolicy` 발동 → producer thread (TX commit 직후 finalizer)가 직접 `repository.save` 실행. 핫패스 영향 — admin 로그인은 실서비스 사용량 낮으므로 무시 가능, 룸 입장 burst가 진짜 risk이지만 normalize 시점 200 queue + 4 thread × ~20ms INSERT = ~1초 흡수 가능. 운영 ALERT: `ual-` thread queue size 모니터링.

### 7.4 `@Async`에서 throw → swallow

listener 메서드 try/catch + ERROR 로그. `@Async`라 throw해도 caller(publisher)에 전파 안 됨 — 단, `Future` 리턴 미사용이라 throw가 silent drop. 명시적 swallow + 로그가 의도된 동작.

### 7.5 `@TransactionalEventListener(AFTER_COMMIT)` + 무 TX context

`AdminLoginService.login`은 `@Transactional` 없음 → `@TransactionalEventListener`는 active TX 부재 시 listener 호출 안 함 (Spring 기본 동작, `fallbackExecution=false`). §5.1.1 결정대로 `@Transactional(readOnly = true)` 추가 필요. 단순 read 흐름이므로 TX 추가 비용 거의 0.

`AuthService` OAuth login도 동일 — TX boundary 확인 후 누락 시 `@Transactional(readOnly = true)` 추가.

`MemberSignService` (SIGNED_UP), `UserBioCommandService` / `UserAvatarCommandService` (PROFILE_UPDATED), `PartyroomCommandService` (PARTYROOM_CREATED), Crew access service (ENTER / EXIT), `CrewPenaltyCommandService` / `AdminCrewPenaltyCommandService` (PENALIZED) — 모두 `@Transactional` write 흐름이라 listener 정상 호출.

### 7.6 PartyroomCreatedEvent의 `pollDomainEvents` 타이밍

`Partyroom.createMainStage` / `createGeneralPartyRoom` 안에서 `registerDomainEvent` → `PartyroomCommandService.create*`에서 `partyroom.pollDomainEvents().forEach(eventPublisher::publishEvent)`가 commit 전 호출 (현재 구현). `@TransactionalEventListener(AFTER_COMMIT)`는 publish 시점이 commit 전이라도 phase 검사 후 commit 후 실행 — 정상.

---

## 8. Testing Strategy

### 8.1 단위 테스트

- **`UserActivityLogListener`** (Mockito) — 7개 핸들러 각각:
  - input 이벤트 → expected `UserActivityLogData` 인자 (eventType, userAccountId, partyroomId, metadata 키).
  - `repository.save` 호출 검증.
  - `repository.save` throw 시 swallow + ERROR 로그 (LogCaptor 또는 동등).
- **`UserAccountSignedInEvent` / `PartyroomCreatedEvent`** — `getOccurredAt()` / `getAggregateId()` 자동 stamp.
- **`Partyroom.createMainStage` / `createGeneralPartyRoom`** — `pollDomainEvents()` 결과에 `PartyroomCreatedEvent` 포함.
- **`AdminLoginService.login`** — 성공 path에서 `eventPublisher.publishEvent(any(UserAccountSignedInEvent.class))` 1회 호출. 실패 path(rate limit / 자격 / revoked)에서 호출 없음.
- **`AuthService` OAuth path** — 동일.

### 8.2 통합 테스트 (Testcontainers MySQL)

- **V10 마이그레이션** — clean DB V9 → V10 적용 → `user_activity_log` 테이블 + 파티션 9개 + p_future + index 2개 검증 (`SHOW CREATE TABLE` 또는 `INFORMATION_SCHEMA.PARTITIONS` 쿼리).
- **`UserActivityLogListenerIT`** (`@SpringBootTest`, MySQL) — `Awaitility` + `@Async` poll:
  - 비즈니스 service 호출(`MemberSignService.register*`, `PartyroomCommandService.createGeneralPartyRoom`, `AdminLoginService.login`, etc.) → user_activity_log row 누적 (await 최대 5초).
  - `repository.save` mock으로 throw → 비즈니스는 commit 정상, ERROR 로그만.
- **`AsyncConfig` bean 검증** — `ThreadPoolTaskExecutor` bean이 `userActivityLogExecutor` 이름으로 등록 + core / max / queue / policy 값.
- **PR 9 IT 갱신** — 구현 단계에서 inspection 결과 `AdminCrewPenaltyCommandServiceIT`는 event payload를 capture하지 않고 `partyroom_admin_action` / `crew_penalty_history` 테이블 row만 검증함 → 별도 갱신 불필요. `punishedUserAccountId` 검증은 `AdminCrewPenaltyCommandServiceTest`(unit, ArgumentCaptor) + 신규 `UserActivityLogListenerAdminPenaltyIT`(end-to-end)로 이중 cover. `PartyroomAdminActionListenerIT`도 `commandService.terminate/setDisplayFlag/...` 경유라 event 직접 생성 부재 → 갱신 불필요. 단 `PartyroomAdminActionListenerTest`(unit)는 event 생성 fixture 보유 → 7-arg constructor cascade 갱신.

### 8.3 ArchUnit

- listener 메서드 모두 `@TransactionalEventListener` + `@Async("userActivityLogExecutor")` 두 어노테이션 보유 (UserActivityLogListener 한정).
- `administration` BC가 `party.domain.event` / `user.domain.event` / `auth.domain.event` 단방향 의존 (PR 8 가드 + 신규 path).
- 신규 위반 없음.

### 8.4 Concurrency

- 동시 SIGNED_IN 2회 → 2 row INSERT (PK 충돌 없음, await 후 count=2).
- 동시 PARTYROOM_ENTERED 다중 user → row 다중 INSERT, await 후 user별 정확.

### 8.5 Out of scope (test)

- `last_activity_desc` 정렬 (PR 12b).
- A-2 `recentActivityLog` projection (PR 12b).
- partition 자동 생성 배치 (future).
- multi-instance executor isolation (single-instance MVP).

대략 신규 테스트 ~22 (unit ~10, IT ~8, archunit ~2, concurrency ~2).

---

## 9. Atomic Commit Groupings

| 그룹 | 묶이는 변경 | 사유 |
|---|---|---|
| **G1: V10 + entity + enum + repository** | V10 SQL + `UserActivityLogData` + `UserActivityLogId` + `UserActivityEventType` enum (10종 전체) + `UserActivityLogRepository` | DDL ↔ JPA entity boot-or-die. enum은 listener와 분리 commit 가능하지만 entity와 한 묶음이 자연스러움 |
| **G2: AsyncConfig + Listener (7 핸들러)** | `AsyncConfig`(`@EnableAsync` + `userActivityLogExecutor` bean) + `UserActivityLogListener` 7 핸들러 (`@Async("userActivityLogExecutor")` reference) | listener `@Async` qualifier가 bean 이름 직접 참조 — bean 부재 시 startup fail. Atomic |
| **G3: AdminCrewPenalizedEvent evolution + PR 9 service publish 갱신 + PR 9 IT 갱신 + PR 9 spec catch-up** | event 필드 추가 + `AdminCrewPenaltyCommandService.apply` publish 인자 + 기존 PR 9 IT/단위 테스트 갱신 + PR 9 design spec §11에 backfill 사유 | event payload 변경 ↔ 모든 publisher / consumer 동시 적용 |
| **G4: PartyroomCreatedEvent + Partyroom aggregate publish + listener 핸들러 결합** | event 클래스 + `Partyroom.create*` 안에서 `registerDomainEvent` + listener 핸들러는 G2의 클래스에 추가 commit | publish-consume pair atomic, missing handler 시 dead event |
| **G5: UserAccountSignedInEvent + AdminLoginService/AuthService publish + readOnly TX 보강 + listener 핸들러 결합** | event 클래스 + 두 service publish + `@Transactional(readOnly = true)` 추가 + listener 핸들러는 G2의 클래스에 추가 commit | publish-consume pair atomic + TX context 보강 |

기타 task 별 독립 commit:
- 기존 이벤트 필드 inventory + 누락 시 evolution commit (`CrewAccessedEvent` / `CrewPenalizedEvent` listener 필요 필드 보강) — 잠재적 G6.
- ArchUnit 회귀.
- IT/단위 테스트들.

총 ~10-12 commits (G1~G5 + 5-7 task commits).

---

## 10. Risks & Mitigations

| # | 위험 | 완화 |
|---|---|---|
| 1 | V10 partition syntax MySQL 버전별 동작 차이 | spec §4.7.1 RANGE 표준 문법, MySQL 8.0 검증. Testcontainers MySQL 8 IT |
| 2 | `@TransactionalEventListener(AFTER_COMMIT)` + 무 TX → silent drop | §5.1.1 `AdminLoginService.login` + `AuthService` publish path에 `@Transactional(readOnly = true)` 보강. 단위 테스트로 publishEvent 호출 검증 |
| 3 | `@Async` listener throw → silent | try/catch + ERROR 로그. drop-가능 정책 (spec §4.7.3) |
| 4 | Executor queue saturation (룸 입장 burst) | `CallerRunsPolicy` + queue 200 + thread 4. 운영 ALERT (queue size 모니터링). 단일 인스턴스 MVP라 절대값 작음 |
| 5 | `CrewAccessedEvent` / `CrewPenalizedEvent` 필드 누락 (`userAccountId` / `stageType` / `durationSec` / `punishedUserAccountId`) | Task 0에서 inventory → 누락 시 PR 12a에 evolution commit (G6 추가) |
| 6 | `pollDomainEvents()` 패턴이 `PartyroomCreatedEvent`도 자동 publish하는지 | inventory에서 `Partyroom` aggregate 메서드 + service polling 시점 검증. 누락 시 service 코드 변경 (예외 케이스만) |
| 7 | partition 9개월 후 (2027-01) 추가 누락 | p_future MAXVALUE fallback. 운영 6개월차에 ALERT job 도입 (future PR) |
| 8 | listener evolution 시 멀티 인스턴스 롤링 deploy → 일부 인스턴스 핸들러 부재 → drop | drop-가능이라 영향 작음. PR 8/9의 atomic listener와 다른 시맨틱 — 운영 메모 |
| 9 | `@EnableAsync` 도입으로 다른 코드의 default executor 사용 발견 시 영향 | grep `@Async` 0건 확인됨 (탐색 결과). 신규 도입이라 영향 0 |
| 10 | `JsonMetadataConverter` (PR 8)와 user_activity_log 통합 | 같은 converter 재사용. PR 8 IT 패턴 IT |
| 11 | `AdminCrewPenalizedEvent` 필드 추가가 PR 9 머지 commit 위에 evolution → PR 9 spec catch-up | spec PR 9 §11에 `punishedUserAccountId` 추가 사유 backfill (PR 8 §15 패턴) |
| 12 | OAuth login publish 위치 — `AuthService` 코드 미파악 | Task 0 inventory에서 OAuth success path 정확히 식별. 누락 시 publish location 이동 |

---

## 11. Decisions Taken (브레인스토밍 결과)

1. **Q1 — PR 12 분할 (B)**: PR 12a (V10 + listener + 이벤트 source 배선) → PR 12b (Member 어드민 API). 인프라/표면 분리.
2. **Q2 — 이벤트 source 범위 (A)**: PR 12a는 7개 event_type만 (SIGNED_UP / _IN / PROFILE_UPDATED / PARTYROOM_CREATED / _ENTERED / _EXITED / PENALIZED_IN_PARTYROOM). WITHDREW / TIER_CHANGED / ADMIN_ACTED_ON은 PR 12b.
3. **Q3 — Listener 정책 (A)**: spec 원안 `@TransactionalEventListener(AFTER_COMMIT) + @Async` + drop-가능. PR 8/9 패턴(sync atomic)과 의도적 차별화 — audit timeline은 비즈니스 흐름을 막으면 안 됨.
4. **Q4 — PR 12a in-scope (4 default 모두 채택)**:
   - 어드민 로그인을 `SIGNED_IN`에 기록 (actor_type metadata로 USER vs ADMINISTRATOR 구분).
   - `@EnableAsync` + `userActivityLogExecutor` bean 신규 도입.
   - Partition 자동 생성 배치는 OUT-of-scope (9개월 + p_future MAXVALUE).
   - `PartyroomCreatedEvent` 신규 추가.
5. **§4.3.1 (B)**: `AdminCrewPenalizedEvent`에 `punishedUserAccountId` 필드 추가 (PR 9 evolution). cross-BC lookup port 회피.
6. **§5.1.1**: `AdminLoginService.login` + `AuthService` OAuth publish path에 `@Transactional(readOnly = true)` 추가하여 `@TransactionalEventListener(AFTER_COMMIT)` 동작 보장.
7. **§6 (A)**: `AsyncConfig` 위치는 `common/config/`. `@EnableAsync` application-wide.
8. **`UserActivityEventType` enum 10종 전체 미리 정의** — PR 12b가 wiring만 추가, enum 재배포 회피.
9. **listener 에러 정책** — `@Async` 핸들러 try/catch swallow + ERROR 로그. spec §4.7.3 "유실 수용".

---

## 12. Open Items / Implementation Reality (post-build catch-up)

PR 12a 구현 완료 시점에 spec과의 차이 / 세부 결정사항을 기록 (PR 8 §15 / PR 9 §11 패턴):

- (placeholder — 구현 후 작성)

---

**다음 단계:** 본 spec이 reviewer + 사용자 승인되면 `superpowers:writing-plans`로 구현 plan 생성 (`docs/superpowers/plans/2026-04-28-admin-platform-pr12a.md`).
