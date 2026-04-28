# PFPlay Admin Platform — Security Design (§5)

> Companion to `2026-04-19-admin-platform-design.md`. 본 문서는 §5 Security만을 다룬다.
> 인증, 인가, 쿠키 전략, 하드닝을 확정한다.

## 5.0 Summary

- 고객(pfplay-web)은 기존 OAuth2 (PKCE) 유지. 변경 없음.
- 어드민(pfplay-admin)은 **로컬 이메일 + 비밀번호** 로그인. 소셜 로그인은 MVP 없음.
- 세션은 **JWT in HttpOnly cookie**. 두 개 쿠키로 분리 (AdminAccessToken + SharedSessionToken).
- **SecurityConfig의 `/api/v1/admin/**` permitAll을 최우선으로 제거** (PR 0).
- 하드닝: rate limit, BCrypt cost 12, env 비번 메모리 노출 최소화, `@Valid` 실패 400 처리는 이미 PR #168로 완료.

## 5.1 Authentication Flows

### 5.1.1 고객 (pfplay-web) — 변경 없음

현재 `pfplay-web`은 OAuth2 PKCE 플로우 사용 중:

```
┌──────────────────┐  1. GET /auth/provider/start
│  pfplay-web      │─────────────────────────────────►  Redirect to Google/Twitter
└──────────────────┘
                               ↓
┌──────────────────┐  2. callback (code + state)
│  OAuth provider  │──────────────────────────────────►  /auth/callback
└──────────────────┘                                      │
                                                          ▼
                            3. POST /api/v1/auth/oauth/token
                               (code exchange)
                                                          ▼
                            4. Backend: 
                               - verify code
                               - find/create UserAccount
                               - find/create Member (if new)
                               - mint JWT
                                                          ▼
                            5. Set-Cookie: SharedSessionToken=...;
                                          Domain=.pfplay.xyz;
                                          HttpOnly; Secure; SameSite=Lax
```

**이번 작업에서 변경되는 것**:
- Member 생성 로직이 IAM/Party 분리 모델에 맞춰 재편 (§4.1)
- JWT 클레임 구조 변경 (§5.3)
- 쿠키 이름이 일반 세션용이라는 의미로 명명 변경 (기존 단일 쿠키 → `SharedSessionToken`으로 리네임 권장)

### 5.1.2 어드민 (pfplay-admin) — 신규

```
┌──────────────────┐  1. POST /api/v1/auth/admin/login
│  pfplay-admin    │      { email, password }
│ (admin.pfplay.   │──────────────────────────────────►  Backend
│  xyz)            │
└──────────────────┘                                       │
                                                           ▼
                             2. Backend:
                                - rate limit check (IP + email)
                                - find UserAccount by email
                                  where providerType=LOCAL
                                - bcrypt.verify(password, hash)
                                - find Administrator by userAccountId
                                - mint two JWTs:
                                  - AdminAccessToken (scoped to admin)
                                  - SharedSessionToken (scoped to all subdomains)
                                                           ▼
                             3. Set-Cookie (2개):
                                AdminAccessToken=...;
                                Domain=admin.pfplay.xyz;
                                HttpOnly; Secure; SameSite=Strict
                                
                                SharedSessionToken=...;
                                Domain=.pfplay.xyz;
                                HttpOnly; Secure; SameSite=Lax
```

**이유**:
- **AdminAccessToken은 admin 서브도메인 전용**. CSRF 표면이 최소화됨.
- **SharedSessionToken은 고객 사이트 방문 시 자동 로그인** 역할. "어드민이 Main stage host로 활동" 시나리오 지원.

### 5.1.3 Admin 로그인 실패 응답 정책

- 이메일 존재 여부 노출 금지: "이메일 또는 비밀번호가 올바르지 않습니다"로 통일
- 잠김 상태: "계정이 일시 잠겼습니다. 잠시 후 다시 시도해주세요" (상세 시간 노출 X)
- Rate limit 초과: 429 Too Many Requests

### 5.1.4 Logout

- `POST /api/v1/auth/admin/logout` — 두 쿠키 모두 clear (`Max-Age=0`)
- `POST /api/v1/auth/logout` (기존 고객용) — SharedSessionToken만 clear

## 5.2 Authorization Model

### 5.2.1 Role 정의

MVP:
- `SUPER_ADMIN`: 1명. 어드민 CRUD 등 Administration 메타 관리.
- `ADMIN`: N명. 서비스 운영.

JWT `access_level` 클레임:
- `ROLE_ADMIN` — 모든 Administrator가 공통으로 보유
- `ROLE_SUPER_ADMIN` — SUPER_ADMIN role일 때만 추가 보유
- `ROLE_MEMBER` — Member인 경우 (어드민이 Member도 가지면 둘 다 보유)
- `ROLE_GUEST` — Guest인 경우

### 5.2.2 확장성

MVP는 enum 수준 2-role. 확장 시나리오:

- **세부 권한**: 유저 관리 전용 어드민, 모더레이션 전용 어드민 등
- **방식 A (권장, 후일)**: 별 `admin_role` / `admin_permission` 테이블 (n:n)
  - `administrator.role` 컬럼 유지하되, 실질 권한 체크는 permission 테이블 경유
  - 기존 `ROLE_ADMIN` / `ROLE_SUPER_ADMIN` 단순 체크가 `hasPermission('USER_MANAGE')` 같은 SpEL로 진화
- **방식 B**: `role`에 값 추가 (예: `USER_ADMIN`, `MOD_ADMIN`)
  - 단순하지만 조합이 많아지면 관리 어려움
- **전환 시점 결정 트리거**: 세부 권한이 필요해지는 시점에 설계 재검토

### 5.2.3 SecurityConfig 개편

**PR 0 (첫 우선 작업)** — 임시 permitAll 제거:

```java
// 기존 (버그)
.requestMatchers("/api/v1/admin/**").permitAll()  // Admin API - no auth required (temporary)

// 개편 후 (belt-and-suspenders 기본 가드)
.requestMatchers("/api/v1/admin/system/**").hasRole("SUPER_ADMIN")  // 슈퍼어드민 전용
.requestMatchers("/api/v1/admin/avatar/**").hasRole("SUPER_ADMIN")  // Avatar BC — 과금 영역, 슈퍼어드민
.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")               // 일반 어드민
.requestMatchers("/api/v1/auth/admin/login").permitAll()            // 로그인 엔드포인트만 예외
.requestMatchers("/api/v1/auth/admin/**").authenticated()           // logout/me 등
```

**메서드 수준 `@PreAuthorize`** — 중복 방어:

```java
@PreAuthorize("hasRole('ADMIN')")  // URL rule 외 추가 방어
@PostMapping("/api/v1/admin/partyrooms/{id}/suspend")
public void suspendPartyroom(...) { ... }

@PreAuthorize("hasRole('SUPER_ADMIN')")
@PostMapping("/api/v1/admin/system/administrators")
public void createAdmin(...) { ... }
```

기존 `@PreAuthorize("hasAuthority('FM')")` — admin 보호 목적이었던 것은 전부 위 패턴으로 교체.
(FM 자체는 파티룸 크루 등급 — 어드민 권한 아님. 의미상 잘못된 사용이었음.)

### 5.2.4 중앙화된 권한 SpEL bean (future-proof)

SpEL 문자열이 컨트롤러마다 하드코딩되면 RBAC 확장 시 수백 곳을 고쳐야 함. 중앙 bean으로 추상화:

```java
@Component("adminAuth")
class AdminAuthorizationSpEL {
    public boolean isSuperAdmin() {
        return hasRole("SUPER_ADMIN");
    }
    public boolean canManageAdmins() {
        return hasRole("SUPER_ADMIN");
    }
    public boolean canSuspendPartyroom() {
        return hasRole("ADMIN");
    }
    public boolean canChangeMemberTier() {
        return hasRole("ADMIN");
    }
    public boolean canManageAvatarResources() {  // §6.I
        return hasRole("SUPER_ADMIN");
        // 추후 RBAC 세분화 시 AVATAR_WRITE permission으로 변경 가능
    }
    // ...
}
```

```java
@PreAuthorize("@adminAuth.canManageAdmins()")
public void createAdmin(...) { ... }

@PreAuthorize("@adminAuth.canManageAvatarResources()")
public void publishAvatarResource(...) { ... }
```

MVP에선 이 bean 메서드가 단순 role 체크이지만, 확장 시 permission 테이블 조회로 바뀌어도 컨트롤러 코드 불변.

**Avatar BC 적용**: `avatar` 모듈의 어드민 컨트롤러(`AdminAvatarCommandController`)는 `@adminAuth.canManageAvatarResources()` 사용. Avatar는 **SUPER_ADMIN 전용** — 과금 직결 영역이라 최초 출시 시점에 엄격 가드하고, 운영 규모 확대 시 RBAC 세분화(§11.1.3)로 일반 ADMIN에 선택 부여.

## 5.3 JWT Claim Structure

### 5.3.1 신규 클레임 구성

```json
{
  "sub": "1000000000000042",        // userAccountId
  "email": "admin@pfplay.xyz",
  "access_level": ["ROLE_ADMIN"],    // Spring Security authorities
  "iat": 1700000000,
  "exp": 1700000900                  // 15분 (어드민 토큰) or 24h (공유 세션)
}
```

**원칙**:
- `sub`는 userAccountId (모든 컨텍스트의 공유 축)
- `access_level`만 보유 — `administratorId` / `memberId`는 **클레임에 넣지 않는다**
  - 필요 시 `userAccountId`로 리포지토리 조회 (각 컨텍스트의 책임)
  - 토큰이 cross-context aggregate가 되는 걸 방지
- 최소 클레임 원칙: 토큰은 "누구인가"만 담고, 각 컨텍스트 내부 정보는 조회

**기존 `authority_tier` 클레임 처리**:
- Amplitude 분석에서 사용 중 (pfplay-web)
- 기존 JWT에는 유지하되 optional로 변경. Member가 없는 어드민은 null.
- 대안: `/api/v1/users/me/info` 응답에서 매번 조회 (클레임 제거)
- 결정: MVP에선 **클레임에 포함 유지** (Amplitude 통합 깨짐 방지), Member 없는 경우 null 허용

### 5.3.2 두 JWT의 속성 차이

| 항목 | AdminAccessToken | SharedSessionToken |
|---|---|---|
| Cookie Domain | `admin.pfplay.xyz` | `.pfplay.xyz` (leading dot) |
| SameSite | `Strict` | `Lax` |
| 용도 | 어드민 API 호출 | pfplay.xyz, admin.pfplay.xyz 전역 세션 |
| access_level | `[ROLE_ADMIN, ROLE_SUPER_ADMIN?]` | `[ROLE_MEMBER]` (해당 Member 있을 때) |
| TTL | 15분 (권장) + refresh | 24h |
| 발급 시점 | 어드민 로그인 | OAuth 콜백 또는 어드민 로그인 |

### 5.3.3 CustomJwtAuthenticationConverter 재설계

현재 `CustomJwtAuthenticationConverter`:
- `uid`, `email`, `access_level`, `authority_tier`, `provider` 클레임 읽음

신규:
- `sub` (=userAccountId), `email`, `access_level`, `authority_tier` (nullable) 읽음
- `access_level`을 `Collection<GrantedAuthority>`로 변환
- 결과 Authentication에 userAccountId, email, roles 담음
- memberId/administratorId는 service 레이어에서 lookup (지연 조회)

### 5.3.4 API 요청 분리

| Path prefix | 필요 쿠키 | 검증 순서 |
|---|---|---|
| `/api/v1/admin/**` | **AdminAccessToken** | (1) filter가 admin 쿠키 요구 (2) `hasRole('ADMIN')` |
| `/api/v1/admin/system/**` | AdminAccessToken | + `hasRole('SUPER_ADMIN')` |
| `/api/v1/auth/admin/login` | — (로그인 전) | permitAll |
| `/api/v1/auth/admin/logout` | AdminAccessToken | 인증된 admin만 |
| `/api/**` (일반 API) | SharedSessionToken | 기존 JWT Resource Server 그대로 |
| `/api/v1/auth/oauth/**` | — | permitAll |

→ 커스텀 `BearerTokenResolver`를 두 개 운영: admin API에는 AdminAccessToken 쿠키를, 일반 API에는 SharedSessionToken 쿠키를 추출.

## 5.4 Cookie Strategy

### 5.4.1 왜 두 개인가 — CSRF & 보안 이유

`admin.pfplay.xyz`와 `pfplay.xyz`는 **same-site** (eTLD+1 = pfplay.xyz). 따라서:

- `SameSite=Lax`: 같은 site(`pfplay.xyz`) 내에서는 cross-origin 요청 시 쿠키가 자동 첨부됨
- `pfplay.xyz`에 XSS가 있거나 악성 스크립트가 주입되면 → `admin.pfplay.xyz`로의 POST에 admin 쿠키가 자동 첨부되어 CSRF 가능
- `SameSite=Strict`도 **same-site 내 이동은 허용**하므로 완벽한 격리 아님 (단, 타 사이트의 악성 링크 통한 navigation은 차단)

**해결**:
- 어드민 API용 쿠키는 `Domain=admin.pfplay.xyz` (leading dot 없음) → admin 서브도메인에만 전송
- 공유 세션용 쿠키는 `Domain=.pfplay.xyz` → 모든 서브도메인
- XSS on pfplay.xyz → `SharedSessionToken`만 탈취됨 (admin 쿠키 도달 안 함)
- XSS on admin.pfplay.xyz → 양쪽 쿠키 모두 영향 (근본적 방어 필요: admin.pfplay.xyz의 XSS 위생 확보)

### 5.4.2 쿠키 속성 정책

**AdminAccessToken**:
```
Name: AdminAccessToken
Domain: admin.pfplay.xyz     (leading dot 없음 — 서브도메인 상속 X)
Path: /
HttpOnly: true
Secure: true
SameSite: Strict             (최대 격리)
Max-Age: 900                 (15분; refresh로 연장)
```

**SharedSessionToken**:
```
Name: SharedSessionToken     (기존 AccessToken 리네임)
Domain: .pfplay.xyz          (leading dot — 서브도메인 공유)
Path: /
HttpOnly: true
Secure: true
SameSite: Lax                (기존 유지)
Max-Age: 86400               (24시간)
```

### 5.4.3 CSRF 방어

어드민 API에서 추가로:

**옵션 A (권장)**: CSRF 토큰 재활성화
- Spring Security CSRF filter 활성
- 프런트엔드가 `GET /api/v1/admin/csrf-token` 호출해 토큰 획득
- 상태 변경 요청 시 `X-CSRF-TOKEN` 헤더로 전송
- 서버는 쿠키의 double-submit 패턴 or HMAC으로 검증

**옵션 B**: Origin/Referer 헤더 화이트리스트
- `Origin: https://admin.pfplay.xyz` 아닌 요청 거부 (admin API 한정)
- 브라우저가 모든 상태 변경 요청에 Origin 전송 (Chrome 76+)
- 간단하지만 브라우저 특정 구현에 의존

**추천: A + B 조합** — defense in depth.

### 5.4.4 Subdomain 관리

- `Domain=.pfplay.xyz`는 **모든** `*.pfplay.xyz`에 유효 (stg-admin, admin, www, api 등)
- 미래 임시 서브도메인(프리뷰, 벤더 마이크로사이트) 등을 다른 eTLD+1 하위로 둘 것 권장
- **절대로** `pfplay.xyz` 아래에 "신뢰할 수 없는" 서브도메인 두지 말 것 (SharedSessionToken이 자동 첨부됨)

## 5.5 Password Policy & Hardening

### 5.5.1 BCrypt

- Cost factor: **12** (MVP 기준)
- Java Spring Security `BCryptPasswordEncoder(12)` 사용
- 로그인 latency 목표: ~250ms on GCP VM
- 측정 후 필요 시 11로 조정 (단, 보안 팀 승인 없이 11 미만 금지)

### 5.5.2 비밀번호 복잡도

MVP:
- 최소 12자
- 소문자/대문자/숫자/특수문자 중 3개 이상 포함
- 일반 사전 단어 / 흔한 패턴 거부 (기본 라이브러리 수준)

검증 위치:
- **Admin CRUD API (어드민 생성/비번 변경)**에서 DTO @Valid로 강제
- Self-service 비번 변경도 동일

### 5.5.3 Rate Limiting (로그인 엔드포인트)

**`/api/v1/auth/admin/login`**:
- IP 기준: 5분 내 10회 초과 시 15분 잠금
- 이메일 기준: 실패 5회 연속 시 해당 계정 15분 잠금
- 둘 중 먼저 걸리는 조건 적용

구현: Spring Boot에 `bucket4j-spring-boot-starter` 추가, in-memory 또는 Redis backed

### 5.5.4 Env 비번 핸들링 (Super Admin Seeding)

```java
// ApplicationReadyEventListener (또는 SuperAdminSeedService)
@Value("${ADMIN_SEED_PASSWORD:#{null}}")
private String adminSeedPassword;  // 1회만 읽고 폐기

@EventListener(ApplicationReadyEvent.class)
public void finalizeSuperAdmin() {
    if (placeholderExists()) {
        if (adminSeedPassword == null) {
            log.error("ADMIN_SEED_PASSWORD env not set — cannot finalize super admin");
            throw new IllegalStateException("super admin password missing");
        }
        String hash = bcrypt.encode(adminSeedPassword, 12);
        updatePlaceholder(hash);
        
        // 메모리 노출 축소
        adminSeedPassword = null;
    }
}
```

- env variable은 1회 읽기 → bcrypt → reference `null` 처리
- 재부팅 시 placeholder 없으면 no-op (이미 교체됨)
- placeholder 있는데 env 없으면 앱 기동 실패 (운영상 필수)

### 5.5.5 계정 lockout & recovery

- 연속 실패 5회 → 15분 lockout
- Lockout 상태 UI 표시 없이 일반 "invalid credentials" 응답
- 슈퍼어드민이 lockout 수동 해제 가능: `DELETE /api/v1/admin/system/administrators/{id}/lockout`
- 슈퍼어드민 본인 lockout 시: env 기반 재시드 or DB 수동 수정 (운영 매뉴얼 별도)

### 5.5.6 Audit of 인증 이벤트

로그인 성공/실패는 `user_activity_log`의 `SIGNED_IN` 이벤트로 기록 (admin/member 공통).

추가 metadata:
- `{"success": true, "method": "LOCAL"}` 성공
- `{"success": false, "reason": "WRONG_PASSWORD"}` 실패 (단, audit 상 상세 정보 보존)

## 5.6 Admin Password Reset Flow (MVP)

어드민이 자기 비밀번호 변경:
- `POST /api/v1/admin/password/change` (PR 6 시점 변경 — 아래 NOTE 참조)
  - Request: `{ currentPassword, newPassword }`
  - 검증: currentPassword bcrypt.verify + 복잡도 체크 (`AdminPasswordPolicy`: 최소 10자 + upper/lower/digit/symbol 각 1개)
  - 성공 시 password_hash 업데이트 + `must_change_password=false`로 클리어
  - **응답: 204 No Content. 토큰 회전 없음** — 기존 AdminAccessToken은 자체 TTL 만료 시까지 유지 (PR 6 Decision 16: 재로그인 UX 회피)

> **NOTE (PR 6 path divergence):** 본 spec은 원래 `/api/v1/auth/admin/password/change` 경로로 명시했으나, PR 4의 `CookieBearerTokenResolver`가 `/api/v1/admin/**` 프리픽스에서만 AdminAccessToken 쿠키를 픽업하므로 (그 외 경로는 SharedSessionToken 폴백) PR 6에서 `/api/v1/admin/password/change`로 이전했다. URL rule은 기존 `/api/v1/admin/**` → ROLE_ADMIN 캐치올로 커버. PR 6 Decision 4 참조.

슈퍼어드민이 타 어드민 비번 **리셋**:
- `POST /api/v1/admin/system/administrators/{id}/reset-password`
  - Response: 새 임시 비번 (첫 로그인 시 변경 강제)
  - 안전 채널로 전달 (슬랙 DM 등)
  - 메타 플래그 `must_change_password_at_next_login` 설정 (V11 마이그레이션, PR 6)
  - 해당 어드민 로그인 시 변경 화면으로 강제 유도 — 로그인 응답 본문에 `mustChangePassword=true` 노출, 프론트엔드가 리다이렉트 (PR 6 Decision 3: JWT 클레임/서버측 잠금 미도입, MVP는 UX 가이드만)
- 임시 비밀번호 길이는 **12자** (서버측 `TempPasswordGenerator` 생성, `[A-Z][a-z][0-9][!@#$%^&*]` 각 1자 보장, 시각 혼동 문자 `I O l o 0 1` 제외). 본 spec의 §6.F-1 예시 "Xk9@aB2z" (8자)는 PR 6 Decision 5에 따라 12자로 상향 — 8자 약 49비트 vs 12자 약 74비트.

## 5.7 Security Testing Requirements

MVP 전에 반드시 커버:

1. **권한 회귀 테스트**: `@RequestMapping("/api/v1/admin/**")` 모든 메서드에 대해
   - 인증 없음 → 401
   - `ROLE_MEMBER` 토큰 → 403
   - `ROLE_ADMIN` 토큰 → 200
   - `/system/**`은 `ROLE_SUPER_ADMIN`만 → 200, `ROLE_ADMIN`은 403
   - parameterized test로 일괄 실행 (1시간 작업)

2. **쿠키 격리 테스트**:
   - SharedSessionToken만 있고 AdminAccessToken 없을 때 `/admin/**` → 401
   - AdminAccessToken만 있고 SharedSessionToken 없을 때 `/admin/**` → 200, `/users/me/**` → 401

3. **CSRF 테스트**:
   - `Origin: https://evil.com`에서 `/admin/**` POST → 403
   - CSRF 토큰 누락 시 403

4. **Rate limit 테스트**:
   - 11회 연속 틀린 로그인 → 429

5. **Placeholder 대체 idempotency**:
   - 앱 재시작 시 placeholder 없으면 no-op
   - placeholder 있고 env 없으면 기동 실패

---

**다음 문서**: `2026-04-19-admin-platform-features.md` (§6, §7)
