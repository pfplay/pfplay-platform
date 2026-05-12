# ADR-006: 어드민 CSRF 토큰 방어 (Option A)

## Status
Accepted

> **작성일**: 2026-04-27
> **관련 spec**: `docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.4.3`
> **관련 plan**: `docs/superpowers/plans/2026-04-27-admin-platform-pr5.md`

## Context

PR 4에서 어드민 CSRF 방어의 Option B(Origin/Referer 헤더 allowlist)를 ship했다. Option A(토큰 기반 double-submit)는 `SecurityConfig` 전면 재배치가 필요해 PR 5로 미뤄졌다. 어드민 외 경로의 stateless JWT 호환을 깨지 않으면서 CSRF를 선택적으로 재활성화하는 것이 까다로웠기 때문이다.

Spec §5.4.3은 A + B를 defense in depth로 권장한다. Option A는 `Origin` 헤더를 위조할 수 있는 공격자(브라우저 버그가 있거나 `admin.pfplay.xyz`의 XSS를 노린 non-browser 클라이언트)를 막는다.

## Decision

Spring Security의 표준 `CookieCsrfTokenRepository.withHttpOnlyFalse()`를 `setCookieCustomizer`로 구성해 `admin.pfplay.xyz` 도메인 한정으로 `XSRF-TOKEN` 쿠키를 발급한다. 어드민 프론트(pfplay-admin)는 쿠키 값을 읽어 모든 상태 변경 요청에 `X-XSRF-TOKEN` 헤더로 echo한다.

### 적용 범위

CSRF 검증은 다음 조건이 **모두** 만족할 때 강제된다:
1. HTTP 메서드가 {POST, PUT, PATCH, DELETE} 중 하나 (상태 변경)
2. 경로가 `/api/v1/admin/**` 또는 정확히 `/api/v1/auth/admin/logout`

CSRF 검증을 **건너뛰는** 경우: `/api/v1/auth/admin/login` (세션이 아직 없음), 그 외 모든 비-어드민 경로 (OAuth 흐름·일반 회원 sign-in 등 기존 동작 유지).

### 쿠키 형태

| 속성 | 값 | 근거 |
|---|---|---|
| Name | `XSRF-TOKEN` | Spring 기본값; pfplay-admin의 axios 표준 설정이 자동 인식 |
| Domain | `admin.pfplay.xyz` (선두 점 없음) | `AdminAccessToken`과 동일 격리 |
| Path | `/` | 어드민 프론트가 `/` 하위 경로를 호출 |
| Secure | true (기본), false (local/test) | 배포 환경에서는 TLS 전용 |
| HttpOnly | **false** | 프론트 JS가 값을 읽어야 함 |
| SameSite | Strict | `AdminAccessToken`과 정렬 |

### 프론트 계약 (pfplay-admin 팀용)

1. 어드민 로그인 성공(`POST /api/v1/auth/admin/login`) 후, `/api/v1/admin/**`의 첫 GET 응답에서 `XSRF-TOKEN` 쿠키가 발급됨
2. 프론트는 `document.cookie`에서 `XSRF-TOKEN`을 읽음 (HttpOnly 아니므로 접근 가능)
3. 모든 상태 변경 요청(POST/PUT/PATCH/DELETE)에 `X-XSRF-TOKEN: <cookie value>` 헤더 포함
4. 서버 측 `CsrfFilter`가 헤더와 쿠키를 비교; 불일치 또는 누락 → 403

Axios 사용자는 `axios.defaults.xsrfCookieName = 'XSRF-TOKEN'; axios.defaults.xsrfHeaderName = 'X-XSRF-TOKEN'`로 자동 처리.

## Consequences

### Positive
- 어드민 프론트는 axios 인터셉터 한 줄 추가만으로 자동 처리
- Origin/Referer guard(PR 4)와 결합되어 defense in depth 확보
- Spring 표준 패턴이라 향후 유지보수 부담 적음

### Negative
- 로컬 개발 시 `secure: false` 분리 필요 — `local` / `test` 프로파일의 `application*.yml` 참고
- HttpOnly=false 트레이드오프: 프론트 JS가 쿠키를 읽어야 하므로 XSS 발생 시 토큰 노출 가능 (origin guard로 보완)

## 채택하지 않은 대안

- **HMAC 파생 per-session 토큰** — MVP에 과도; 쿠키-헤더 페어 자체로 충분히 결합
- **별도 `GET /admin/csrf-token` 엔드포인트** (spec §5.4.3 제안) — Spring 표준이 safe 응답마다 쿠키 발급으로 동등하고 더 간단
- **admin / 일반용 `SecurityFilterChain` 분리** — plan Decision 9 참고; 보류

## Verification

- `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminCsrfIntegrationTest.java`: 토큰 누락 → 403, 토큰 불일치 → 403, 정상 토큰 → 200/기대값
- 프론트 통합은 pfplay-admin CI에서 검증; 본 리포 범위 밖
