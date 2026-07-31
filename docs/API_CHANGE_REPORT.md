# API 변경 보고서 (Backend → Frontend 연동용)

> ⚠️ **2026-03-09 시점 산출물이다** (확인: 2026-07-31). 그 시점 프론트 연동을 위해 작성된
> 변경 보고서이며, 이후 API 변경은 반영돼 있지 않다. 현재 API 계약은 Swagger(`/spec/api`)를 본다.

> **작성일**: 2026-03-09
> **브랜치**: `chore/test-coverage-expansion`
> **대상**: 프론트엔드 엔지니어

---

## 변경 요약

이번 변경은 크게 **12가지 영역**을 다룹니다:

1. **REST API 경로(URL) 변경** — 동사를 제거하고 리소스 명사 중심으로 전환
2. **HTTP 상태 코드 정리** — RESTful 규약에 맞게 201/204/200 통일
3. **응답 패턴 일관성 확보** — 모든 API의 요청/응답 구조를 통일된 규칙으로 정리
4. **에러 메시지 한국어화** — 모든 도메인 에러 메시지를 한국어로 변환
5. **일부 에러 코드의 HTTP 상태 코드 변경** — 의미에 맞는 상태 코드로 교정
6. **Swagger 에러 응답 가시성 수정** — 4xx 에러 응답이 Swagger UI에 정상 표시
7. **Map → Typed Record DTO 마이그레이션** — 응답 타입 명시화 (JSON 구조 변경 없음)
8. **응답 DTO 필드 변경** — PlaybackDto 반응 카운트 제거, WebSocket 이벤트 페이로드 축소
9. **Request DTO 유효성 검사 강화** — 필수 필드 서버 측 검증 추가
10. **WebSocket 이벤트 이름 변경** — 과거형 snake_case 컨벤션 적용
11. **WebSocket 메시지 메타데이터 추가** — id, timestamp 필드 추가 + partyroomId 플랫 직렬화
12. **WebSocket 입퇴장 이벤트 분리 + 아바타 스키마 변경** — 구조적 변경

---

## 1. REST API 경로(URL) 변경

REST 리소스 중심 설계로 전환하여 동사(enter/exit/skip)를 제거하고, 리소스 명사로 통일했습니다.

| 기능 | 변경 전 | 변경 후 | 비고 |
|------|---------|---------|------|
| 파티룸 입장 | `POST /api/v1/partyrooms/{id}/enter` | `POST /api/v1/partyrooms/{id}/crews` | 크루 리소스 생성 |
| 파티룸 퇴장 | `POST /api/v1/partyrooms/{id}/exit` | `DELETE /api/v1/partyrooms/{id}/crews/me` | 크루 리소스 삭제 |
| DJ 등록 | `POST /api/v1/partyrooms/{id}/djs` | `POST /api/v1/partyrooms/{id}/dj-queue` | DJ 큐 리소스 생성 |
| DJ 본인 해제 | `DELETE /api/v1/partyrooms/{id}/djs/me` | `DELETE /api/v1/partyrooms/{id}/dj-queue/me` | DJ 큐 리소스 삭제 |
| DJ 강제 해제 | `DELETE /api/v1/partyrooms/{id}/djs/{djId}` | `DELETE /api/v1/partyrooms/{id}/dj-queue/{djId}` | DJ 큐 리소스 삭제 |
| 재생 스킵 | `POST /api/v1/partyrooms/{id}/playbacks/skip` | `DELETE /api/v1/partyrooms/{id}/playbacks/current` | HTTP 메서드도 POST→DELETE 변경 |

---

## 2. HTTP 상태 코드 & 응답 패턴 통일

### 전체 규칙 (RFC 7231 기반)

| 동작 | HTTP 상태 | 응답 구조 | 설명 |
|------|----------|----------|------|
| GET (조회) | 200 OK | `{ "data": { ... } }` | `ApiCommonResponse.success(data)` |
| POST (생성) | 201 Created | `{ "data": { "xxxId": 123 } }` | 생성된 리소스 ID 반환 |
| POST (동작, 데이터 반환) | 200 OK | `{ "data": { ... } }` | OAuth URL/Callback 등 |
| POST (동작, 데이터 없음) | 204 No Content | _(본문 없음)_ | 로그아웃 등 |
| PUT/PATCH (수정, 데이터 없음) | 204 No Content | _(본문 없음)_ | 응답 본문 파싱하지 말 것 |
| DELETE (삭제) | 204 No Content | _(본문 없음)_ | 응답 본문 파싱하지 말 것 |

> **핵심 원칙 (RFC 7231)**: 돌려줄 데이터가 있으면 **200/201 + body**, 없으면 **204 No Content (빈 본문)**. `200 + { "data": null }` 패턴은 안티패턴으로 제거했습니다.

### 변경 상세

#### 2-1. 생성 요청: → 201 Created + 생성된 리소스 ID 반환

| 엔드포인트 | 변경 전 | 변경 후 | 응답 본문 |
|-----------|---------|---------|----------|
| `POST /api/v1/partyrooms` (파티룸 생성) | 200 OK | **201 Created** | `{ "data": { ... } }` |
| `POST /api/v1/partyrooms/{id}/crews` (입장) | 200 OK | **201 Created** | `{ "data": null }` |
| `POST /api/v1/partyrooms/{id}/dj-queue` (DJ 등록) | 200 OK | **201 Created** | `{ "data": { "djId": 123 } }` |
| `POST /api/v1/crews/me/blocks` (크루 차단) | 200 OK | **201 Created** | `{ "data": { "blockId": 123 } }` |
| `POST /api/v1/partyrooms/{id}/penalties` (페널티 부과) | 202 Accepted | **201 Created** | `{ "data": { "penaltyId": 123 } }` |
| `POST /api/v1/playlists/{id}/tracks` (트랙 추가) | _(기존 동일)_ | 201 Created | `{ "data": { "trackId": 456 } }` |

#### 2-2. 삭제 요청: → 204 No Content (본문 없음)

| 엔드포인트 | 변경 전 | 변경 후 |
|-----------|---------|---------|
| `DELETE /api/v1/partyrooms/{id}` (파티룸 삭제) | 200 OK | **204 No Content** |
| `DELETE /api/v1/partyrooms/{id}/crews/me` (퇴장) | 200 OK | **204 No Content** |
| `DELETE /api/v1/partyrooms/{id}/dj-queue/me` (DJ 본인 해제) | 200 OK | **204 No Content** |
| `DELETE /api/v1/partyrooms/{id}/dj-queue/{djId}` (DJ 강제 해제) | 200 OK | **204 No Content** |
| `DELETE /api/v1/partyrooms/{id}/playbacks/current` (재생 스킵) | 200 OK | **204 No Content** |
| `DELETE /api/v1/crews/me/blocks/{blockId}` (차단 해제) | 200 OK | **204 No Content** |
| `DELETE /api/v1/partyrooms/{id}/penalties/{penaltyId}` (페널티 해제) | 202 Accepted | **204 No Content** |
| `DELETE /api/v1/playlists/{id}/tracks/{trackId}` (트랙 삭제) | 202 Accepted | **204 No Content** |
| `DELETE /api/v1/playlists` (플레이리스트 삭제) | 200 OK + 본문 | **204 No Content** |

#### 2-3. 수정 요청: → 204 No Content (본문 없음)

| 엔드포인트 | 변경 전 | 변경 후 |
|-----------|---------|---------|
| `PUT /api/v1/partyrooms/{id}` (파티룸 수정) | 200 OK + `{ "data": null }` | **204 No Content** |
| `PUT /api/v1/partyrooms/{id}/dj-queue` (DJ 큐 업데이트) | 200 OK + `{ "data": null }` | **204 No Content** |
| `PUT /api/v1/partyrooms/{id}/notice` (공지 등록) | 200 OK | **204 No Content** |
| `PATCH /api/v1/partyrooms/{id}/crews/{crewId}/grade` (등급 변경) | 200 OK + `{ "data": null }` | **204 No Content** |
| `PATCH /api/v1/playlists/{id}` (이름 변경) | 200 + `UpdatePlaylistNameResponse` | **204 No Content** — 별도 DTO 제거 |
| `PUT /api/v1/playlists/{id}/tracks/{trackId}` (순서 변경) | 202 Accepted | **204 No Content** |
| `PATCH /api/v1/playlists/{id}/tracks/{trackId}/move` (트랙 이동) | 202 Accepted | **204 No Content** |
| `PUT /api/v1/users/me/profile/avatar` (아바타 변경) | 200 OK + `{ "data": null }` | **204 No Content** |
| `PUT /api/v1/users/me/profile/bio` (자기소개 수정) | 200 OK + `{ "data": null }` | **204 No Content** |

#### 2-4. 동작 요청 (Action POST): → 204 No Content

| 엔드포인트 | 변경 전 | 변경 후 |
|-----------|---------|---------|
| `POST /api/v1/auth/logout` (로그아웃) | 200 OK + `{ "data": null }` | **204 No Content** |

> **프론트엔드 조치**: PUT/PATCH/DELETE 응답 및 로그아웃 시 본문 파싱을 하지 마세요. HTTP 상태 코드(204)로만 성공을 판단하세요.

---

## 3. Auth API 응답 구조 변경

Auth API도 `ApiCommonResponse` 래퍼로 통일했습니다. 기존의 `success`, `message` 필드는 **제거**되었습니다.

### 3-1. OAuth URL 생성 (`POST /api/v1/auth/oauth/url`)

**변경 전:**
```json
{
  "authUrl": "https://accounts.google.com/...",
  "state": "abc123",
  "provider": "google",
  "expiresIn": 300,
  "success": true
}
```

**변경 후:**
```json
{
  "data": {
    "authUrl": "https://accounts.google.com/...",
    "state": "abc123",
    "provider": "google",
    "expiresIn": 300
  }
}
```

### 3-2. OAuth 콜백 로그인 (`POST /api/v1/auth/oauth/callback`)

**변경 전:**
```json
{
  "tokenType": "Cookie",
  "expiresIn": 3600,
  "issuedAt": "2026-03-09T...",
  "success": true,
  "message": "Authentication successful"
}
```

**변경 후:**
```json
{
  "data": {
    "tokenType": "Cookie",
    "expiresIn": 3600,
    "issuedAt": "2026-03-09T..."
  }
}
```

### 3-3. 로그아웃 (`POST /api/v1/auth/logout`)

**변경 전:** 빈 본문 (200 OK)

**변경 후:** 204 No Content _(본문 없음)_

### 3-4. Auth 에러 응답 변경

기존에 Auth API는 자체적으로 `{ "success": false, "message": "..." }` 형태의 에러를 반환했으나, 이제 다른 API와 동일한 글로벌 에러 핸들러를 통해 에러가 반환됩니다.

**신규 에러 코드:**

| 코드 | 메시지 | HTTP | 설명 |
|------|--------|------|------|
| AUTH-003 | 유효하지 않은 OAuth 제공자입니다 | 400 | 잘못된 provider 값 |
| AUTH-004 | 유효하지 않거나 만료된 state 파라미터입니다 | 400 | state 검증 실패 |

> **프론트엔드 조치**: `$.success` / `$.message` 필드 참조를 제거하고, `$.data`에서 실제 응답 데이터를 꺼내도록 변경하세요. 에러 발생 시 HTTP 상태 코드와 에러 응답 구조(`errorCode`, `message`)로 판단하세요.

---

## 4. 제거된 API / 응답 DTO

| 항목 | 설명 |
|------|------|
| `GET /api/v1/users/members/sign` | 미사용 API — 완전 삭제 |
| `DeletePlaylistsResponse` | 삭제 응답에 같은 ID를 되돌려주는 것은 불필요 → 204 빈 본문으로 대체 |
| `UpdatePlaylistNameResponse` | 수정 요청의 같은 이름을 되돌려주는 것은 불필요 → `ApiCommonResponse.ok()` 로 대체 |

---

## 5. 에러 메시지 한국어화

모든 도메인 에러 메시지가 영어에서 한국어로 변경되었습니다. **에러 코드(`errorCode`)는 변경 없음**.

### Partyroom (PTR)

| 코드 | 변경 전 | 변경 후 | HTTP |
|------|---------|---------|------|
| PTR-001 | Can not find Partyroom | 파티룸을 찾을 수 없습니다 | 404 |
| PTR-002 | Already Terminated Partyroom | 이미 종료된 파티룸입니다 | 403 |
| PTR-003 | Exceeded Entrance Limit | 입장 인원 제한을 초과했습니다 | 403 |
| PTR-004 | Already Active in Another Partyroom | 이미 다른 파티룸에 입장 중입니다 | 403 |
| PTR-005 | Authority Restriction | 권한이 부족합니다 | 403 |
| PTR-006 | Already Host in Another Partyroom | 이미 다른 파티룸의 호스트입니다 | 403 |

> **주의**: 기존 PTR-005 (CACHE_MISSED_SESSION)은 인프라 이슈로 도메인 예외에서 제거됨. PTR-005~007이 PTR-005~006으로 재번호 매김.

### Crew (CRW)

| 코드 | 변경 전 | 변경 후 | HTTP |
|------|---------|---------|------|
| CRW-001 | Can not find My Active Room | 참여 중인 파티룸을 찾을 수 없습니다 | 404 |
| CRW-002 | Invalid My Active Room | 유효하지 않은 파티룸 참여 상태입니다 | 409 |

### DJ

| 코드 | 변경 전 | 변경 후 | HTTP |
|------|---------|---------|------|
| DJ-001 | Already Registered Dj | 이미 DJ로 등록되어 있습니다 | 409 |
| DJ-002 | Dj Queue is Closed | DJ 대기열이 닫혀 있습니다 | 403 |
| DJ-003 | Cannot Register Empty Playlist | 빈 플레이리스트로 등록할 수 없습니다 | 403 |
| DJ-004 | DJ not found in queue | DJ 대기열에서 해당 DJ를 찾을 수 없습니다 | **404** (기존 400) |

### Grade (GRD)

| 코드 | 변경 전 | 변경 후 | HTTP |
|------|---------|---------|------|
| GRD-001 | Manager grade is required... | 이 작업을 수행하려면 관리자 등급이 필요합니다 | 403 |
| GRD-002 | Unable to set Host | 호스트 등급을 설정할 수 없습니다 | 403 |
| GRD-003 | The current grade level is insufficient... | 현재 등급으로는 해당 작업을 수행할 수 없습니다 | 403 |
| GRD-004 | The specified grade level exceeds... | 지정한 등급이 허용 범위를 초과합니다 | 403 |
| GRD-005 | Guest is Only possible Listener | 게스트는 리스너 등급만 가능합니다 | 403 |

### Block (BLK)

| 코드 | 변경 전 | 변경 후 | HTTP |
|------|---------|---------|------|
| BLK-001 | No block history found | 차단 이력을 찾을 수 없습니다 | 404 |
| BLK-002 | Crew member is already blocked | 이미 차단된 크루입니다 | **409** (기존 400) |

### Penalty (PNT)

| 코드 | 변경 전 | 변경 후 | HTTP |
|------|---------|---------|------|
| PNT-001 | Banned User | 이용이 정지된 사용자입니다 | 403 |
| PNT-002 | No penalty history found | 페널티 이력을 찾을 수 없습니다 | 404 |

### Reaction (RCT)

| 코드 | 변경 전 | 변경 후 | HTTP |
|------|---------|---------|------|
| RCT-001 | Invalid reaction type | 유효하지 않은 리액션 타입입니다 | 403 |

### Auth (AUTH)

| 코드 | 변경 전 | 변경 후 | HTTP |
|------|---------|---------|------|
| AUTH-001 | Failed to generate code challenge | 코드 챌린지 생성에 실패했습니다 | 400 |
| AUTH-002 | OAuth provider not configured | OAuth 제공자가 설정되지 않았습니다 | 400 |
| AUTH-003 | _(신규)_ | 유효하지 않은 OAuth 제공자입니다 | 400 |
| AUTH-004 | _(신규)_ | 유효하지 않거나 만료된 state 파라미터입니다 | 400 |

### Playlist (PLL)

| 코드 | 변경 전 | 변경 후 | HTTP |
|------|---------|---------|------|
| PLL-001 | Wallet connection required | 지갑 연결이 필요합니다 | 403 |
| PLL-002 | Playlist limit exceeded | 플레이리스트 생성 한도를 초과했습니다 | 409 |
| PLL-003 | Playlist not found | 플레이리스트를 찾을 수 없습니다 | 404 |

### Track (TRK)

| 코드 | 변경 전 | 변경 후 | HTTP |
|------|---------|---------|------|
| TRK-001 | Track cannot be added... | 이미 플레이리스트에 존재하는 트랙입니다 | 409 |
| TRK-002 | Track limit exceeded... | 플레이리스트의 트랙 한도를 초과했습니다 | 409 |
| TRK-003 | Track does not exist | 트랙을 찾을 수 없습니다 | 404 |
| TRK-004 | Invalid track order number | 유효하지 않은 트랙 순서입니다 | 400 |

### Avatar (UAV)

| 코드 | 변경 전 | 변경 후 | HTTP |
|------|---------|---------|------|
| UAV-001 | Cannot Select Due To Restrictions | 제한으로 인해 선택할 수 없습니다 | 403 |

---

## 6. ErrorType 매핑 변경 (HTTP 상태 코드 변경)

| 코드 | 변경 전 HTTP | 변경 후 HTTP | 이유 |
|------|-------------|-------------|------|
| BLK-002 | 400 Bad Request | **409 Conflict** | "이미 차단됨"은 중복 충돌 |
| DJ-004 | 400 Bad Request | **404 Not Found** | "DJ를 찾을 수 없음"은 리소스 부재 |

---

## 7. 제거된 에러 코드

| 코드 | 메시지 | 이유 |
|------|--------|------|
| ~~PTR-005~~ | ~~No cached data found for sessionId~~ | 인프라 이슈(Redis 캐시 미스)로 도메인 예외에서 제거 |

> **기존 PTR-005~007 번호가 PTR-005~006으로 재배정**되었습니다.

---

## 프론트엔드 체크리스트

### URL & 메서드 변경
- [ ] API URL 경로 변경 6건 반영 (enter/exit/djs/skip)
- [ ] 재생 스킵: HTTP 메서드 `POST` → `DELETE` 변경

### 상태 코드 & 응답 처리
- [ ] **204 No Content** 응답 시 본문 파싱 건너뛰기 (DELETE 9건 + PUT/PATCH 9건 + 로그아웃 1건 = 총 19개 엔드포인트)
- [ ] **201 Created** 응답 처리 (6개 POST 생성 엔드포인트)
- [ ] POST 생성 응답에서 리소스 ID 추출:
  - DJ 등록: `response.data.djId`
  - 크루 차단: `response.data.blockId`
  - 페널티 부과: `response.data.penaltyId`
  - 트랙 추가: `response.data.trackId`

### Auth API
- [ ] OAuth URL/Callback 응답: `$.data.xxx`로 접근 경로 변경 (기존 `$.xxx`)
- [ ] `$.success`, `$.message` 필드 참조 제거
- [ ] 로그아웃: 200 → **204 No Content** 변경, 본문 파싱 제거
- [ ] Auth 에러: HTTP 상태 코드 + 글로벌 에러 응답 구조로 판단

### 플레이리스트
- [ ] 플레이리스트 삭제: 200 + 본문 → **204 + 빈 본문**
- [ ] 플레이리스트 이름 변경: 200 + 본문 → **204 + 빈 본문**

### 유저 프로필
- [ ] 아바타 변경: 200 → **204 No Content**, 본문 파싱 제거
- [ ] 자기소개 수정: 200 → **204 No Content**, 본문 파싱 제거

### 응답 DTO & WebSocket 이벤트 변경
- [ ] Setup API (`GET /api/v1/partyrooms/setup`): `display.playback`에서 `likeCount`, `dislikeCount`, `grabCount` 제거됨 → `display.reaction.aggregation`에서 조회
- [ ] WebSocket `playback_started` 이벤트: `playback` 페이로드에서 반응 카운트 제거 → 클라이언트에서 초기값(0, 0, 0) 설정 후 `reaction_aggregation_updated` 이벤트로 업데이트

### WebSocket 이벤트 변경 (섹션 12~14)
- [ ] `eventType` 문자열 상수 전체 업데이트 (11개 이벤트 이름 변경, 위 테이블 참조)
- [ ] `eventType` 값이 대문자 ENUM 형태로 변경: `"chat"` → `"CHAT_MESSAGE_SENT"` 등
- [ ] `partyroomId` 접근 경로 변경: `event.partyroomId.id` → `event.partyroomId` (플랫 정수)
- [ ] `partyroom_access` 핸들러 → `crew_entered` / `crew_exited` 2개 핸들러로 분리
- [ ] `accessType` 필드 기반 분기 로직 제거
- [ ] 아바타 필드 접근 경로 변경: `crew.avatarBodyUri` → `crew.avatar.avatarBodyUri` (crew_entered, crew_profile_changed)
- [ ] (선택) 신규 `id`, `timestamp` 메타데이터 활용 — 멱등성 처리, 메시지 순서 보장에 활용 가능

### Swagger & 응답 타입
- [ ] 에러 응답(4xx)이 Swagger UI에 정상 표시되는지 확인
- [ ] 기존 `Map<String, Object>` 응답이 typed record DTO로 변경된 필드 확인 (아래 섹션 9 참조)

### 에러 처리
- [ ] BLK-002: 400 → 409 변경 반영
- [ ] DJ-004: 400 → 404 변경 반영
- [ ] PTR-005~006 에러 코드 재매핑 확인
- [ ] 에러 메시지 표시 로직 확인 (한국어로 변경됨)
- [ ] `GET /api/v1/users/members/sign` 호출 코드 제거 (API 삭제됨)

---

## 8. Swagger 에러 응답 가시성 수정

### 문제
Swagger UI에서 200 이외의 에러 응답 코드(400, 403, 404, 409)가 표시되지 않았습니다.

### 원인
- `application.yml`의 `group-configs`로 정의된 API 그룹은 `@Component` 기반 `OperationCustomizer`를 자동 적용하지 않음
- `ApiErrorResponse` 스키마가 OpenAPI components에 등록되지 않아 `$ref` 참조 실패 → Swagger UI가 해당 응답 섹션을 숨김

### 해결
- `application.yml`의 `group-configs` 제거
- `SwaggerConfig`에서 `GroupedOpenApi` 빈으로 대체하고 `ApiErrorCodeCustomizer`(OperationCustomizer)와 `apiErrorResponseSchemaCustomizer`(OpenApiCustomizer)를 명시적으로 등록

---

## 9. Map → Typed Record DTO 마이그레이션

Swagger에서 필드가 드러나지 않는 `Map<String, Object>` / `Map<String, Boolean>` 응답을 모두 typed record DTO로 교체했습니다.

### 변경 내역

| 기존 타입 | 신규 DTO | 사용처 |
|----------|---------|-------|
| `Map<String, Boolean>` | `ReactionHistoryDto(isLiked, isDisliked, isGrabbed)` | `POST /partyrooms/{id}/playbacks/reaction`, Setup API `history` 필드 |
| `Map<String, Object>` (playback info) | `PlaybackSummary(name, thumbnailImage, duration)` | DJ 큐 조회, 파티룸 목록 |
| `Map<String, Object>` (avatar icon) | `CrewIcon(bodyUri, faceUri)` | 크루 아이콘 응답 |
| `Map<String, Object>` (생성 ID) | `CreateDjResponse(djId)` | DJ 등록 |
| `Map<String, Object>` (생성 ID) | `CreateBlockResponse(blockId)` | 크루 차단 |
| `Map<String, Object>` (생성 ID) | `CreatePenaltyResponse(penaltyId)` | 페널티 부과 |
| `Map<String, Object>` (생성 ID) | `CreateTrackResponse(trackId)` | 트랙 추가 |
| `Map<String, Object>` (시뮬레이션) | `ChatSimulationStartResponse(partyroomId, status, scriptType)` | Admin 시뮬레이션 시작 |
| `Map<String, Object>` (시뮬레이션) | `ChatSimulationStopResponse(partyroomId, status)` | Admin 시뮬레이션 중지 |

> **프론트엔드 조치**: 응답 JSON 구조 자체는 변경되지 않았습니다. 기존 `Map`으로 직렬화되던 키-값이 동일한 필드명의 record로 대체되었으므로, 클라이언트 측 파싱 로직 변경은 불필요합니다.

---

## 10. Request DTO 유효성 검사 강화

모든 Request DTO에 Jakarta Bean Validation 어노테이션을 추가하고, 컨트롤러의 `@RequestBody`에 `@Valid`를 적용했습니다.

### 영향
- 기존에 서버 측에서 검증되지 않던 필수 필드가 이제 검증됩니다
- 누락 필드 시 **400 Bad Request** + 필드별 에러 메시지가 반환됩니다

### 적용된 검증 규칙

| Request DTO | 필드 | 어노테이션 |
|-------------|------|-----------|
| `CreatePartyroomRequest` | title | `@NotBlank` `@Size(max=100)` |
| | playbackTimeLimit | `@NotNull` `@Min(1)` |
| `UpdatePartyroomRequest` | title | `@NotBlank` `@Size(max=100)` |
| | playbackTimeLimit | `@NotNull` `@Min(1)` |
| `UpdateDjQueueStatusRequest` | queueStatus | `@NotNull` |
| `RegisterDjRequest` | playlistId | `@NotNull` |
| `AddBlockRequest` | crewId | `@NotNull` |
| `AdjustGradeRequest` | gradeType | `@NotNull` |
| `ApplyPenaltyRequest` | crewId | `@NotNull` |
| `ReactPlaybackRequest` | reactionType | `@NotNull` |
| `AddTrackRequest` | name, linkId, duration, thumbnailImage | `@NotBlank` |
| `CreatePlaylistRequest` | name | `@NotBlank` `@Size(max=100)` |
| `DeletePlaylistsRequest` | playlistIds | `@NotEmpty` |
| `MoveTrackRequest` | targetPlaylistId | `@NotNull` |
| `UpdatePlaylistNameRequest` | name | `@NotBlank` `@Size(max=100)` |
| `UpdateTrackOrderRequest` | nextOrderNumber | `@NotNull` `@Min(0)` |
| `MusicSearchRequest` | q | `@NotBlank` |
| `UpdateMyWalletRequest` | walletAddress | `@NotBlank` |

### `@Valid` 적용된 컨트롤러 (신규 추가분)

| 컨트롤러 | 메서드 |
|---------|--------|
| `PartyroomCommandController` | `updatePartyroom`, `updateDjQueue` |
| `PlaybackReactionCommandController` | `reactToPlayback` |
| `TrackCommandController` | `addTrack`, `moveTrack`, `updateMusicOrder` |
| `CrewBlockCommandController` | `blockOtherCrew` |
| `DjCommandController` | `enqueueDj` |
| `PlaylistCommandController` | `deletePlaylist`, `modifyPlaylistName` |
| `CrewGradeCommandController` | `updateCrewGrade` |
| `UserWalletCommandController` | `updateMyWallet` |

> **프론트엔드 조치**: 필수 필드 누락 시 기존에는 서버 내부에서 `NullPointerException` 등이 발생했으나, 이제 명확한 400 응답이 반환됩니다. 기존 정상 호출에는 영향이 없습니다.

---

## 11. 응답 DTO 필드 변경 & WebSocket 이벤트 축소

### 11-1. PlaybackDto — 반응 카운트 필드 제거

ERD 정규화로 `PlaybackAggregationData` 엔티티가 분리되면서, `PlaybackDto`에서 `likeCount`, `dislikeCount`, `grabCount` 3개 필드가 제거되었습니다.

**영향 받는 API**: `GET /api/v1/partyrooms/setup` (`DisplayDto.playback`)

```json
// Before
{
  "display": {
    "playback": {
      "id": 1, "linkId": "...", "name": "...",
      "duration": "3:00", "thumbnailImage": "...", "endTime": 999,
      "likeCount": 5,       // REMOVED
      "dislikeCount": 1,    // REMOVED
      "grabCount": 2        // REMOVED
    },
    "reaction": {
      "history": { "isLiked": false, "isDisliked": false, "isGrabbed": false },
      "aggregation": { "likeCount": 5, "dislikeCount": 1, "grabCount": 2 }
    }
  }
}

// After
{
  "display": {
    "playback": {
      "id": 1, "linkId": "...", "name": "...",
      "duration": "3:00", "thumbnailImage": "...", "endTime": 999
    },
    "reaction": {
      "history": { "isLiked": false, "isDisliked": false, "isGrabbed": false },
      "aggregation": { "likeCount": 5, "dislikeCount": 1, "grabCount": 2 }
    }
  }
}
```

> **프론트엔드 조치**: `display.playback.likeCount` 대신 `display.reaction.aggregation.likeCount` 사용

### 11-2. PLAYBACK_START 이벤트 — playback 페이로드 축소

WebSocket `playback_start` 이벤트의 `playback` 필드에서 반응 카운트가 제거되었습니다.

```json
// Before
{
  "partyroomId": { "id": 10 },
  "eventType": "PLAYBACK_START",
  "crewId": 1,
  "playback": {
    "id": 1, "linkId": "...", "name": "...",
    "duration": "3:00", "thumbnailImage": "...", "endTime": 999,
    "likeCount": 0,       // REMOVED
    "dislikeCount": 0,    // REMOVED
    "grabCount": 0        // REMOVED
  }
}

// After
{
  "partyroomId": { "id": 10 },
  "eventType": "PLAYBACK_START",
  "crewId": 1,
  "playback": {
    "id": 1, "linkId": "...", "name": "...",
    "duration": "3:00", "thumbnailImage": "...", "endTime": 999
  }
}
```

> **프론트엔드 조치**: 재생 시작 시 카운트는 항상 0이므로, 클라이언트에서 초기값(0, 0, 0)을 설정하면 됩니다. 이후 `reaction_aggregation_updated` 이벤트로 실시간 업데이트 수신.

---

## 12. WebSocket 이벤트 이름 변경 (과거형 컨벤션)

모든 WebSocket 이벤트 이름이 **과거형 snake_case**로 통일되었습니다. `eventType` 필드 값도 동일하게 변경됩니다.

| 변경 전 | 변경 후 | 설명 |
|---------|---------|------|
| `chat` | `chat_message_sent` | 채팅 메시지 |
| `partyroom_access` | `crew_entered` / `crew_exited` | 입퇴장 (**분리**, 아래 14번 참조) |
| `partyroom_deactivation` | `playback_deactivated` | 재생 비활성화 |
| `playback_start` | `playback_started` | 재생 시작 |
| `reaction_motion` | `reaction_performed` | 리액션 모션 |
| `reaction_aggregation` | `reaction_aggregation_updated` | 리액션 집계 |
| `dj_queue_change` | `dj_queue_changed` | DJ 큐 변경 |
| `crew_grade` | `crew_grade_changed` | 등급 변경 |
| `crew_penalty` | `crew_penalized` | 페널티 |
| `crew_profile` | `crew_profile_changed` | 프로필 변경 |
| `partyroom_closed` | `partyroom_closed` | 변경 없음 |

> **프론트엔드 조치**: STOMP 구독 후 `eventType` 기반 메시지 라우팅 로직의 문자열 상수를 모두 업데이트하세요. `eventType` 값은 이제 **대문자 ENUM** 형태입니다 (예: `CHAT_MESSAGE_SENT`, `CREW_ENTERED`).

---

## 13. WebSocket 메시지 메타데이터 추가 + partyroomId 직렬화 변경

### 13-1. 공통 메타데이터 필드 추가

모든 서버→클라이언트 브로드캐스트 메시지에 다음 2개 필드가 **신규 추가**되었습니다:

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | `string` (UUID v4) | 메시지 고유 식별자 (멱등성 처리, 중복 수신 방지에 활용) |
| `timestamp` | `integer` (int64) | 메시지 발행 시각 (Unix epoch milliseconds) |

```json
// 모든 브로드캐스트 메시지의 공통 구조
{
  "partyroomId": 10,
  "eventType": "PLAYBACK_STARTED",
  "id": "550e8400-e29b-41d4-a716-446655440003",
  "timestamp": 1709964213000,
  // ... 이벤트별 데이터
}
```

### 13-2. partyroomId 직렬화 변경 (객체 → 플랫 정수)

`partyroomId` 필드가 **중첩 객체에서 플랫 정수**로 변경되었습니다.

```json
// Before
{ "partyroomId": { "id": 10 } }

// After
{ "partyroomId": 10 }
```

> **프론트엔드 조치**:
> - `event.partyroomId.id` → `event.partyroomId`로 접근 경로를 변경하세요.
> - `id`, `timestamp` 필드가 추가되었으므로, 메시지 파싱 시 이를 활용할 수 있습니다 (필수 처리는 아님).

---

## 14. WebSocket 입퇴장 이벤트 분리 + 아바타 스키마 변경

### 14-1. `partyroom_access` → `crew_entered` + `crew_exited` 분리

기존의 단일 `partyroom_access` 이벤트가 **입장과 퇴장으로 완전 분리**되었습니다.

**기존 (단일 이벤트):**
```json
{
  "partyroomId": { "id": 10 },
  "eventType": "partyroom_access",
  "accessType": "ENTER",
  "crew": { "crewId": 42, "gradeType": "CLUBBER", "nickname": "DJ_파티왕", ... }
}
```

**변경 후 — 입장 (`crew_entered`):**
```json
{
  "partyroomId": 10,
  "eventType": "CREW_ENTERED",
  "id": "...",
  "timestamp": 1709964213000,
  "crew": {
    "crewId": 42,
    "gradeType": "CLUBBER",
    "nickname": "DJ_파티왕",
    "avatar": {
      "avatarCompositionType": "SINGLE_BODY",
      "avatarBodyUri": "/avatars/body/1.png",
      "avatarFaceUri": null,
      "avatarIconUri": "/avatars/icon/1.png",
      "combinePositionX": 0,
      "combinePositionY": 0,
      "offsetX": 0.0,
      "offsetY": 0.0,
      "scale": 1.0
    }
  }
}
```

**변경 후 — 퇴장 (`crew_exited`):**
```json
{
  "partyroomId": 10,
  "eventType": "CREW_EXITED",
  "id": "...",
  "timestamp": 1709964213000,
  "crewId": 42
}
```

### 14-2. 아바타 필드 → `avatar` 중첩 객체로 변경

`crew_entered`와 `crew_profile_changed` 이벤트에서 아바타 관련 9개 필드가 **`avatar` 객체로 그룹화**되었습니다.

```json
// Before (플랫)
{
  "crewId": 42,
  "nickname": "DJ_파티왕",
  "avatarCompositionType": "SINGLE_BODY",
  "avatarBodyUri": "...",
  "avatarFaceUri": null,
  "avatarIconUri": "...",
  "combinePositionX": 0,
  "combinePositionY": 0,
  "offsetX": 0.0,
  "offsetY": 0.0,
  "scale": 1.0
}

// After (중첩)
{
  "crewId": 42,
  "nickname": "DJ_파티왕",
  "avatar": {
    "avatarCompositionType": "SINGLE_BODY",
    "avatarBodyUri": "...",
    "avatarFaceUri": null,
    "avatarIconUri": "...",
    "combinePositionX": 0,
    "combinePositionY": 0,
    "offsetX": 0.0,
    "offsetY": 0.0,
    "scale": 1.0
  }
}
```

> **프론트엔드 조치**:
> - `partyroom_access` 이벤트 핸들러를 `crew_entered` / `crew_exited` 2개로 분리하세요.
> - `accessType` 필드 기반 분기 로직은 제거하세요.
> - 아바타 필드 접근: `crew.avatarBodyUri` → `crew.avatar.avatarBodyUri`로 변경하세요.
> - `crew_profile_changed`도 동일: `event.avatarBodyUri` → `event.avatar.avatarBodyUri`
