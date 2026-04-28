# PR 12b1: Member 어드민 read API + listener skeleton + §12.10 polish — Design

> Brainstormed design spec. Implementation plan is generated separately via `superpowers:writing-plans`.

**Date:** 2026-04-28
**Branch:** `feature/admin-auth-iam-schema` (PR 12a HEAD `47cf0e23` 위에 빌드)
**Roadmap row:** §9.1 PR 12 — *member 관리 API (A-1~A-4)* split (b) 중 read 부분 + PR 12a §12.10 미결정 polish
**Milestone:** M5 (PR 12-13, 유저 관리 + 활동 로그 + 신고 시스템) 진행
**Split rationale:** roadmap의 PR 12는 size L. PR 12a (인프라)에 이어 PR 12b도 (B) 분할 — PR 12b1 (read + listener skeleton + polish) → PR 12b2 (write A-3/A-4). read 먼저 머지되면 PR 12a audit log가 어드민 콘솔에서 즉시 검증됨, write의 비식별화/이벤트 chain은 더 신중한 검토.

---

## 1. Goal

PR 12a가 구축한 user_activity_log 인프라 위에 Member 어드민 API의 read 절반(A-1 목록/검색, A-2 상세 + recentActivityLog 30건)을 도입한다. 동시에 PR 12a §12.10에 누적된 polish 4건 중 cheap 2건(`UserActivityLogId` 삭제 + listener grouping divider)을 정리하고, PR 12b2가 도입할 write API의 이벤트 source(`MemberTierChangedEvent` / `UserAccountWithdrawnEvent`) 소비 listener handler 2종을 skeleton + unit test 형태로 미리 wiring한다 — 단 publish source 부재라 PR 12b2 머지 시점까지 listener handler는 dead path (단위 테스트만 활성).

본 PR이 끝나면:
- 어드민이 Member 목록을 조회·검색·정렬 가능 (`GET /api/v1/admin/members`).
- 어드민이 Member 상세 + recentActivityLog 30건 조회 가능 (`GET /api/v1/admin/members/{memberId}`) — PR 12a audit log가 어드민 인터페이스에서 즉시 검증됨.
- listener에 `on(MemberTierChangedEvent)` + `on(UserAccountWithdrawnEvent)` skeleton 미리 도입 — PR 12b2가 publish 추가만 하면 즉시 활성.
- `UserActivityLogId` dead code 정리 + listener handler grouping divider 추가.

---

## 2. Scope

### 2.1 In Scope (PR 12b1)

1. **A-1 endpoint** — `GET /api/v1/admin/members` (`@PreAuthorize("@adminAuth.isAdmin()")` PR 5 SpEL bean 일관). Query: `email`(LIKE) / `tier`(AuthorityTier enum) / `joined_from`/`joined_to`(LocalDate) / `page`(default 0) / `size`(default 50, max 200) / `sort`(`created_at_desc` default, `created_at_asc`, `last_activity_desc`).
2. **A-2 endpoint** — `GET /api/v1/admin/members/{memberId}`. Detail response: member + linked userAccount + profile(nickname/introduction/avatar) + authorityTier + recentActivityLog 30건.
3. **`recentActivityLog` projection** — `UserActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDesc(Long)` (Spring Data JPA derived query, idx_ual_user_time DESC cover). Response DTO `RecentActivityLogItem(eventType, partyroomId, metadata, occurredAt)`.
4. **`last_activity_desc` sort** — A-1 sort 옵션. user_activity_log `MAX(occurred_at) GROUP BY user_account_id` LEFT JOIN. MVP 회원 수에서 acceptable (§10 risk #2).
5. **Administration BC 신규 컴포넌트**:
   - `AdminMemberQueryController` (`administration/adapter/in/web/`)
   - `AdminMemberQueryService` (`administration/application/service/`)
   - `AdminMemberQueryRepository` + `AdminMemberQueryRepositoryImpl` (QueryDSL — PR 8 `AdminPartyroomQueryRepositoryImpl` 패턴 일관)
   - DTO: `AdminMemberSummaryResponse` (목록 row), `AdminMemberDetailResponse` (상세), `RecentActivityLogItem`, `UserAccountSummary`, `MemberProfileSummary`
   - Query parameters DTO: `AdminMemberListQuery` (page/size/sort/email/tier/dates)
6. **신규 도메인 이벤트 1종 (user domain)**:
   - `MemberTierChangedEvent(userAccountId, memberId, oldTier, newTier, byAdministratorId)` — getAggregateId() returns memberId. 단, PR 12b1은 listener handler skeleton만, publish source(`AdminMemberTierCommandService` 등)는 PR 12b2에서.
7. **`UserAccountWithdrawnEvent` evolution (user domain, PR 1 이벤트)**:
   - 기존 `(userAccountId, anonymizedEmail)` → `(userAccountId, anonymizedEmail, byAdministratorId)`. PR 12a `AdminCrewPenalizedEvent` evolution 패턴 동형 (G3). 사용처 inventory 후 cascade. PR 1 spec 또는 user 모듈 README backfill (필요 시).
8. **listener handler 2종 skeleton** (`UserActivityLogListener`):
   - `on(MemberTierChangedEvent)` → 2 row INSERT: `TIER_CHANGED` (대상 user 관점, metadata: `old_tier`, `new_tier`, `by_administrator_id`) + `ADMIN_ACTED_ON` (대상 user 관점, metadata: `action_type` = "TIER_CHANGED", `by_administrator_id`).
   - `on(UserAccountWithdrawnEvent)` → 2 row INSERT: `WITHDREW` (대상 user 관점, metadata: `by_administrator_id`) + `ADMIN_ACTED_ON` (대상 user 관점, metadata: `action_type` = "WITHDRAW", `by_administrator_id`).
   - 단위 테스트만 (Mockito repository.save 검증). end-to-end IT는 PR 12b2 (publish source 도입 후).
9. **§12.10 polish 2건**:
   - `UserActivityLogId.java` + `UserActivityLogIdTest.java` 삭제 (dead code).
   - `UserActivityLogListener`에 grouping divider comment 추가.
10. **A-1/A-2 응답에 `withdrawn` 필드 노출** — `UserAccountData.withdrawnAt` non-null check.
11. **테스트** — 단위/IT/WebMvc/ArchUnit (§8).

### 2.2 Out of Scope (PR 12b2 또는 future)

| 항목 | 위치 | 사유 |
|---|---|---|
| A-3 PATCH `/.../{id}/tier` + `MemberTierChangedEvent` publish | PR 12b2 | write — 별 PR 격리 |
| A-4 POST `/.../{id}/withdraw` + 비식별화 + `UserAccountWithdrawnEvent` publish | PR 12b2 | write + 비식별화 위험 |
| listener handler end-to-end IT (실제 publish path 검증) | PR 12b2 | publish source 도입 후 |
| `crew.user_id NOT NULL` V11 ALTER | future schema PR | PR 12a §12.10 / PR 12b1 무관 |
| Queue size monitoring (Prometheus/CloudWatch) | future 운영 PR | infra 작업 |
| AvatarBody/Face/Icon 응답 nesting (A-2 detail) | PR 12b2 또는 future | PR 11 Avatar 모듈 query port 통합 검토 필요 |
| `activities` (DJ_PNT/ROOM_ACT) 점수 응답 (A-2 detail) | future | scoring 시스템 설계 미정 |
| `walletAddress` 응답 (A-2 detail) | future | 지갑 연동 시점 결정 |
| `member.last_activity_at` denormalized column | future | MVP 회원 수에서 GROUP BY join acceptable |
| Re-registration 동일 email 처리 정책 | future | 비식별화 PR 12b2의 부수 결정 |

---

## 3. Endpoint Specification

### 3.1 `GET /api/v1/admin/members` — A-1 목록/검색

| 항목 | 값 |
|---|---|
| Auth | `@PreAuthorize("@adminAuth.isAdmin()")` (PR 5 SpEL bean) |
| Cookie | `AdminAccessToken` (PR 4 admin cookie chain) |
| Query | `email` (LIKE, optional) / `tier` (AuthorityTier enum, optional) / `joined_from` `joined_to` (LocalDate, optional) / `page` (default 0) / `size` (default 50, max 200) / `sort` |
| Sort | `created_at_desc` (default), `created_at_asc`, `last_activity_desc` |
| Response | `200 OK`, `ApiCommonResponse<Page<AdminMemberSummaryResponse>>` |

**Response shape** (PR 8/9 wrapper 패턴):
```json
{
  "data": {
    "content": [
      {
        "memberId": 123,
        "userAccountId": 456,
        "email": "user@example.com",
        "providerType": "GOOGLE",
        "nickname": "DJ_Master",
        "authorityTier": "FM",
        "lastLoginAt": "2026-04-19T10:30:00Z",
        "createdAt": "2025-12-01T10:00:00Z",
        "withdrawn": false,
        "withdrawnAt": null
      }
    ],
    "pageInfo": { "page": 0, "size": 50, "totalElements": 1234 }
  },
  "meta": {...}
}
```

**Validation**:
- `size > 200` → 400 (DoS 방지).
- `joined_from > joined_to` → 400.
- `tier`가 enum 외 값 → 400 (Spring conversion 자동).
- `sort`가 허용 외 값 → 400.
- 결과 0건 → 200 + 빈 content (404 아님).

**Errors**:
| HTTP | 사유 |
|---|---|
| 400 | DTO validation (size cap, date range, sort/tier enum) |
| 401 | 어드민 미인증 |
| 403 | `@adminAuth.isAdmin()` 실패 |

### 3.2 `GET /api/v1/admin/members/{memberId}` — A-2 상세

| 항목 | 값 |
|---|---|
| Auth | `@PreAuthorize("@adminAuth.isAdmin()")` |
| Path | `memberId` (Long) |
| Response | `200 OK`, `ApiCommonResponse<AdminMemberDetailResponse>` |

**Response shape**:
```json
{
  "data": {
    "memberId": 123,
    "userAccount": {
      "userAccountId": 456,
      "email": "user@example.com",
      "providerType": "GOOGLE",
      "lastLoginAt": "...",
      "withdrawnAt": null
    },
    "profile": {
      "nickname": "DJ_Master",
      "introduction": "..."
    },
    "authorityTier": "FM",
    "createdAt": "...",
    "recentActivityLog": [
      {
        "eventType": "PARTYROOM_ENTERED",
        "partyroomId": 1,
        "occurredAt": "2026-04-28T12:00:00Z",
        "metadata": null
      }
    ]
  },
  "meta": {...}
}
```

**구현**:
- `MemberData` + linked `UserAccountData` join (single QueryDSL query in `AdminMemberQueryRepositoryImpl.findDetail`).
- `recentActivityLog`: `UserActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDesc(userAccountId)` — Spring Data JPA derived query. Returns `List<UserActivityLogData>` mapped to `RecentActivityLogItem` DTO. idx_ual_user_time DESC cover.
- `profile.avatarBody/Face/Icon` 미포함 (OOS §2.2). `MemberData.profileData.nickname` + `introduction`만 매핑.
- `activities` + `walletAddress` 미포함 (OOS).

**Errors**:
| HTTP | 사유 |
|---|---|
| 401 | 어드민 미인증 |
| 403 | `@adminAuth.isAdmin()` 실패 |
| 404 | `MEMBER_NOT_FOUND` |

**Withdrawn member 처리**: 어드민은 모든 member 조회 가능 (404 아님). `withdrawn=true` + `withdrawnAt` + anonymizedEmail 노출.

---

## 4. Domain Events + Listener Handlers

### 4.1 신규 이벤트 — `MemberTierChangedEvent`

**파일:** `user/src/main/java/com/pfplaybackend/api/user/domain/event/MemberTierChangedEvent.java`

```java
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

`getAggregateId()` returns `memberId` (member is the aggregate of mutation). `userAccountId`는 listener-friendly subject. `byAdministratorId`는 ADMIN_ACTED_ON metadata.

### 4.2 `UserAccountWithdrawnEvent` evolution (PR 1 이벤트)

기존 PR 1 시그니처: `(Long userAccountId, String anonymizedEmail)`. PR 12b1에서 forward-evolution — `byAdministratorId` 추가:

```java
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
        return String.valueOf(userAccountId);
    }
}
```

**사용처 inventory + cascade fix**: `grep -rn "new UserAccountWithdrawnEvent(" app/src user/src --include="*.java"` 후 모든 caller(production + test)를 3-arg form으로 갱신. PR 12a `AdminCrewPenalizedEvent` evolution 패턴 동형 (G3).

**Forward-evolution 패턴 (PR 12a G3 동형)**:
- (a) PR 12b1에서 evolution + cascade
- (b) PR 1 spec 또는 user 모듈 README/`MATURITY_ASSESSMENT.md`에 backfill (PR 12b1 §12 catch-up — 단, PR 1은 admin-platform spec series 전이라 §11 catch-up 대상 없음 → user 모듈 doc만)
- (c) listener handler가 `byAdministratorId` 활용

### 4.3 `UserActivityLogListener` 핸들러 2종 추가 (8/9th)

새 핸들러는 `// === User/Member events ===` divider 그룹에 추가:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async(AsyncConfig.UAL_EXECUTOR_BEAN)
public void on(MemberTierChangedEvent e) {
    // Row 1: 대상 user 관점 TIER_CHANGED
    Map<String, Object> tierMeta = new HashMap<>();
    tierMeta.put("old_tier", e.getOldTier().name());
    tierMeta.put("new_tier", e.getNewTier().name());
    tierMeta.put("by_administrator_id", e.getByAdministratorId());
    log(e.getUserAccountId(), UserActivityEventType.TIER_CHANGED, null,
        JsonMetadata.of(tierMeta), e.getOccurredAt());

    // Row 2: 대상 user 관점 ADMIN_ACTED_ON
    Map<String, Object> actMeta = new HashMap<>();
    actMeta.put("action_type", "TIER_CHANGED");
    actMeta.put("by_administrator_id", e.getByAdministratorId());
    log(e.getUserAccountId(), UserActivityEventType.ADMIN_ACTED_ON, null,
        JsonMetadata.of(actMeta), e.getOccurredAt());
}

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

**미세 결정**:
- **2 row INSERT의 occurredAt**: 같은 `e.getOccurredAt()` 재사용 → 두 row가 정확히 같은 timestamp. ORDER BY occurred_at DESC + log_id DESC tie-breaker로 안정 정렬. 핸들러는 결정적 순서로 INSERT — `MemberTierChangedEvent`는 `TIER_CHANGED` 먼저 → `ADMIN_ACTED_ON` 나중. `log_id`가 AUTO_INCREMENT이므로 `ADMIN_ACTED_ON.log_id > TIER_CHANGED.log_id`. ORDER BY `occurred_at DESC, log_id DESC` 정렬에서는 `ADMIN_ACTED_ON`이 먼저 노출 — 어드민 viewer 입장에서 "어드민이 액션을 했다 → 결과 state change" 순으로 audit-first 읽기. `UserAccountWithdrawnEvent`도 동형(WITHDREW → ADMIN_ACTED_ON, 표시는 ADMIN_ACTED_ON 먼저).
- **Listener total handlers = 9 (PR 12a 7 + PR 12b1 2)**: divider comment로 grouping (`// === User/Member events ===`: SIGNED_UP / SIGNED_IN / PROFILE_UPDATED / TIER_CHANGED / WITHDREW; `// === Party events ===`: PARTYROOM_CREATED / ENTERED·EXITED / PENALIZED-CREW / PENALIZED-ADMIN).
- **ADMIN_ACTED_ON row 발생 패턴**: source event(MemberTierChanged / UserAccountWithdrawn)가 internal logic으로 row 2건 INSERT. 별도 `AdminActedOnEvent`는 도입 안 함 (Q3.5).
- **`UserActivityEventType.TIER_CHANGED` / `WITHDREW` / `ADMIN_ACTED_ON`** — PR 12a §11.8에서 미리 정의된 enum 값. PR 12b1에서 처음 활성.

---

## 5. §12.10 Polish 항목 (G1)

### 5.1 `UserActivityLogId` 삭제

PR 12a `UserActivityLogId.java` (`administration/domain/entity/`) + `UserActivityLogIdTest.java`(unit, 3 tests) 모두 삭제. PR 12a §12.10 결정 (a) wire vs (b) delete에서 (b) 채택.

**근거**: A-2 `recentActivityLog` projection은 `findTop30ByUserAccountIdOrderByOccurredAtDesc(Long)` derived query — composite key 식별자 불필요. PR 12b future use case에서 필요해지면 5분에 재추가 가능.

**영향 범위 검증**: `grep -rn "UserActivityLogId" app/src` → 현재 entity Javadoc 참조 1건 + test 1건만. 삭제 시 entity Javadoc도 갱신 ("composite key class 보존" → "DB-level composite PK는 V10 SQL에 보존, JPA는 log_id 단독 @Id").

### 5.2 Listener grouping divider comment

`UserActivityLogListener` 9 handlers (PR 12a 7 + PR 12b1 2)에 divider:

```java
// === User/Member events ===
public void on(MemberRegisteredEvent e) { ... }      // SIGNED_UP
public void on(UserAccountSignedInEvent e) { ... }   // SIGNED_IN
public void on(UserProfileChangedEvent e) { ... }    // PROFILE_UPDATED
public void on(MemberTierChangedEvent e) { ... }     // TIER_CHANGED + ADMIN_ACTED_ON
public void on(UserAccountWithdrawnEvent e) { ... }  // WITHDREW + ADMIN_ACTED_ON

// === Party events ===
public void on(PartyroomCreatedEvent e) { ... }      // PARTYROOM_CREATED
public void on(CrewAccessedEvent e) { ... }          // PARTYROOM_ENTERED/_EXITED
public void on(CrewPenalizedEvent e) { ... }         // PENALIZED_IN_PARTYROOM (by=CREW)
public void on(AdminCrewPenalizedEvent e) { ... }    // PENALIZED_IN_PARTYROOM (by=ADMIN)
```

---

## 6. AdminMemberQueryRepository 구조

PR 8 `AdminPartyroomQueryRepository(Impl)` QueryDSL 패턴 일관:

```
administration/adapter/out/persistence/
├── AdminMemberQueryRepository.java       (interface)
└── impl/AdminMemberQueryRepositoryImpl.java  (QueryDSL JPAQueryFactory)
```

**메서드**:
- `Page<AdminMemberSummaryRow> search(AdminMemberListQuery q, Pageable pageable)` — A-1.
- `Optional<AdminMemberDetailRow> findDetail(Long memberId)` — A-2 (member + userAccount join).

`recentActivityLog`는 별도 `UserActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDesc(Long)` Spring Data JPA derived query — Administration BC 안에서 cross-repository orchestration은 `AdminMemberQueryService.getDetail()`이 담당 (PR 8 패턴).

**`last_activity_desc` sort 구현 (QueryDSL)**:
```sql
SELECT m.*, COALESCE(MAX(ual.occurred_at), m.created_at) AS last_activity
FROM member m
LEFT JOIN user_activity_log ual ON ual.user_account_id = m.user_account_id
GROUP BY m.member_id
ORDER BY last_activity DESC
LIMIT ?, ?;
```

`COALESCE(MAX(ual.occurred_at), m.created_at)` — 신규 가입자(활동 로그 0건)는 `last_activity` 정렬 키가 NULL이 되어 ORDER BY DESC에서 마지막에 밀리는 surprise를 차단. `m.created_at`을 fallback으로 사용 → 최근 가입자가 자연스럽게 상위에 노출. IT는 SEED 시 활동 0건 member도 정렬 결과에 포함됨을 검증해야 함.

QueryDSL 형태로 작성. user_activity_log MVP 회원 수에서 acceptable. future polish: Member에 `lastActivityAt` denormalized column + listener fan-out write.

---

## 7. Race / Concurrency

PR 12b1은 read-only API + listener skeleton(unit test only). 큰 race risk 없음:
- A-1/A-2 query는 read transaction. write와 race 시 stale data 노출 가능성 (해당 시점 commit 기준 — 정상).
- listener handler skeleton은 publish source 부재라 PR 12b1 동안 dead path.
- `recentActivityLog` 30건 query는 partition pruning 안 됨(WHERE on user_account_id only), idx_ual_user_time DESC + LIMIT 30이 cover. 활동 많은 user에서도 ~10ms 이내.

**미세**: `last_activity_desc` sort은 user_activity_log GROUP BY 결과를 정렬 — 큰 회원/이력 시 expensive. MVP scope에서 acceptable. monitoring 추가 시 ALERT 후보.

---

## 8. Testing Strategy

### 8.1 단위 테스트
- `MemberTierChangedEventTest` — 5 필드 + getOccurredAt + getAggregateId.
- `UserAccountWithdrawnEventTest` evolution — `byAdministratorId` 추가 단언 + 기존 fixture 갱신.
- `UserActivityLogListenerTest` — `on(MemberTierChangedEvent)` 2 row INSERT (TIER_CHANGED + ADMIN_ACTED_ON, ArgumentCaptor 2회 검증) + `on(UserAccountWithdrawnEvent)` 2 row INSERT (WITHDREW + ADMIN_ACTED_ON). 9 handlers total (existing 7 + new 2).
- `AdminMemberQueryServiceTest` — `getList` / `getDetail` mock 단위:
  - `getList` 분기 (filter/sort/pagination 인자가 repository로 정확히 위임).
  - `getDetail` cross-repository orchestration — `AdminMemberQueryRepository.findDetail` mock + `UserActivityLogRepository.findTop30...` mock 2개를 함께 stub하여 두 source 결과가 단일 `AdminMemberDetailResponse`로 합쳐지는 지 검증. memberId 부재 시 `MEMBER_NOT_FOUND` 예외 매핑.

### 8.2 통합 테스트 (Testcontainers MySQL)
- `AdminMemberQueryRepositoryImplIT` — A-1 filter (email LIKE / tier / dates) + sort (3 옵션) + pagination. PR 8 `AdminPartyroomQueryRepositoryImplIT` 패턴.
- `AdminMemberQueryServiceIT` — A-2 detail end-to-end (member + userAccount + recentActivityLog 30건 join). user_activity_log SEED 30+1 row → 정확히 30 limit 확인. ORDER BY occurred_at DESC tie-breaker도 검증.

### 8.3 WebMvc
- `AdminMemberQueryControllerTest` — A-1 query parameter 검증(400 cases) + 401/403/200, A-2 path / 401/403/404/200.

### 8.4 ArchUnit
- 기존 `CrossContextDependencyTest`의 listener annotation rule 9 handlers cover (annotation-driven 매처 — 자동).
- `user.domain.event` → administration 의존 가드 (PR 12a) — 신규 event들도 자동 cover.

### 8.5 Out of scope (PR 12b2)
- listener handler end-to-end IT (publish source 도입 후).
- A-3/A-4 service/IT.
- 비식별화 정책 검증.

대략 신규 테스트 ~22 (unit ~10, IT ~6, WebMvc ~6).

---

## 9. Atomic Commit Groupings

| Group | 묶음 | 사유 |
|---|---|---|
| **G1: §12.10 polish** | `UserActivityLogId.java` 삭제 + `UserActivityLogIdTest.java` 삭제 + entity Javadoc 갱신 + listener divider comment 추가 | dead code 정리 + readability — write API 시작 전 깔끔한 baseline 확보 |
| **G2: 이벤트 evolution + listener skeleton** | `MemberTierChangedEvent` 신규 + `UserAccountWithdrawnEvent` evolution(`byAdministratorId` 필드 추가) + 기존 `UserAccountWithdrawnEvent` 사용처 cascade(현재 publish 안 됨, 단위 테스트 fixture만 영향) + listener 2 핸들러 + 단위 테스트 + (필요 시) PR 1 spec/doc backfill | publish-consume pair atomic + forward-evolution discipline (PR 12a G3 동형) |
| **G3: A-2 detail endpoint** | `AdminMemberDetailResponse` DTO + `RecentActivityLogItem` + `UserAccountSummary` + `MemberProfileSummary` + `AdminMemberQueryRepository` + `Impl.findDetail` + `UserActivityLogRepository.findTop30...` derived query + `AdminMemberQueryService.getDetail` + controller GET `{memberId}` + WebMvc + IT | endpoint 단위 PR 8/9 패턴 |
| **G4: A-1 list endpoint** | `AdminMemberSummaryResponse` DTO + `AdminMemberListQuery` + `AdminMemberQueryRepositoryImpl.search` (filter + 3 sort + pagination) + service `getList` + controller GET `/` + WebMvc + IT | A-2 위에 build (Repository class 공유) |
| **G5: spec catch-up** | docs/superpowers/specs §12 PR 12b1 placeholder backfill + PR 1 `UserAccountWithdrawnEvent` evolution backfill (필요 시 user 모듈 doc) | 누적 deviations + cross-PR forward-evolution audit trail |

총 5 chunks (PR 12a 7 chunks보다 작음).

---

## 10. Risks & Mitigations

| # | 위험 | 완화 |
|---|---|---|
| 1 | `UserAccountWithdrawnEvent` evolution이 PR 1 사용처 cascade 영향 | inventory grep 후 모든 caller 2-arg → 3-arg로 갱신. PR 12a G3 `AdminCrewPenalizedEvent` evolution 패턴 동형 |
| 2 | `last_activity_desc` sort 성능 (user_activity_log GROUP BY) | MVP 회원 수에서 acceptable. monitoring 후 회원 1만 명+ 시 denormalized `member.last_activity_at` column 도입 |
| 3 | listener skeleton dead path (publish source 부재) | 단위 테스트로 logic 활성. PR 12b2 머지 시점에 end-to-end IT 추가 |
| 4 | A-1 size DoS (size=10000) | size cap 200 (validation 400) |
| 5 | `recentActivityLog` 응답 누적 — 활동 많은 user에서 metadata JSON 크기 | 30 limit + metadata는 짧은 string/number 위주 metadata 컨벤션 유지 |
| 6 | `withdrawn` member의 detail 조회 정책 | 어드민은 모든 member 조회 가능 (404 아님). withdrawn=true + anonymizedEmail 노출 |
| 7 | `MemberTierChangedEvent`가 `userAccountId` + `memberId` 둘 다 보유 — 중복 위험 | §11 #10 결정 따라 `userAccountId`는 listener subject, `memberId`는 `getAggregateId()` (audit log eventType 외 컨텍스트 미저장) 한정 사용. listener metadata에는 `memberId` 미기록 (불필요). |
| 8 | listener handler 2 row INSERT의 timestamp 동일 | ORDER BY occurred_at DESC + log_id DESC tie-breaker로 안정 정렬. recentActivityLog query에 명시 |
| 9 | A-2 response의 profile에 avatar 미포함 — 어드민 프런트엔드 사용성 | future PR (PR 11 Avatar 모듈 query port 통합) |

---

## 11. Decisions Taken (브레인스토밍 결과)

1. **Q1 — PR 12b 분할 (B)**: PR 12b1 (read + listener skeleton + polish) → PR 12b2 (write A-3/A-4). PR 12a (B) 분할 패턴 일관.
2. **Q2 — Member-level admin action 기록 (B)**: `user_activity_log`의 `ADMIN_ACTED_ON` 단독 사용. `partyroom_admin_action` 미사용 (테이블 컨셉 mismatch). spec features.md A-3/A-4의 partyroom_admin_action 라인은 §12 catch-up.
3. **Q3 §12.10 polish 4건 (default 수용)**:
   - (1) `UserActivityLogId` 삭제 (b)
   - (2) listener divider comment 포함
   - (3) `crew.user_id NOT NULL` future PR
   - (4) Queue size monitoring future PR
4. **Q3.5 ADMIN_ACTED_ON pattern**: 별도 `AdminActedOnEvent` 미도입. listener가 source event(`MemberTierChangedEvent` / `UserAccountWithdrawnEvent`) 받아 internal logic으로 row 2건 INSERT.
5. **Q3.6 PR 8/9 admin actions backfill 미수행**: PR 8 lifecycle은 partyroom 대상이라 user 식별 불가, PR 9 PERMANENT_EXPULSION은 이미 PR 12a `PENALIZED_IN_PARTYROOM (by=ADMIN)`으로 cover됨. 별도 backfill 불필요.
6. **`UserAccountWithdrawnEvent` evolution**: `byAdministratorId` 필드 추가 (PR 1 forward-evolution). PR 12a `AdminCrewPenalizedEvent` evolution 패턴 동형.
7. **`last_activity_desc` 구현**: user_activity_log MAX(occurred_at) GROUP BY join. denormalized column은 future polish.
8. **A-2 response 축소**: `activities`(DJ_PNT/ROOM_ACT) + `walletAddress` OOS — scoring/wallet 시스템 미정. avatar nesting은 PR 12b2 또는 future.
9. **2 row INSERT의 occurredAt 동일**: source event의 `getOccurredAt()` 재사용. ORDER BY tie-breaker는 log_id DESC. 결정적 INSERT 순서(TIER_CHANGED → ADMIN_ACTED_ON, WITHDREW → ADMIN_ACTED_ON)로 어드민 viewer는 ADMIN_ACTED_ON을 먼저 보게 됨 (audit-first 읽기).
10. **`MemberTierChangedEvent.memberId` 보유 결정**: `userAccountId`는 listener의 audit row subject로 사용. `memberId`는 `getAggregateId()` 반환값으로만 사용 (DDD aggregate 식별). listener metadata에는 `memberId` 미기록 — audit 시 user_account_id가 식별자로 충분, member_id는 derived. PR 12b2 A-3 endpoint도 `memberId` path → 내부에서 `MemberData.getUserAccountId()` 참조하여 publish.
11. **Response field `withdrawn` + `withdrawnAt` 둘 다 노출**: 의미적으로 `withdrawnAt != null` ↔ `withdrawn=true`로 redundant이지만, 프런트엔드 편의 (boolean 분기 + timestamp 포맷팅 양쪽 사용)로 둘 다 응답에 포함. spec features.md A-1 표 그대로.

---

## 12. Open Items / Implementation Reality (post-build catch-up)

PR 12b1 구현 완료 시점에 spec과의 차이/세부 결정사항 기록 (PR 8 §15 / PR 9 §11 / PR 12a §12 패턴):

- (placeholder — 구현 후 작성)

---

**다음 단계:** 본 spec이 reviewer + 사용자 승인되면 `superpowers:writing-plans`로 구현 plan 생성 (`docs/superpowers/plans/2026-04-28-admin-platform-pr12b1.md`).
