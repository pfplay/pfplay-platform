# VOC — 버그 리포팅 창구 (BugReport) 설계

- 작성일: 2026-05-21
- 분류: 신규 기능 (Voice of Customer 1차 도입). 3 레포 vertical slice.
- 대상 레포: pfplay-platform (backend, V19 + 2 endpoint) · pfplay-admin (read-only 목록·상세) · pfplay-web (Header 버튼 + Dialog)
- 심각도: Feature (priority: 사용자 피드백 수집 창구가 현재 부재)

## 1. 배경 / 문제

사용자가 겪은 버그를 직접 제보할 수 있는 in-app 창구가 부재. 현재는 외부 채널(메일/메신저 등)에 의존하거나 신고 자체를 안 함 → 운영자 사각지대. 첫 prod 진입(2026-05-09) 후 누적되는 cross-module 버그 디버깅에 사용자 시점 신호가 부족.

본 spec 은 **1차 도입(Voice of Customer 최소 기능)**을 정의한다:
- 사용자: in-app 버튼 → 자유텍스트 모달 → 제출.
- 어드민: 콘솔에서 제출 목록·상세 read-only 조회.
- 답변·사용자 본인 이력 조회·첨부 이미지·이메일 알림 = **out-of-scope** (별건 후속).

## 2. 결정 (사용자 확정, 2026-05-21)

1. **버튼 위치 = Header 우측 🐛 아이콘** (LanguageChangeMenu 옆). 노출: 로비+룸 공통, sign-in/maintenance/환영화면 제외. 분위기 = 대수론적·조용함.
2. **권한 = 인증 사용자 (멤버 + 게스트 GT 모두)**. anonymous 비허용 — Header 노출 정합 + spam 차단.
3. **데이터 = 자유텍스트 + 자동메타** (page_url / user_agent / user_id / partyroomId). 사용자 입력 = content TEXT 하나, 나머지는 백엔드/클라이언트가 자동 수집.
4. **Rate limit = 유저당 1분 내 연속 제출 1건** (bucket4j 기반, AdminLoginRateLimit 정합). 초과 시 BUG-001 RATE_LIMIT_EXCEEDED (429).
5. **모듈 배치 = `administration` 모듈 내부 bug_report 패키지** (V13 partyroom_report 와 동위 패턴 정합). 신규 모듈 분리 회피 ([[feedback_elegant_no_code_dirtying]]).
6. **API namespace = `/api/v1/voc/bug-reports`** (submit) + `/api/v1/admin/voc/bug-reports[+/{id}]` (admin query). `voc/` namespace 가 향후 다른 VOC 종류 확장 친화적.
7. **어드민 메뉴 라벨 = "사용자 피드백"** (한글). 라우트 `/admin/voc/bug-reports`.
8. **UI = 기존 패밀리룩 정합**: pfplay-web 의 `useDialog().openDialog(...)` API + `Textarea` + `Button` (기존 컴포넌트 재사용), pfplay-admin 의 D/#8 GUEST 페이지 패턴 (FSD 슬라이스).

## 3. 설계

### 3-1. 데이터 모델 (Flyway V19)

`bug_report` 테이블 — V13 `partyroom_report` 정합 (status enum / FK / resolution_note 생략 — 1차 도입, 답변 워크플로 out-of-scope):

```sql
-- V19__create_bug_report.sql
CREATE TABLE bug_report (
    bug_report_id              BIGINT       NOT NULL AUTO_INCREMENT,
    reporter_user_account_id   BIGINT       NOT NULL,
    content                    TEXT         NOT NULL,
    page_url                   VARCHAR(500) NULL,
    user_agent                 VARCHAR(500) NULL,
    partyroom_id               BIGINT       NULL,
    created_at                 DATETIME     NOT NULL,
    PRIMARY KEY (bug_report_id),
    INDEX idx_br_created (created_at DESC),
    INDEX idx_br_reporter (reporter_user_account_id, created_at DESC),
    INDEX idx_br_partyroom (partyroom_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- **FK 명시 안 함**: V13 정합 — V18 까지 user_account / partyroom 관계는 application layer 보장.
- **partyroom_id nullable**: 룸 안에서만 채움, 로비/홈/링크 화면은 null.
- **content TEXT**: 길이 검증은 application (`@NotBlank @Size(min=5, max=2000)`). DB 측 length 제한 없음 (V13 description 동일).

### 3-2. 도메인 / Entity

**`BugReportData`** (`administration/domain/entity/data/BugReportData.java`) — V13 `PartyroomReportData` 미러:

```java
@Getter
@DynamicInsert
@Table(name = "BUG_REPORT", indexes = {...})
@Entity
public class BugReportData {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bug_report_id")
    private Long id;

    @Column(name = "reporter_user_account_id", nullable = false)
    private Long reporterUserAccountId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "page_url", length = 500)
    private String pageUrl;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "partyroom_id")
    private Long partyroomId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected BugReportData() {}

    public static BugReportData create(Long reporterUserAccountId, String content,
                                       String pageUrl, String userAgent, Long partyroomId,
                                       LocalDateTime now) {
        BugReportData data = new BugReportData();
        data.reporterUserAccountId = reporterUserAccountId;
        data.content = content;
        data.pageUrl = pageUrl;
        data.userAgent = userAgent;
        data.partyroomId = partyroomId;
        data.createdAt = now;
        return data;
    }
}
```

- mutator 없음 (제출 후 immutable, 답변 워크플로 부재).
- `BaseEntity` 상속 안 함 — `updated_at` 불요. V13 PartyroomReportData 도 동일.

**`BugReportException`** (`administration/domain/exception/BugReportException.java`)

```java
public enum BugReportException implements DomainException {
    RATE_LIMIT_EXCEEDED("BUG-001", "잠시 후 다시 시도해주세요", ErrorType.TOO_MANY_REQUESTS);
    ...
}
```

> `ErrorType.TOO_MANY_REQUESTS` 가 GlobalExceptionHandler 에 매핑되어 있는지 확인 필요. 부재 시 추가 (spec §6 cross-cutting note).

### 3-3. Submit API (pfplay-platform)

```
POST /api/v1/voc/bug-reports
Auth: cookieAuth, @PreAuthorize("isAuthenticated()")
Body: { "content": "<5..2000 자>", "partyroomId": <Long nullable> }
Server-extracted: pageUrl ← Referer header, userAgent ← User-Agent header
Success: 201 + { "bugReportId": <Long> }
```

**Error matrix**:

| HTTP | code | 사유 |
|---|---|---|
| 400 | INVALID_REQUEST | content blank / size < 5 / size > 2000 / partyroomId non-positive |
| 401 | (AuthEntryPoint) | 인증 결여 |
| 429 | BUG-001 RATE_LIMIT_EXCEEDED | 유저당 60s 내 재제출 |

**DTO**:
```java
// administration/adapter/in/web/dto/SubmitBugReportRequest.java
@Getter
public class SubmitBugReportRequest {
    @NotBlank @Size(min = 5, max = 2000) private String content;
    @Positive private Long partyroomId;   // nullable, but if present must be positive
}

// administration/adapter/in/web/dto/SubmitBugReportResponse.java
public record SubmitBugReportResponse(Long bugReportId) {}
```

**Controller**:
```java
// administration/adapter/in/web/BugReportCommandController.java
@RequestMapping("/api/v1/voc/bug-reports")
@PostMapping
@PreAuthorize("isAuthenticated()")
public ResponseEntity<ApiCommonResponse<SubmitBugReportResponse>> submit(
        @Valid @RequestBody SubmitBugReportRequest request,
        @RequestHeader(value = "Referer", required = false) String referer,
        @RequestHeader(value = "User-Agent", required = false) String userAgent) {
    Long id = bugReportCommandService.submit(
            request.getContent(),
            referer,    // server truncates to 500
            userAgent,  // server truncates to 500
            request.getPartyroomId());
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiCommonResponse.success(new SubmitBugReportResponse(id)));
}
```

### 3-4. Submit Command Service

```java
@Service
@RequiredArgsConstructor
public class BugReportCommandService {
    private final BugReportRepository repository;
    private final BugReportRateLimitGuard rateLimit;
    private final Clock clock;  // 기존 ClockConfig.kst 주입

    @Transactional
    public Long submit(String content, String pageUrl, String userAgent, Long partyroomId) {
        Long userId = ThreadLocalContext.getAuthContext().getUserId().getUid();
        rateLimit.acquireOrThrow(userId);   // BUG-001 if blocked
        BugReportData data = BugReportData.create(
                userId, content,
                truncate(pageUrl, 500),
                truncate(userAgent, 500),
                partyroomId,
                LocalDateTime.now(clock));
        BugReportData saved = repository.save(data);
        log.info("[bugReport.submit] OK requestId={} reporterUserId={} partyroomId={} bugReportId={}",
                RequestIdInterceptor.current(), userId, partyroomId, saved.getId());
        return saved.getId();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
```

**`BugReportRateLimitGuard`** (`administration/application/util/BugReportRateLimitGuard.java`):
- bucket4j `Bucket` per `userAccountId`, `Caffeine` cache (TTL 5분, 동시 활성 사용자 100k 추정 = 메모리 미미)
- bandwidth: `Bandwidth.classic(1, Refill.intervally(1, Duration.ofMinutes(1)))` — 1분당 1 토큰, capacity 1.
- API: `acquireOrThrow(Long userId)` — block 시 `throw ExceptionCreator.create(BugReportException.RATE_LIMIT_EXCEEDED)`.
- 기존 `AdminLoginRateLimitGuard` 가 있다면 그 패턴 미러 (없으면 본 spec 이 첫 도입).

### 3-5. Admin Query API (D/#8 패턴 정합)

```
GET /api/v1/admin/voc/bug-reports?page=0&size=20&sortBy=createdAt&direction=DESC
GET /api/v1/admin/voc/bug-reports/{bugReportId}
Auth: AdminAccessToken (D/#8 AbstractAdminWebMvcTest 정합)
```

**Response 목록** (`AdminBugReportListResponse`):
```json
{
  "totalElements": 42,
  "totalPages": 3,
  "page": 0,
  "size": 20,
  "items": [
    {
      "bugReportId": 1,
      "reporterUserAccountId": 100,
      "reporterEmail": "user@example.com",
      "reporterNickname": "닉네임",
      "contentPreview": "재생 중 일시정지 안 됨...",  // 앞 80자
      "partyroomId": 7,
      "createdAt": "2026-05-21T10:00:00"
    }
  ]
}
```

**Response 상세** (`AdminBugReportDetailResponse`):
```json
{
  "bugReportId": 1,
  "reporterUserAccountId": 100,
  "reporterEmail": "user@example.com",
  "reporterNickname": "닉네임",
  "content": "전체 본문...",
  "pageUrl": "https://pfplay.xyz/parties/7",
  "userAgent": "Mozilla/5.0 ...",
  "partyroomId": 7,
  "partyroomName": "테스트 룸",
  "createdAt": "2026-05-21T10:00:00"
}
```

**Repository / Service**:
- `AdminBugReportQueryRepository` (interface, `findRows`/`findDetail`/`count`)
- `AdminBugReportQueryRepositoryImpl` (QueryDSL JPQL, D/#8 `AdminGuestQueryRepositoryImpl` 미러)
  - JOIN `bug_report br` × `user_account ua` (reporter email/nickname) × `partyroom p` LEFT JOIN (partyroom name)
- `AdminBugReportQueryService` (paging/sort 유효성 + DTO 매핑)
- `AdminBugReportQueryController` (`@PreAuthorize("hasRole('ADMIN')")`, D/#8 AbstractAdminWebMvcTest 패턴)

**Error matrix** (admin):

| HTTP | code | 사유 |
|---|---|---|
| 400 | BUG-002 INVALID_LIST_QUERY | sortBy/direction/page/size invalid |
| 401 | (AuthEntryPoint) | admin 미인증 |
| 403 | (AdminOriginGuard) | admin origin 아님 |
| 404 | BUG-003 BUG_REPORT_NOT_FOUND | `{bugReportId}` 미존재 |

### 3-6. pfplay-web UI

**위치**: `src/widgets/layouts/ui/header.component.tsx` line 53 `<div className='items-center gap-6 flexRow'>` 안 `<LanguageChangeMenu />` 직전 또는 직후 — 메뉴 항목 1개 추가.

**컴포넌트 트리** (FSD):
```
src/features/bug-report/
├── api/
│   ├── submit-bug-report.ts          // POST /api/v1/voc/bug-reports
│   └── submit-bug-report.test.ts     // MSW
├── model/
│   ├── bug-report-schema.ts          // zod: content 5..2000
│   └── use-submit-bug-report.hook.ts // react-query mutation
└── ui/
    ├── bug-report-button.component.tsx       // Header 진입 아이콘
    ├── bug-report-button.component.test.tsx
    ├── bug-report-dialog.component.tsx       // 모달 본체
    ├── bug-report-dialog.component.test.tsx
    └── index.ts
```

**`BugReportButton`** (Header):
```tsx
<button
  onClick={openBugReportDialog}
  aria-label={t.bug_report.btn.open}
  className='text-gray-400 hover:text-gray-200 transition-colors'
  data-testid='bug-report-button'
>
  <PFBug width={24} height={24} />
</button>
```
- 아이콘: `@/shared/ui/icons` 에 `PFBug` 추가 (24px SVG, 기존 PF* 아이콘 패밀리룩 정합 — gray-400 default, hover gray-200, transition-colors). 후보 = lucide `Bug` 스타일 미러.
- Tooltip 은 (현 Header 의 다른 항목들에 tooltip 없으니 생략, aria-label 로 충분).

**`BugReportDialog`** (모달):
```tsx
openDialog((_, onCancel) => ({
  title: ({ defaultClassName }) => (
    <Typography type='title2' className={defaultClassName}>
      {t.bug_report.title.report_bug}
    </Typography>
  ),
  titleAlign: 'left',
  showCloseIcon: true,
  classNames: { container: 'w-[480px] py-7 px-8 bg-black' },
  Body: <BugReportForm onSubmitted={onCancel} />,
}));
```
- Profile 모달(`sidebar.component.tsx` line 46-65) 의 옷자락 패턴 미러.
- 너비 480px (Profile 모달 620 보다 컴팩트 — 텍스트 입력 단일).

**`BugReportForm`** 내부:
- `Textarea` (기존 `@/shared/ui/components/textarea`) — rows=6, placeholder "어떤 버그를 경험하셨나요? (5~2000자)"
- 문자수 카운터 (현재/최대, 한도 근접 시 색상 변경)
- 안내 텍스트 small: "운영팀이 확인 후 조치합니다. 답변이 어려울 수 있어요."
- 버튼 행: 취소(`TextButton`) / 제출(`Button color='primary'`, disabled until valid)
- `react-hook-form` + zod (`bug-report-schema` content 5..2000)
- mutation 성공: 토스트("피드백이 등록되었습니다") + 다이얼로그 닫기
- 429 에러: 토스트("잠시 후 다시 시도해주세요") + 폼 유지
- 그 외 에러: 토스트("제출에 실패했어요") + 폼 유지

**partyroomId 자동 첨부**:
- 클라이언트 `useSubmitBugReport` mutation 이 호출 시점에 현재 URL 에서 partyroomId 추출
- `usePathname()` (next/navigation) → `/parties/{id}` 매칭 → number. 매칭 없으면 undefined.
- 룸 store 사용 안 함 — pathname 이 진실원천 ([[single-partyroom-subscription-invariant]] 정합).

**i18n keys** (`shared/lib/localization/dictionaries/{ko,en}.json`):
```json
{
  "bug_report": {
    "btn": { "open": "버그 제보", "submit": "제출", "cancel": "취소" },
    "title": { "report_bug": "버그 제보" },
    "placeholder": "어떤 버그를 경험하셨나요? (5~2000자)",
    "help": "운영팀이 확인 후 조치합니다. 답변이 어려울 수 있어요.",
    "toast": {
      "success": "피드백이 등록되었습니다",
      "rate_limit": "잠시 후 다시 시도해주세요",
      "error": "제출에 실패했어요"
    }
  }
}
```
en.json 동일 키 영문 번역. [[feedback_pfplay_web_i18n_drift]] 정합 — ko/en json 직접 수정, `yarn i18n` 호출 금지.

### 3-7. pfplay-admin UI (D/#8 패턴 정합)

**사이드바 메뉴 추가**:
- 라벨 "사용자 피드백" (한글)
- 아이콘: lucide `MessageSquareWarning` 또는 `Inbox` (기존 admin 사이드바 아이콘 패밀리 따름)
- 위치: "회원" / "GUEST" / "파티룸" 다음 (D/#8 GUEST 가 별도 탭 분리한 정신 정합)

**라우트**:
- `/admin/voc/bug-reports` — 목록 페이지
- `/admin/voc/bug-reports/:bugReportId` — 상세 페이지

**FSD 슬라이스** (D/#8 정합):
```
src/entities/bug-report/
├── api/
├── model/    // types: AdminBugReportSummary, AdminBugReportDetail
└── index.ts
src/features/bug-reports/
├── api/      // listBugReports, getBugReportDetail
├── model/    // filter schema (period from/to), hooks (useBugReportsList, useBugReportDetail)
└── ui/       // BugReportsFilterForm, BugReportsTable
src/widgets/
├── bug-reports-list.tsx
└── bug-reports-detail.tsx
src/pages/
├── bug-reports-page.tsx              // /admin/voc/bug-reports
└── bug-report-detail-page.tsx        // /admin/voc/bug-reports/:bugReportId
```

**목록 페이지**:
- Filter row: 기간 from/to (Datepicker) + content keyword (Input, 선택)
- Table cols: 작성자 (이메일·닉네임) / 본문 미리보기(80자) / 파티룸 ID / 작성일
- Click row → 상세 페이지 (`/admin/voc/bug-reports/{id}`)
- 페이지네이션 (members-page 정합 — useUrlQueryState)

**상세 페이지**:
- Card: 작성자 (이메일, 닉네임, → 회원·게스트 상세 링크 — D/#8 정합)
- Card: 본문 (전체, white-space: pre-wrap)
- Card: 컨텍스트 (page_url 클릭 가능 외부 링크, user_agent, partyroom_id → 파티룸 상세 링크)
- Card: 메타 (작성일)
- 액션 없음 (read-only)

**App.tsx** 라우트 등록 1줄 추가:
```tsx
<Route path='/voc/bug-reports' element={<BugReportsPage />} />
<Route path='/voc/bug-reports/:bugReportId' element={<BugReportDetailPage />} />
```

### 3-8. 알림 / 조회 동선

- 1차 도입은 **polling 없음, push 없음**. 어드민이 사이드바 메뉴 → 목록 페이지 열어서 확인.
- 향후 후속: 어드민 헤더에 "신규 N건" 뱃지 (Cloud Logging 알림 또는 polling).

## 4. 테스트 전략

### 4-1. pfplay-platform (backend)

- **Domain unit**:
  - `BugReportDataTest` — create / immutable 필드 ~2 case
- **Specification / Guard unit**:
  - `BugReportRateLimitGuardTest` — 1분 1토큰 / 초과 throw ~3 case
- **Service unit**:
  - `BugReportCommandServiceTest` (`@ExtendWith(MockitoExtension.class)`, `ThreadLocalContext` mock):
    - happy: content/pageUrl/UA/partyroomId 모두 채움 → repository.save 1회
    - happy: pageUrl/UA null → null 저장
    - happy: pageUrl 600자 → truncate to 500
    - rate-limit throw (RateLimitGuard mock)
  - `AdminBugReportQueryServiceTest` — list paging / detail / NOT_FOUND ~4 case
- **Repository IT** (Testcontainers MySQL, V19 적용):
  - `AdminBugReportQueryRepositoryImplIT` — seed 5건 + filter/sort/page/detail ~5 case
- **Controller WebMvc**:
  - `BugReportCommandControllerTest` (AbstractPartyCommandWebMvcTest 류 신규 또는 AbstractAdminWebMvcTest 모방) — 201/400/401/429 ~5 case
  - `AdminBugReportQueryControllerTest` (AbstractAdminWebMvcTest 정합) — 200 list/detail / 400 / 401 / 403 / 404 ~6 case

### 4-2. pfplay-web (frontend)

- `BugReportButton` — render / aria-label / click → openDialog 호출 (`useDialog` mock) ~3 case
- `BugReportDialog` (`BugReportForm`):
  - 초기 disabled / 5자 미만 disabled / 2000자 초과 에러 / 정상 입력 enable / 제출 mutation 호출 / 성공 토스트 / 429 토스트 / 그 외 에러 토스트 ~8 case (MSW)
- `submit-bug-report` API integration (MSW) — payload / page_url 자동 / partyroomId 추출 ~3 case
- i18n: ko/en 키 정합 1 case
- (vitest, 기존 컨벤션)

### 4-3. pfplay-admin (frontend, D/#8 정합)

- `entities/bug-report` types ~2 case (타입 컴파일·shape)
- `features/bug-reports/api/list` MSW ~3 case
- `features/bug-reports/api/detail` MSW ~3 case
- `features/bug-reports/model/use-bug-reports-list` hook ~2
- `features/bug-reports/model/use-bug-report-detail` hook ~2
- `widgets/bug-reports-list` 렌더링·페이지네이션·필터 ~4
- `widgets/bug-reports-detail` 렌더링 ~3
- `pages/bug-reports-page` MSW integration ~3
- `pages/bug-report-detail-page` MSW integration ~3
- 사이드바 메뉴 항목 회귀 1

## 5. 영향 / Out-of-scope

### 영향 (in-scope)
- **pfplay-platform**:
  - 신규 Flyway V19 + `BugReportData` entity + `BugReportRepository(+Impl)` + `BugReportException` + `BugReportRateLimitGuard` + `BugReportCommandService` + `BugReportCommandController` + `SubmitBugReportRequest`·`SubmitBugReportResponse`
  - 신규 `AdminBugReportQueryRepository(+Impl)` + `AdminBugReportQueryService` + `AdminBugReportQueryController` + 4 DTO (Summary/Detail/Row/ListQuery)
  - `ErrorType.TOO_MANY_REQUESTS` 가 GlobalExceptionHandler 에 매핑되어 있는지 확인 (없으면 추가, cross-cutting note)
  - `AbstractPartyCommandWebMvcTest` 또는 동등 base test 에 `BugReportCommandController` MockBean 추가 (관행 — 또는 admin 전용 path 라 admin base 만)
- **pfplay-admin**:
  - 사이드바 메뉴 1줄 + 라우트 2개 + FSD 슬라이스 신규 (entities/bug-report, features/bug-reports, widgets/bug-reports-{list,detail}, pages/bug-report-detail-page)
- **pfplay-web**:
  - Header 1줄 + `features/bug-report/*` 신규 + i18n ko/en 키 + `PFBug` 아이콘 추가

### Out-of-scope (별건 후속)
- 사용자 본인 이력 조회 (UI · API)
- 어드민 답변·status 워크플로 (V13 partyroom_report 풀 패턴: status enum / reviewed_by_administrator / resolution_note / resolved_at)
- 첨부 이미지·스크린샷 업로드 (S3 storage 결정 필요)
- 이메일 알림 (어드민에게 새 VOC 도착) — Cloud Logging 알림 또는 polling
- "신규 N건" 뱃지 (admin Header)
- anonymous 사용자 허용 — 현재 권한 정책으로 sign-in 없으면 Header 노출 자체가 없음(login 필요)
- spam / abuse 패턴 발견 시 IP 기반 rate-limit 추가

## 6. 잠재 위험 / 검증 포인트

- **`ErrorType.TOO_MANY_REQUESTS` 매핑 존재 여부**: GlobalExceptionHandler 가 BUG-001 을 429 로 변환할 수 있어야. 부재 시 plan task 1 에서 동반 추가 — D/#8 의 `AdminGuestException` 도입 시 보강한 정합.
- **`BugReportRateLimitGuard` 가 기존 bucket4j 패턴과 충돌 안 함**: `AdminLoginRateLimitGuard` 가 이미 있다면 같은 Caffeine cache 인스턴스를 공유 vs 분리 — 분리 권장 (cache key namespace 충돌 회피).
- **Referer header 신뢰성**: 브라우저가 `Referrer-Policy` 에 따라 안 보낼 수 있음 (특히 cross-origin). null 허용 + 클라이언트가 body 에 `pageUrl` 명시도 후속 검토 (1차 도입 = Referer null 허용).
- **content XSS**: admin 콘솔에서 React 가 자동 escape (별도 sanitize 없음). content 안에 마크다운/HTML 무가공 출력 안 함.
- **partyroomId 위조**: 클라이언트가 임의 partyroomId 보낼 수 있음. 1차 도입은 검증 안 함(어드민이 진단 시 reporter user vs partyroom 정합성 확인). 후속 = 사용자 crew 멤버십 검증 추가.
- **content 길이 2000자 제한**: TEXT 컬럼은 64KB 까지 가능하나 application 측에서 2000 자 cap. 향후 확대 시 인덱스 영향 0.
- **pageUrl/UA 500자 cap**: 매우 긴 URL/UA 대비 server-side truncate. `VARCHAR(500)` 컬럼 정합.
- **Index 효율**: `idx_br_created` 가 어드민 목록 default sort (createdAt DESC) 정합. `idx_br_reporter` 는 후속 본인 이력 조회 대비 인덱스 미리 둠 (YAGNI vs 마이그레이션 비용 trade-off — 인덱스 1개 미미).
- **타임존**: `Clock` 주입(`ClockConfig.kst()`) 으로 `LocalDateTime.now(clock)` — [[project_jvm_tz_kst_policy]] 정합.

## 7. 머지·배포 순서 (3 레포 cross-cutting)

[[reference_branch_env_mapping]] develop=dev / release=stg / main=prod.

1. **pfplay-platform** PR → develop merge → V19 마이그레이션 적용 + 2 endpoint 활성
2. **dev 스모크**: POST 201, GET 200(목록), GET 200(상세), 429 (1분 내 재제출)
3. **pfplay-admin** PR → develop merge → 사이드바 메뉴 + 페이지 활성
4. **pfplay-web** PR → develop merge → Header 버튼 + Dialog 활성
5. dev 통합 스모크 (web 제출 → admin 조회 e2e 흐름)
6. release(stg) 격상 — 별건 release PR (사용자 영역)
7. prod(main) 승격 — [[feedback_main_squash_merge]] squash, 사용자 영역

## 8. 관련

- 인접 참조: V13 `partyroom_report` (자유텍스트 신고 시스템) · D/#8 admin GUEST 페이지 (read-only FSD 패턴) · `AdminLoginRateLimitGuard` (bucket4j 패턴)
- 메모리: [[feedback_pr_series_workflow]], [[feedback_commit_consolidation_before_push]], [[feedback_korean_issue_commit_pr]], [[feedback_elegant_no_code_dirtying]], [[feedback_pfplay_web_i18n_drift]], [[reference_pfplay_platform_jdk]], [[project_jvm_tz_kst_policy]], [[reference_mysql_datetime0_rounding]], [[single-partyroom-subscription-invariant]]
- 후속 작업 트리거 시점: admin 콘솔 사용 1~2주 후 운영 피드백 → 답변 워크플로 / 알림 확장 여부 재평가
