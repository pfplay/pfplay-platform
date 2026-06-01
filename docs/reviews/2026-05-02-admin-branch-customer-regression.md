# Customer Regression Review — feature/admin-auth-iam-schema

**날짜**: 2026-05-02
**브랜치**: `feature/admin-auth-iam-schema` @ `9ddc0f95` *(3회 rebase + 6 polish: PA-7.1 / PA-3 / A2-A5; 원본 `3b53fb9f` → safety branches `backup-before-rebase{,2,3}-2026-05-02`. Polish 6건 = 회귀 리뷰 4건(PA-7.1, PA-3, U-2/3/4 검증) + admin frontend ask 4건(A2-A5))*
**Base**: main @ `1cf0599e` (merge-base)
**Tier**: B (배포 전 QA 백업 — 정적 분석 + 실행 검증 + 컨트랙트 매칭)
**대상**: pfplay-web (대고객) 영향 회귀
**상태**: 정적 리뷰 + 로컬 docker compose 실행 검증 완료. 신규 P0 2건은 근본 원인 commit에 amend rebase로 해소 (자세히는 §7).

---

## 1. Scope

**Customer surface 정의**:
- (a) `pfplay-web/swagger.json` 등록 endpoint (39개, admin 0개)
- (b) pfplay-web 소스에서 호출하는 endpoint
- (c) 세션/인증 플로우 (cookie, JWT, OAuth, guest sign)

**조사 대상 모듈** (current branch):
- `app/api/auth/**` — OAuth/세션 (1 controller)
- `app/api/party/**` — 파티룸 (15 controllers)
- `app/api/partyview/**` — 파티룸 setup (1 controller)
- `playlist/**` — 플레이리스트 (5 controllers)
- `realtime/**` — heartbeat (1 controller)
- `user/**` — 유저/프로필/지갑/아바타/사인업 (8 controllers)
- `common/**` — 보안/JWT/쿠키 인프라 (공통)

**검토 제외**: `app/api/admin/**`, `app/api/administration/**` (admin surface — 단, customer 도메인을 호출하는 부분이 있다면 영향 추적)

---

## 2. Change Inventory (vs main `1cf0599e`)

### 2.1 변경 규모

| 분류 | 수 |
|---|---|
| Added | 323 |
| Modified | 159 |
| Deleted | 13 |
| Renamed (similarity ≥50) | 8 |
| **Total** | **503** |
| 삽입/삭제 LOC | +64,906 / −2,369 |

### 2.2 모듈 구조 변경

- **Multi-module Gradle**: `common`, `realtime`, `playlist`, `user`, `avatar`(NEW), `app`
- **`avatar/` 모듈 신설** — 아바타 리소스(Body/Face/Icon Data·DTO·Repo·Uri·ObtainmentType)가 `user/`에서 분리·이동

### 2.3 Customer-impacting 삭제

| 삭제 파일 | 영향 |
|---|---|
| `common/.../jwt/CookieUtil.java` | **세션 쿠키 유틸 완전 삭제 (−97 LOC, 대체 파일 없음)** → `SharedSessionCookieWriter`/`AdminCookieWriter`로 재구성된 것으로 추정. 모든 customer 인증 경로가 신규 writer로 정상 마이그레이션됐는지 P0 검증 대상 |
| `common/.../CookieUtilTest.java` | 위 함께 삭제 |
| `common/.../CookieBearerTokenResolverTest.java` | 토큰 추출 테스트 삭제 — 신규 테스트로 대체됐는지 확인 필요 |
| `user/.../AdminUserInitializeService.java` | 어드민 초기화 서비스. customer 영향 없을 것으로 보이나 확인 |
| `user/.../enums/PairType.java` | 사용처 잔존 여부 확인 필요 |
| `user/.../{Avatar*}ResourceData.java` | avatar 모듈로 이동 (기능 보존 가정 → 검증 필요) |

### 2.4 Customer-impacting 리네임 (user → avatar)

8개 파일이 `user/` 패키지에서 `avatar/` 패키지로 이동. import 변경이 user 모듈 customer 코드 전반에 영향. 빌드 단계에서 자동 검증.

### 2.5 Customer-side 변경 파일 수 (감 잡기용)

- `common/`, `auth/`, `user/`, `avatar/`, `party/`, `partyview/`, `playlist/`, `realtime/` 합계: **164 파일** 변경

### 2.6 핫스팟 (+LOC 큰 customer-domain 파일)

| 파일 | 변경 LOC |
|---|---|
| `user/.../entity/data/MemberData.java` | +191 |
| `user/.../service/MemberSignServiceTest.java` | +190 |
| `user/.../entity/data/UserAccountData.java` | +169 |
| `user/.../entity/data/UserAccountDataTest.java` | +157 |
| `user/.../service/AvatarResourceQueryServiceTest.java` | −127 (삭제) |
| `user/.../entity/data/GuestData.java` | +79 |
| `user/.../service/initialize/TemporaryUserInitializeService.java` | +62 |
| `user/.../service/UserProfileQueryService.java` | +55 |
| `user/.../service/UserActivityCommandService.java` | +57 |

이들은 모두 customer-facing(가입·프로필·지갑·아바타·신원 초기화)에 직접 닿는 코드. F2/F3/F5 (DTO/응답·DB) 회귀가 가장 발생하기 쉬운 지점.

### 2.7 신규 도메인 이벤트

- `MemberTierChangedEvent`
- `UserAccountWithdrawnEvent`

이벤트 발행이 admin mutation에서 시작 → customer 도메인이 listener를 가지면 부수 효과 발생. listener 매핑 추적 필요.

---

## 3. Static Analysis (F1–F4) — 직접 분석 발견

### 3.1 Party 도메인

| ID | P | 면 | 위치 | 회귀 후보 | 근거 | 권장 검증 |
|---|---|---|---|---|---|---|
| **PA-1** | P2 | F1 | `PartyroomEntrySpecification.java:13-18` | SUSPENDED 룸 진입 거부 (NEW 가드) | admin이 룸을 SUSPENDED로 두면 customer 입장 불가. 그러나 SUSPENDED 상태는 신규 — 기존 데이터엔 존재하지 않음. **의도된 변경**, 회귀 아님. | admin이 SUSPENDED로 바꾼 룸을 customer가 진입 시도 시 `ILLEGAL_STATE_TRANSITION` 반환 확인 |
| **PA-2** | P2 | F1 | `PartyroomRepositoryImpl.java:111` | `GET /api/v1/partyrooms` 리스트가 SUSPENDED 룸 노출 | ✅ **product 의도와 일치** — 노출 OK, 진입은 `PartyroomEntrySpecification`이 차단. **회귀 아님.** |
| **PA-3** | ~~P1~~→해소 | F1 | 동상 | `GET /api/v1/partyrooms` 리스트가 HIDDEN 룸도 노출 — 의도(노출 X, 진입 O)와 불일치 | ✅ **polish commit으로 해소** (§7.10) — list query에 `displayFlag.ne(HIDDEN)` 추가. 진입은 link 직접 접근으로 그대로 가능 |
| **PA-7** | **P0** | F2/F3 | `PartyroomCommandService.createMainStage:42-44` (root) + `QueryPartyroomListResponse:38` (latent) | 부팅 시 `initializeMainStage`가 super-admin을 active crew로 등록 → admin은 profile 없음 → customer `GET /api/v1/partyrooms` 응답 빌드 시 `profileSettings.get(adminUserId)` null → `.avatarIconUri()` NPE → **500** | **§5.5 + 7.8 검증/해소** | ✅ rebase 3차로 해소: `createMainStage`에서 admin `enterByHost` 제거 (admin은 host로만, crew row 없음). 도메인 invariant("프로필 없는 사용자는 partyroom에 active crew로 등록하지 않는다")와 일치. |
| **PA-4** | P2 | F3 | `PartyroomData.java` ↔ `PartyroomWithCrewDto`/`ActivePartyroomDto` | 응답 DTO 자체는 변경 없음 (git diff 비어있음) — `status`/`displayFlag`/`crewCount`/`lastActivityAt` 필드가 customer 응답에 누출되지 않음 | DTO 파일 변경 0건 | 안심 |
| **PA-5** | P1 | F2/F6 | `PartyroomAccessCommandService.java:70-127` | `tryEnter` 재진입 시 ENTER 이벤트 발행 안 함 (idempotent). 동일 룸 재연결 시 카운터 inflate 차단 — **거동 변경** | 신규 `ensureCrewActive` + `transitioned` flag | 재연결 시 채팅/카운터에 ENTER 이벤트 발생 안 하는 것이 frontend 가정과 맞는지 검증 |
| **PA-6** | P2 | F5 | V6 migration | `is_terminated BOOLEAN → status ENUM` 데이터 이관. 기존 row는 `ACTIVE` default + terminated만 `TERMINATED`로 업데이트 → 안전 | V6 SQL line 17 `WHERE is_terminated = 1` | OK |

### 3.2 Auth/Cookie/JWT 도메인

| ID | P | 면 | 위치 | 회귀 후보 | 근거 | 권장 검증 |
|---|---|---|---|---|---|---|
| **A-1** | **P0** | F2/F3 | `common/.../jwt/properties/SharedCookieProperties.java:7` + `application.yml:130` | **쿠키 이름이 `AccessToken` → `SharedSessionToken`로 변경**. 기존 브라우저의 `AccessToken` 쿠키는 더 이상 인증되지 않음. swagger의 `cookieAuth.name=accessToken`과도 불일치 | main: `accessTokenName="AccessToken"`. branch: `name="SharedSessionToken"`. swagger: `securitySchemes.cookieAuth.name="accessToken"` | (1) 배포 시 모든 기존 세션 강제 로그아웃 발생 — 공지 필요. (2) swagger 재생성. (3) HttpOnly이므로 JS가 직접 읽진 않으나 서버가 새 이름으로만 리졸브 |
| **A-2** | **P0** | F2 | `common/.../jwt/CustomJwtAuthenticationConverter.java:24-42` | **JWT claim 스키마 변경**. `uid` claim → `sub`(subject), `access_level` String → List&lt;String&gt;, `provider` 제거, `authority_tier` 필수→선택. 기존 토큰이 무효화됨 | branch: `UserId.fromString(subject) + jwt.getClaim("access_level")`(List). main: `UserId.fromString(jwt.getClaim("uid")) + AccessLevel.valueOf(jwt.getClaim("access_level"))`(String) | 모든 기존 토큰 무효화. 401 응답 깨끗이 떨어지는지 IT로 확인 |
| **A-3** | P1 | F2 | `app/src/test/resources/application-test.yml:80-88` | 테스트 설정이 구 JwtProperties 스키마(`access-token-name` 등) 사용. strict binding이면 기동 실패 | application-test.yml의 구 키들이 신규 `JwtProperties.cookie.shared.name` 등으로 마이그레이션 안 됨 | strict binding 여부 확인 + 테스트 yml 마이그레이션 |
| **A-4** | P1 | F1 | `common/.../SecurityConfig.java:87-101` | `/api/v1/admin/**`가 `permitAll` → `hasRole`. customer 매처/필터 체인 영향 없음. customer 코드가 admin path 호출하지 않는다면 무관 | branch: `requestMatchers("/api/v1/admin/system/**").hasRole("SUPER_ADMIN")` etc. main: `permitAll` (TODO temporary) | pfplay-web에서 `/api/v1/admin` 호출 grep → 없으면 안전 |
| **A-5** | P1 | F1 | `common/.../web/AdminOriginGuardFilter.java:21-39` | AdminOriginGuard는 admin path scope로 한정 — customer 무관 | `ADMIN_PATH_PATTERN = "/api/v1/admin/**"` | 정상 |
| **A-6** | P1 | F1 | `common/.../SecurityConfig.java:57-79` | CSRF가 admin-only로 enable. customer logout/oauth callback POST는 매칭 안 됨 — 정상 | `AdminCsrfRequestMatcher.matches()`는 admin path-prefix만 검사 | customer logout/oauth IT 회귀 |
| **A-7** | P2 | F3 | `app/auth/.../AuthController.java:83` + `AuthService.java:81` | OAuth callback `expiresIn` 단위가 ms 그대로 전달됨. swagger 스키마가 초/ms 어느 쪽인지 UNCERTAIN | `new AuthResult(token, "Cookie", jwtProperties.getSharedSessionTokenExpirationMs(), …)` | swagger `LoginOAuthResponse` 스키마 비교 |
| **A-8** | P2 | F4 | `SecurityConfig.java:71-77` | CSRF 실패 응답이 `{status, error}` 포맷 — `ApiErrorResponse{status, errorCode, message}` 표준과 다름. customer 무관 (admin only) | `res.getWriter().write("{\"status\":403,\"error\":\"...\"}")` | admin 클라이언트만 영향 |
| **A-9** | P2 | F1 | `SecurityConfig.java:99-100` | `/api/v1/auth/admin/**`가 authenticated. login은 89행 permitAll에 포함되어 안전. customer 무관 | matcher 순서 검증됨 | 정상 |
| **A-10** | P2 | F6 | `CookieBearerTokenResolverTest.java`, `CookieUtilTest.java` 삭제 | 회귀 가드 테스트 삭제. 신규 분기(admin/customer cookie) 커버리지 미확인 | git diff D D | `AdminCookieIsolationIntegrationTest` 커버리지 점검 |
| **A-11** | P2 | F2 | `SharedCookieProperties.java:11` + `application.yml:131,134` | SameSite default `None`(default 프로파일), prod `Lax`. `secure: true`로 None 안전. cross-origin OAuth 콜백 정상. `COOKIE_DOMAIN` env 실제값 UNCERTAIN | `same-site: None / secure: true` (default), prod `Lax / secure: true` | 운영 환경의 `COOKIE_DOMAIN` 실제값 확인 |

#### 핵심 결론 (Agent A 답변 요약)

1. **CookieUtil 삭제는 안전한가**: **부분 NO (P0).** SharedSessionCookieWriter가 발행 동작 자체는 동등하나 **이름 변경으로 기존 세션 무효화** + swagger와 불일치.
2. **`/sign/temporary/full-member`의 토큰 재발급**: **YES.** 컨트롤러가 새 토큰을 새 쿠키로 정확히 재발행.
3. **SecurityConfig의 customer 권한 매트릭스**: **거의 YES.** `/api/v1/admin/**` permitAll→hasRole만 변경, customer 경로는 동일.
4. **AuthController 시그니처/DTO**: **YES (쿠키 이름만 NO).** path/method/status/응답 DTO 모두 swagger와 일치, 쿠키 이름만 어긋남.

#### 잔여 의심 지점 (Agent A)

- swagger.json 재생성 필요 (현 mtime 2026-04-19, 신규 admin path/cookie name 미반영)
- `COOKIE_DOMAIN` 운영값 확인 (`.pfplay.xyz` vs `pfplay.xyz`)
- `AdminCookieIsolationIntegrationTest` 분기 커버리지
- JwtProperties strict binding 여부 → 기동 영향
- `provider` claim 제거 영향 (Amplitude 등 분석 코드 grep 필요)
- OAuth callback `expiresIn` 단위 (ms vs sec) 프론트 해석 점검

### 3.3 User/Avatar 도메인 — pfplay-web 컨트랙트 매칭

pfplay-web MSW 핸들러(`src/shared/api/__test__/handlers.ts`)의 프론트 기대치:

**`/v1/users/me/info`** (handlers.ts:127): `uid`, `email`, `authorityTier`, `registrationDate`, `profileUpdated` (5필드)
**`/v1/users/me/profile/summary`** (handlers.ts:140): 13필드 (nickname/introduction/avatar*Uri/walletAddress/activitySummaries/offset/scale)
**`/v1/users/me/profile/avatar/faces`** (handlers.ts:390): `[{id, name, resourceUri, available}]`

| ID | P | 면 | endpoint/파일:라인 | 회귀 후보 | 근거 | 권장 검증 |
|---|---|----|--------------------|-----------|------|-----------|
| **U-1** | **P0** | F3 | `GET /api/v1/users/me/profile/avatar/bodies` `avatar/.../AvatarBodyDto.java:20,28` | `AvatarBodyDto` 응답에 신규 필드 2개 누출: **`iconUri`**, **`lifecycleStatus`** | swagger 10필드 vs 코드 13필드. lombok `@Data` getter → Jackson 자동 직렬화. merge-base의 user 모듈 AvatarBodyDto에 없던 필드 | 실제 응답 호출해 JSON 키 비교. pfplay-web parser가 strict인지 lenient인지 확인 |
| **U-2** | P1 | F1 | `GET .../avatar/bodies` `AvatarCatalogQueryService.java:28-32` | `findPublishedBodies()`가 `lifecycleStatus=PUBLISHED` 필터. 옛 `findAll()`은 미필터 | 신규 쿼리. seed/마이그레이션이 모든 기존 row를 PUBLISHED로 backfill했는지 미확인 | DB의 `avatar_body_resource.lifecycle_status` 분포 확인. 모두 PUBLISHED면 OK |
| **U-3** | P1 | F3 | `GET .../avatar/faces` `AvatarCatalogQueryService.java:79-86` | `AvatarFaceDto.available` 산출이 `obtainableType==BASIC` 비교로 **변경**. 옛 코드는 hardcoded `true` | 신규 toFaceDto 매핑. non-BASIC face row가 존재하면 응답값 변경 | row 분포 확인 + 응답 비교 |
| **U-4** | P1 | F1 | `GET .../avatar/faces` `AvatarCatalogQueryService.java:35-39` | `findPublishedFaces()`도 PUBLISHED 필터 (U-2와 동일 패턴) | 동상 | 동상 |
| **U-5** | P2 | F3 | `GET /users/me/profile/summary` `UserProfileQueryService.java:46-83` + `MemberData.java:127-149` | 응답 빌드 경로 재구성. **13필드 일치 (회귀 없음)**. Guest 경로에 walletAddress null check 추가됨(NPE 회귀 *해소*) | `ProfileSummary` record 필드 동일. `QueryMyProfileSummaryResponse.from(...)` 매핑 동일 | 회원/게스트 양쪽 13필드 모두 존재 확인 |
| **U-6** | P1 | F5 | `GET .../summary` Member 경로 `MemberData.java:144` | walletAddress null인 Member(AM) 호출 시 NPE → 500. **옛/신 동등 위험** (회귀 아님) | `getWalletAddress().getValue()` 직접 호출, null 체크 없음 | wallet 미설정 AM이 호출하는 경로가 실제로 존재하는지 frontend 확인 |
| **U-7** | P2 | F3 | `GET /users/me/info` `MyInfoResult.java:9-15` | record 5필드 동일. 출처만 `UserAccount` → `Member`/`Guest` 분리. **응답 변경 없음** | swagger와 1:1 매칭 | 5필드 정확히 노출 확인 |
| **U-8** | P1 | F5 | `GET /users/me/info` `UserInfoQueryService.java:38-43` | UserAccount 있고 Member/Guest 없는 인증 상태에서 `UnauthorizedException("USER_NOT_FOUND")`. 옛: 200 + null tier | 마이그레이션 후 정합성 깨진 row가 있으면 401 | 마이그레이션 후 UserAccount-Member/Guest 1:1 매핑 검증 |
| **U-9** | P2 | F4 | `PUT .../profile/avatar` `UserAvatarCommandService.java:46-53` | non-BASIC body 선택 시 옛: NPE→500, 신: `AVATAR_SELECTION_FORBIDDEN`(403). **bug fix**, swagger 403과 부합 | `orElseThrow(...)` 도입 | 정상 (개선) |
| **U-10** | P2 | F4 | `PUT .../profile/wallet` `UserWalletCommandController.java:42` | `@PreAuthorize`가 `'ROLE_MEMBER'` → `'MEMBER'` 정정. **옛 코드는 `ROLE_ROLE_MEMBER` 요구로 dead path였음**. 신규는 활성화 | Spring Security가 ROLE_ prefix 자동 부여 | merge-base 환경에서 PUT /wallet이 실제 통과했는지 staging 로그 확인 (없으면 신규 활성화) |
| **U-11** | P2 | F2 | request DTO들 (wallet/bio/avatar) | **변경 0건**. swagger 100% 매칭 | git diff 결과 request DTO 미변경 | 회귀 없음 |
| **U-12** | P2 | F3 | `POST /sign/temporary/{full,associate}-member` | 응답 200 + `ApiCommonResponse.ok()` (data=null). **옛/신 동일** | swagger `ApiCommonResponseVoid` | 응답 형식 비교 |
| **U-13** | P1 | F5 | `POST .../full-member` `EasyUserManagementController.java:54-56` | full-member 생성: `addAssociateMember → upgradeMember` 동기 호출. **2-step INSERT** (UserAccount + Member), `@Transactional` 클래스 레벨로 부분 실패 시 전체 롤백 | 트랜잭션 boundary 보존 | 트랜잭션 단위 IT |
| **U-14** | P2 | F3 | `POST /guests/sign` `GuestSignController.java:39` | 응답 200 + ok(). token claim source 변경 (`guest.getUserId()` → `guest.getUserAccountId().toString()`). uid 값 동일 | userAccountId == userId.uid 보장 | 후속 `/me/info` sub claim 일치 확인 |
| **U-15** | P2 | F5 | `POST /guests/sign` `GuestSignService.java:30-39` | placeholder email `guest-{uid}@guest.local`로 UserAccount 미리 INSERT. 옛: GuestData만 저장 | UNIQUE(email) 충돌 위험 없음 (uid unique) | placeholder email이 admin 검색에 노출되지 않는지 확인 |
| **U-16** | P2 | F6 | 신규 이벤트 listener | `MemberTierChangedEvent`, `UserAccountWithdrawnEvent` listener는 admin 모듈에만 존재. customer 부수효과 0 | grep 결과 customer caller 0 | 정상 |

#### 핵심 결론 (Agent B 답변 요약)

1. **`/profile/summary` 스키마**: **YES, 동일 13필드**. avatar 모듈 분리는 응답 형태에 영향 없음 (value object만 이동, 직렬화 시 String 평면화).
2. **`/me/info` 신규 필드 누출**: **NO**. MemberData/UserAccountData의 +191/+169 변경은 JPA 매핑 + 도메인 메서드에 한정, 응답 DTO에 노출 없음.
3. **sign endpoint 요청 DTO**: **NO 변경** (request body 모두 empty). 응답도 동일. 다만 internal 2-step INSERT로 동작 변화 (`TemporaryUserInitializeService` +62).
4. **`/avatar/{faces,bodies}` 응답 형태**: **NO (P0 응답 변경)** — AvatarBodyDto에 신규 필드 2개 추가 + Faces의 `available` 의미 변경 + DRAFT/RETIRED 제외 필터.
5. **신규 이벤트 customer 부수효과**: **NO**. listener는 admin 모듈에만, customer endpoint에서 publish 없음.

#### 잔여 의심 지점 (Agent B)

- DB의 lifecycle_status backfill 검증 (U-2/U-4)
- UserAccount-Member/Guest 데이터 정합성 (U-8)
- placeholder email admin 검색 노출 (U-15)
- pfplay-web JSON parser strict 여부 (U-1)
- Member(AM) walletAddress null 호출 경로 (U-6)
- `/wallet` 옛 dead path 실제 사용 여부 (U-10)
- swagger.json 갱신 시점 (모든 ID에 영향)

---

## 4. DB Schema Impact (F5)

### 4.1 신규 마이그레이션 (이 브랜치에서 추가됨, main에는 V1-V3까지만)

| Slot | 파일 | 성격 |
|---|---|---|
| V4 | `refactor_user_account_to_iam.sql` | **🚨 DROP TABLE member, guest, user_account → CREATE TABLE** |
| V5 | `create_administrator.sql` | NEW (admin) |
| V6 | `evolve_partyroom_state.sql` | ALTER + 데이터 이관 (안전) |
| V7 | `create_partyroom_admin_action.sql` | NEW (admin) |
| V8 | `add_punisher_type_to_penalty_history.sql` | ALTER (안전) |
| V9 | `create_system_config.sql` | NEW (admin) |
| V10 | `create_user_activity_log.sql` | NEW (admin) |
| V11 | `add_must_change_password_to_user_account.sql` | ALTER (admin only) |
| V12 | `avatar_bc_restructure.sql` | **⚠️ DROP TABLE avatar_icon_resource** (데이터는 body/face로 이관 후 drop) |
| V13 | `create_partyroom_report.sql` | NEW (customer-facing 신고) |

### 4.2 P0 데이터 손실 위험 — V4

| 항목 | 값 |
|---|---|
| ID | **DB-1** |
| P | **P0** |
| 면 | F5 |
| 파일 | `app/src/main/resources/db/migration/V4__refactor_user_account_to_iam.sql:5-11` |
| 회귀 후보 | `member`, `guest`, `user_account` 테이블을 `DROP TABLE IF EXISTS` 후 재생성 — 운영 데이터가 있다면 **회복 불가능 데이터 손실** |
| 근거 (커밋 메시지) | `ca122c1a feat(iam): add V4 Flyway migration for IAM composition refactor` — 메시지에 "DROP + CREATE strategy (pre-launch)" 명시. 작성자 의도는 "런칭 전, 데이터 없음 가정". |
| 권장 검증 | (1) 현재 운영 DB에 user_account/member/guest 행 수 확인. (2) 행이 있다면 DROP+CREATE 대신 컬럼별 ALTER로 재작성 또는 백업/복구 절차 필수. (3) 행이 0이면 OK (실제로 pre-launch). |

### 4.3 P1 데이터 손실 위험 — V12

| 항목 | 값 |
|---|---|
| ID | **DB-2** |
| P | **P1** |
| 면 | F5 |
| 파일 | `V12__avatar_bc_restructure.sql:54-67` |
| 회귀 후보 | `avatar_icon_resource` 테이블 DROP. `icon_uri`는 body/face 테이블로 이관 (LIKE 매칭 기반 UPDATE) — **이름 prefix 매칭이 100%가 아니면 일부 icon_uri 누락** |
| 근거 | V12:54-64 `INNER JOIN avatar_icon_resource ... ON i.name LIKE 'ava_icon_body_%' AND i.name = CONCAT('ava_icon_', SUBSTRING(b.name, 5))` — naming convention 의존 |
| 권장 검증 | (1) 운영 데이터에서 `ava_icon_body_*`/`ava_icon_face_*` 네이밍 일관성 확인. (2) 매칭 안 된 row 수를 마이그레이션 실행 전 dry-run으로 카운트 — 0이어야 안전. |

### 4.4 partyroom 상태 모델 (V6) — 안전

V6은 `is_terminated BOOLEAN`을 `status ENUM` 3-state로 진화시키며 기존 데이터를 보존(`SET status='TERMINATED' WHERE is_terminated=1`). 신규 `crew_count`, `last_activity_at`, `display_flag`는 안전한 default. 인덱스 추가도 read 안전.

### 4.5 Migration↔Entity 정합성 (로컬 실행 검증으로 발견 — 신규 P0)

**카테고리 회귀**: Hibernate 6.4.4 + MySQL dialect는 `@Enumerated(EnumType.STRING)`을 기본값으로 native `ENUM(...)` 컬럼에 매핑하려 한다. 본 브랜치의 마이그레이션은 일관되게 `VARCHAR(N)` + `CHECK` 패턴을 사용 → 모든 STRING enum 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 명시가 누락된 채 들어왔다. 이 카테고리는 정적 리뷰 + 단위 테스트(H2 + ddl-auto=create-drop)에서는 검출 불가하며, **MySQL + ddl-auto=validate** 조합에서만 드러난다.

| 항목 | 값 |
|---|---|
| ID | **DB-3** |
| P | **P0** |
| 면 | F5 |
| 위치 | `avatar/.../AvatarBodyResourceData.java:lifecycleStatus`, `avatar/.../AvatarFaceResourceData.java:lifecycleStatus + obtainableType`, `app/administration/.../PartyroomAdminActionData.java:actionType + targetType` |
| 회귀 후보 | Hibernate가 ENUM 기대, DB가 VARCHAR → `Schema-validation: wrong column type` → **앱 기동 불가** |
| 근본 원인 | `@Enumerated(EnumType.STRING)`만 부착, `@JdbcTypeCode(SqlTypes.VARCHAR)` 누락 (5개 필드) |
| 검출 경위 | docker compose 로컬 부팅 시 첫 SchemaManagementException; ddl-auto=update로 ALTER 카탈로그 수집 후 한 번에 식별 |
| 해소 | rebase amend (commit `d23ea919` PR 10, `80c5f172` PR 8 G1) — §7 참조 |

| 항목 | 값 |
|---|---|
| ID | **DB-4** |
| P | **P0** |
| 면 | F5 |
| 위치 | `app/administration/.../PartyroomAdminActionData.java:28` |
| 회귀 후보 | `extends BaseEntity`로 `created_at`/`updated_at` 컬럼 기대, V7 마이그레이션은 미포함 → `Schema-validation: missing column` → **앱 기동 불가** |
| 근본 원인 | 스펙 §3 ("Append-only — setter 없음, 별도 `occurred_at` 보유")과 코드 불일치. 엔티티 작성 시 부적절한 `BaseEntity` 상속 |
| 해소 | rebase amend (commit `80c5f172` PR 8 G1) — `extends BaseEntity` 제거 |

**카탈로그화 방법**: local 프로파일 한정 `ddl-auto: update`로 임시 변경 후 부팅 → Hibernate가 모든 mismatch에 대해 `ALTER TABLE` 시도 SQL을 출력 → 그게 곧 entity↔DB 차이의 정확한 카탈로그. 픽스 적용 후 `ddl-auto: validate` 모드로 복귀해 부팅 성공 확인.

---

## 5. Build & Test Verification

### 5.1 Build

```
JAVA_HOME=C:/Users/Eisen/.jdks/ms-21.0.7
./gradlew build -x test --no-daemon  →  BUILD SUCCESSFUL in 39s
```

전체 6 모듈(`common`, `realtime`, `playlist`, `user`, `avatar`, `app`) 컴파일 성공.

### 5.2 Tests — 1,104 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED

```
./gradlew test  →  BUILD SUCCESSFUL in 53s
```

| 모듈 | 테스트 클래스 | 통과 |
|---|---|---|
| `app` | 155 | 769 |
| `common` | 14 | 79 |
| `user` | 35 | 133 |
| `avatar` | 7 | 45 |
| `playlist` | 17 | 70 |
| `realtime` | 3 | 8 |
| **TOTAL** | **231** | **1,104** |

### 5.3 해석 — 강한 양성 신호, 단 한 가지 주의

**양성 신호**:
- 1,104 모두 통과 → 정적 회귀(컴파일 깨짐, 빈 메서드, 시그니처 불일치) 0건.
- 변경 핫스팟(MemberData +191, UserAccountData +169, PartyroomAccessCommandService +179)이 모두 내부 단위/IT 테스트로 커버됨.
- avatar 모듈 분리 후에도 user 모듈의 33개 테스트 클래스가 새 import 경로로 정상 통과.

**주의**:
- 테스트는 **신규 사양**을 반영하도록 함께 업데이트되었으므로, 통과 자체가 "main과의 행동 동등"을 의미하진 않음. swagger/MSW 핸들러와의 매칭(F1~F4)은 별도 검증.
- Spring Security strict binding(A-3) 등 *기동 환경 회귀*는 현 테스트가 잡지 못할 수 있음(테스트는 application-test.yml 사용, prod yml은 별 경로).
- DB 마이그레이션의 데이터 손실(DB-1 V4) 위험은 **빈 DB 가정**으로 작성됨 → 테스트는 통과해도 운영 데이터에는 위험.
- 더 결정적으로, **테스트는 H2 + ddl-auto=create-drop|update**로 굴려 entity에서 스키마를 생성하므로 Flyway↔entity 불일치(§4.5의 DB-3/DB-4) 카테고리를 **한 케이스도 못 잡는다** — 이 종류는 §5.4 실행 검증에서만 드러남.

### 5.4 로컬 docker compose 실행 검증

```
docker compose -f docker-compose.local.yml -p pfplay-local --env-file .env.local up -d --build
```

| 회차 | Spring profile | ddl-auto | 결과 |
|---|---|---|---|
| 1차 | `local` | `validate` | ❌ Schema-validation: `lifecycle_status` (DB VARCHAR ↔ entity ENUM 기대) — 부팅 실패 |
| 2차 | `local` | `validate` | ❌ Schema-validation: `obtainable_type` (avatar_body는 V1의 ENUM, 엔티티에 잘못 추가한 @JdbcTypeCode가 오히려 깨뜨림) |
| 3차 | `local` | `validate` | ❌ Schema-validation: `partyroom_admin_action.created_at` 컬럼 부재 (entity가 BaseEntity 상속) |
| 4차 | `local` | `update` (임시) | ✅ 부팅 성공. Hibernate가 ALTER SQL 출력으로 모든 차이 카탈로그화 |
| 5차 | `local` | `validate` (rebase 후) | ✅ **부팅 성공 (Tomcat 8080, 33s)**. 모든 mismatch 해소 확인 |

부팅 후 endpoint 핑:
- `/` → 401 (auth required, 정상)
- `/api/v1/auth/oauth/url` → 405 (POST-only, 정상)
- `/api/v1/partyrooms` → 401 (auth required, 정상)

**핵심 함의**: 테스트 1,104 통과 + 정적 리뷰만으로는 이 회귀를 **0건도** 못 잡았다. B-tier "실행 검증"의 가치가 카테고리 회귀(DB-3/DB-4)에서 가장 크게 발현된 케이스.

### 5.5 U-1 응답 키 누출 — 실 응답으로 검증 (P0 → P2 강등)

**검증 절차**:
1. `POST /api/v1/users/guests/sign` → `Set-Cookie: SharedSessionToken=...` 획득 (HTTP 200, JWT payload `access_level=[ROLE_GUEST]`)
2. `GET /api/v1/users/me/profile/avatar/bodies` with cookie → HTTP 200, 15개 row 반환

**응답 첫 row의 키 목록** (실제):
```
id, name, resourceUri, iconUri, obtainableType, obtainableScore,
combinable, defaultSetting, available, combinePositionX, combinePositionY, lifecycleStatus
```
→ **12 필드**. 일부 row의 `iconUri`는 null이지만 키 자체는 존재.

**`pfplay-web/swagger.json` AvatarBodyDto** (정적 컨트랙트):
```
id, name, resourceUri, obtainableType, obtainableScore,
combinable, defaultSetting, available, combinePositionX, combinePositionY
```
→ **10 필드**. 신규 `iconUri`/`lifecycleStatus` 미반영.

**Drift 확정**: 백엔드 +2 필드 누출.

**그러나 pfplay-web 측 런타임 영향은 없음**:
- `src/shared/api/http/types/users.ts:94-108`의 `AvatarBody = AvatarPartsDefaultMeta + 6 fields`는 **TypeScript interface**로 컴파일 타임 구조 검사만 수행. JSON 파싱 결과 객체에 추가 필드가 있어도 런타임 무시.
- `package.json` 런타임 validator: `zod` 만 존재하나, grep 결과 `playlist-form.component.tsx`/`edit-profile-bio` 같은 **폼 입력**에만 사용, API 응답 파싱엔 미사용.
- 따라서 frontend 동작 깨짐 0건.

**결론**: U-1은 **컨트랙트 drift이지만 사용자 영향 0건**. P0 → P2로 강등.

권장 처리 (택1):
- (a) swagger.json 재생성 (`/v3/api-docs` 라이브 스펙으로 갱신) → drift 자체 해소
- (b) 엔티티에서 admin-내부 필드(`lifecycleStatus`)는 `@JsonIgnore` 또는 별 응답 DTO 분리 → customer 응답에서 마스킹

### 5.5b PA-7 발견 — Customer `GET /api/v1/partyrooms` 500 NPE (해소)

PA-2/PA-3 검증 도중 발견 → 진단 → 해소까지 처리됨.

**증상**:
```
GET /api/v1/partyrooms → 500
{"errorCode":"E1","message":"Cannot invoke ProfileSettingDto.avatarIconUri() because profileSettingDto is null"}
```

**진단**:
- `ApplicationReadyEventListener.onApplicationEvent()` → `partyroomCommandService.initializeMainStage(SUPER_ADMIN_USER_ID)` → `createMainStage(...)` → `enterByHost(adminId, ...)` → super-admin이 grade=HOST의 active crew로 등록.
- super-admin (V5 seed)은 `member.profile_id=NULL` — profile 없음.
- 사용자 의도: "프로필 없는 사용자는 아바타가 없으므로 partyroom에 들어올 수 없다"는 도메인 invariant. super-admin도 예외 아님.
- `QueryPartyroomListResponse.from()`은 crew마다 `profileSettings.get(crewDto.userId())` 결과를 dereference — admin이 crew면 null → NPE.

**근본 원인**: invariant("프로필 없는 사용자는 active crew가 될 수 없다")가 코드로 표현되지 않은 채, 부팅 트리거가 invariant 위반 상태를 만든 것. main 시점엔 트리거 없음 → 잠재. 본 브랜치(`b8e8138f`)에서 트리거 활성화로 노출.

**해소** (§7.8): `createMainStage`에서 `enterByHost(adminId, ...)` 호출 제거 — admin은 `partyroom.host_id`로 host 권한 보유, active crew row는 만들지 않음. invariant와 일치. customer list 응답 시 admin이 crew에 없으므로 NPE 차단.

**ID**: **PA-7** (rebase 3차로 해소)

### 5.8 추가 검증 (PA-3 fix 이후)

PA-3 해소 직후 라이브 `/v3/api-docs` 추출 + pfplay-web `swagger.json` diff + 단발 endpoint 호출로 잔여 회귀 후보 일괄 검증.

| ID | 결과 |
|---|---|
| **A-4** | pfplay-web `/src/`에 `/api/v1/admin` 호출 0건 → admin 매처 강화(permitAll→hasRole) **customer 무영향** ✅ |
| **A-2 follow-up** | pfplay-web에 `provider` claim 사용 0건 → 클레임 제거 **frontend 무영향** ✅ |
| **U-3** | `avatar_face_resource.obtainable_type`가 모두 BASIC (1/1) → `available=true` 유지, 회귀 0건 ✅ |
| **U-8** | orphan UserAccount(Member/Guest 없는) 0건 → `/me/info` 401 케이스 부재 ✅ |
| **U-13** | guest sign + `/sign/temporary/full-member` 호출 → user_account +1 / member +1 / profile +1 / HTTP 200 → 2-step INSERT 트랜잭션 동작 확인 ✅ |
| **U-6** | `MemberData.java:150`의 `getWalletAddress().getValue()` 직접 호출 — main과 동일, **회귀 아님**. wallet 미설정 AM이 `/summary` 호출 시 NPE 잠재 (frontend가 그 경로를 호출하지 않으면 무관) 🟡 |

#### Swagger live vs static 비교

| 차원 | live `/v3/api-docs` | static `pfplay-web/swagger.json` |
|---|---|---|
| paths | 73 | 39 |
| schemas | 168 | 94 |

- 제거된 customer path: **0건** ✅
- 추가 path: 34건 모두 admin (customer 무영향)
- 공통 schema 중 customer 영향 필드 차이: **2개**
  - `AvatarBodyDto: +iconUri, +lifecycleStatus` (U-1 — TypeScript 무영향, P2)
  - **`CrewSetupDto: +countryCode`** (NEW — 같은 카테고리, P2)

### 5.7 PA-2 / PA-3 — SUSPENDED / HIDDEN 룸 가시성 실측

직접 DB INSERT로 테스트 룸 4개 (Main Stage 외 ACTIVE/NORMAL, SUSPENDED/NORMAL, ACTIVE/HIDDEN) 추가 후 guest cookie로 `GET /api/v1/partyrooms` 호출.

**결과**: 4개 룸 모두 응답에 포함됨.

| ID | 상태 | display_flag | 응답 노출? |
|---|---|---|---|
| 1 (Main Stage) | ACTIVE | NORMAL | ✓ |
| 2 (Test ACTIVE NORMAL) | ACTIVE | NORMAL | ✓ |
| 3 (Test SUSPENDED) | SUSPENDED | NORMAL | ✓ — **PA-2 확인** |
| 4 (Test HIDDEN) | ACTIVE | HIDDEN | ✓ — **PA-3 확인** |

**판정**: PA-2/PA-3 모두 회귀 후보 사실로 확정. `PartyroomRepositoryImpl.java:111`이 `where(status.ne(TERMINATED))`만 적용하고 SUSPENDED/HIDDEN을 필터하지 않음. 의도된 동작인지(예: SUSPENDED는 운영 투명성 노출, HIDDEN은 미세 분류 라벨로 customer엔 노출 OK) product 결정 사안. 코드 픽스 대상 아님.

**부수 발견 — U-2/U-4 정상**: 같은 검증 흐름에서 `avatar_body_resource`/`avatar_face_resource`의 `lifecycle_status` 분포 확인 — 모두 `PUBLISHED` (body 15/15, face 1/1). V12 default 정상 적용. customer 응답에서 사라지는 row 0건. → **U-2/U-4 회귀 없음, 안심**.

### 5.6 부수 발견 — `/v3/api-docs` 500 NPE (해소)

라이브 OpenAPI 스펙 추출 시도 중 발견 → 진단 → 해소까지 한 번에 처리됨.

**증상**:
```
GET /v3/api-docs → 500
{"errorCode":"E1","message":"Cannot invoke java.lang.Integer.intValue() because the return value of java.util.Map.get(Object) is null"}
```

**진단** (stack trace 캡처 후):
```
at com.pfplaybackend.api.common.config.swagger.ApiErrorCodeCustomizer.customize(ApiErrorCodeCustomizer.java:74)
```
`STATUS_MAP.get(errorType)` 결과가 null → autobox 시 NPE. 원인은 **`ErrorType.TOO_MANY_REQUESTS`가 enum에는 추가됐지만 `ApiErrorCodeCustomizer`의 `STATUS_MAP`/`STATUS_DESCRIPTION` 병행 Map에는 누락**.

**근본 원인**: 두 개의 병행 자료구조(enum 값 ↔ STATUS_MAP 항목) 사이의 동기화를 컴파일러가 강제하지 않음. enum 값 추가 시 STATUS_MAP 갱신 누락은 런타임에서만 드러남.

**해소** (§7.6 참조): `ErrorType` enum이 `(statusCode, description)`을 자체 보유하도록 리팩터링 → 병행 Map 제거. 새 enum 값 추가 시 컴파일러가 (statusCode, description) 지정을 강제하므로 동기화 누락 불가.

**ID**: **DB-5** (rebase로 해소)

---

## 6. Findings & Verdict

### 6.1 우선순위별 회귀 후보 정리

#### P0 (배포 전 반드시 처리) — 6건 (4 정적 + 2 실행)

| ID | 면 | 위치 | 한 줄 요약 | 상태 |
|---|---|---|---|---|
| **DB-1** | F5 | V4__refactor_user_account_to_iam.sql | `user_account/member/guest` DROP+CREATE — 운영 데이터 있다면 회복 불가 | 🟡 검증 필요 |
| **A-1** | F2/F3 | SharedCookieProperties.java | 쿠키 이름 `AccessToken`→`SharedSessionToken` — 모든 기존 세션 강제 만료 | 🟡 공지 필요 |
| **A-2** | F2 | CustomJwtAuthenticationConverter.java | JWT 클레임 스키마 변경(`uid`→`sub`, `access_level` String→List, `provider` 제거) — 기존 토큰 무효화 | 🟡 공지 필요 |
| ~~**U-1**~~ | F3 | avatar/AvatarBodyDto.java | 응답에 신규 필드 2개(`iconUri`, `lifecycleStatus`) 누출 — pfplay-web parser strict면 깨짐 | ✅ **P2로 강등** (§5.5 검증 결과) |
| **DB-3** | F5 | avatar/Body+Face entity, PartyroomAdminActionData enum 필드 | `@JdbcTypeCode(VARCHAR)` 누락 — Hibernate validate 실패로 **앱 기동 불가** | ✅ rebase로 해소 (§7.2) |
| **DB-4** | F5 | PartyroomAdminActionData `extends BaseEntity` | V7이 `created_at/updated_at` 미포함 — **앱 기동 불가** | ✅ rebase로 해소 (§7.2) |
| **DB-5** | F4 | `ApiErrorCodeCustomizer` STATUS_MAP | `ErrorType.TOO_MANY_REQUESTS` 누락 → `/v3/api-docs` NPE → 라이브 스펙 재생성 불가 | ✅ rebase로 해소 (§7.6 — 구조적 픽스로 미래 재발 차단) |
| **PA-7** | F2/F3 | `PartyroomCommandService.createMainStage` | super-admin 자동 enter → profile 없음 → `GET /api/v1/partyrooms` NPE 500 | ✅ rebase 3차로 해소 (§7.8 — admin은 host만, crew 등록 안 함) |

#### P1 (검증 필요) — 9건

| ID | 면 | 한 줄 |
|---|---|---|
| **DB-2** | F5 | V12 icon_uri naming-prefix 이관, 매칭 누락 row 가능 |
| **A-3** | F2 | application-test.yml 구 JwtProperties 키 잔존 |
| **A-4** | F1 | `/api/v1/admin/**` permitAll→hasRole (customer가 admin path 호출 안 하면 무관) |
| **A-5** | F1 | AdminOriginGuardFilter (admin scope only — 정상) |
| **A-6** | F1 | CSRF admin-only (customer 무관 — 정상) |
| **U-2/U-4** | F1 | `findPublishedBodies/Faces` → 미-PUBLISHED row가 customer 응답에서 사라질 수 있음 |
| **U-3** | F3 | `AvatarFaceDto.available` 의미 변경 (true → obtainableType==BASIC) |
| **U-6** | F5 | Member(AM) wallet null 호출 시 NPE — 옛/신 동등 위험 |
| **U-8** | F5 | UserAccount만 있고 Member/Guest 없는 row → `/me/info` 401 (옛: 200+null) |
| **U-13** | F5 | full-member 가입 시 2-step INSERT (트랜잭션 롤백 보장) |
| **PA-2** | F1 | `/api/v1/partyrooms` 리스트가 SUSPENDED 룸 노출 (못 들어가는 ghost) |
| **PA-3** | F1 | `/api/v1/partyrooms` 리스트가 HIDDEN 룸 노출 (의도 확인 필요) |
| **PA-5** | F2/F6 | 룸 재진입 시 ENTER 이벤트 발행 안 함 (idempotent) |

#### P2 (잠재적 / cosmetic / 개선) — 다수

A-7~A-11, U-5/U-7/U-9/U-10/U-11/U-12/U-14/U-15/U-16, PA-1/PA-4/PA-6 — 보고서 §3 표 참조.

### 6.2 머지/배포 권고

**개발 코드 자체는 견고함**: 1,104 테스트 통과, 컴파일 깨짐 0, 신규 admin 인프라가 path scope로 잘 격리됨, 응답 DTO 대부분 미변경. 이 브랜치의 *코드 품질* 회귀는 사실상 없음.

**그러나 운영 시작 전 다음 4가지가 충족되어야 안전**:

1. **DB 데이터 상태 확인 (DB-1)**: `SELECT COUNT(*) FROM user_account; FROM member; FROM guest;` — 0건이면 V4 안전. 1건이라도 있으면 머지/배포 차단 또는 V4를 ALTER 기반으로 재작성.
2. **세션 무효화 공지 (A-1, A-2)**: 배포 시점에 모든 사용자가 강제 로그아웃됨. 이미 없는 사용자만 있으면 무관, 있다면 공지/전환 윈도우 필요.
3. **swagger.json 재생성**: 프론트 컨트랙트(`pfplay-web/swagger.json`) 가 본 브랜치의 변경을 반영하지 못한 상태 — 재생성 후 pfplay-web의 OpenAPI 클라이언트 코드 재생성 필수.
4. **U-1 응답 키 누출 확인**: 운영 빌드 한 번 띄워 `GET /api/v1/users/me/profile/avatar/bodies` 호출 → `iconUri`/`lifecycleStatus` 키가 노출되는지 확인 → 노출되면 (a) `@JsonIgnore` 마스킹 또는 (b) swagger 갱신 + 프론트 lenient parser 정책 명시.

### 6.3 결론 (B-tier — rebase 반영 후)

| 차원 | 결론 |
|---|---|
| **기동 회귀** | **DB-3 + DB-4 발견 → rebase로 해소** (§7). 현 HEAD `539931c3`에서 validate 모드 부팅 성공 |
| **코드 회귀** | **거의 없음** (정적 분석 + 1,104 테스트, rebase 후에도 동일) |
| **컨트랙트 회귀** | **U-1, A-1 두 건 확인** — pfplay-web 측 영향은 strict parser 여부 + 세션 무효화 공지로 처리 가능 |
| **데이터 회귀** | **DB-1이 P0 차단** — 운영 DB 행 수 확인이 머지 게이트 |
| **거동 회귀** | SUSPENDED/HIDDEN 가시성, ENTER 이벤트 idempotent 등 product 의도 확인 필요 (PA-2/3/5) |

**최종**: rebase 후 기동 회귀(DB-3/4) 해소. U-1은 §5.5 실측으로 P2로 강등. 운영 시작 전 남은 항목:
1. **DB-1**: 운영 DB의 `user_account/member/guest` 행 수 확인 (0 이면 V4 안전, 아니면 ALTER 재작성).
2. **A-1/A-2**: 배포 시 모든 사용자 강제 로그아웃 공지.
3. **swagger.json 재생성**: 단, `/v3/api-docs`가 500 (§5.6) 이므로 **NPE 선해결 필요**.
4. **PA-2/PA-3**: 룸 리스트의 SUSPENDED/HIDDEN 가시성 product 의도 확인.

위 4항목 처리 후 머지 가능.

---

## 7. Resolution — Rebase로 근본 원인 해소

### 7.1 적용 범위

신규 P0 2건(DB-3, DB-4)을 root cause commit에 amend하는 interactive rebase를 수행. 새 commit 추가 없이 원인 commit 자체를 정정 — 피처 브랜치 미머지 상태이므로 안전.

### 7.2 Rebase 타겟

| commit | PR | amend 내용 |
|---|---|---|
| `80c5f172` → `9a0d8ee2` | PR 8 G1 (V7 + entity) | `PartyroomAdminActionData`: `extends BaseEntity` 제거, `actionType`/`targetType`에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 추가 |
| `d23ea919` → `b3f8de58` | PR 10 (avatar 모듈 carbon copy) | `AvatarBodyResourceData.lifecycleStatus`, `AvatarFaceResourceData.lifecycleStatus + obtainableType`에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 추가 |

### 7.3 Rebase 결과

- HEAD: `3b53fb9f` → `539931c3` (213 commits replay 모두 충돌 없음)
- 변경 stat (전체 brunch vs 원본): 3 파일, +12/-2 — 의도한 픽스 외 변화 0
- 안전망: `backup-before-rebase-2026-05-02` 브랜치 보존 (복구용)
- 검증: BUILD SUCCESSFUL, 1,104 PASS / 0 FAIL (rebase 전과 동일), validate 모드 부팅 성공

### 7.4 Force-push 필요

원격(`origin/feature/admin-auth-iam-schema`)과 102/102 divergence 상태. 동료 참조가 없으면 `git push --force-with-lease` 필요.

### 7.5 카테고리 교훈 (Rebase 1회차)

> **Hibernate 6 + MySQL dialect는 `@Enumerated(EnumType.STRING)`을 native ENUM에 매핑하려 하므로, DB 컬럼이 VARCHAR면 모든 STRING enum 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 명시 필요.**

향후 예방책 후보:
- ArchUnit 룰: `@Enumerated(EnumType.STRING)` + DB 컬럼 VARCHAR 조합엔 `@JdbcTypeCode(VARCHAR)` 강제
- Custom MySQL dialect: `getPreferredSqlTypeCodeForEnum()`을 VARCHAR로 override (프로젝트 전역 정책화)
- 통합 테스트에 MySQL Testcontainer + ddl-auto=validate 시나리오 1개 추가 (이 카테고리 회귀를 CI에서 잡기)

### 7.6 Rebase 2회차 — DB-5 (`/v3/api-docs` NPE) 구조적 해소

**타겟 commit**: `308f0fa5` (PR 4 — admin login DTOs + exception types) — 이 commit이 `ErrorType.TOO_MANY_REQUESTS`를 추가하면서 `ApiErrorCodeCustomizer`의 병행 Map 갱신을 누락한 시점.

**Amend 내용** (2 파일, +28/-19):

1. **`ErrorType.java`**: enum 값마다 `(statusCode, description)` 직접 박기 + getter
   ```java
   BAD_REQUEST(400, "잘못된 요청"),
   ...
   TOO_MANY_REQUESTS(429, "요청 한도 초과");
   ```
2. **`ApiErrorCodeCustomizer.java`**: `STATUS_MAP`/`STATUS_DESCRIPTION` 정적 Map 제거 → `errorType.getStatusCode()` / `errorType.getDescription()` 직접 호출.

**Rebase 결과**:
- HEAD: `539931c3` → `804149ba` (213 commits replay 모두 충돌 없음)
- 변경 stat (vs 1차 rebase 후): 2 파일, +28/-19 — 의도한 픽스 외 변화 0
- 안전망: `backup-before-rebase2-2026-05-02` 브랜치 보존
- 검증: BUILD SUCCESSFUL, 1,104 PASS / 0 FAIL, validate 모드 부팅 성공, **`/v3/api-docs` HTTP 200 (201,675 bytes, 73 paths, 168 schemas)**

### 7.7 카테고리 교훈 (Rebase 2회차)

> **두 개의 자료구조가 같은 정보를 보유하면(enum 값 ↔ 별 Map의 항목), 동기화 강제는 컴파일러가 해야 한다. 한쪽이 다른 쪽을 lookup하는 형태로는 한쪽만 갱신해도 컴파일이 통과 → 런타임에서만 NPE로 드러남.**

원칙: **단일 진실원(SST, Single Source of Truth)** + **컴파일러 강제 동기화**.

### 7.8 Rebase 3회차 — PA-7 (`GET /api/v1/partyrooms` NPE) 해소

**타겟 commit**: `b8e8138f` (feat(admin): rewire bootstrap to use V5-seeded super-admin) — 이 commit이 부팅 시 super-admin을 Main Stage active crew로 등록하는 트리거를 도입한 시점.

**Amend 내용** (1 파일, +5/-2):

`PartyroomCommandService.createMainStage`에서 `partyroomAccessCommandService.enterByHost(adminId, ...)` 호출 제거 + 사유 주석 추가. admin은 `partyroom.host_id`로 host 권한 유지, active crew row는 만들지 않음.

```java
@Transactional
public void createMainStage(CreatePartyroomCommand command, UserId adminId) {
    // 도메인 invariant: 프로필 없는 사용자는 partyroom에 active crew로 등록하지 않는다.
    // V5-seeded super-admin은 profile이 없으므로 enterByHost를 호출하면 customer GET /api/v1/partyrooms
    // 응답 빌드 시 ProfileSettingDto null lookup → NPE. (PA-7)
    createPartyroom(command, StageType.MAIN, adminId);
}
```

**Rebase 결과**:
- HEAD: `804149ba` → `9c459dbf` (213 commits replay 모두 충돌 없음)
- 변경 stat (vs 2차 rebase 후): 1 파일, +5/-2 — 의도한 픽스 외 변화 0
- 안전망: `backup-before-rebase3-2026-05-02` 브랜치 보존
- 검증:
  - BUILD SUCCESSFUL (34초)
  - 1,104 PASS / 0 FAIL (3회 rebase 모두 동일)
  - validate 모드 부팅 18.7초
  - **`GET /api/v1/partyrooms` HTTP 200** (`primaryIcons:[]` — admin이 crew 아니라 비어있음)

### 7.8b Polish — Defense-in-depth: `assertHasProfile` 가드 (`288d4c69`)

PA-7의 트리거 제거는 *현 시점*의 NPE를 막지만, `enterByHost`/`tryEnter` 자체는 invariant를 강제하지 않으므로 미래 코드 추가(시나리오: 신규 admin demo init / Featured Stage 같은 system room / bot user 등)가 같은 패턴을 재도입할 수 있다. polish commit으로 invariant를 코드에 박아 어떤 진입 경로든 컴파일 통과 후 런타임에서 즉시 명시적 예외로 떨어지도록.

**변경** (3 파일, +36/-1):
- `CrewException.PROFILE_REQUIRED` (CRW-004, ErrorType.FORBIDDEN/403) 추가
- `PartyroomAccessCommandService`: `UserProfileQueryPort` 주입 + `assertHasProfile(UserId)` 메서드 + `tryEnter`/`enterByHost` 시작 시 호출
- `PartyroomAccessCommandServiceTest`: `UserProfileQueryPort` mock + happy-path stub (`any(List)` → 모든 user에 profile 보유)

**검증**: BUILD SUCCESSFUL, 1,104 PASS / 0 FAIL, validate boot 정상, `GET /api/v1/partyrooms` HTTP 200 유지.

이 commit은 rebase amend가 아니라 끝에 추가한 polish — 본 브랜치가 main으로부터 받은 잠재 버그(`QueryPartyroomListResponse:38` null-unsafe)를 invariant 측에서 차단하는 형태. 응답 측 null-safe map은 별 follow-up 가능 (현재 가드만으로도 NPE 봉쇄됨).

### 7.10 Polish — PA-3 (HIDDEN 룸 list 필터)

**의도 확정** (사용자 결정): HIDDEN 룸은 customer list에서 노출 X, 링크 직접 접근으로 진입은 O (private/share-only 패턴).

**변경** (1 파일, +5/-1):
- `PartyroomRepositoryImpl.java:111`의 list query에 `qPartyroomData.displayFlag.ne(DisplayFlag.HIDDEN)` 추가
- `DisplayFlag` import 추가
- 진입 path는 미변경 — `PartyroomEntrySpecification`에 displayFlag 검사 없으므로 link 직접 접근으로 진입 가능

**검증**:
- HIDDEN/SUSPENDED/NORMAL 4 룸 INSERT → `GET /partyrooms` 응답 3 룸 (HIDDEN 제외)
- `GET /api/v1/partyrooms/link/test-hidden-2` → HTTP 200 (진입 가능)
- 1,104 PASS / 0 FAIL

### 7.9 카테고리 교훈 (Rebase 3회차)

> **도메인 invariant는 코드로 표현되지 않으면 잠재 결함이다. 트리거 commit이 invariant 위반 상태를 만들면 런타임에서만 결과로 드러난다.**

본 케이스의 invariant: **"프로필 없는 사용자는 partyroom에 active crew로 등록될 수 없다."**

이 invariant가 어디에도 코드로 강제되지 않음 → 우연히 트리거가 admin(profile 없음)을 crew로 만들 수 있음 → customer 응답 빌드에서 NPE.

방어책:
- ✅ `enterByHost` / `tryEnter` 양쪽에 `assertHasProfile(userId)` 도메인 가드 추가 (§7.8b — 본 브랜치에 polish commit으로 적용 완료)
- (follow-up) `QueryPartyroomListResponse:38`에 null-safe map (코드 fault tolerance — 다른 잠재 케이스 대비). 가드로 봉쇄되었으므로 우선순위 낮음.
- (follow-up) ArchUnit 룰: crew 등록 경로에 profile 검증 호출 강제

본 브랜치에서 발견된 모든 패턴 (DB-3, DB-4, DB-5, PA-7)은 메타-원인이 동일 — *"코드로 표현되지 않은 invariant 또는 컴파일러가 강제하지 않는 동기화"*. 후속 ArchUnit/audit 검토 권장.

---

## 부록 A — 검증 권장 명령 모음

```bash
# DB-1 검증 (운영/스테이징 DB 행 수)
mysql> SELECT 'user_account' AS t, COUNT(*) FROM user_account
        UNION SELECT 'member', COUNT(*) FROM member
        UNION SELECT 'guest', COUNT(*) FROM guest;

# A-1 검증 (pfplay-web에 AccessToken 하드코딩 부재 확인)
grep -r 'AccessToken\|accessToken' pfplay-web/src --include='*.ts' --include='*.tsx' | grep -iv 'WebSocket\|authorization'

# DB-2 검증 (icon_uri 매칭 누락 row)
mysql> SELECT COUNT(*) FROM avatar_body_resource WHERE icon_uri IS NULL;
mysql> SELECT COUNT(*) FROM avatar_face_resource WHERE icon_uri IS NULL;

# U-2/U-4 검증 (lifecycle_status 분포)
mysql> SELECT lifecycle_status, COUNT(*) FROM avatar_body_resource GROUP BY 1;
mysql> SELECT lifecycle_status, COUNT(*) FROM avatar_face_resource GROUP BY 1;

# U-8 검증 (UserAccount-Member 정합성)
mysql> SELECT COUNT(*) FROM user_account ua
        LEFT JOIN member m ON m.user_account_id = ua.user_id
        LEFT JOIN guest g ON g.user_account_id = ua.user_id
        WHERE m.member_id IS NULL AND g.guest_id IS NULL;

# U-1 응답 키 확인 (running app)
curl -s -H "Cookie: SharedSessionToken=..." http://localhost:8080/api/v1/users/me/profile/avatar/bodies \
  | jq '.data[0] | keys'
```

---

*이 리뷰는 정적 분석 + 1,104 테스트 실행 + pfplay-web swagger/MSW 컨트랙트 매칭 + 로컬 docker compose 실행 검증(MySQL 8 + Redis 7 + Hibernate 6.4.4 validate 모드) + 라이브 OpenAPI 스펙 추출(`/v3/api-docs`) + 실 endpoint 호출 검증(guest sign + 룸 list + 가시성 측정)으로 작성됨. 운영 데이터 상태와 환경 설정(`COOKIE_DOMAIN` 등)은 외부 사실이라 본 보고서 범위 밖. 발견 P0 8건 중 기동/스펙/customer-NPE 회귀 4건(DB-3, DB-4, DB-5, PA-7)은 3회 rebase로 해소(§7), PA-7은 추가 polish commit으로 도메인 invariant 가드 강화(§7.8b), U-1은 P2로 강등(§5.5), 나머지 3건은 머지 게이트로 §6.2 참조. PA-2/PA-3는 §5.7 실측으로 가시성 노출 확정 — product 의도 결정 사안.*
