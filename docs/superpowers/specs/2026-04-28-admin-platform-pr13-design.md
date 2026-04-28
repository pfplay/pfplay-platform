# PR 13: V13 partyroom_report + 유저용 신고 API + 어드민 검토 API (C-1~C-2) — Design

> Brainstormed design spec. Implementation plan is generated separately via `superpowers:writing-plans`.

**Date:** 2026-04-28
**Branch:** `feature/admin-auth-iam-schema` (PR 12b2 HEAD `16907ee3` 위에 빌드)
**Roadmap row:** §9.1 PR 13 — *V13 partyroom_report + 유저용 신고 API + 어드민 검토 API (C-1~C-2)*
**Migration:** V13
**Milestone:** M5 (PR 12-13, 유저 관리 + 활동 로그 + 신고 시스템) 마무리

---

## 1. Goal

Administration BC에 모더레이션 도메인 1개(`PartyroomReport`)를 추가하고 신고 라이프사이클의 양 끝(유저 신고 접수 / 어드민 검토)을 완성한다. 본 PR이 끝나면:

- 인증된 Member가 active partyroom을 신고할 수 있다 — `POST /api/v1/partyrooms/{partyroomId}/reports`. 24h 동일 카테고리 중복 / self-report / 비-active partyroom 신고는 거부.
- 어드민이 신고 목록을 조회할 수 있다 — `GET /api/v1/admin/reports?status=...&category=...&page=...&size=...&sort=...` (PR 12b2 G1에서 정착된 Bean Validation 표준 패턴 적용).
- 어드민이 신고 상세를 조회할 수 있다 — `GET /api/v1/admin/reports/{reportId}`. 신고자 식별 정보(email + member nickname) + 파티룸 정보(title + host nickname) cross-context join 응답.
- 어드민이 신고 status를 전이할 수 있다 — `PATCH /api/v1/admin/reports/{reportId}`. `PENDING → REVIEWING / DISMISSED`, `REVIEWING → RESOLVED / DISMISSED / PENDING(보류)` 5개 transition 허용. 첫 PATCH 시 `reviewedByAdministratorId` set, terminal 진입 시 `resolvedAt` set.
- M5 (PR 12-13) 완성 → M6 (PR 14, pfplay-admin 프런트) 의존 해소.

---

## 2. Scope

### 2.1 In Scope (PR 13)

1. **V13 마이그레이션** — `flyway/migrations/V13__create_partyroom_report.sql`. schema.md §4.8.1 DDL 그대로 사용 (status/category enum, FK on `reviewed_by_administrator_id`, 3 인덱스). day_bucket 컬럼 / unique index는 추가하지 않음 (D1 결정 — 앱 검증 채택).
2. **Domain Entity 신규**:
   - `administration/domain/entity/data/PartyroomReportData` (BaseEntity 상속, `@DomainEvents`/`registerEvent` 미사용 — D4 결정으로 이벤트 발행 0).
   - `administration/domain/value/ReportStatus` enum: `PENDING, REVIEWING, RESOLVED, DISMISSED` + `isTerminal()` / `isOpen()` 헬퍼.
   - `administration/domain/value/ReportCategory` enum: `INAPPROPRIATE_CONTENT, HARASSMENT, SPAM, COPYRIGHT, OTHER`.
   - 도메인 메서드 (status 전이 + 동시에 review/resolved 메타 기록):
     - `startReview(byAdministratorId)` — PENDING → REVIEWING + `reviewedByAdministratorId` set (`null`일 때만).
     - `resolve(byAdministratorId, resolutionNote)` — REVIEWING → RESOLVED + `resolutionNote` 기록 + `resolvedAt = now`.
     - `dismiss(byAdministratorId, resolutionNote)` — PENDING/REVIEWING → DISMISSED + `resolvedAt = now` (PENDING 직접 dismiss 시 `reviewedByAdministratorId`도 set).
     - `hold(byAdministratorId)` — REVIEWING → PENDING (보류, `resolutionNote` 옵션). `reviewedByAdministratorId` 보존.
   - 모든 전이 메서드는 `if (!status.canTransitionTo(target)) throw INVALID_STATE_TRANSITION` guard 선행.
3. **Repository 신규** — `PartyroomReportRepository extends JpaRepository<PartyroomReportData, Long>`:
   - `existsByReporterUserAccountIdAndPartyroomIdAndCategoryAndCreatedAtAfter(...)` — 24h 중복 검증.
   - 어드민 list용 query (status `IN`, category `IN` 필터 + `createdAt DESC` sort) — Spring Data method query 또는 `@Query` JPQL.
4. **C-1 유저 endpoint** — `POST /api/v1/partyrooms/{partyroomId}/reports`:
   - Auth: Member only (`@PreAuthorize("isAuthenticated()")`, AuthorityTier 확인은 service-layer — Guest는 차단).
   - Path: `partyroomId` (`@Min(1)`).
   - Body: `PartyroomReportCreateRequest { @NotNull ReportCategory category, @Size(max=2000) String description }`.
   - Response: `201 Created`, `ApiCommonResponse<PartyroomReportCreateResponse(reportId)>`.
   - 처리 순서: (1) `partyroom = partyroomRepository.findById` — 부재 `PARTYROOM_NOT_FOUND(404)`. (2) `if (!partyroom.isReportable()) throw PARTYROOM_NOT_REPORTABLE(400)` — 비-active 상태(`TERMINATED` 등) 거부. (3) `if (partyroom.getHostId() == reporterUserId) throw SELF_REPORT_FORBIDDEN(400)`. (4) `if (reportRepository.existsByReporter...CreatedAtAfter(now-24h)) throw DUPLICATE_REPORT(400)`. (5) save.
5. **C-2 어드민 목록 endpoint** — `GET /api/v1/admin/reports`:
   - Auth: `@PreAuthorize("@adminAuth.isAdmin()")`.
   - QueryParams: `status` (List<ReportStatus>, optional, 기본 전체), `category` (List<ReportCategory>, optional), `createdFrom`/`createdTo` (LocalDate, optional, cross-field check inline), `page` (`@Min(0)`), `size` (`@Min(1) @Max(200)`), `sort` (`@Pattern("created_at_desc|created_at_asc")`).
   - Response: `200 OK`, `ApiCommonResponse<Page<AdminReportSummaryResponse>>`.
   - `AdminReportSummaryResponse(reportId, partyroomId, reporterUserAccountId, category, status, createdAt, reviewedByAdministratorId, resolvedAt)` — list view는 cross-context join 없이 raw fields만(성능 + 단순). 상세는 detail에서.
6. **C-2 어드민 상세 endpoint** — `GET /api/v1/admin/reports/{reportId}`:
   - Path: `reportId` (`@Min(1)`).
   - Response: `200 OK`, `ApiCommonResponse<AdminReportDetailResponse>`.
   - cross-context loose ref read: `MemberRepository.findByUserAccountId(reporterUserAccountId)` (nickname + email 추출), `PartyroomRepository.findById(partyroomId)` (title + hostId), `MemberRepository.findByUserAccountId(hostUserAccountId)` (host nickname). 부재 시 응답에는 `null` 표기 (탈퇴/룸 종료 케이스 — orphan 허용).
   - `AdminReportDetailResponse(reportId, status, category, description, reporter{userAccountId, email, nickname}, partyroom{partyroomId, title, host{userAccountId, nickname}}, review{reviewedByAdministratorId, resolutionNote, resolvedAt}, createdAt)`.
7. **C-2 어드민 PATCH endpoint** — `PATCH /api/v1/admin/reports/{reportId}`:
   - Body: `AdminReportStatusUpdateRequest { @NotNull ReportStatus status, @Size(max=2000) String resolutionNote }`.
   - 검증: terminal 진입(RESOLVED/DISMISSED) 시 `resolutionNote` `@NotBlank` (service-layer 추가 검증).
   - Response: `200 OK`, `ApiCommonResponse<AdminReportDetailResponse>` — 상세 endpoint 응답 재사용.
   - 처리: (1) `report = reportRepository.findById` — `REPORT_NOT_FOUND(404)`. (2) target status에 따라 도메인 메서드 분기 (`startReview` / `resolve` / `dismiss` / `hold`). (3) 도메인 메서드 내부에서 `INVALID_STATE_TRANSITION(400)` 검증. (4) save. (5) detail 응답 build (cross-context read 재사용).
8. **신규 Application Services**:
   - `PartyroomReportCommandService` (user-facing C-1) — `@Transactional`, `@Validated` 무관(controller-level).
   - `AdminReportQueryService` (C-2 list/detail) — `@Transactional(readOnly=true)`.
   - `AdminReportCommandService` (C-2 PATCH) — `@Transactional`.
9. **신규 Web Controllers**:
   - `PartyroomReportCommandController` (`administration/adapter/in/web/`) — `/api/v1/partyrooms` 매핑(파티룸 sub-resource).
   - `AdminReportQueryController` — `/api/v1/admin/reports`.
   - `AdminReportCommandController` — `/api/v1/admin/reports`.
   - 클래스 레벨 `@Validated` (PR 12b2 G1 표준 패턴).
10. **신규 Exception 코드** (`administration/domain/exception/AdminReportException`):
    - `REPORT_NOT_FOUND("RPT-001", NOT_FOUND)`.
    - `INVALID_STATE_TRANSITION("RPT-002", BAD_REQUEST)`.
    - `RESOLUTION_NOTE_REQUIRED("RPT-003", BAD_REQUEST)` — terminal 진입 시 note 누락.
    - `INVALID_LIST_QUERY("RPT-004", BAD_REQUEST)` — cross-field(`createdFrom > createdTo`) 검증 — PR 12b2 패턴.
   유저용 신고 예외 (별 enum 또는 동일 enum 분리):
    - `PARTYROOM_NOT_FOUND` — 기존 partyroom BC enum 재사용 가능 시 follow, 없으면 신설.
    - `PARTYROOM_NOT_REPORTABLE("RPT-005", BAD_REQUEST)`.
    - `SELF_REPORT_FORBIDDEN("RPT-006", BAD_REQUEST)`.
    - `DUPLICATE_REPORT("RPT-007", BAD_REQUEST)`.
   - 단일 enum vs 분리는 implementer 판단 (ground-truth — 기존 administration BC가 어떻게 분리하는지 확인 후 일관 follow).
11. **AbstractAdminWebMvcTest 등록**:
    - `AdminReportQueryController.class`, `AdminReportCommandController.class` controller 추가.
    - `@MockBean AdminReportQueryService`, `@MockBean AdminReportCommandService`.
   - `PartyroomReportCommandController`는 user-facing이므로 admin WebMvc base가 아니라 별 base or 단독 `@WebMvcTest` (ground-truth — PR 7+ 유저 endpoint controller test base 확인).
12. **테스트** — 단위/IT/WebMvc/ArchUnit:
    - 단위: `PartyroomReportData` 도메인 메서드 4종(startReview/resolve/dismiss/hold) 각 happy + INVALID_STATE_TRANSITION 케이스 + `ReportStatus.canTransitionTo(...)` 매트릭스.
    - 단위: 3개 service 각 happy + 주요 예외 케이스.
    - WebMvc: 4개 controller × (200 happy + 401 + 403 + 4xx) = ~20 case.
    - IT (Testcontainers): C-1 happy + 24h 중복 차단 + self-report 차단 + 비-active 차단. C-2 list 필터/sort/pagination + detail cross-context join + PATCH 4 transition × happy + INVALID_STATE_TRANSITION 1건.
    - ArchUnit: 기존 `CrossContextDependencyTest`가 administration → user/party loose-ref read 패턴 cover 여부 확인. PR 7/8/12b1+12b2의 cross-context read가 이미 통과하므로 추가 가드 불요.

대략 신규 테스트 ~32 (unit ~10, WebMvc ~20, IT ~10).

### 2.2 Out of Scope (future PR)

| 항목 | 사유 |
|---|---|
| `ReportStatusChanged` 이벤트 발행 | D4 결정 — 현 시점 consumer 0. YAGNI. consumer 도입 시 별 PR로 evolve |
| `partyroom_admin_action`에 `REPORT_REVIEWED` 액션 기록 | D5 결정 — `partyroom_report` 자체가 audit 테이블. 중복 audit 회피 |
| 신고자 측 본인 신고 이력 조회 (`GET /partyrooms/{id}/reports/me`) | features.md C-1 요청 미포함. 향후 사용자 경험 측면 도입 검토 |
| Bulk action (여러 신고 일괄 처리) | features §6.B-8 패턴 차용 가능하지만 신고는 case-by-case 검토 본질이라 후순위 |
| 신고 자동 분류/탐지 (ML, 키워드) | C-3 금지어 관리(`banned_word`)와 동시 future scope |
| Report 첨부 파일/스크린샷 | C-1 description text-only. 첨부는 GCS 업로드 + URL 컬럼 확장 필요 — 별 PR |
| Notification (어드민에게 신고 알림 / 신고자에게 처리 결과 알림) | D-3 push notification 인프라 구축 후 |
| Optimistic lock(version) — 동시 어드민 두 명이 같은 report PATCH | row lock + 마지막 write win MVP 허용 |
| Report metrics dashboard (`pendingReportCount` 등) | features §6.E-2 metrics endpoint에서 cover — 별 endpoint |

---

## 3. Endpoint Specification

### 3.1 `POST /api/v1/partyrooms/{partyroomId}/reports` — C-1 신고 접수

| 항목 | 값 |
|---|---|
| Auth | `@PreAuthorize("isAuthenticated()")` + service-layer Member tier check |
| Cookie | `AccessToken` (Member용) |
| Path | `partyroomId` (Long, `@Min(1)`) |
| Body | `PartyroomReportCreateRequest { @NotNull ReportCategory category, @Size(max=2000) String description }` |
| Response | `201 Created`, `ApiCommonResponse<PartyroomReportCreateResponse>` |

**Request**:
```json
{
  "category": "HARASSMENT",
  "description": "특정 크루를 지속적으로 비방. 채팅 로그 N분 ~ M분 구간 참고."
}
```

**Response**:
```json
{
  "data": { "reportId": 123 },
  "meta": {...}
}
```

**Domain flow**:
1. `PartyroomReportCommandService.create(partyroomId, request, memberContext)`:
   - `partyroom = partyroomRepository.findById(partyroomId).orElseThrow(PARTYROOM_NOT_FOUND)`.
   - `if (!partyroom.isReportable()) throw PARTYROOM_NOT_REPORTABLE` — `isReportable()` 메서드는 partyroom 도메인이 보유. 부재 시 PR 7 enum의 active state 집합 정의에 따라 service-layer inline check 임시 적용 후 §12.5 deviations에 반영.
   - `if (partyroom.getHostId().equals(memberContext.userAccountId())) throw SELF_REPORT_FORBIDDEN`.
   - `since = now().minus(24, HOURS)`. `if (reportRepository.existsByReporter...After(reporterId, partyroomId, category, since)) throw DUPLICATE_REPORT`.
   - `report = PartyroomReportData.create(partyroomId, reporterUserAccountId, category, description)`.
   - `reportRepository.save(report)`.
   - return `new PartyroomReportCreateResponse(report.getReportId())`.

**Errors**:
| HTTP | code | 사유 |
|---|---|---|
| 400 | `MethodArgumentNotValidException` | category null / description >2000 |
| 400 | `PARTYROOM_NOT_REPORTABLE` (RPT-005) | active 아닌 partyroom |
| 400 | `SELF_REPORT_FORBIDDEN` (RPT-006) | 본인이 호스트인 룸 |
| 400 | `DUPLICATE_REPORT` (RPT-007) | 24h 동일 카테고리 중복 |
| 401 | — | 미인증 |
| 403 | — | 인증된 Guest (Member 아님) — service-layer `if (!memberContext.isMember()) throw 403` 또는 `@PreAuthorize` SpEL bean(`@memberAuth.isMember()`) — ground-truth 확인 |
| 404 | `PARTYROOM_NOT_FOUND` | partyroomId 부재 |

### 3.2 `GET /api/v1/admin/reports` — C-2 목록

| 항목 | 값 |
|---|---|
| Auth | `@PreAuthorize("@adminAuth.isAdmin()")` |
| Cookie | `AdminAccessToken` |
| Query | status (List, optional), category (List, optional), createdFrom/createdTo (LocalDate, optional), page (`@Min(0)`, 기본 0), size (`@Min(1) @Max(200)`, 기본 50), sort (`@Pattern`, 기본 `created_at_desc`) |
| Response | `200 OK`, `ApiCommonResponse<Page<AdminReportSummaryResponse>>` |

**Sort 키**: `created_at_desc | created_at_asc`. resolved/reviewed 시간 sort는 future.

**Cross-field**: `createdFrom > createdTo` 시 `INVALID_LIST_QUERY` (RPT-004) — inline 검증, A-1 동일 패턴.

**Response**:
```json
{
  "data": {
    "content": [
      {
        "reportId": 123,
        "partyroomId": 555,
        "reporterUserAccountId": 777,
        "category": "HARASSMENT",
        "status": "PENDING",
        "createdAt": "2026-04-28T03:21:00Z",
        "reviewedByAdministratorId": null,
        "resolvedAt": null
      }
    ],
    "pageable": {...},
    "totalElements": 42,
    "totalPages": 1
  }
}
```

### 3.3 `GET /api/v1/admin/reports/{reportId}` — C-2 상세

| 항목 | 값 |
|---|---|
| Auth | `@PreAuthorize("@adminAuth.isAdmin()")` |
| Path | `reportId` (`@Min(1)`) |
| Response | `200 OK`, `ApiCommonResponse<AdminReportDetailResponse>` |

**Cross-context loose-ref reads** (D6):
- `member = memberRepository.findByUserAccountId(report.reporterUserAccountId)` → reporter.nickname; reporter.email은 `userAccountRepository.findById(...)` 별 read.
- `partyroom = partyroomRepository.findById(report.partyroomId)` → title + hostId.
- `host = memberRepository.findByUserAccountId(partyroom.hostId)` → host.nickname.

부재 시 응답에 `null` 허용 (탈퇴/룸 종료 케이스). 503 fail-fast 안 함.

**Response (raw shape — implementer는 ground-truth ApiCommonResponse envelope 그대로 적용)**:
```json
{
  "data": {
    "reportId": 123,
    "status": "REVIEWING",
    "category": "HARASSMENT",
    "description": "...",
    "reporter": {
      "userAccountId": 777,
      "email": "user@example.com",
      "nickname": "alice"
    },
    "partyroom": {
      "partyroomId": 555,
      "title": "...",
      "host": { "userAccountId": 999, "nickname": "bob" }
    },
    "review": {
      "reviewedByAdministratorId": 1,
      "resolutionNote": null,
      "resolvedAt": null
    },
    "createdAt": "2026-04-28T03:21:00Z"
  }
}
```

**Errors**:
| HTTP | code | 사유 |
|---|---|---|
| 401 / 403 | — | 동일 |
| 404 | `REPORT_NOT_FOUND` (RPT-001) | reportId 부재 |

### 3.4 `PATCH /api/v1/admin/reports/{reportId}` — C-2 status 전이

| 항목 | 값 |
|---|---|
| Auth | `@PreAuthorize("@adminAuth.isAdmin()")` |
| Path | `reportId` (`@Min(1)`) |
| Body | `AdminReportStatusUpdateRequest { @NotNull ReportStatus status, @Size(max=2000) String resolutionNote }` |
| Response | `200 OK`, `ApiCommonResponse<AdminReportDetailResponse>` (3.3과 동일 shape) |

**Transition 매트릭스** (D3):

| From → To | PENDING | REVIEWING | RESOLVED | DISMISSED |
|---|---|---|---|---|
| **PENDING** | — (no-op 거부) | ✅ startReview | ❌ INVALID | ✅ dismiss(direct) |
| **REVIEWING** | ✅ hold | — (no-op 거부) | ✅ resolve | ✅ dismiss |
| **RESOLVED** | ❌ | ❌ | ❌ | ❌ (terminal) |
| **DISMISSED** | ❌ | ❌ | ❌ | ❌ (terminal) |

- `from == to` 동일 status 전이 요청 → `INVALID_STATE_TRANSITION` (idempotent silent 거부 안 함 — admin이 의도적으로 같은 status 입력은 audit 측면 explicit 피드백, PR 12b2 TIER_UNCHANGED 패턴 일관).
- terminal(RESOLVED/DISMISSED) 진입 시 `resolutionNote` `@NotBlank` (controller 단계 또는 service `if (target.isTerminal() && isBlank(note)) throw RESOLUTION_NOTE_REQUIRED`).

**Service 분기**:
```java
@Transactional
public AdminReportDetailResponse changeStatus(Long reportId, AdminReportStatusUpdateRequest req, AdminContext ctx) {
    PartyroomReportData report = reportRepository.findById(reportId)
            .orElseThrow(() -> ExceptionCreator.create(REPORT_NOT_FOUND));
    Long byAdminId = ctx.currentAdministratorId();
    switch (req.status()) {
        case REVIEWING -> report.startReview(byAdminId);
        case RESOLVED -> {
            if (isBlank(req.resolutionNote())) throw ExceptionCreator.create(RESOLUTION_NOTE_REQUIRED);
            report.resolve(byAdminId, req.resolutionNote());
        }
        case DISMISSED -> {
            if (isBlank(req.resolutionNote())) throw ExceptionCreator.create(RESOLUTION_NOTE_REQUIRED);
            report.dismiss(byAdminId, req.resolutionNote());
        }
        case PENDING -> report.hold(byAdminId);
    }
    reportRepository.save(report);
    return detailService.buildDetailResponse(report);  // cross-context join 재사용
}
```

**Errors**:
| HTTP | code | 사유 |
|---|---|---|
| 400 | `MethodArgumentNotValidException` | status null / enum 외 / note >2000 |
| 400 | `INVALID_STATE_TRANSITION` (RPT-002) | matrix 위반 |
| 400 | `RESOLUTION_NOTE_REQUIRED` (RPT-003) | terminal 진입 + note blank |
| 401 / 403 | — | 동일 |
| 404 | `REPORT_NOT_FOUND` | reportId 부재 |

---

## 4. Domain Entity & Methods

### 4.1 `PartyroomReportData` (administration/domain/entity/data/)

```java
@Entity
@Table(name = "partyroom_report")
public class PartyroomReportData extends BaseEntity {

    @Id @GeneratedValue(strategy = IDENTITY)
    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "partyroom_id", nullable = false)
    private Long partyroomId;

    @Column(name = "reporter_user_account_id", nullable = false)
    private Long reporterUserAccountId;

    @Enumerated(STRING)
    @Column(nullable = false)
    private ReportCategory category;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(STRING)
    @Column(nullable = false)
    private ReportStatus status;

    @Column(name = "reviewed_by_administrator_id")
    private Long reviewedByAdministratorId;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public static PartyroomReportData create(Long partyroomId, Long reporterUserAccountId,
                                             ReportCategory category, String description) {
        PartyroomReportData r = new PartyroomReportData();
        r.partyroomId = partyroomId;
        r.reporterUserAccountId = reporterUserAccountId;
        r.category = category;
        r.description = description;
        r.status = ReportStatus.PENDING;
        r.createdAt = LocalDateTime.now();
        return r;
    }

    public void startReview(Long byAdministratorId) {
        guardTransition(ReportStatus.REVIEWING);
        this.status = ReportStatus.REVIEWING;
        if (this.reviewedByAdministratorId == null) {
            this.reviewedByAdministratorId = byAdministratorId;
        }
    }

    public void resolve(Long byAdministratorId, String resolutionNote) {
        guardTransition(ReportStatus.RESOLVED);
        if (this.reviewedByAdministratorId == null) {
            this.reviewedByAdministratorId = byAdministratorId;  // PENDING→RESOLVED는 matrix 금지지만 방어
        }
        this.resolutionNote = resolutionNote;
        this.status = ReportStatus.RESOLVED;
        this.resolvedAt = LocalDateTime.now();
    }

    public void dismiss(Long byAdministratorId, String resolutionNote) {
        guardTransition(ReportStatus.DISMISSED);
        if (this.reviewedByAdministratorId == null) {
            this.reviewedByAdministratorId = byAdministratorId;  // PENDING→DISMISSED 직접 전이 시
        }
        this.resolutionNote = resolutionNote;
        this.status = ReportStatus.DISMISSED;
        this.resolvedAt = LocalDateTime.now();
    }

    public void hold(Long byAdministratorId) {
        guardTransition(ReportStatus.PENDING);
        this.status = ReportStatus.PENDING;
        // reviewedByAdministratorId 보존 — 누가 검토했었는지 audit 가시
    }

    private void guardTransition(ReportStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw ExceptionCreator.create(AdminReportException.INVALID_STATE_TRANSITION);
        }
    }
}
```

### 4.2 `ReportStatus` 전이 매트릭스

```java
public enum ReportStatus {
    PENDING, REVIEWING, RESOLVED, DISMISSED;

    public boolean isTerminal() { return this == RESOLVED || this == DISMISSED; }
    public boolean isOpen() { return !isTerminal(); }

    public boolean canTransitionTo(ReportStatus target) {
        if (this == target) return false;  // no-op 거부
        return switch (this) {
            case PENDING -> target == REVIEWING || target == DISMISSED;
            case REVIEWING -> target == RESOLVED || target == DISMISSED || target == PENDING;
            case RESOLVED, DISMISSED -> false;
        };
    }
}
```

### 4.3 Domain method 단위 테스트 매트릭스

`PartyroomReportDataTest`:
- `startReview_fromPending_setsReviewerAndStatus`.
- `startReview_fromReviewing_throwsInvalidTransition`.
- `resolve_fromReviewing_setsTerminalState`.
- `resolve_fromPending_throwsInvalidTransition` (matrix 금지).
- `dismiss_fromPending_directDismissSetsReviewerAndResolvedAt`.
- `dismiss_fromReviewing_setsTerminalState`.
- `hold_fromReviewing_revertsToPendingPreservingReviewer`.
- `hold_fromPending_throwsInvalidTransition`.
- `anyTransition_fromTerminal_throwsInvalidTransition` (parameterized).

`ReportStatusTest`:
- `canTransitionTo_matrix` (parameterized 4×4 = 16 케이스).

---

## 5. Cross-Context Reads (D6)

`AdminReportQueryService.getDetail()` + `AdminReportCommandService.changeStatus()` 응답 build에서 동일 cross-context join 로직 재사용 필요. 추출 위치 옵션:

- **(a)** `AdminReportDetailAssembler` 별 컴포넌트 — 두 서비스가 inject + 호출.
- **(b)** Query service에 `buildDetailResponse(PartyroomReportData)` public method — Command service가 Query service inject (BC 내부 service-to-service OK).
- 추천: **(b)** — 별 추상화 회피, PR 12b1 패턴(`AdminMemberQueryService`가 list/detail 양쪽 보유)과 일관.

**부재 처리**: orphan tolerance — 응답 필드는 `null`. 단언 IT는 happy path에 모든 cross-context entity 시드 + 1 케이스(reporter member 시드 누락)에서 `nickname=null` 검증 추가.

**Loose-ref repository 호출**: `MemberRepository.findByUserAccountId(...)` / `PartyroomRepository.findById(...)` — 이미 PR 12b1 / PR 7+ 시점 존재. ground-truth 확인 후 §12.5 backfill.

---

## 6. AdminContext / MemberContext 획득 패턴

- 어드민 endpoint 3종은 PR 12b2 패턴 — `AdminContext ctx` injection or `SecurityContextHolder` 직접 read. `ctx.currentAdministratorId()` 그대로 사용.
- 유저 endpoint(C-1)는 Member 인증 컨텍스트 — `@AuthenticationPrincipal MemberPrincipal` 또는 동일 패턴(ground-truth 확인). PR 7+ 유저 endpoint(`partyroom` 생성/조회) follow.

신규 패턴 도입 금지. ground-truth deviation은 §12.5에 backfill.

---

## 7. Event Publish (D4 결정)

**`ReportStatusChanged` 이벤트는 발행하지 않음.**

이유: 현 시점 consumer 0. `user_activity_log`는 user-centric이라 신고 status 변경을 audit하지 않고, `partyroom_admin_action`은 D5 결정으로 신고 검토 액션 미기록. 가까운 future PR(C-3 banned-words / D-1 announcements / E-2 metrics)도 신고 status 이벤트 의존 없음.

ADR-004 hybrid publish 패턴은 본 PR scope에 적용되지 않음 — `BaseEntity.registerEvent` 호출 0, `pollDomainEvents().forEach(eventPublisher::publishEvent)` 호출 0. 향후 consumer 도입 시 별 PR로 patten 추가.

---

## 8. Audit Policy (D5 결정)

**`partyroom_admin_action`에 신고 검토 액션 기록하지 않음.**

이유:
- `partyroom_report` 테이블 자체가 audit 테이블 — `reviewedByAdministratorId`, `resolvedAt`, `resolutionNote`, `status` 보유.
- `partyroom_admin_action`은 *partyroom 자체의 상태/속성을 변경하는 어드민 액션*(SUSPEND/TERMINATE/SET_HIDDEN/페널티 등) 기록용. 신고 status 전이는 `partyroom_report`의 lifecycle이지 partyroom의 lifecycle이 아님.
- 신고 1건당 audit row 2건(report + admin_action) 중복 회피.

향후 신고 처리가 partyroom 액션을 *trigger*하는 경우(예: REPORT REVIEWED → AUTO_SUSPEND), 그 시점에 `partyroom_admin_action`에 별 row 기록 — but 그건 신고 처리 자체가 아니라 후속 액션. 현 PR scope OOS.

---

## 9. Testing Strategy

### 9.1 단위 (신규)

- `PartyroomReportDataTest` — §4.3 매트릭스.
- `ReportStatusTest` — `canTransitionTo` 4×4 매트릭스.
- `PartyroomReportCommandServiceTest` (mock repos):
  - happy 201.
  - `PARTYROOM_NOT_FOUND` → 404.
  - `PARTYROOM_NOT_REPORTABLE` → 400.
  - `SELF_REPORT_FORBIDDEN` → 400.
  - `DUPLICATE_REPORT` → 400.
- `AdminReportQueryServiceTest`:
  - list happy (필터 + sort + pagination).
  - detail happy + cross-context null tolerance (reporter member orphan 케이스).
  - `REPORT_NOT_FOUND` → 404.
- `AdminReportCommandServiceTest`:
  - 4 transition happy(`REVIEWING / RESOLVED / DISMISSED / PENDING(보류)`).
  - `INVALID_STATE_TRANSITION` (terminal에서 변경 시도).
  - `RESOLUTION_NOTE_REQUIRED` (terminal note blank).
  - `REPORT_NOT_FOUND` → 404.

### 9.2 IT (Testcontainers MySQL)

- `PartyroomReportCommandServiceIT extends AbstractIntegrationTest`:
  - SEED: super-admin V5 보존, 1 admin + 1 host member + 1 reporter member + 1 active partyroom + (optional) 1 terminated partyroom. Scoped DELETE cleanup.
  - happy: 201 + DB row 1건 + status=PENDING + createdAt 검증.
  - 24h 중복 동일 카테고리 → DUPLICATE_REPORT.
  - 24h 후 동일 카테고리 → 허용 (시간 경계 검증 — `created_at = now-25h` 사전 시드 후 신규 신고 happy).
  - 24h 내 다른 카테고리 → 허용.
  - self-report → SELF_REPORT_FORBIDDEN.
  - 비-active partyroom → PARTYROOM_NOT_REPORTABLE.
  - non-existent partyroom → 404.
- `AdminReportQueryServiceIT`:
  - SEED: 5건 신고(다양한 status/category/createdAt).
  - list status=`PENDING` → 1건 등.
  - list category=`HARASSMENT` 다중 status → N건.
  - list `createdFrom > createdTo` → INVALID_LIST_QUERY.
  - detail happy + cross-context join 검증 (reporter nickname/email + host nickname).
  - detail orphan reporter (member 삭제 후) → reporter.nickname=null.
  - detail not-found → 404.
- `AdminReportCommandServiceIT`:
  - 4 transition happy (각 시드 다른 from-status).
  - terminal(RESOLVED) → DISMISSED 시도 → INVALID_STATE_TRANSITION.
  - PENDING → RESOLVED skip 시도 → INVALID_STATE_TRANSITION.
  - REVIEWING → RESOLVED + resolutionNote=blank → RESOLUTION_NOTE_REQUIRED.
  - PATCH 후 detail 응답 cross-context join 검증 (3.3과 동일 shape).

### 9.3 WebMvc (신규)

- `PartyroomReportCommandControllerTest`:
  - 201 happy + 400 (category null/enum 외, description >2000) + 401 anonymous + 403 Guest tier + 404 partyroom not-found.
- `AdminReportQueryControllerTest`:
  - list 200 happy + 400 (size>200, page<0, sort 패턴 외) + 400 cross-field(`createdFrom > createdTo`) + 401 + 403 non-admin.
  - detail 200 happy + 401 + 403 + 404.
- `AdminReportCommandControllerTest`:
  - PATCH 200 (4 transition × 1 case = 4 happy 또는 1 happy 대표 + 3 transition은 service-layer test cover) + 400 (status null + INVALID_STATE_TRANSITION + RESOLUTION_NOTE_REQUIRED) + 401 + 403 + 404.

### 9.4 ArchUnit

- `CrossContextDependencyTest` 기존 가드가 administration → user/party loose-ref read 패턴 cover. 신규 import 추가는 patterns/imports에 자동 포함 — 추가 가드 불요.
- `BaseEntityArchTest`(존재 시) — `PartyroomReportData extends BaseEntity` 자동 cover.

### 9.5 Out of Scope

- 동시 두 어드민 race (동일 report PATCH) — 명시 race test future.
- 신고 데이터 대량 (10만+) pagination 성능 — load test future.
- ML 분류 / 자동 거부 — C-3 동시.

---

## 10. Atomic Commit Groupings

| Group | 묶음 | 사유 |
|---|---|---|
| **G1: V13 + PartyroomReport 도메인** | `V13__create_partyroom_report.sql` 마이그 + `PartyroomReportData` 엔티티 + `ReportStatus` / `ReportCategory` enum + `PartyroomReportRepository` + `AdminReportException` enum + `PartyroomReportData` 단위 테스트 + `ReportStatus` 매트릭스 테스트 | 도메인 + 스키마 단일 PR 12b1/PR 8 패턴. service/controller 없으므로 commit 후 빌드는 성공하나 endpoint 0. |
| **G2: C-1 유저 신고 endpoint** | `PartyroomReportCommandService` + `PartyroomReportCommandController` + DTO 2종(`Create Request/Response`) + `MemberContext` 의존성 wiring + 24h 중복/self-report/active 검증 + WebMvc + IT(7 케이스) + `PartyroomData.isReportable()`(필요 시 신설) | endpoint 단위 PR 8/12b1 패턴. cross-context partyroom read 첫 도입. |
| **G3: C-2 어드민 list/detail** | `AdminReportQueryService` + `AdminReportQueryController` + DTO 2종(`Summary`, `Detail` + nested) + cross-context loose-ref read 4종(member×2, userAccount, partyroom) + `AbstractAdminWebMvcTest` 등록 + WebMvc + IT(list 필터/sort + detail join + orphan tolerance) | Query 측 endpoint pair. PATCH 응답 build이 detail에 의존하므로 G3 → G4 순서 강제. |
| **G4: C-2 PATCH status 전이** | `AdminReportCommandService` + `AdminReportCommandController` + `AdminReportStatusUpdateRequest` DTO + `RESOLUTION_NOTE_REQUIRED` 검증 + `AbstractAdminWebMvcTest` 등록 확장 + WebMvc + IT(4 transition + INVALID_STATE_TRANSITION + RESOLUTION_NOTE_REQUIRED) | endpoint 단위. detail 응답 재사용(`AdminReportQueryService.buildDetailResponse`). |
| **G5: spec catch-up + features.md 정정 + roadmap 갱신** | docs/superpowers/specs/PR 13 design.md §12 backfill (chunk SHAs + deviations) + features.md C-1/C-2 본문 ground-truth 정정 + roadmap.md PR 13 status ✅ 표기 + M5 milestone 완료 표기 | PR 8/PR 9/PR 12a/PR 12b1/PR 12b2 동형 패턴 §12 catch-up. |
| **G5.1: SHA backfill follow-up** | G5 commit SHA를 §12 본문에 채워 별 commit | PR 12a/12b1/12b2 패턴 follow. |

총 5 chunks (+ G5.1 polish). PR 12b2 4 chunks보다 1개 많음 — V13 마이그 + 3개 endpoint 양쪽이 단일 PR에 포함.

---

## 11. Risks & Mitigations

| # | 위험 | 완화 |
|---|---|---|
| 1 | Cross-context loose-ref read 4종(member×2, userAccount, partyroom) — orphan tolerance 미설계 시 NPE | 응답 DTO에서 `null` 명시 허용. IT에 orphan reporter 케이스 추가. PR 7+ admin partyroom detail이 동형 패턴 |
| 2 | 24h 중복 검증 race window — 동시 2건 신고 시 둘 다 통과 가능 | 앱 검증 race window는 ms 단위. 2건 통과 시 audit 영향 미미(중복 row 1개 증가). 정확성 필요 시 future unique index(`reporter, partyroom, category, day_bucket`) 추가 — 현재 schema spec과 일관(D1) |
| 3 | `partyroom.isReportable()` 메서드 부재 가능성 | G2 시작 시 ground-truth 확인. 없으면 `PartyroomData`에 `isReportable()` 신설(`status != TERMINATED && status != SUSPENDED` 정도 — PR 7 enum 기반). 신설 시 PR 7 코드 영향 0(읽기 전용 메서드 추가). |
| 4 | `MemberContext` API 패턴 PR 12b2 admin context와 다름 — Member 인증 패턴 ground-truth 미확인 | G2 시작 시 ground-truth 확인. `@AuthenticationPrincipal MemberPrincipal` / `SecurityContextHolder.getContext()...` 등 기존 user endpoint(`partyroom` 생성) 1개 read 후 일관 follow. §12.5 deviation 가능성 |
| 5 | `AdminReportQueryService.buildDetailResponse(report)` 재사용 — Command service가 Query service inject 시 BC 내부 결합 증가 | BC 내부 service-to-service injection은 PR 12b1에서 이미 사용. layered violation 아님 |
| 6 | `ReportStatus.canTransitionTo` 매트릭스 누락 시 조용한 버그 | 4×4 parameterized 매트릭스 테스트 — Junit `@MethodSource` 패턴, 16 케이스 모두 단언 |
| 7 | RESOLVED→PENDING 같은 `from == to`가 아니지만 matrix 금지 케이스에서 `INVALID_STATE_TRANSITION`이 발생하나 사용자 메시지에 "현재 status가 X인데 Y로 전이 불가" 같은 details 없음 | exception detail 필드(`from`, `to`)에 status 명시 — `ApiErrorResponse.message`에 노출. 현재 ApiErrorResponse shape이 detail 메시지 cover하는지 ground-truth 확인 |
| 8 | C-1 description text-only — XSS/abusive content 위험 | description은 어드민이 검토하는 내부 audit 데이터, 일반 유저에게 렌더 안 함. `@Size(max=2000)`로 길이만 제한. content sanitization은 future |
| 9 | PR 12b2 G1에서 정착된 `@Validated` controller 패턴 — 신규 controller 3종 모두 처음부터 표준 적용 가능. | G2/G3/G4 작성 시 PR 12b2 `AdminMemberQueryController`을 참조 모델로 사용 |
| 10 | M5 (PR 12-13) 완료 후 M6 (PR 14, 프런트)이 본 API spec에 의존 | PR 13 endpoint 응답 shape 결정은 PR 14 프런트 의존성에 영향. 응답 DTO field 이름은 features.md/이 spec 기준 final. PR 14 시점 변경 발생 시 forward-evolution 패턴(PR 12b 시리즈)으로 처리 |

---

## 12. Decisions Taken (브레인스토밍 결과)

1. **D1 — 24h 중복 신고 방지: 앱 검증 채택**: schema 본문(`partyroom_report` DDL)에 day_bucket 컬럼/unique index 추가 안 함. `ReportRepository.existsByReporter...After(reporterId, partyroomId, category, now-24h)` 앱 검증. race window는 ms 단위로 audit 영향 미미. 향후 abuse 패턴 발견 시 unique index 도입 별 PR.
2. **D1.5 — 신고자 자격: Member only**: features §6.C-1 "인증된 Member" spec 그대로. Guest는 service-layer 또는 SpEL bean(`@memberAuth.isMember()`)으로 차단 → 403.
3. **D2 — Self-report 차단**: `if (partyroom.hostId == reporterUserId) throw SELF_REPORT_FORBIDDEN`. 명시 거절이 audit-friendly + 의도적 abuse 차단.
4. **D3 — PATCH 전이 매트릭스**: 5 transition 허용 — `PENDING→REVIEWING/DISMISSED`, `REVIEWING→RESOLVED/DISMISSED/PENDING(보류)`. terminal(RESOLVED/DISMISSED)에서 변경 금지. PENDING→RESOLVED skip 금지. `from==to` 거부(idempotent silent 안 함, PR 12b2 TIER_UNCHANGED 패턴 일관).
5. **D3.1 — `resolutionNote` 검증**: terminal 진입(RESOLVED/DISMISSED) 시 `@NotBlank` 추가 검증 — service-layer guard. REVIEWING/PENDING 진입 시 옵션. 도메인 메서드 시그니처는 note를 항상 수용(blank 허용)하고 service에서 fail-fast.
6. **D3.2 — `reviewedByAdministratorId` set 시점**: 첫 PATCH(REVIEWING 진입 OR PENDING→DISMISSED 직접) 시 set. 이후 hold(REVIEWING→PENDING)에서도 보존(audit 가시 — 누가 검토했는지). 같은 admin이 다시 검토 진입해도 첫 admin id 보존.
7. **D3.3 — `resolvedAt` set 시점**: terminal 진입(RESOLVED/DISMISSED) 시 set. 다른 전이는 null 유지. hold로 PENDING 회귀 시 `resolvedAt` 보존? — terminal에서 회귀 불가하므로 자연스럽게 null 유지.
8. **D4 — `ReportStatusChanged` 이벤트 미발행**: YAGNI — 현 시점 consumer 0. ADR-004 hybrid publish 패턴은 본 PR scope에 적용 0(`registerEvent` 호출 0). consumer 도입 시 별 PR로 evolve.
9. **D5 — `partyroom_admin_action` 미기록**: `partyroom_report` 자체가 audit 테이블. 신고 status 전이는 partyroom의 lifecycle이 아닌 신고의 lifecycle. 중복 audit 회피.
10. **D6 — Partyroom active 검증: PartyroomRepository direct read**: PR 7+ admin BC가 partyroom read하는 패턴 그대로. `partyroom.isReportable()` 메서드 신설(없으면) — `status` enum 기반 check. cross-context loose ref라 FK 없음.
11. **D7 — atomic group 5개 분할**: G1 도메인+스키마, G2 C-1 유저 endpoint, G3 C-2 list/detail, G4 C-2 PATCH, G5 §12 catch-up. PR 12a/12b1/12b2 동형. G3 → G4 순서 강제(PATCH 응답이 detail에 의존).
12. **C-1 description max length 2000**: 한국어 신고 평균 100~500자. UPPER cap 2000자(약 4KB UTF-8) 충분 + DB `TEXT` 컬럼 over-budget 회피. abuse 자제.
13. **C-2 detail 응답에서 reporter.email 노출**: 어드민 화면에서 신고자 식별/연락 needed. PII 처리 정책은 어드민 권한(`@adminAuth.isAdmin()`) 가드로 충분 — features §6.C-2 "신고자 프로필 정보" spec follow.
14. **list 응답에 cross-context join 미포함**: list summary는 raw fields(reporterUserAccountId, partyroomId)만. nickname/title 등 상세는 detail endpoint에서. 성능 + N+1 방지.
15. **Bean Validation 표준 패턴 처음부터 적용**: PR 12b2 G1에서 정착된 `@Validated` 클래스 + `@Min/@Max/@Pattern` 패턴을 신규 admin controller 2종에 처음부터 적용. inline `if` validation 추가 안 함(`createdFrom > createdTo` cross-field 1건 제외).
16. **Empty body POST 회피**: C-1은 body 필수(`PartyroomReportCreateRequest`). PATCH는 body 필수(`AdminReportStatusUpdateRequest`). PR 12b2 A-4 withdraw의 empty-body 패턴 차용 안 함 — 신고/검토는 본질적으로 데이터 입력 행위라 body가 자연스러움.
17. **PR 12b2 G1 `INVALID_LIST_QUERY` 패턴 follow**: 어드민 list cross-field 검증(`createdFrom > createdTo`)은 동일 패턴(`AdminReportException.INVALID_LIST_QUERY`) — Bean Validation 표준 부재라 inline 보존.

---

## 13. Open Items / Implementation Reality (post-build catch-up)

PR 12b1/12b2 §12 패턴 follow. G1~G5 commit 후 본 섹션을 atomic group마다 backfill.

### 13.1 G1 — V13 마이그 + 도메인

- **G1 commit `2037b1c0`** (8 files, 578 insertions): V13 SQL + PartyroomReportData entity + ReportStatus/ReportCategory enums + AdminReportException + PartyroomReportRepository + 단위 테스트 35건(전체 통과).
- **G1.1 commit `<SHA pending>`**: code reviewer follow-up — `resolve()` dead defensive branch 삭제(matrix가 PENDING→RESOLVED 차단하므로 도달 불가) + `@Column(length=16/32)` ENUM 컬럼 sibling consistency 보강 + `hold(byAdministratorId)` callsite-symmetry 의도 주석 + resolve/dismiss `resolutionNote` 검증 위치(controller @NotBlank 위임, D3.1) 명시 주석.
- DDL 변경 deviations: 없음 (schema.md §4.8.1 그대로).
- **enum 패키지 위치**: `app/src/main/java/com/pfplaybackend/api/administration/domain/enums/` 채택. spec/plan 초안의 `domain/value/`는 misnomer — 기존 administration BC convention 검사 결과 status/category-style enums(`PartyroomAdminActionType`, `AdminActionTargetType`, `AdminPenaltyType`, `BulkActionType`, `UserActivityEventType`)는 모두 `domain/enums/`이고 `domain/value/`는 first-class VO(`AdministratorId`, `AdminRole`, `JsonMetadata*`) 전용. 후속 G2-G4 신규 enum도 `domain/enums/` follow.
- **BaseEntity**: 미상속 (Plan §3 Option A). V13 DDL은 `created_at`만 보유, `updated_at` 부재. BaseEntity가 audit `updated_at`을 추가하면 Hibernate가 비존재 컬럼 write 시도 → JPA 부트 실패 risk. 엔티티가 자체 `private LocalDateTime createdAt;` 보유 + factory에서 `LocalDateTime.now()` 세팅. spec §4.1 코드 예시는 `extends BaseEntity`로 적혔으나 plan §3가 정정 — plan이 canonical.
- **`AdminReportException` 단일 enum**: 단일 채택. 7 entries(RPT-001~007) 모두 본 enum에 수용. `PARTYROOM_NOT_FOUND`는 G2 시점에 기존 partyroom BC enum 또는 `AdminException`(ADM-006) 재사용 검토 — G1에서는 별도 추가 안 함.
- **Exception interface naming**: spec/plan의 `ExceptionType` + `getCode()`는 misnomer. 실제 ground-truth는 `DomainException` + `getErrorCode()`(예: `AdminMemberException` 패턴). G1 `AdminReportException`도 `implements DomainException` + `getErrorCode()` 사용.
- **모듈 path correction**: spec/plan은 `administration/src/main/...`로 적혔으나 `administration`은 별 Gradle 모듈 아님 — `app/src/main/java/com/pfplaybackend/api/administration/...` 패키지. 모든 G1 file은 `app` 모듈 안에 land. G2-G4 신규 file도 동일 위치 follow.
- **JPA ENUM column 매핑**: V13 DDL은 MySQL `ENUM(...)`, 엔티티는 `@Enumerated(STRING) + @Column(length=N)` 사용(V4/V6/V8 precedent). `columnDefinition = "ENUM(...)"` 미사용 — Flyway가 DDL 소유, JPA는 string write/read만 책임. `hbm2ddl.auto=validate` mode에서 안전(prod precedent).
- **resolutionNote 검증 위치**: 도메인은 빈 note도 영속(D3.1). G4 controller에서 `@NotBlank` 또는 service guard로 강제 — `RESOLUTION_NOTE_REQUIRED(RPT-003)` throw. 도메인 메서드 javadoc에 호출자 책임 명시.

### 13.2 G2 — C-1 유저 endpoint

- _G2 commit: <SHA pending>_
- `MemberContext` 실제 API 명칭(`@AuthenticationPrincipal` 형태 / `currentUserId()` 등) deviation:
- `partyroom.isReportable()` — 신설 OR 기존 메서드 재사용 deviation:
- description max length 2000 — DTO `@Size` vs DB column constraint 양쪽 적용 여부:

### 13.3 G3 — C-2 list/detail

- _G3 commit: <SHA pending>_
- Cross-context loose-ref read 4종 — repository 명칭 / API 시그니처 deviations:
- list 응답 envelope (Spring `Page<>` JSON shape — `content`/`pageable`/`totalElements`)이 PR 12b1 `AdminMemberQueryController`와 일관한지:
- detail 응답 nested DTO 패키지 위치:
- orphan tolerance — 부재 시 응답 필드 형태(null vs `{}` empty object) 결정:

### 13.4 G4 — C-2 PATCH

- _G4 commit: <SHA pending>_
- `AdminReportQueryService.buildDetailResponse(report)` public method 추출 deviations:
- `from == to` no-op 거부 메시지 형태(`message`에 status 노출):

### 13.5 G5 — spec §12 catch-up + features.md 정정

- _G5 commit: <SHA pending>_
- features.md C-1 정정 항목 (24h 중복 방지 메커니즘 명확화 / Member-only 명시 / SELF_REPORT_FORBIDDEN 추가):
- features.md C-2 정정 항목 (PATCH 응답 shape이 detail과 동일 / `ReportStatusChanged` 미발행 명시 / 전이 매트릭스 명시):
- roadmap.md PR 13 status ✅ + M5 milestone 완료 표시:

### 13.6 Deviations / ground-truth 정정 (implementer 발견)

(PR 12b2 §12.5 패턴 follow — 빌드 중 발견되는 spec과 ground-truth 불일치 모두 본 섹션에 기록)

### 13.7 Future polish 잔존

- `ReportStatusChanged` 이벤트 발행 (consumer 도입 시 별 PR — D4 evolve).
- `partyroom_admin_action.REPORT_REVIEWED` 액션 도입 (신고 처리 → 자동 partyroom action chain 시 — D5 evolve).
- 24h 중복 unique index (abuse 패턴 발견 시 — D1 evolve).
- Bulk PATCH (여러 신고 일괄 처리).
- 신고 첨부 파일 (GCS URL 컬럼 확장).
- 신고자 측 본인 신고 이력 조회 (`GET /partyrooms/{id}/reports/me`).
- 어드민 측 알림 (D-3 push 인프라 후).
- list `resolved_at` / `reviewed_at` sort 키 추가.
- Optimistic lock(version) — 동시 어드민 race 방지.
- Description content sanitization (XSS / abuse).
- Report metrics dashboard (E-2 metrics).

---

**다음 단계:** 본 spec이 사용자 승인되면 `superpowers:writing-plans`로 구현 plan 생성 (`docs/superpowers/plans/2026-04-28-admin-platform-pr13.md`).
