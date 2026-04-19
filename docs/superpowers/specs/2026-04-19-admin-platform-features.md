# PFPlay Admin Platform — Feature Specs (§6, §7)

> Companion to `2026-04-19-admin-platform-design.md`. 본 문서는 §6 Features + §7 Listing UI를 다룬다.
> A~H 카테고리별 기능 명세 + 파티룸 목록 UI 템플릿 (유저 목록 등에도 적용 가능).

## 6. Feature Specs by Category

### 6.A 유저 관리

#### A-1. 유저 목록/검색

**API**: `GET /api/v1/admin/members`

Query parameters:
- `email` (partial match, LIKE) — optional
- `tier` — ENUM filter, optional
- `joined_from`, `joined_to` — DATE range, optional
- `page`, `size` (default 50)
- `sort` — `created_at_desc` (default), `created_at_asc`, `last_activity_desc` (활동 이력 테이블 join)

Response:
```json
{
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
      "withdrawn": false
    }
  ],
  "pageInfo": { "page": 0, "size": 50, "totalElements": 1234 }
}
```

권한: `@PreAuthorize("hasRole('ADMIN')")`

#### A-2. 유저 상세

**API**: `GET /api/v1/admin/members/{memberId}`

Response:
```json
{
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
    "introduction": "...",
    "avatarBody": { ... },
    "avatarFace": { ... },
    "avatarIcon": { ... },
    "walletAddress": "0x..."
  },
  "authorityTier": "FM",
  "activities": [
    { "type": "DJ_PNT", "score": 120 },
    { "type": "ROOM_ACT", "score": 45 }
  ],
  "recentActivityLog": [
    { "eventType": "PARTYROOM_ENTERED", "partyroomId": 1, "occurredAt": "..." },
    ...
  ]
}
```

- `recentActivityLog`: `user_activity_log`에서 최근 30건 (유저 상세 조회 시 내부적으로 join)
- 권한: `@PreAuthorize("hasRole('ADMIN')")`

#### A-3. 티어 조정

**API**: `PATCH /api/v1/admin/members/{memberId}/tier`

Request: `{ "tier": "FM" }`

처리:
1. 기존 tier 읽기
2. `member.authority_tier` UPDATE
3. 도메인 이벤트 `MemberTierChanged(memberId, oldTier, newTier, byAdministratorId)` 발행
4. 리스너가 `user_activity_log`에 `TIER_CHANGED` + `ADMIN_ACTED_ON` 2건 기록
5. 리스너가 `partyroom_admin_action`에 `action_type='CHANGE_MEMBER_TIER'` 1건 기록

권한: `@PreAuthorize("hasRole('ADMIN')")`

#### A-4. 탈퇴 처리 (비식별화)

**API**: `POST /api/v1/admin/members/{memberId}/withdraw`

처리:
1. Member의 userAccountId 조회
2. `UserAccount.withdraw()` 호출 (IAM aggregate 메서드)
   - `email = "withdrawn-{userAccountId}@pfplay.local"`
   - `password_hash = NULL`
   - `last_login_at = NULL` (또는 유지 — 정책 결정 필요)
   - `withdrawn_at = NOW()`
3. Member profile 익명화:
   - `profileData.nickname = "탈퇴한 회원"`
   - `profileData.introduction = NULL`
   - avatar 기본값으로 리셋
   - `walletAddress = NULL` (지갑 노출 방지)
4. Crew, DJ, Playback 이력은 **그대로 유지** (userId 참조 보존 → orphan 방지)
5. `UserAccountWithdrawn(userAccountId)` 이벤트 발행
6. `user_activity_log` WITHDREW 기록

**정책**: 실제 row 삭제는 X. userAccountId는 영구 보존 (FK 없는 상태에서 다른 테이블의 참조 무결성 간접 보호). 재가입 가능: 새 이메일이면 새 UserAccount 생성.

권한: `@PreAuthorize("hasRole('ADMIN')")`

### 6.B 파티룸 관리

#### B-1. 룸 목록

**API**: `GET /api/v1/admin/partyrooms` — §7 상세 참고

#### B-2. 룸 상세

**API**: `GET /api/v1/admin/partyrooms/{partyroomId}`

Response (발췌):
```json
{
  "partyroomId": 1,
  "title": "Main Stage",
  "status": "ACTIVE",
  "displayFlag": "FEATURED",
  "hostUserAccountId": 1,
  "hostNickname": "운영자",
  "crewCount": 42,
  "lastActivityAt": "...",
  "stageType": "MAIN",
  "playback": {
    "isActivated": true,
    "currentTrack": { ... },
    "currentDjCrewId": 99
  },
  "crews": [
    { "crewId": 99, "memberId": 123, "gradeType": "HOST", "nickname": "...", "enteredAt": "..." },
    ...
  ],
  "djQueue": [
    { "djId": 1, "crewId": 99, "playlistName": "...", "orderNumber": 0 }
  ],
  "recentPenalties": [
    { "id": 10, "crewId": 88, "penaltyType": "CHAT_BAN_30_SECONDS", "punisherType": "ADMIN", "reason": "...", "date": "..." },
    ...
  ],
  "recentReports": [
    { "id": 5, "category": "INAPPROPRIATE_CONTENT", "status": "PENDING", "reporterUserAccountId": 456, "createdAt": "..." }
  ],
  "recentAdminActions": [
    { "actionId": 10, "actionType": "SET_FEATURED", "administratorId": 1, "occurredAt": "..." }
  ]
}
```

#### B-3. 룸 강제 종료

**API**: `POST /api/v1/admin/partyrooms/{partyroomId}/terminate`

Request: `{ "reason": "저작권 침해 확인" }`

처리:
1. `Partyroom.terminate()` — status=TERMINATED, isTerminated=true (만약 호환성으로 남겨둠)
2. 활성 crew 전부 자동 퇴장 처리
3. `PartyroomTerminated(partyroomId, terminatedByAdministratorId, reason)` 이벤트 발행
4. Administration 리스너가 `partyroom_admin_action` INSERT `action_type='TERMINATE_PARTYROOM'`, `reason=...`

#### B-4. 룸 일시 정지/재개

**API**: 
- `POST /api/v1/admin/partyrooms/{partyroomId}/suspend` — `{ reason }`
- `POST /api/v1/admin/partyrooms/{partyroomId}/restore` — 재개

SUSPEND 시: 신규 crew 입장 거부. 기존 crew는 그대로 유지. 채팅/리액션/DJ는 작동?
- **결정 필요**: SUSPEND 시 채팅/리액션도 막을지? 아니면 "입장만" 막고 내부는 정상?
- 권장: 입장만 막음 + 어드민이 개별 페널티로 추가 조치. SUSPEND는 "이 룸 관찰/조사 중" 의미로 가볍게.

#### B-5. 룸 메타데이터 수정

**API**: `PATCH /api/v1/admin/partyrooms/{partyroomId}`

Request: `{ "title": "...", "introduction": "...", "playbackTimeLimit": 30 }` (부분 수정)

처리:
- Partyroom 엔티티 상의 값 변경 (host가 아닌 admin도 수정 가능)
- 도메인 이벤트 `PartyroomMetaUpdatedByAdmin` 발행
- `partyroom_admin_action` UPDATE_PARTYROOM_META + metadata에 old/new

#### B-6. Display flag 변경

**API**: `PATCH /api/v1/admin/partyrooms/{partyroomId}/display-flag`

Request: `{ "flag": "FEATURED" | "HIDDEN" | "NORMAL" }`

처리:
- `partyroom.display_flag` UPDATE
- `partyroom_admin_action` `action_type='SET_FEATURED'` 등

#### B-7. 어드민 페널티 부과

**경로**: 기존 `CrewPenaltyCommandController` 재사용.

차이점:
- 어드민이 호출 시 `@PreAuthorize`에 `hasRole('ADMIN')` or 기존 crew grade 권한 중 하나 통과
- 서비스에서 요청 주체가 admin인지 확인 (SecurityContext 기반)
- `crew_penalty_history` INSERT with `punisher_type='ADMIN'`
- Administration 리스너가 `partyroom_admin_action` action_type='PENALIZE_CREW' 추가 기록 with `metadata.crew_penalty_history_id`

#### B-8. 일괄 액션

**API**: `POST /api/v1/admin/partyrooms/bulk-action`

Request:
```json
{
  "partyroomIds": [1, 2, 3],
  "action": "TERMINATE" | "SUSPEND" | "SET_HIDDEN",
  "reason": "...",
  "skipErrors": true | false
}
```

처리:
- 각 partyroomId에 대해 반복 처리
- `skipErrors=true`면 개별 실패 무시하고 진행 (기본)
- 결과 응답:
```json
{
  "results": [
    { "partyroomId": 1, "success": true },
    { "partyroomId": 2, "success": false, "error": "TERMINATED 상태에서 SUSPEND 불가" },
    { "partyroomId": 3, "success": true }
  ]
}
```

각 성공은 `partyroom_admin_action`에 개별 기록 (일괄이지만 감사 레코드는 N건).

권한: 해당 action 타입에 따른 role 필요. `hasRole('ADMIN')` 기본.

### 6.C 모더레이션

#### C-1. 파티룸 신고 접수 (유저용)

**API**: `POST /api/v1/partyrooms/{partyroomId}/reports`

권한: 인증된 Member (크루)

Request: `{ "category": "HARASSMENT", "description": "..." }`

처리:
- 해당 partyroom active인지 확인
- `partyroom_report` INSERT status=PENDING
- 신고자(reporterUserAccountId) 동일 룸 동일 카테고리 24h 내 중복 신고 방지 (unique constraint 또는 앱 검증)

응답: 201 + `{ "reportId": 123 }`

#### C-2. 어드민 신고 목록/검토

**API**: 
- `GET /api/v1/admin/reports?status=PENDING` — 목록
- `GET /api/v1/admin/reports/{reportId}` — 상세 (신고자 프로필 정보 + 룸 정보)
- `PATCH /api/v1/admin/reports/{reportId}` — status 전이

Status 전이 요청 예:
```json
{
  "status": "RESOLVED",
  "resolutionNote": "해당 호스트에게 경고 메시지 전송함. 룸은 유지."
}
```

처리:
- `status` 업데이트, `reviewedByAdministratorId` + `resolvedAt` 세팅
- `ReportStatusChanged` 이벤트 발행 (필요 시)

#### C-3. 금지어 관리

**Future scope — MVP 비포함**: 지금은 일단 플레이스홀더만.

- 테이블 `banned_word (id, word, applied_to ENUM('CHAT','NICKNAME','ALL'), created_at)` 예상
- 적용 시점: 채팅 송신 WebSocket handler (거부), Profile 수정 API
- 관리 API: `GET/POST/DELETE /api/v1/admin/banned-words`

### 6.D 커뮤니케이션

**Future scope — MVP 비포함**: 설계만 언급.

#### D-1. 전체 공지

- 테이블 `announcement (id, title, body, published_at, expires_at, priority, target_audience, created_by_administrator_id)` 예상
- 관리 API: `GET/POST/PATCH/DELETE /api/v1/admin/announcements`
- 유저용 API: `GET /api/v1/announcements/active`
- 프런트 배너/팝업 렌더링 규칙은 응답에 포함 (`priority`, `display_type` 등)

#### D-2. 팝업/배너

공지 엔티티와 통합: `announcement.display_type ENUM('BANNER','POPUP','INLINE')` 같은 구조

#### D-3. 푸시 알림

- 테이블 `push_notification_schedule (id, title, body, target, scheduled_at, sent_at, status)` 예상
- 관리 API: `GET/POST /api/v1/admin/push-notifications`
- 인프라: FCM/APNs — 별도 구축 필요 (구축 전까지는 MVP에서 제외)

### 6.E 시스템 운영

#### E-1. 유지보수 모드

**API**:
- `GET /api/v1/admin/system/config/maintenance` — 현재 상태 조회
- `PATCH /api/v1/admin/system/config/maintenance` — 토글

Request: `{ "enabled": true, "message": "시스템 점검 중입니다." }`

처리:
- `system_config` UPDATE (`maintenance.enabled`, `maintenance.message`)
- 캐시 무효화 이벤트 발행

Spring Filter:
- 매 요청 시 `SystemConfigService.isMaintenanceMode()` 체크 (Redis/in-memory 캐시)
- `/api/v1/admin/**`, `/actuator/health` 제외하고 503 응답
- Response body: `{ "message": config['maintenance.message'] }`

#### E-2. 서비스 메트릭

**API**: `GET /api/v1/admin/metrics/summary`

Response:
```json
{
  "activePartyroomCount": 42,
  "totalCrewsOnline": 350,
  "pendingReportCount": 5,
  "totalMembersToday": 12000,
  "newMembersToday": 45,
  "newPartyroomsToday": 8,
  "mainStage": { "isActivated": true, "crewCount": 40 }
}
```

구현:
- SQL 집계 쿼리 (일회성) 또는 Redis 캐시된 카운터
- MVP는 실시간 SQL로 단순 구현, 부하 보이면 캐시 도입
- 심화 메트릭 (DAU, 주간 트렌드 등)은 Amplitude 대시보드 링크 제공

#### E-3. Feature flag — 후순위

`system_config` 재활용 예상. 지금은 유지보수 모드만.

### 6.F 어드민 거버넌스 (슈퍼어드민 전용)

#### F-1. 어드민 목록

**API**: `GET /api/v1/admin/system/administrators`

권한: `hasRole('SUPER_ADMIN')`

#### F-1. 어드민 생성

**API**: `POST /api/v1/admin/system/administrators`

Request:
```json
{
  "email": "new-admin@pfplay.xyz",
  "nickname": "새 운영자",
  "includeMemberProfile": true
}
```

처리:
1. 이메일 중복 검사
2. UserAccount 생성 (providerType=LOCAL, 임시 password_hash=bcrypt(랜덤 8자))
3. Administrator 생성 (role=ADMIN, grantedBy=현재 슈퍼어드민)
4. `includeMemberProfile=true` 시 Member 생성 (default profile)
5. 응답에 임시 비번 1회 노출 — 안전 채널 전달 필요

Response:
```json
{
  "administratorId": 5,
  "userAccountId": 10,
  "memberId": 8,
  "tempPassword": "Xk9@aB2z",
  "message": "임시 비번은 첫 로그인 후 반드시 변경하세요."
}
```

#### F-1. 어드민 수정/비활성화

**API**: 
- `PATCH /api/v1/admin/system/administrators/{id}` — 정보 수정 (role 변경 등은 제한)
- `POST /api/v1/admin/system/administrators/{id}/revoke` — 권한 회수 (revokedAt 설정, 로그인 불가)
- `POST /api/v1/admin/system/administrators/{id}/reset-password` — 비번 재발급 (§5.6)

#### F-2. RBAC — MVP 2-role, 미래 확장

MVP는 SUPER_ADMIN/ADMIN 2-role. Permission 세분화 미래. `administrator.role` 컬럼 + enum 유지.

### 6.G 고객 지원

**Future scope**:
- 인앱 문의 폼: `support_ticket` 테이블 예상
- 어드민 응답 기능: `support_ticket_reply`
- MVP 시점엔 외부 채널 (이메일/디스코드) 활용 권장 — 인프라 간소화

### 6.H 가상 유저/데모

**기존 기능 유지** (`AdminUserController`, `AdminDemoController`):
- `@ConfigurationProperty` 또는 SPRING_PROFILES_ACTIVE 체크로 **prod에선 비활성화**
- local/dev 한정 사용
- 코드 삭제 X (개발자 편의 필요)
- 향후 feature flag (E-3)로 제어하는 것 고려

---

## 7. Listing UI Spec (파티룸 목록 기준 템플릿)

어드민의 **목록형 화면** (파티룸/유저/신고/공지 등)에 일관되게 적용할 패턴.

파티룸 목록을 대표 예시로 상세 정의하고, 유저 목록 등에는 동일 패턴의 변형으로 적용.

### 7.1 기본 레이아웃

```
┌────────────────────────────────────────────────────────────────┐
│ 메트릭 헤더 (§7.5)                                               │
│ [활성 룸: 42]  [접속 크루: 350]  [PENDING 신고: 5]  [Main: ON]    │
├────────────────────────────────────────────────────────────────┤
│ 세그먼트 탭 (§7.4)                                               │
│ [전체] [주의 필요] [유휴 룸] [인기 룸] [오늘 생성] [Main & Featured]│
├────────────────────────────────────────────────────────────────┤
│ 필터 바 (§7.2)                                                   │
│ [상태▼] [스테이지▼] [생성일범위] [호스트검색]  [↻ 새로고침]       │
├────────────────────────────────────────────────────────────────┤
│ 일괄 액션 바 (§7.6) — 선택 시 표시                               │
│ ☑ 3개 선택됨  [일괄 종료] [일괄 일시정지] [일괄 숨김]               │
├────────────────────────────────────────────────────────────────┤
│ 테이블 (§7.3)                                                    │
│ ☐ ID | Title | Stage | Host | Crew | DJs | Playback | Status |  │
│     | Created | LastActivity | Flag | [Actions]                 │
│ ...                                                              │
├────────────────────────────────────────────────────────────────┤
│ 페이지네이션                                                      │
│ ← 1 2 3 ... 50 →   per-page: [50 ▼]                             │
└────────────────────────────────────────────────────────────────┘
```

### 7.2 필터 (MVP: 기본 필터)

- 상태: ACTIVE / SUSPENDED / TERMINATED / 전체
- 스테이지 타입: MAIN / GENERAL / 전체
- 생성일 범위: date picker
- 호스트 검색: email partial match 또는 nickname search
- 기본값: 상태=ACTIVE, 나머지=전체

Query: `GET /api/v1/admin/partyrooms?status=ACTIVE&stageType=GENERAL&createdFrom=2026-01-01&host=...&page=0&size=50`

### 7.3 테이블 컬럼 (MVP)

| 컬럼 | 데이터 원천 | 정렬 가능? | 비고 |
|---|---|---|---|
| ☐ | — | — | 체크박스 (일괄 선택) |
| ID | `partyroom.partyroom_id` | 아니오 | monospace |
| Title | `partyroom.title` | 예 | 너무 길면 truncate |
| Stage | `partyroom.stage_type` | 아니오 | MAIN 뱃지 / GENERAL |
| Host | `partyroom.host_id` → member → nickname | 예 (nickname) | 클릭 시 유저 상세 이동 |
| Crew | `partyroom.crew_count` | 예 | active 크루 수 |
| DJs | DJ queue count | 예 | 별도 count 쿼리 |
| Playback | `partyroom_playback.is_activated` | 아니오 | ON/OFF 뱃지 |
| Status | `partyroom.status` | 아니오 | 뱃지 (초록/노랑/빨강) |
| Created | `partyroom.created_at` | 예 | relative time |
| Last Activity | `partyroom.last_activity_at` | 예 | relative time |
| Flag | `partyroom.display_flag` | 아니오 | FEATURED/HIDDEN 뱃지 |
| Actions | — | — | [상세] [종료] [정지] |

### 7.4 세그먼트 탭 (사전 정의 뷰)

| 탭 | 필터 조합 | 정렬 |
|---|---|---|
| **전체** | 기본 (ACTIVE) | 최근 활동 ↓ |
| **주의 필요** | PENDING/REVIEWING 신고 있음 OR 최근 24h 페널티 있음 | 신고 건수 ↓ |
| **유휴 룸** | status=ACTIVE, crew_count=0, last_activity_at > 1시간 전 | 유휴 기간 ↓ |
| **인기 룸** | status=ACTIVE, crew_count >= 10 | crew_count ↓ |
| **오늘 생성** | created_at >= 오늘 00:00 | 생성일 ↓ |
| **Main & Featured** | stage_type=MAIN OR display_flag=FEATURED | Main 우선 |

구현: 각 탭은 서로 다른 query param 세트로 동일 API 호출.

### 7.5 메트릭 헤더 (상단 요약)

- 활성 파티룸 수: `COUNT(partyroom WHERE status=ACTIVE)`
- 접속 크루 총합: `SUM(partyroom.crew_count WHERE status=ACTIVE)` — denormalized 덕에 즉시 계산
- PENDING 신고 수: `COUNT(partyroom_report WHERE status='PENDING')`
- Main Stage 재생 상태: `partyroom_playback WHERE partyroom_id=<main>`

API: `GET /api/v1/admin/metrics/summary` (§6.E-2)

### 7.6 일괄 액션

- 선택 UI: 테이블 각 행의 체크박스 + 상단 "전체 선택"
- 선택 개수 ≥1 시 상단 바 표시: "N개 선택됨" + 액션 버튼들
- 지원 액션 (MVP):
  - 일괄 종료 (`TERMINATE`)
  - 일괄 일시정지 (`SUSPEND`)
  - 일괄 숨김 (`SET_HIDDEN`)
- 각 액션 실행 전 confirm 모달 (사유 입력 포함)
- API: `POST /api/v1/admin/partyrooms/bulk-action` (§6.B-8)

### 7.7 새로고침 전략 (폴링)

MVP: **수동 새로고침** 버튼 + 선택적 자동 폴링 (5~10초)

- 자동 폴링 기본 OFF (UI 토글)
- 새로고침 중 현재 페이지/필터/선택 상태 유지
- 불필요한 재렌더 방지: ETag 또는 last-modified 기반 304 처리

실시간 WebSocket 구독은 MVP 제외. 운영 감내 가능한 지연 수용.

### 7.8 동일 패턴을 다른 목록에 적용

| 목록 | 필터 | 세그먼트 |
|---|---|---|
| 유저 목록 | email, tier, joined range | [전체] [최근 가입] [비활성] [탈퇴] |
| 신고 목록 | status, category, created range | [PENDING] [REVIEWING] [최근 7일] |
| 어드민 목록 | role, revoked | [활성] [혜제] |
| (향후) 공지 | published/scheduled/expired | ... |

모든 목록은 동일 API 규약 (pageable, sort, filter) 준수. 프런트에서 공통 컴포넌트로 재사용.

---

**다음 문서**: `2026-04-19-admin-platform-integrity.md` (§8)
