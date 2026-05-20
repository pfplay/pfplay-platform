# D/#8 — 어드민 회원 콘솔 GUEST read-only 조회 설계

- 작성일: 2026-05-20
- 대상 이슈: pfplay-platform D/#8 (로드맵 Cluster D, `bugs/2026-05-14-bug-fix-roadmap.md`). 노트: `bugs/2026-05-14-admin-guest-union-view.md`
- 대상 레포: pfplay-platform (administration BC) + pfplay-admin (frontend)
- 분류: Feature gap — 어드민 콘솔에 GUEST 가 안 보임
- 심각도: Medium
- 선결 결정 (사용자, 2026-05-20):
  1. 어드민이 GUEST 에 대해 수행 가능한 액션 범위 = **조회만 (read-only)**
  2. 목록 페이지 = **별도 탭 분리** (`정회원` | `GUEST`)
  3. URL/식별자 = **도메인별 완전 분리** (`/admin/members/{memberId}` 그대로 + 신규 `/admin/guests/{guestId}`)
  4. tier filter UX (자율 판단) = MEMBER 탭 기존 유지(GT 옵션 보존, admin 강등 edge case 노출용), GUEST 탭은 tier filter 자체 제거

## 1. 배경 / 문제

`GuestData`(`guest` 테이블)와 `MemberData`(`member` 테이블)는 의도적으로 분리된 도메인이다. 어드민 회원 콘솔은 `AdminMemberQueryRepositoryImpl.search` / `findDetail` 에서 `from(memberData)` 만 사용 → **GUEST 사용자가 어드민 콘솔에 절대 노출되지 않는다**.

부수: `pfplay-admin/src/entities/member/model/types.ts` 의 `AuthorityTier` 주석은 이미 *"GT = GUEST 별 테이블이 정상 경로 — member 테이블에는 admin 강등 edge case 에만 등장"* 으로 명시되어 있어, **프론트엔드는 이미 GT 표시 준비를 가지고 있다**(`members-filter-form.tsx` 에 GT 옵션 노출). 백엔드 union/분리 endpoint 만 들어오면 자연스럽게 보임.

다만 사용자 결정으로 통합 union 이 아니라 **탭 분리**가 채택됨. 따라서 backend 도 GUEST 전용 endpoint 를 신설하고, MEMBER 코드는 **무수정** 으로 유지하여 회귀 위험을 zero 로 만든다.

## 2. 결정의 외부 컨텍스트 — D/#9 B′ 와의 정합

직전 cycle 에서 **D/#9 = B′** 채택 (pfplay-web PR #321, 2026-05-20):
- 게스트 amplitude 미식별 → 멤버 인증 시만 `setUserId` → device_id 병합으로 콘솔 단일 user = 멤버.
- 옵션 D(IAM 대수술), 옵션 B (canonical pin), `previous_guest_user_account_id` backend FK 모두 폐기.
- **crew/페널티/영구추방 단절 = 제품 결정상 의도된 정상**.

이 결정으로 D/#8 의 본래 노트가 후보로 두었던 "옵션 D: `userAccountId` 를 PK 로 한 from(userAccountData) 후 member/guest left join" 의 *통합 join 유스케이스 (이전 GUEST 활동 → 현 MEMBER 연결)* 가 **product-level 차단**. GUEST → MEMBER promotion trigger 액션 역시 product-level 차단 (D/#9 B′ 의도와 충돌).

결론: **GUEST 와 MEMBER 는 어드민 콘솔에서도 독립 도메인으로 다룬다.** 그 둘의 history continuity 는 제품이 의도적으로 끊었기 때문에, 어드민 view 도 끊는 것이 정합.

## 3. 범위

### 포함
- ✅ GUEST 목록 조회 (페이징/필터/정렬, MEMBER 와 동형 패턴)
- ✅ GUEST 상세 조회 (`UserAccountData` + 최근 활동 로그 30건)
- ✅ 신규 endpoint `/api/v1/admin/guests` (목록), `/api/v1/admin/guests/{guestId}` (상세)
- ✅ 어드민 frontend `/members` 페이지에 탭(`정회원` | `GUEST`) 추가, URL search param `tab` 으로 동기화
- ✅ 신규 frontend slice `entities/guest`, `features/guests`

### 제외 (out-of-scope)
- ❌ GUEST mutation 일체 (withdraw / tier / penalty / promotion)
  - withdraw: guest 정상경로는 ephemeral 이라 비식별화 탈퇴 빈도 낮음
  - tier: guest 는 항상 GT, 변경 무의미
  - penalty: `AdminCrewPenaltyCommandService` 는 crew-scoped (partyroom 내) 라 본 콘솔 책임 영역 아님
  - promotion: D/#9 B′ 와 충돌 (crew/페널티/영구추방 단절 = 제품 의도)
- ❌ MEMBER 코드 변경 일체 (controller/service/repository/dto/UI)
- ❌ `AdminCrewPenaltyCommandService` / `AdminMemberTierCommandService` / `AdminMemberWithdrawCommandService` 변경 일체
- ❌ DB 마이그레이션 (schema 무변경)

## 4. 아키텍처

### 4.1 컴포넌트 신설 (Backend)

```
pfplay-platform/app/src/main/java/com/pfplaybackend/api/administration/
├── adapter/in/web/
│   ├── AdminGuestController.java              [신규]
│   └── dto/
│       ├── AdminGuestListQuery.java           [신규]
│       ├── AdminGuestSummaryResponse.java     [신규]
│       ├── AdminGuestDetailResponse.java      [신규]
│       └── GuestProfileSummary.java           [신규]
├── adapter/out/persistence/
│   ├── AdminGuestQueryRepository.java         [신규 interface]
│   ├── impl/AdminGuestQueryRepositoryImpl.java[신규 QueryDSL]
│   └── dto/
│       ├── AdminGuestSummaryRow.java          [신규]
│       └── AdminGuestDetailRow.java           [신규]
├── application/service/
│   └── AdminGuestQueryService.java            [신규, activity log 합성]
└── domain/exception/
    └── AdminGuestException.java               [신규 — GUEST_NOT_FOUND]
```

기존 `AdminMember*` 파일은 **무수정**. ArchUnit 가드 (administration BC → user BC 단방향 entity 참조, repository impl 내부에서만) 는 기존 룰이 자동 적용.

### 4.2 컴포넌트 신설 (Frontend, pfplay-admin)

pfplay-admin 은 FSD(Feature-Sliced Design) 레이아웃 — `pages/*-page.tsx` 는 5줄짜리 widget wrapper, 실 컨테이너는 `widgets/<feature-list>.tsx` (members-list 패턴 확인). GUEST 도 같은 패턴 적용:

```
pfplay-admin/src/
├── entities/guest/                           [신규 슬라이스]
│   ├── index.ts
│   └── model/types.ts                        (AdminGuestSummary, AdminGuestDetail, GuestProfileSummary)
├── features/guests/                          [신규]
│   ├── api/
│   │   ├── guests-api.ts                     (listGuests, getGuestDetail)
│   │   ├── use-guests-list.ts
│   │   └── use-guest-detail.ts
│   ├── model/
│   │   └── filter-schema.ts                  (GuestsListQuery — tier 필드 부재)
│   └── ui/
│       ├── guests-table.tsx
│       ├── guests-filter-form.tsx            (tier filter 없음)
│       └── guest-detail-cards.tsx
├── widgets/
│   └── guests-list.tsx                       [신규 — MembersListWidget 패턴 동형]
└── pages/                                    (FSD: 평면 구조)
    ├── members-page.tsx                      (수정: Tabs 컨테이너로 — <MembersListWidget/> + <GuestsListWidget/>)
    └── guest-detail-page.tsx                 [신규 — MemberDetailPage 패턴 동형, GuestDetailCards 래핑]
```

`pages/members-page.tsx` 는 `Tabs` 로 두 widget 을 감싸는 형태:

```tsx
// 개략
export function MembersPage() {
  const [tab, setTab] = useTabUrlState() // ?tab=member | guest, default=member
  return (
    <Tabs value={tab} onValueChange={setTab}>
      <TabsList>
        <TabsTrigger value="member">정회원</TabsTrigger>
        <TabsTrigger value="guest">GUEST</TabsTrigger>
      </TabsList>
      <TabsContent value="member"><MembersListWidget /></TabsContent>
      <TabsContent value="guest"><GuestsListWidget /></TabsContent>
    </Tabs>
  )
}
```

탭 컴포넌트는 `tab` URL param 으로 sync (`?tab=member` / `?tab=guest`), refresh 보존. 두 widget 은 각자 `useUrlQueryState` 로 독립적인 query state 보유 — page/size/filter 충돌 없음 (탭별 prefix 또는 분리 schema 로 격리, 구현 시 결정).

### 4.3 Cross-BC 경계

- `AdminGuestQueryRepositoryImpl` 만 `GuestData` / `UserAccountData` 직접 import (기존 `AdminMemberQueryRepositoryImpl` 와 동일 패턴, ArchUnit 통과).
- `AdminGuestQueryService`, controller, DTO 는 administration BC 내부 타입만 사용.
- `UserActivityLogRepository` 재사용 (activity log 는 user_account_id 키 — guest 도 동일하게 사용 가능).

## 5. API 계약

### 5.1 GET `/api/v1/admin/guests`

Query params (전부 optional):
| name | type | 의미 |
|---|---|---|
| `page` | int | 0-based, default 0 |
| `size` | int | default 20, max 100 |
| `email` | string | 부분 일치 (case-insensitive) |
| `joined_from` | date `YYYY-MM-DD` | inclusive |
| `joined_to` | date `YYYY-MM-DD` | inclusive (서버 `lt(joined_to.plusDays(1).atStartOfDay())`) |
| `sort` | enum | `created_at_desc` (default) / `created_at_asc` / `last_activity_desc` |

Response: `ApiCommonResponse<Page<AdminGuestSummaryResponse>>`

### 5.2 GET `/api/v1/admin/guests/{guestId}`

Path: `guestId` Long

Response: `ApiCommonResponse<AdminGuestDetailResponse>`
- 404 `GUEST_NOT_FOUND` 시 standard error envelope

### 5.3 DTO 정의

```java
public record AdminGuestSummaryResponse(
    Long guestId,
    Long userAccountId,
    String email,                  // user_account.email
    ProviderType providerType,
    String nickname,               // guest.profile_data.bio.nickname (nullable — isProfileUpdated=false 시 미존재)
    String agent,                  // guest.agent (User-Agent 기록, nullable)
    boolean isProfileUpdated,
    LocalDateTime lastLoginAt,
    LocalDateTime createdAt,
    boolean withdrawn,             // user_account.withdrawn_at != null
    LocalDateTime withdrawnAt
) {}

public record AdminGuestDetailResponse(
    Long guestId,
    UserAccountSummary userAccount,    // 기존 DTO 재사용
    GuestProfileSummary profile,       // 신규 — nickname + introduction
    String agent,
    boolean isProfileUpdated,
    LocalDateTime createdAt,
    boolean withdrawn,
    LocalDateTime withdrawnAt,
    List<RecentActivityLogItem> recentActivityLog   // 기존 DTO 재사용
) {}
```

`UserAccountSummary` / `RecentActivityLogItem` 은 기존 `administration.adapter.in.web.dto` 패키지에서 재사용 — DTO 분리 정합 + 응답 shape 일관성.

`GuestProfileSummary` (신규): `MemberProfileSummary` 와 동일 shape (nickname, introduction) — 별도 타입으로 둠으로써 향후 guest-specific 필드 추가 시 영향 격리.

### 5.4 Frontend type

```typescript
// pfplay-admin/src/entities/guest/model/types.ts
export interface AdminGuestSummary {
  guestId: number
  userAccountId: number
  email: string
  providerType: ProviderType  // entities/member 재사용 import
  nickname: string | null
  agent: string | null
  isProfileUpdated: boolean
  lastLoginAt: string | null
  createdAt: string
  withdrawn: boolean
  withdrawnAt: string | null
}

export interface AdminGuestDetail {
  guestId: number
  userAccount: UserAccountSummary  // entities/member 재사용
  profile: GuestProfileSummary
  agent: string | null
  isProfileUpdated: boolean
  createdAt: string
  withdrawn: boolean
  withdrawnAt: string | null
  recentActivityLog: RecentActivityLogItem[]  // entities/member 재사용
}

export interface GuestProfileSummary {
  nickname: string | null
  introduction: string | null
}
```

## 6. QueryDSL 구현

### 6.1 `AdminGuestQueryRepositoryImpl.search`

`AdminMemberQueryRepositoryImpl.search` 와 거의 동형 (member → guest 치환). nickname `cast(... as string)` 패턴, last_activity_desc 시 left join `userActivityLogData` + GROUP BY + COALESCE fallback 패턴 그대로.

차이점:
- `from(guestData)` + `leftJoin(userAccountData).on(userAccountData.userId.uid.eq(guestData.userAccountId))`
- `tier` 필터 없음 (guest 는 항상 GT)
- 추가 컬럼: `guestData.agent`, `guestData.isProfileUpdated`

### 6.2 `AdminGuestQueryRepositoryImpl.findDetail`

`findDetail(Long guestId)`: guest + userAccount left join 단일 row.

### 6.3 정렬 enum

`AdminGuestListQuery.SORT_*` 는 `AdminMemberListQuery` 의 상수와 같은 string 값을 재사용 (UI 정렬 옵션을 두 탭에서 통일 — `created_at_desc` / `created_at_asc` / `last_activity_desc`).

## 7. Service layer

`AdminGuestQueryService` 는 `AdminMemberQueryService` 와 거의 동형 (member → guest 치환). 핵심:

```java
public AdminGuestDetailResponse getDetail(Long guestId) {
    AdminGuestDetailRow row = guestRepository.findDetail(guestId)
        .orElseThrow(() -> ExceptionCreator.create(AdminGuestException.GUEST_NOT_FOUND));

    List<UserActivityLogData> logs = row.userAccountId() == null
        ? List.of()
        : userActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(row.userAccountId());

    // ... DTO 조립 (Member 와 동일 패턴)
}
```

`@Transactional(readOnly = true)` 클래스 레벨, RECENT_ACTIVITY_LIMIT=30 상수 재사용 가능 시 import (별도 상수 정의 필요 시 동일 값으로 둠).

## 8. Frontend 상세

### 8.1 탭 컨테이너 (`members-page.tsx` 수정)

- URL search param `tab` 으로 active tab 결정 (default `member`)
- Tabs 컴포넌트 (`@/components/ui/tabs`) 기반
- 두 탭 내부는 각자 독립적인 React Query state 보유 (`useMembersList`, `useGuestsList`)
- 탭 전환 시 URL 만 변경 — 상태 보존을 위해 React Query cache 유지

### 8.2 `GuestsTable` 컬럼

| 컬럼 | 비고 |
|---|---|
| ID (`guestId`) | clickable → `/guests/{guestId}` |
| 이메일 | user_account.email |
| 가입 경로 | providerType |
| 닉네임 | nullable (isProfileUpdated=false 시 `-`) |
| agent | nullable, `-` fallback |
| 마지막 로그인 | KST format |
| 가입일 | KST format |
| 상태 | 활동 중 / 탈퇴됨 (기존 패턴) |

권한(tier) 컬럼은 제거 (항상 GT).

### 8.3 `GuestsFilterForm`

기존 `MembersFilterForm` 에서 tier 필터만 제거한 형태. 이메일 debounce(300ms), 가입일 from/to, 정렬.

### 8.4 `GuestDetailCards`

기존 `MemberDetailCards` 와 동일 골격에서:
- UserAccount 카드 (재사용 가능)
- Guest 카드: agent, isProfileUpdated 표시 (Member 카드의 wallet/tier 영역 대체)
- 최근 활동 로그 (재사용)
- **mutation 작업 dropdown 미렌더** — read-only

### 8.5 라우팅

```
/members                  → 탭 컨테이너 (default tab=member)
/members?tab=guest        → GUEST 탭 active
/members/{memberId}       → MemberDetailPage (기존, 무변경)
/guests/{guestId}         → GuestDetailPage (신규)
```

## 9. Error handling

### 9.1 Backend exception

```java
public enum AdminGuestException implements ErrorCode {
    GUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "AG001", "게스트를 찾을 수 없습니다.");
    // ... 표준 패턴
}
```

기존 `GlobalExceptionHandler` 가 자동 처리, 별도 매핑 불필요.

### 9.2 Frontend

기존 `http` 클라이언트의 401/403/404 분기 처리 그대로. `use-guest-detail` 의 404 시 빈 상태 노출 (members 패턴 동일).

## 10. Testing 전략

### 10.1 Backend

| 테스트 | 의도 |
|---|---|
| `AdminGuestQueryRepositoryImplIT` | fixture (guest + userAccount + activityLog 3종) 로 QueryDSL 검증 — 페이징/정렬 3종/필터(email/joined_from/to)/last_activity_desc COALESCE fallback |
| `AdminGuestQueryServiceIT` | service → repository → DTO 조립 일관성, 활동 로그 30건 cap, withdrawn flag derive |
| `AdminGuestControllerIT` | endpoint level — 200/404, 페이징 응답 envelope, 인증/인가 (401/403) |
| `AdminMemberQueryRepositoryImplIT` (기존) | **무수정** 으로 동일 GREEN — 회귀 zero 검증 |

### 10.2 Frontend

| 테스트 | 의도 |
|---|---|
| `guests-api.test.ts` | listGuests / getGuestDetail 의 querystring 직렬화 + ApiCommonResponse unwrap |
| `use-guests-list.test.tsx` / `use-guest-detail.test.tsx` | React Query hook 동작, error/loading state |
| `guests-table.test.tsx` | 컬럼 렌더, empty state, clickable row 라우팅 |
| `guests-filter-form.test.tsx` | 이메일 debounce, 가입일/정렬 onChange, **tier filter 부재 검증** |
| `guest-detail-cards.test.tsx` | 카드 렌더, **mutation dropdown 부재 검증** |
| `guest-detail-page.test.tsx` (신규) | `pages/__tests__/` 위치 — `member-detail-page.test.tsx` 대칭. 라우트 param → useGuestDetail → GuestDetailCards 렌더 통합 |
| 탭 컨테이너 테스트 (`pages/__tests__/members-page.test.tsx` 신규/확장) | URL `?tab=*` ↔ active tab sync, 탭 전환 시 두 탭 동시 마운트되지 않음, **MEMBER 탭 기능 동일성 검증 (회귀 가드)** |

### 10.3 회귀 잠금

- 기존 Member API/UI 테스트 **zero change** 의무. 수정 시 PR 리뷰서 reject.
- ArchUnit 자동 검증 — 별도 룰 추가 없음.

## 11. 보안 / 인가

기존 admin endpoint 와 동일한 SecurityFilterChain 적용:
- `@PreAuthorize("hasRole('ADMINISTRATOR')")` 또는 기존 admin 패턴 자동 적용
- Origin allowlist, CSRF double-submit, AdminAccessToken cookie 등 기존 정책 그대로

별도 권한 등급 분리 없음 — GUEST 조회는 일반 admin 권한으로 충분 (개인정보 노출량은 Member 조회와 동등).

## 12. 관측 가능성

기존 admin endpoint 의 INFO 로그 패턴 (`[AdminGuestQuery.getList] query=...`, `[AdminGuestQuery.getDetail] guestId=...`) 따른다. `RequestIdInterceptor` (A6) 가 자동으로 `requestId=` 첨부. Phase A 정책 정합.

## 13. 마이그레이션 / 배포

- DB 마이그레이션 zero (schema 무변경)
- env 변경 zero
- 배포 순서 강제: **backend PR 머지 → stg 자동배포 → 안정화 확인 → admin PR 오픈/머지**. 역순 시 frontend 가 신규 endpoint 호출에 stg 백엔드가 404 응답 → 콘솔 표시 깨짐. pfplay 표준 ([[feedback_pr_series_workflow]] 의 cross-repo 순서 정합).
- contract sync gate: admin PR 의 신규 type (`AdminGuestSummary` 등) 은 backend PR 의 DTO record 와 1:1 대응. backend PR 머지 후 actual response shape 확인 → admin type 작성 권장 (drift 방지).

## 14. 작업 분량 추정

- Backend: 신규 파일 10개 (controller/service/repository/impl/exception/dto 6개), 약 600~700 LOC + 테스트 ~500 LOC = 1.5~2일
- Frontend: 신규 slice 2개(entities/guest, features/guests), 탭 컨테이너 분리, ~700 LOC + 테스트 ~600 LOC = 2~2.5일
- 통합 검증/PR 리뷰/문서: 0.5일

총 **약 4~5일** (single dev). 결정 게이트 소진 + product blocker 0 라 자율 진행 가능.

## 15. 후속/별건 (out-of-scope, 향후 backlog)

- D/#8 follow-up: GUEST 활동 통계 대시보드 (일 가입수, profile 미완료 비율 등) — 결정 게이트 필요 시 별도 spec
- GUEST 활동 로그 cleanup 정책 ADR — guest 가 ephemeral 라면 활동 로그도 retention 적용해야 할지 (별도 의사결정)

## 16. 관련 메모리

- [[project_admin_platform_prod_ship_pending]] — admin 콘솔 prod 진입 컨텍스트
- [[project_pr14g_completed]] — 개발 단계 종료 후 발견된 gap
- [[reference_admin_seed_lifecycle]] — super-admin 라이프사이클 (본 spec 무관, cross-check 만)
- [[feedback_pr_series_workflow]] — chunk + atomic group 패턴 (구현 단계 적용)
- [[feedback_autonomous_execution]] — 결정 게이트 외 자율 진행 (본 spec 진입 근거)
- [[project_amplitude_followup_shipped]] / D/#9 B′ — GUEST → MEMBER 단절 = 제품 의도
