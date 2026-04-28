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
- **G1.1 commit `b0105119`**: code reviewer follow-up — `resolve()` dead defensive branch 삭제(matrix가 PENDING→RESOLVED 차단하므로 `if (this.reviewedByAdministratorId == null) ...` 분기는 도달 불가) + `@Column(length=16/32)` ENUM 컬럼 sibling consistency 보강(PartyroomAdminActionType / UserActivityEventType 등 동형) + `hold(byAdministratorId)` callsite-symmetry 의도 주석(`@SuppressWarnings("unused")` + javadoc — REVIEWING→PENDING 전이는 reviewer id를 보존하므로 인자는 의도적 미사용) + resolve/dismiss javadoc에 D3.1 contract 명시(`resolutionNote` non-blank 검증은 caller(G4 controller/service) 책임이지 도메인 책임 아님 — `RESOLUTION_NOTE_REQUIRED(RPT-003)`는 service-layer guard에서 throw).
- DDL 변경 deviations: 없음 (schema.md §4.8.1 그대로).
- **enum 패키지 위치**: `app/src/main/java/com/pfplaybackend/api/administration/domain/enums/` 채택. spec/plan 초안의 `domain/value/`는 misnomer — 기존 administration BC convention 검사 결과 status/category-style enums(`PartyroomAdminActionType`, `AdminActionTargetType`, `AdminPenaltyType`, `BulkActionType`, `UserActivityEventType`)는 모두 `domain/enums/`이고 `domain/value/`는 first-class VO(`AdministratorId`, `AdminRole`, `JsonMetadata*`) 전용. 후속 G2-G4 신규 enum도 `domain/enums/` follow.
- **BaseEntity**: 미상속 (Plan §3 Option A). V13 DDL은 `created_at`만 보유, `updated_at` 부재. BaseEntity가 audit `updated_at`을 추가하면 Hibernate가 비존재 컬럼 write 시도 → JPA 부트 실패 risk(`hbm2ddl.auto=validate` mismatch). 엔티티가 자체 `private LocalDateTime createdAt;` 보유 + factory에서 `LocalDateTime.now()` 세팅. spec §4.1 코드 예시는 `extends BaseEntity`로 적혔으나 plan §3가 정정 — plan이 canonical.
- **`AdminReportException` 단일 enum**: 단일 채택. 7 entries(RPT-001~007) 모두 본 enum에 수용. `PARTYROOM_NOT_FOUND`는 G2 시점에 기존 partyroom BC enum 또는 `AdminException`(ADM-006) 재사용 검토 — G1에서는 별도 추가 안 함(G2에서 `PartyroomException.NOT_FOUND_ROOM(PTR-001)` 재사용 결정 — §13.2 참조).
- **Exception interface naming**: spec/plan의 `ExceptionType` + `getCode()`는 misnomer. 실제 ground-truth는 `DomainException` + `getErrorCode()`(예: `AdminMemberException` 패턴). G1 `AdminReportException`도 `implements DomainException` + `getErrorCode()` 사용.
- **모듈 path correction**: spec/plan은 `administration/src/main/...`로 적혔으나 `administration`은 별 Gradle 모듈 아님 — `app/src/main/java/com/pfplaybackend/api/administration/...` 패키지. 모든 G1 file은 `app` 모듈 안에 land. G2-G4 신규 file도 동일 위치 follow.
- **JPA ENUM column 매핑**: V13 DDL은 MySQL `ENUM(...)`, 엔티티는 `@Enumerated(STRING) + @Column(length=N)` 사용(V4/V6/V8 precedent). `columnDefinition = "ENUM(...)"` 미사용 — Flyway가 DDL 소유, JPA는 string write/read만 책임. `hbm2ddl.auto=validate` mode에서 안전(prod precedent).
- **resolutionNote 검증 위치 (D3.1 contract)**: 도메인은 빈 note도 영속. G4 service-layer guard가 `if (target.isTerminal() && isBlank(note)) throw RESOLUTION_NOTE_REQUIRED(RPT-003)` 강제. 도메인 메서드 javadoc(G1.1 polish)에 호출자 책임 명시.

### 13.2 G2 — C-1 유저 endpoint

- **G2 commit `64e2a1c3`**: `PartyroomReportCommandService` + `PartyroomReportCommandController` + `PartyroomReportCreateRequest/Response` DTO + `PartyroomData.isReportable()` 신설 + 24h 중복/self-report/active/Guest 차단 검증 + WebMvc + IT.
- **G2.1 commit `a2454d09`** (reviewer follow-up polish): `PartyroomRepository` direct injection → `PartyroomAggregatePort` 전환(admin BC 4 service 일관) + Guest/active 검증을 deny-list → allow-list 패턴으로 재작성.
- **`PartContextAspect` 비-coverage**: administration BC service는 `PartContextAspect` weave 대상 아님 — service 내부에서 `ThreadLocalContext.getAuthContext()` 호출 시 `IllegalStateException` throw. 패턴 정착: auth context 추출은 controller layer에서 수행하고 primitive args(reporterTier, reporterUserAccountId)로 service에 전달.
- **Auth 추출 — Member context**: controller가 `SecurityContextHolder.getContext().getAuthentication()`를 read → `CustomJwtAuthenticationToken`으로 cast → `getUserId().getUid()` (Long) + `getAuthorityTier()` 추출. service signature: `create(Long partyroomId, PartyroomReportCreateRequest request, AuthorityTier reporterTier, Long reporterUserAccountId)` (4-arg). spec/plan의 `MemberContext` 추상 명칭 — 실제로는 별 추상 없이 primitive 2개로 분해.
- **`PartyroomData.getHostId()` 반환 타입**: `UserId` (Long 직접 아님). Self-report 비교는 `partyroom.getHostId().getUid().equals(reporterUserAccountId)`로 unwrap.
- **`PartyroomStatus` enum**: 3 값(`ACTIVE`, `SUSPENDED`, `TERMINATED`). spec §3.1의 "비-active" 표현이 모호했으나 ground-truth 결정.
- **`PartyroomData.isReportable()` 신설** (party module 내부, non-breaking pure-read): allow-list — `status == ACTIVE`만 reportable. G2.1 polish에서 deny-list(`status != TERMINATED && status != SUSPENDED`) → allow-list로 전환 — future status 추가 시 안전(default 거부).
- **`PARTYROOM_NOT_FOUND` exception 재사용**: 신설 안 함. `PartyroomException.NOT_FOUND_ROOM(PTR-001)` party BC enum 재사용 — cross-context loose-ref read 패턴이라 administration BC가 party BC exception을 import해도 layered violation 아님(read-only). `AdminReportException`에 추가하지 않음.
- **Guest 차단 — service-layer guard + party BC exception 재사용**: `if (reporterTier != AuthorityTier.AM && reporterTier != AuthorityTier.FM) throw PartyroomException.RESTRICTED_AUTHORITY(PTR-005)` — 별 SpEL bean(`@memberAuth.isMember()`) 신설 안 함. allow-list 패턴(G2.1 polish) — Guest/Anonymous 외 future tier 추가 시 안전 default 거부.
- **`PartyroomAggregatePort` 사용 (G2.1)**: `AdminPartyroomCommandService` / `AdminPartyroomQueryService` / `AdminCrewPenaltyCommandService` precedent — 4개 admin service가 일관 port 경유. 직접 `PartyroomRepository` 주입은 admin BC 외부 의존성 노출 + 테스트 stubbing 비대칭이라 회피.
- **WebMvc test scaffolding**: `PartyroomReportCommandController`은 user-facing endpoint이므로 `AbstractAdminWebMvcTest` 미상속. bespoke `TestSecurityConfig` 사용(authenticated Member + Guest 차단 시나리오 직접 wire).
- **IT cleanup pattern**: PR 12b2 `deleteAll()` unscoped 패턴 follow — V5 super-admin SEED은 `administrator` 테이블에 land하지 `member`/`user_account`에 영향 없음을 verify. `partyroom_report` / `partyroom` / `member` / `user_account` 4 테이블 unscoped delete 안전.
- description max length 2000 — DTO `@Size(max=2000)` 적용. DB column은 `TEXT`(64KB)이므로 column-level constraint 별도 추가 안 함 — `@Size`가 first line of defense.

### 13.3 G3 — C-2 list/detail

- **G3 commit `d14fc603`**: `AdminReportQueryService` + `AdminReportQueryController` + `AdminReportSummaryResponse` + `AdminReportDetailResponse`(nested Reporter/Partyroom/Host/Review records) + `AdminReportListQuery` + `PartyroomReportRepository` QueryDSL impl + cross-context loose-ref read 4종(member×2, userAccount, partyroom) + WebMvc + IT.
- **G3.1 commit `ab460fae`** (reviewer follow-up polish): `createdTo` 종일 inclusion 경계 IT 2건 추가 + `SORT_PATTERN` 상수 통합(controller가 `AdminReportListQuery.SORT_PATTERN` import — 이전엔 controller-private 중복).
- **`MemberRepository.findByUserAccountId(Long)`**: Spring Data method query를 JpaRepository에 직접 추가 — 별 `@Query` JPQL 불필요. spec §3.3의 `findByUserAccountId(...)` 표현 그대로.
- **MemberData → nickname 접근 경로**: `member.getProfileData().getBio().getNicknameValue()`. `Bio.getNicknameValue()`가 내부 null-safe(profileData 존재 + bio 존재 가정)이지만 service는 `profileData == null` / `bio == null` 외부 null 가드를 추가(profile 미시드 fixture 케이스 tolerance).
- **`QPartyroomReportData` Q-class 위치**: 프로젝트-wide QueryDSL annotation processor가 자동 생성 — `app/build/generated/sources/annotationProcessor/java/main/com/pfplaybackend/api/administration/domain/entity/data/QPartyroomReportData.java`. build script 변경 0(precedent: `QAdminMemberData` / `QPartyroomData` 동형).
- **`AdminReportListQuery` placement**: `app/.../administration/adapter/in/web/dto/AdminReportListQuery.java` (PR 12b1 `AdminMemberListQuery` mirror). controller-bound Bean Validation record.
- **`AdminReportListQuery` `sortKey` 필드 미포함**: controller가 `Pageable.Sort`를 직접 build해서 service에 pass(service signature는 Pageable 수용). repo는 `pageable.getSort().getOrderFor("createdAt")`로 derive. `SORT_CREATED_AT_DESC/ASC` 상수는 record class에 거주 — controller import 가능.
- **`createdTo` upper bound 의미론**: `createdAt < createdTo.plusDays(1).atStartOfDay()` — 종일(end-of-day) inclusive. `2026-04-28` 입력 시 `2026-04-28T23:59:59`까지의 row 포함. spec/plan은 단지 "LocalDate" optional만 명시 — G3 implementer가 inclusive UX 채택.
- **`PartyroomReportRepositoryImpl` packaging**: `app/.../administration/adapter/out/persistence/impl/PartyroomReportRepositoryImpl.java`(`AdminMemberQueryRepositoryImpl`, `AdminPartyroomQueryRepositoryImpl` mirror). `adapter/out/persistence/` 하위 `impl/` 서브패키지 convention follow.
- **Orphan tolerance — nested record always built, internal nullable**: spec §5는 "응답 필드 null"만 명시. ground-truth 결정: nested `Reporter` / `Partyroom` / `Host` / `Review` record 자체는 항상 build(except: Host는 `partyroom.hostId == null` 시 전체 null), internal 필드(email, nickname, title 등)만 individually nullable. 503 fail-fast 안 함 — cross-context entity 누락은 normal(탈퇴/룸 종료).
- **`buildDetailResponse(PartyroomReportData)` 가시성**: `public` (G3 구현 시점에 G4 PATCH 응답 재사용 의도). spec §5(b) 채택 — Command service가 Query service inject. BC 내부 service-to-service 결합은 PR 12b1 precedent(`AdminMemberCommandService` ↔ `AdminMemberQueryService`).
- **list 응답 envelope**: PR 12b1 `AdminMemberQueryController`와 동일한 `ApiCommonResponse<Page<AdminReportSummaryResponse>>` shape (Spring `Page<>` JSON `content` / `pageable` / `totalElements` / `totalPages` / `size` / `number` 그대로).
- **G3.1 polish: 2 IT cases 추가** — `createdTo`가 입력 day end-of-day까지 inclusive(boundary case) + date-range 필터 happy/exclusion. `SORT_PATTERN` controller-private 중복을 `AdminReportListQuery.SORT_PATTERN` 단일 상수로 consolidate(reviewer 권고).
- **MockitoExtension strict-stubbing trap**: `Mockito.when(...)` 호출이 outer `BDDMockito.given(...)` chain 안에 nested되면 `UnfinishedStubbingException` throw. 우회: mock-bearing 중간 값들을 outer `given(...)` 호출 *전*에 build해서 변수에 저장 후 stubbing chain은 한 단계만 — `AdminReportQueryServiceTest` cross-context mock 다중 케이스에서 발견.

### 13.4 G4 — C-2 PATCH

- **G4 commit `56686ce3`**: `AdminReportCommandService` + `AdminReportCommandController` + `AdminReportStatusUpdateRequest` DTO + `RESOLUTION_NOTE_REQUIRED(RPT-003)` service-layer guard + 4 transition switch dispatch + `AbstractAdminWebMvcTest` 등록 확장 + WebMvc + IT 10건.
- **`AdminContext`는 service-field, NOT method-parameter**: PR 12b2 `AdminMemberTierCommandService` mirror. controller는 `AdminContext`를 inject 안 함. service signature: `changeStatus(Long reportId, AdminReportStatusUpdateRequest request)` (2-arg). service field로 `private final AdminContext adminContext;`. spec §3.4 코드 예시는 `(reportId, req, ctx)` 3-arg로 적혔으나 PR 12b2 정착 패턴이 canonical.
- **IT 어드민 시드 — `@MockBean AdminContext`**: `given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID)` 패턴으로 SecurityContext 우회. `AdminMemberTierCommandServiceIT` mirror — `@WithMockUser` + `SecurityContextHolder` 직접 stub 없이 service-layer가 보는 `AdminContext`만 모킹.
- **D3.2 reviewer-preservation IT**: 별 admin id 분리(`FIRST_REVIEWER_ADMIN_ID = 88L`은 JPQL로 직접 시드 — REVIEWING entry 시점 set / `ADMIN_ID = 99L`은 `@MockBean AdminContext`가 반환). REVIEWING→RESOLVED와 REVIEWING→PENDING(hold) 시나리오 모두에서 `report.getReviewedByAdministratorId() == 88L`로 보존 검증 — 두 번째 검토 admin이 first reviewer를 덮어쓰지 않음.
- **IT 케이스 수 = 10** (plan 추정 ~8): G4 implementer가 DISMISSED→REVIEWING terminal-rejection 1건과 RESOLVED→DISMISSED 1건을 추가 — RESOLVED/DISMISSED 두 terminal 모두에서 invalid transition reject가 symmetric하게 cover되도록 보강.
- **`save(report)` 명시 — defensive flush rationale**: JPA dirty-checking이 `@Transactional` 종료 시 자동 flush하므로 `save()`는 redundant — but G4는 *의도적으로 explicit save* 호출. 이유: (1) 순서 문서화(mutation → flush → cross-context query in `buildDetailResponse`), (2) 동일 트랜잭션 내 이후 cross-context read가 fresh state 보장, (3) reader가 transition → audit row 갱신 흐름을 명시적으로 인지.
- **`from == to` no-op 거부 메시지**: `INVALID_STATE_TRANSITION(RPT-002)` enum의 default message에 from/to status는 노출하지 않음(현재 `ApiErrorResponse` shape이 detail params 미지원 — PR 12b2 `TIER_UNCHANGED` 패턴 일관). 향후 reviewer 권고 시 detail params 확장 검토.
- **`buildDetailResponse(report)` 재사용**: G3에서 `public`으로 노출된 `AdminReportQueryService.buildDetailResponse(PartyroomReportData)`를 Command service가 inject + 호출 — PATCH 200 응답이 GET /admin/reports/{id}와 동일 shape 보장.

### 13.5 G5 — spec §13 catch-up + features.md 정정 + roadmap M5 완료

- **G5 commit `b867c3b2`** (3 files, +106/-34): PR 13 design.md §13.1~§13.4 backfill (G1-G4 SHAs + 누적 ground-truth deviations) + §13.6 cross-chunk index 재구성 + §13.7 deferred reviewer follow-ups 추가 + features.md §6.C-1/§6.C-2 본문 정정 + roadmap.md PR 12 / PR 13 ✅ + M5 milestone 완료 표기.
- **features.md C-1 정정 항목**: 24h 중복 방지 — 앱 검증 채택(D1) 명시, DB unique constraint 미사용 / Member-only(D1.5) — Guest 차단은 service-layer guard + `PartyroomException.RESTRICTED_AUTHORITY(PTR-005)` 재사용 / `SELF_REPORT_FORBIDDEN(RPT-006)` D2 추가 / `PARTYROOM_NOT_REPORTABLE(RPT-005)` allow-list `status == ACTIVE` 명시 / `PartyroomException.NOT_FOUND_ROOM(PTR-001)` cross-context 재사용 명시.
- **features.md C-2 정정 항목**: PATCH 응답 shape — `AdminReportDetailResponse` (GET detail과 동일 shape) cross-context join 4종 포함 / 전이 매트릭스(D3) 5 transitions 명시 + `from == to` 거부 / `resolutionNote` 검증 위치(D3.1) — service-layer guard `RESOLUTION_NOTE_REQUIRED(RPT-003)` / `reviewedByAdministratorId` 보존(D3.2) — hold 시 first reviewer 유지 / `ReportStatusChanged` 미발행(D4) / `partyroom_admin_action` 미기록(D5) / list cross-context join 미포함(D7) — summary는 raw fields만 / list cross-field 검증 `INVALID_LIST_QUERY(RPT-004)` / `createdTo` end-of-day inclusive 의미론.
- **roadmap.md 정정**: §9.1 PR Sequence 표 — PR 12 / PR 13 row에 ✅ 마커 추가. §9.4 Milestone — `M5 (PR 12-13) ✅ — 완료` 표기.

### 13.6 Deviations / ground-truth 정정 (implementer 발견)

PR 12b2 §12.5 패턴 follow — 모든 chunk별 deviation은 §13.1~§13.4에 inline 기록. 본 절은 cross-chunk 공통 패턴 + index 역할.

- **모듈 / 패키지 명명 정정** (G1 발견, G2-G4 follow): `administration`은 `app` Gradle 모듈 내부 package. spec/plan의 `administration/src/main/...`는 misnomer. 모든 PR 13 file은 `app/src/main/java/com/pfplaybackend/api/administration/...` 거주. → §13.1
- **enum 패키지 — `domain/enums/`** (sibling convention): `domain/value/`는 first-class VO 전용. → §13.1
- **Exception interface — `DomainException` + `getErrorCode()`**: spec/plan의 `ExceptionType` + `getCode()`는 misnomer. → §13.1
- **Auth context 추출 패턴**: `PartContextAspect`가 administration BC service를 weave하지 않음 → controller layer에서 SecurityContext read + primitive args로 service에 전달. user-facing(C-1)은 `CustomJwtAuthenticationToken`, admin(C-2)은 `AdminContext` (service-field). → §13.2 / §13.4
- **Cross-context exception 재사용 (read-only loose-ref)**: party BC `PartyroomException.NOT_FOUND_ROOM(PTR-001)` / `RESTRICTED_AUTHORITY(PTR-005)` 재사용. `AdminReportException`에는 추가 안 함. → §13.2
- **`PartyroomData.getHostId()` 반환 타입**: `UserId` (Long unwrap via `.getUid()` 필요). → §13.2
- **`PartyroomData.isReportable()` 신설** (allow-list `status == ACTIVE`, G2.1 polish 후): party module 내부, non-breaking pure-read 메서드. → §13.2
- **`PartyroomAggregatePort` 채택 (G2.1 polish)**: admin BC 4 service 일관 — direct `PartyroomRepository` 주입 회피. → §13.2
- **Cross-context loose-ref 4종 read 시그니처**: `MemberRepository.findByUserAccountId(Long)` Spring Data method query / nickname access는 `member.getProfileData().getBio().getNicknameValue()` (외부 null 가드 포함). → §13.3
- **Orphan tolerance shape**: nested record 자체는 항상 build, internal 필드만 nullable (Host record는 hostId null 시 전체 null). → §13.3
- **`createdTo` end-of-day inclusive** (`< plusDays(1).atStartOfDay()`): UX 결정. → §13.3
- **`AdminReportListQuery.sortKey` 필드 미포함**: controller가 Pageable.Sort build, service는 Pageable 수용. → §13.3
- **`buildDetailResponse(report)` public method**: G3에서 G4 재사용 의도로 노출. → §13.3 / §13.4
- **`AdminContext`는 service-field, NOT method-parameter**: PR 12b2 mirror. service signature 2-arg. → §13.4
- **save(report) 명시는 defensive flush** (mutation → cross-context query 순서 문서화). → §13.4
- **MockitoExtension strict-stubbing trap**: outer `given(...)` chain 안에 `when(...)` nested 회피. → §13.3

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

#### Deferred reviewer follow-ups (PR 13 시점 미흡수, future PR 후보)

- **G1 M1**: `hold(byAdministratorId)` 인자 callsite-symmetry 의도 — javadoc + `@SuppressWarnings("unused")`로 표현했으나 reviewer는 별 메서드 분리(`hold()` no-arg) 또는 record 패턴(`HoldRequest`)을 권고. 현 채택은 sibling 메서드(startReview/resolve/dismiss)와 시그니처 대칭 우선.
- **G2 M3**: `ReporterContext` 추상화(reporterTier + reporterUserAccountId 묶음 record) — 현재는 service 4-arg primitive. controller-layer에서 추출 책임이 분산되므로 record 도입 시 인터페이스 안정성 +. PR 14 프런트 진입 후 user-facing endpoint가 추가될 때 상위 패턴으로 reconsider.
- **G3 M2-M7**: cosmetic — (M2) `AdminReportListQuery` Bean Validation message i18n 키 / (M3) `Sort.Order` factory를 record 정적 메서드로 / (M4) `buildDetailResponse` 응답 record를 outer class assembler로 분리 / (M5) repo `BooleanExpression` null-safe wrapper 유틸 / (M6) `MemberRepository.findByUserAccountId` projection으로 nickname-only fetch / (M7) WebMvc test의 `@WithMockUser` 대신 `JwtMockBeanFactory` 도입. 모두 acceptable-as-is.
- **G4 M1-M4**: cosmetic — (M1) `INVALID_STATE_TRANSITION` exception detail에 from/to enum 노출 (`ApiErrorResponse` shape 확장 필요) / (M2) `RESOLUTION_NOTE_REQUIRED` 검증을 controller `@AssertTrue` validator로 / (M3) IT 10건의 fixture 시드 helper builder 패턴 도입 / (M4) `save(report)` 호출을 javadoc 주석으로 의도 노출. 모두 acceptable-as-is.

---

**다음 단계:** 본 spec이 사용자 승인되면 `superpowers:writing-plans`로 구현 plan 생성 (`docs/superpowers/plans/2026-04-28-admin-platform-pr13.md`).
