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

**API**: `GET /api/v1/admin/system/administrators?role={SUPER_ADMIN|ADMIN}&includeRevoked={true|false}`

권한: `hasRole('SUPER_ADMIN')` (`@adminAuth.canManageAdmins()`)

응답 필드: `administratorId, role, grantedAt, grantedByAdministratorId, revokedAt, userAccountId, email, lastLoginAt, mustChangePassword, memberId, nickname`. 정렬: `grantedAt DESC`.

> **MVP 스케일 노트 (PR 6 Decision 7):** 어드민 행 수가 적어 페이지네이션 미적용, 필터링은 in-memory. 행 수가 ~200을 넘어가면 derived query 메서드(`findAllByRoleOrderByGrantedAtDesc` 등)로 SQL push-down 필요. `Pageable` 파라미터 추가도 같은 시점에 검토.

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
1. 이메일 중복 검사 (V4 `uk_user_account_email` UNIQUE; check-then-save TOCTOU는 `DataIntegrityViolation` catch로 보호)
2. UserAccount 생성 (providerType=LOCAL, 임시 password_hash=bcrypt(**랜덤 12자**, PR 6 Decision 5))
3. Administrator 생성 (role=ADMIN, grantedBy=현재 슈퍼어드민)
4. `includeMemberProfile=true` (기본값) 시 Member 생성 — `MemberSignService.getOrCreateMemberFor(ua)` 경로로 `recordLogin` 부작용 회피, 닉네임은 `ProfileData.updateNickname` (`@OneToOne` cascade)
5. 응답에 임시 비번 1회 노출 — 안전 채널 전달 필요. 신규 어드민은 `must_change_password=true`로 시드되어 첫 로그인 응답 본문이 `mustChangePassword=true`를 포함

Response:
```json
{
  "administratorId": 5,
  "userAccountId": 10,
  "memberId": 8,
  "tempPassword": "Xk9@aB2zCdEf",
  "message": "임시 비번은 첫 로그인 후 반드시 변경하세요."
}
```

#### F-1. 어드민 수정/비활성화

**API**: 
- `PATCH /api/v1/admin/system/administrators/{id}` — 정보 수정. PR 6 시점은 nickname만 mutable (role/email은 불변, grant 정보는 audit). Member가 없는 어드민에는 409 `MEMBER_PROFILE_REQUIRED` (PR 6 Decision 6)
- `POST /api/v1/admin/system/administrators/{id}/member-profile` — Member 미연결 어드민에 member-profile 부착 (PR 6 신설)
- `POST /api/v1/admin/system/administrators/{id}/revoke` — 권한 회수 (revokedAt 설정, 로그인 불가). 셀프 회수 + 마지막 슈퍼어드민 회수 차단 (PR 6 Decision 11)
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

### 6.I 아바타 리소스 관리 🆕 (SUPER_ADMIN 전용)

Avatar BC (§3.3.5)의 어드민 대면 기능. 전 엔드포인트 `@PreAuthorize("@adminAuth.canManageAvatarResources()")` 가드 (§5.2.4). URL rule(§5.2.3)도 `/api/v1/admin/avatar/**` → `SUPER_ADMIN`로 이중 방어.

컨트롤러 위치: `avatar/.../adapter/in/web/AdminAvatarCommandController`, `AdminAvatarQueryController` (BC가 자기 API를 소유).

**기본 경로 (base path): `/api/v1/admin/avatar/...`** — I-1~I-5 엔드포인트는 모두 이 prefix 하위.

#### I-1. 카탈로그 조회

**API**:
- `GET /api/v1/admin/avatar/bodies`
- `GET /api/v1/admin/avatar/faces`

유저 피커와 달리 **모든 lifecycle 상태**(DRAFT/PUBLISHED/RETIRED)를 반환.

Query parameters:
- `status`: `DRAFT | PUBLISHED | RETIRED | ALL` (기본 `ALL`)
- `obtainableType`: `BASIC | DJ_PNT` (body만 의미 있음)
- `page`, `size` (기본 50)

Response (body):
```json
{
  "content": [
    {
      "id": 1,
      "name": "ava_body_djing_005",
      "resourceUri": "https://storage.googleapis.com/.../djing_005.png",
      "iconUri": "https://storage.googleapis.com/.../icon_djing_005.png",
      "obtainableType": "DJ_PNT",
      "obtainableScore": 150,
      "isCombinable": true,
      "isDefaultSetting": false,
      "combinePositionX": 60,
      "combinePositionY": 40,
      "lifecycleStatus": "PUBLISHED",
      "createdAt": "2026-04-20T10:00:00Z",
      "createdBy": 1,
      "updatedAt": "2026-04-20T10:00:00Z",
      "updatedBy": 1
    }
  ],
  "pageInfo": { "page": 0, "size": 50, "totalElements": 15 }
}
```

#### I-2. 리소스 생성

**API**: `POST /api/v1/admin/avatar/bodies` — `multipart/form-data`

필드:
| 필드 | 타입 | 제약 |
|---|---|---|
| `bodyImage` | file | 필수. PNG/JPG. 최대 2MB. |
| `iconImage` | file | 선택. PNG. 최대 200KB. |
| `name` | string | 필수. `^[a-z0-9_]{3,64}$`. 전역 UNIQUE. |
| `obtainableType` | string | `BASIC | DJ_PNT` |
| `obtainableScore` | int | `BASIC` → 0 강제, `DJ_PNT` → 양수 |
| `isCombinable` | bool | |
| `isDefaultSetting` | bool | true이면 `obtainableType=BASIC` 강제 |
| `combinePositionX/Y` | int | |

**처리 순서 (원자성 보장)**:
1. 요청 검증 (name 중복 조회, 값 범위, 파일 포맷/용량)
2. `bodyImage`를 GCS 업로드 → `bodyUri` 획득
3. `iconImage` 제공된 경우 GCS 업로드 → `iconUri` 획득
4. `AvatarBodyResource.draft(...)` 생성 → INSERT
5. **실패 시**: 업로드된 GCS 파일을 **즉시 delete 호출**. 삭제 실패 시에만 orphan으로 남음.

**원자성 한계 (known gap)**: JVM이 GCS 업로드 완료 후 DB INSERT 또는 즉시 delete 호출 전에 죽으면(OOM, SIGKILL, 배포 중단) orphan GCS 객체가 남는다. 이 경우는 §8.3.4에서 언급한 장래 배치 청소가 도입되기 전까지 복구되지 않는다. MVP에서는 수용 (운영 체감 후 배치 도입 여부 판단).

Response `201`:
```json
{
  "id": 123,
  "name": "ava_body_new_001",
  "lifecycleStatus": "DRAFT",
  "resourceUri": "...",
  "iconUri": "...",
  "createdAt": "2026-04-20T10:00:00Z",
  "createdBy": 1
}
```

**API**: `POST /api/v1/admin/avatar/faces` — 동일 구조 (face 필드만). `obtainableType` 필드는 선택(기본 `BASIC`).

#### I-3. 리소스 수정

**API**: `PATCH /api/v1/admin/avatar/bodies/{id}` — `multipart/form-data` (partial update)

수정 가능 필드 (항상):
- `obtainableType`, `obtainableScore`, `isCombinable`, `isDefaultSetting`, `combinePositionX/Y`

수정 가능 필드 (**DRAFT 상태에서만**):
- `bodyImage` (file) — 본 이미지 교체. 제공 시 GCS 새 파일 업로드 + 기존 파일 즉시 delete + `resource_uri` 갱신.
- `iconImage` (file) — 아이콘 교체. 동일 패턴. (I-4의 아이콘 전용 엔드포인트도 DRAFT 제약 동일 적용)

수정 불가 필드:
- `name` — 전역 식별자, 불변
- `lifecycleStatus` — 별 전용 엔드포인트(I-5)

제약:
- `obtainableType` 등 **메타데이터 수정은 DRAFT 또는 PUBLISHED**에서 가능. `RETIRED`는 수정 불가 (`AVATAR_RESOURCE_RETIRED` 409).
- **이미지 교체(`bodyImage`/`iconImage`)는 DRAFT에서만 가능** (`AVATAR_IMAGE_IMMUTABLE_AFTER_PUBLISH` 409). PUBLISHED 상태에서 이미지 교체 시도는 거부.
- `isDefaultSetting=true`로 전환 시 현재 `BASIC` + `PUBLISHED` 아니면 400

**이미지 교체가 DRAFT 제한인 이유**:
- `member.avatarSetting.avatar*Uri`는 리소스 URI를 **값으로 캐시**함 (§3.3.2). PUBLISHED 상태에서 `resource_uri`를 바꾸면 기존 유저가 캐시해둔 URI가 stale이 됨 — 유저에게는 여전히 이전 이미지가 보임. 즉 PUBLISHED 이후 교체는 "새 유저에게만" 수정이 반영되어 오타 수정 목적을 제대로 달성하지 못함.
- 올바른 운영 흐름: DRAFT에서 검수(§I-1 `status=DRAFT` 필터 활용) → 문제 없으면 publish. publish 이후 발견된 이미지 오류는 **retire + 새 리소스 draft + publish** 절차로 대응.
- 이 경로는 cross-BC 쓰기(Avatar → User Profile `avatar_setting` UPDATE) 없이 URI-단조 불변식을 유지한다. 향후 운영 피드백 누적 후 필요 시 `AvatarResourceImageReplaced` 이벤트 + User Profile cascade 기능 추가 검토.

#### I-4. 아이콘 전용 재업로드

**API**:
- `POST /api/v1/admin/avatar/bodies/{id}/icon` — `multipart/form-data` (field: `iconImage`)
- `POST /api/v1/admin/avatar/faces/{id}/icon` — 동일

I-3의 PATCH에서도 가능하지만, 아이콘만 재업로드하는 빈도가 높을 것으로 예상되므로 전용 엔드포인트 제공.

처리: 기존 `icon_uri`의 GCS 파일 즉시 delete → 새 파일 업로드 → DB UPDATE.

**제약: DRAFT 상태에서만 허용** (I-3과 동일 근거 — URI 값 참조 모델 유지). PUBLISHED에서 호출 시 `409 AVATAR_IMAGE_IMMUTABLE_AFTER_PUBLISH`. RETIRED에서 호출 시 `409 AVATAR_RESOURCE_RETIRED`.

#### I-5. 상태 전이

**API**:
- `POST /api/v1/admin/avatar/bodies/{id}/publish` — DRAFT → PUBLISHED
- `POST /api/v1/admin/avatar/bodies/{id}/retire` — PUBLISHED → RETIRED
  - Body: `{ "reason": "string" }` (필수)
- Face 동일 패턴

**도메인 이벤트**:
- publish 시 `AvatarResourcePublished(resourceType, resourceId, resourceUri)` 발행
  - Administration 리스너 → `admin_action(action_type='PUBLISH_AVATAR_RESOURCE', target_type='AVATAR_BODY'/'AVATAR_FACE', target_id=resourceId, metadata={...})` 기록
  - User Profile 피커 캐시(도입 시점에) 무효화
- retire 시 `AvatarResourceRetired(resourceType, resourceId, reason)` 발행
  - Administration 리스너 → `admin_action` 기록 (reason 포함)
  - 기존 유저 `avatarSetting`에는 영향 없음 (URI 값 참조)

#### I-6. 파일 업로드 상세

- **버킷**: 기존 `pfplay-firebase.appspot.com` (Firebase Storage = GCS) 재사용. 현 시드 URI가 이 버킷에 있음.
- **경로 규칙**: `ava_body/{yyyymmdd}_{random}.png`, `ava_face/...`, `ava_icon/...`
  - 기존 Firebase Storage 구조와 호환 (`ava_basic`, `ava_djing`, `ava_face`, `ava_icon` 폴더 존재)
  - 신규는 분류별 폴더 + 날짜/랜덤으로 충돌 방지
- **SDK**: `com.google.cloud:google-cloud-storage`. 서비스 계정 키는 application yml 외부(Secret Manager 또는 env) 주입.
- **접근 권한**: 업로드 파일은 `publicRead`. 기존 URI가 공개이므로 동일.
- **다운로드 URL**: GCS SDK가 반환하는 `https://storage.googleapis.com/...` 공개 URL을 `resource_uri`/`icon_uri`로 저장.

#### I-7. 유저 피커 영향 (Non-breaking)

기존 `GET /api/v1/users/me/profile/avatar/bodies` / `faces` 엔드포인트:
- 내부 조회 조건에 `WHERE lifecycle_status = 'PUBLISHED'` 추가
- 응답 계약(필드/JSON 스키마) 불변 — 프런트 변경 0

#### I-8. 에러 케이스

| 시나리오 | HTTP | 코드 |
|---|---|---|
| name 중복 | 409 | `AVATAR_NAME_ALREADY_EXISTS` |
| 지원 안 하는 파일 포맷 | 400 | `AVATAR_INVALID_FILE_FORMAT` |
| 파일 크기 초과 | 400 | `AVATAR_FILE_TOO_LARGE` |
| GCS 업로드 실패 | 502 | `AVATAR_STORAGE_UPLOAD_FAILED` |
| lifecycle 전이 불가 (예: DRAFT → retire) | 409 | `AVATAR_INVALID_LIFECYCLE_TRANSITION` |
| RETIRED 리소스 수정 시도 | 409 | `AVATAR_RESOURCE_RETIRED` |
| PUBLISHED 상태에서 이미지(body/face/icon) 교체 시도 | 409 | `AVATAR_IMAGE_IMMUTABLE_AFTER_PUBLISH` |
| 권한 부족 (일반 ADMIN 접근) | 403 | `FORBIDDEN` (global) |
| `isDefaultSetting=true`지만 BASIC/PUBLISHED 아님 | 400 | `AVATAR_INVALID_DEFAULT_SETTING` |

#### I-9. 권한

전 엔드포인트 `@PreAuthorize("@adminAuth.canManageAvatarResources()")` (§5.2.4 중앙 SpEL bean). MVP에서 이 메서드는 `hasRole("SUPER_ADMIN")`.

근거: 과금 직결 영역. 초기 단계(어드민 수 ≈ 1명)에선 분산 운영 필요성 없음. 추후 RBAC 세분화(§11) 때 `canManageAvatarResources()`의 구현만 permission 테이블 조회로 교체 — 컨트롤러 코드 불변.

#### I-10. UI/프런트 참고 (pfplay-admin)

백엔드 스펙은 여기까지. 프런트 연결점만 요약:
- 카탈로그 리스트: 상태 필터(DRAFT/PUBLISHED/RETIRED/ALL), 썸네일(iconUri) 미리보기, 본 이미지(resourceUri) 클릭 확대
- 생성 모달: 드래그드롭 업로드 2슬롯(bodyImage, iconImage), 폼 검증, multipart POST
- 상태 버튼: `Publish` (DRAFT only), `Retire` (PUBLISHED only, 사유 입력 모달)

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
