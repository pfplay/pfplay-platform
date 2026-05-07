# PR 4: Admin Login + JWT Claim Redesign + 2-Cookie Split + Rate Limit Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the local-credential admin login flow (`POST /api/v1/auth/admin/login`), redesign JWT claims (subject = userAccountId, `access_level` as array, nullable `authority_tier`), split the single auth cookie into `AdminAccessToken` (admin subdomain, SameSite=Strict, 15-min sliding) + `SharedSessionToken` (apex domain, SameSite=Lax, 24h), enforce IP+email rate limits + lockout via bucket4j, and add an Origin/Referer guard for admin endpoints.

**Architecture:** Two cookies, one Spring filter chain. A single path-aware `CookieBearerTokenResolver` reads `AdminAccessToken` for `/api/v1/admin/**` and `SharedSessionToken` elsewhere — this avoids splitting `SecurityFilterChain` (deferred to PR 5's full SecurityConfig overhaul). `JwtService` exposes two minting paths (`mintAdminAccessToken`, `mintSharedSessionToken`) with distinct TTLs and claim shapes; both share a single signing key. `AdminLoginService` resolves `UserAccount(email, providerType=LOCAL)` → bcrypt verify → resolve active `Administrator` → mint tokens (SharedSessionToken only when a `Member` exists for that `userAccountId`). `AdminLoginRateLimiter` (bucket4j, in-memory Caffeine-backed) trips on IP-bucket OR email-bucket consumption; lockout window is 15 minutes. `AdminTokenRenewalFilter` (post-auth `OncePerRequestFilter`) extends `AdminAccessToken` when the request authenticated successfully and the token has < 5 minutes remaining. `AdminOriginGuardFilter` rejects state-changing admin requests whose `Origin`/`Referer` header is not in the configured allowlist.

**Tech Stack:** Java 21, Spring Boot 3.2 (Spring Security OAuth2 Resource Server), Spring Data JPA, MySQL 8.0, jjwt 0.12.x, BCrypt (cost 12 — already wired in `SecurityConfig` since PR 2), `bucket4j-core` 8.x + Caffeine, JUnit 5, Mockito, Spring Security Test, Testcontainers (already present).

**Spec sources (read once, applied throughout):**
- `docs/superpowers/specs/2026-04-19-admin-platform-security.md` §5.1, §5.3, §5.4, §5.5 (entire security spec drives this PR)
- `docs/superpowers/specs/2026-04-19-admin-platform-design.md` §3.1 BC table — Auth/Administration rows
- `docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md` §9.1 PR 4 row + §11.2.4 (sliding 15-min TTL confirmed)

**Decisions taken (spec leaves open):**

1. **JWT claim cutover is hard.** Pre-launch (per roadmap §9.3); we do not implement a dual-read fallback for the legacy `uid`/single-string `access_level` shape. Existing OAuth tokens become invalid; users re-login via the OAuth flow which mints a new-shape token. Rationale: pre-launch + minimal surface area.
2. **AdminAccessToken sliding renewal lives in this PR.** Without it, admins are forced to re-login every 15 minutes, which would block PR 14 frontend testing. Implemented as a post-auth filter that re-issues the cookie when the request authenticated successfully *and* the token has < 5 minutes remaining.
3. **Rate-limit store: bucket4j in-memory (Caffeine).** Single-instance MVP. Admin endpoints are low traffic, and an instance restart resets the lockout counters — acceptable until we move to a distributed limiter (out-of-scope; trigger when admin scale-out becomes real).
4. **CSRF defense in PR 4: option B only (Origin/Referer header allowlist).** Spec §5.4.3 recommends A+B. Option A (CSRF token + double-submit) requires re-enabling Spring's CSRF filter and threading tokens through admin frontend; coupled with PR 5's full `SecurityConfig` overhaul, defer Option A to PR 5.
5. **Single path-aware `BearerTokenResolver`.** Spec §5.3.4 anticipates "두 개 운영" — but the *intent* (admin path reads admin cookie, shared path reads shared cookie) is satisfied with one path-aware resolver inside one `SecurityFilterChain`. Splitting into two `SecurityFilterChain` beans is a structural change that fits PR 5's overhaul better.
6. **SharedSessionToken issued at admin login *only when* a `Member` exists for the user account.** SUPER_ADMIN seed has no `Member` → only `AdminAccessToken` set on response. Avoids issuing a session-shaped token with empty `access_level`.
7. **Login audit (`SIGNED_IN` event) is slf4j-only in PR 4.** `user_activity_log` table arrives in V10 / PR 12. PR 4 emits a structured slf4j INFO with fields {userAccountId, success, reason}; PR 12 wires this into the table.
8. **Lockout reset endpoint deferred.** `DELETE /api/v1/admin/system/administrators/{id}/lockout` is part of admin CRUD (PR 6). PR 4 ships lockout enforcement only — recovery is "wait 15 min" or "restart instance" until PR 6.

**Lessons applied from PR 1 / 2 / 3:**
- Mechanical implementation tasks → `sonnet` model. Architecture-touching tasks (filter ordering, security chain edits, claim shape redesign) → `opus`.
- Claim-shape change is *cross-module* (common module read/write + app module write at AuthService). One PR must change all call sites in lockstep — compile errors will catch leftover legacy claim writes.
- Plan must explicitly preserve PR 0–3 guarantees: `/api/v1/admin/**` still returns 401/403 (not 503) under maintenance mode; `MaintenanceModeFilter` already bypasses admin paths (PR 3).
- New BC for `Auth.AdminLogin` — but the existing `auth` package already exists, so no new Gradle module; we extend `app/.../auth/...` with `application/service/AdminLoginService` etc.
- jjwt 0.12.x signing key construction matches existing `JwtService.getSigningKey()`; reuse, don't re-implement.

**Branching:** Continue on `feature/admin-auth-iam-schema`. Each task ends in its own commit. PR 3 HEAD: `d5b88120`; PR 4 builds on top.

**Out of scope (deferred):**
- Admin lockout reset endpoint (PR 6 — admin CRUD).
- CSRF token (option A) — PR 5.
- `SecurityConfig` decomposition into multiple `SecurityFilterChain` beans — PR 5.
- Login audit persistence to `user_activity_log` — PR 12.
- `must_change_password_at_next_login` flow (§5.6) — PR 6.
- Admin self-service password change endpoint — PR 6.
- Distributed rate-limit store (Redis / Hazelcast) — out-of-scope; trigger on admin scale-out.
- Spec §5.7 권한 회귀 parameterized test (`/api/v1/admin/**` × {ROLE_MEMBER, ROLE_ADMIN, ROLE_SUPER_ADMIN}) — PR 5 (along with `SecurityConfig` overhaul that introduces the `adminAuth` SpEL bean).

---

## Atomic commit groupings

Several tasks are intentionally broken out for readability but **must be committed together** to keep the multi-module Gradle tree compiling across commit boundaries. The implementer should accumulate changes for each group and create a single commit at the end of the group with a combined message.

| Group | Tasks | Reason |
|---|---|---|
| **G1: Properties + cookie surface** | Tasks 1 + 7 + 8 | Task 1 changes `JwtProperties.cookie` shape; existing `CookieUtil` + `JwtCookieValidator` + `CookieBearerTokenResolver` + `AuthController` callers all break and must be re-routed in the same commit. |
| **G2: JWT claim cutover** | Tasks 4 + 6 | Task 4 deletes `JwtService.generateAccessToken`; `AuthService.processOAuthLogin` is the sole remaining caller and must be migrated in the same commit. |

Within each group:
- The per-task step lists are still a checklist (write code, run tests, verify behavior).
- **Skip the `git commit` step at the end of each task in the group.** Run only at the END of the group with the combined message provided in the group's last task.
- Tasks NOT listed in a group commit individually (the default per-task instruction).

---

## Hard precondition (verify BEFORE Task 1)

PR 4 introduces `bucket4j-core` and removes the read-side dependency on the existing `provider` JWT claim (currently dead code in `CustomJwtAuthenticationConverter`). Before Task 1:

- [ ] **Step 1: Confirm PR 3 is on HEAD**

```bash
git log --oneline -1
```

Expected: `d5b88120 feat(operations): add MaintenanceModeFilter (503 except admin/health)` (or a later PR-3-related commit if `feature/admin-auth-iam-schema` advanced).

- [ ] **Step 2: Confirm working tree is clean**

```bash
git status -s
```

Expected: empty output, or only the existing untracked `docs/superpowers/specs/2026-04-04-event-taxonomy-design.md`. Any other dirty files → STOP and ask.

- [ ] **Step 3: Confirm `JwtService` and `CookieBearerTokenResolver` shape match plan assumptions**

```bash
grep -n 'TokenClaim.UID\|TokenClaim.ACCESS_LEVEL\|access_token_name' \
  common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/JwtService.java \
  common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolver.java
```

Expected: at least one match for each. If `JwtService` no longer reads `TokenClaim.UID`, the codebase has drifted from this plan — STOP.

- [ ] **Step 4: Confirm JWT signing secret length is HS256-compatible**

`jjwt 0.12.x` throws `WeakKeyException` at boot when the HS256 signing key is < 32 bytes. The HMAC key is derived from `${JWT_SECRET}` via UTF-8 byte length.

```bash
# Local dev: read from your shell or .env. Verify length.
echo -n "$JWT_SECRET" | wc -c
```

Expected: ≥ 32. If < 32 in any deployment profile, regenerate (e.g., `openssl rand -base64 48`) and update the secret store BEFORE this PR ships. PR 4 does NOT change the secret length but actively exercises HS256 minting on every request — a weak secret will fail the boot smoke in Task 12 / Task 15.

- [ ] **Step 5: Confirm `MemberRepository.findByUserAccountId(Long)` does NOT yet exist**

```bash
grep -n 'findByUserAccountId' user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository.java
```

Expected: no match. (Only `existsByUserAccountId` should be present.) `findByUserAccountId` is added explicitly in Task 11 Step 0 as a separate commit. If it already exists, **delete that step** from Task 11.

---

## Verified codebase facts (read once, applied throughout)

- `BCryptPasswordEncoder(12)` already wired in `common/.../SecurityConfig.java:82` since PR 2. **Do NOT re-wire.**
- `/api/v1/admin/**` already gated by `hasRole("ADMIN")` in `SecurityConfig` since PR 0/2. **PR 4 only adds `/api/v1/auth/admin/login` permitAll** — does not retouch the `/api/v1/admin/**` rule.
- `AdministratorRepository.findByUserAccountId(Long)` exists (PR 2).
- `UserAccountRepository.findByEmailAndProviderType(String, ProviderType)` exists (PR 1).
- `ProviderType.LOCAL` exists (PR 1) and is the discriminator for admin accounts.
- `JwtService` currently uses jjwt 0.12.x (`Jwts.builder().claims(...).subject(...).signWith(...)`). The subject is the literal string `"AccessToken"` (`TokenSubject.ACCESS_TOKEN_SUBJECT.getValue()`) — this is misuse; spec §5.3.1 wants subject = userAccountId. PR 4 fixes this.
- Single `SecurityFilterChain` at `common/.../SecurityConfig.java`. We will register two new filters via `HttpSecurity.addFilterBefore/After` rather than via `FilterRegistrationBean` (which would put them outside the security chain — wrong layer for these admin-aware filters).
- `MaintenanceModeFilter` (PR 3) is registered via `FilterRegistrationBean` at `Ordered.HIGHEST_PRECEDENCE`, runs before security chain, bypasses `/api/v1/admin/**`. PR 4's filters live *inside* the security chain — no ordering conflict.
- `application.yml` uses kebab-case (`access-token-name`, `same-site`). New properties follow the same convention.
- `app/build.gradle` already includes `spring-boot-starter-cache`, so Caffeine pulls in transitively when we declare `com.github.ben-manes.caffeine:caffeine` directly. We add it explicitly to avoid version drift.
- `JwtCookieValidator` is referenced by `app/.../partyroom/...` controllers (verify with grep before Task X). Its public API (`extractAndValidateAccessToken`, `extractUserId`, etc.) reads via `jwtProperties.getCookie().getAccessTokenName()` — we keep this working by keeping the legacy `accessTokenName` field aliased to `SharedSessionToken` in the new properties shape (handled in Task 1).
- The test suite uses Testcontainers for repository tests and `@SpringBootTest` for slice tests. Pure-unit tests (Mockito) are preferred where possible — match PR-3 Test Strategy.

---

## File Structure

### Files Created

**Common module — JWT + cookie + filter:**
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/properties/AdminCookieProperties.java` — `Domain`/`SameSite`/`MaxAge` for the admin cookie, separate from shared.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/properties/SharedCookieProperties.java` — same for shared cookie. Existing `JwtProperties.CookieProperties` is renamed/restructured.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/AdminCookieWriter.java` — write/clear `AdminAccessToken`.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/SharedSessionCookieWriter.java` — write/clear `SharedSessionToken`.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/AdminTokenRenewalFilter.java` — sliding renewal.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/web/AdminOriginGuardFilter.java` — Origin/Referer allowlist.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/web/properties/AdminOriginProperties.java` — allowlist config.

**App module — admin auth flow:**
- `app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthController.java` — `POST /api/v1/auth/admin/login` + `POST /api/v1/auth/admin/logout`.
- `app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/payload/request/AdminLoginRequest.java` — `email`, `password` with `@NotBlank` + `@Email`.
- `app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/payload/response/AdminLoginResponse.java` — `tokenType`, `expiresIn`, `issuedAt`, `role`.
- `app/src/main/java/com/pfplaybackend/api/auth/application/service/AdminLoginService.java` — orchestrate verify+mint.
- `app/src/main/java/com/pfplaybackend/api/auth/application/dto/command/AdminLoginCommand.java` — record with `email`, `password`, `clientIp`.
- `app/src/main/java/com/pfplaybackend/api/auth/application/dto/result/AdminAuthResult.java` — record with admin token + optional shared token + role + expiry.
- `app/src/main/java/com/pfplaybackend/api/auth/domain/exception/AdminAuthException.java` — error codes (`INVALID_CREDENTIALS`, `RATE_LIMITED`, `ACCOUNT_REVOKED`).
- `app/src/main/java/com/pfplaybackend/api/auth/application/ratelimit/AdminLoginRateLimiter.java` — bucket4j orchestration.
- `app/src/main/java/com/pfplaybackend/api/auth/config/RateLimitConfig.java` — bucket4j + Caffeine bean wiring + `RateLimitProperties` binding.
- `app/src/main/java/com/pfplaybackend/api/auth/config/properties/RateLimitProperties.java` — `app.ratelimit.admin-login.{ip,email}.{capacity,window-seconds,lockout-seconds}`.

**Tests (mirror created files):**
- `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/JwtServiceAdminTokenTest.java` — admin token shape (subject, claims, TTL).
- `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/JwtServiceSharedTokenTest.java` — shared token shape.
- `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CustomJwtAuthenticationConverterTest.java` — new claim shape parsing (NEW; previously absent).
- `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/AdminCookieWriterTest.java`
- `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/SharedSessionCookieWriterTest.java`
- `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolverPathAwareTest.java` — extends existing test file or replaces it (existing test verifies single-cookie behavior; we keep its scenarios + add admin-path scenarios).
- `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/AdminTokenRenewalFilterTest.java`
- `common/src/test/java/com/pfplaybackend/api/common/config/security/web/AdminOriginGuardFilterTest.java`
- `app/src/test/java/com/pfplaybackend/api/auth/application/service/AdminLoginServiceTest.java`
- `app/src/test/java/com/pfplaybackend/api/auth/application/ratelimit/AdminLoginRateLimiterTest.java`
- `app/src/test/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthControllerTest.java` — `@WebMvcTest` slice or `@SpringBootTest` with security; covers 200 / 401 / 429 / 403 (Origin guard).

### Files Modified

- `app/build.gradle` — add `bucket4j-core` + `caffeine`.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/JwtService.java` — split into `mintAdminAccessToken(...)` + `mintSharedSessionToken(...)`; subject = userAccountId; access_level as `List<String>`; nullable authority_tier; remove `provider` claim helpers.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/dto/TokenClaimsRequest.java` — change `accessLevel` → `List<AccessLevel> accessLevels`; add static factories.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CustomJwtAuthenticationConverter.java` — read `sub`/`access_level` array/nullable `authority_tier`; drop `provider`; produce one `GrantedAuthority` per access_level entry.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CustomJwtAuthenticationToken.java` — drop `provider` field.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolver.java` — path-aware: read `AdminAccessToken` for `/api/v1/admin/**`, `SharedSessionToken` otherwise.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/properties/JwtProperties.java` — replace single `CookieProperties` with `admin: AdminCookieProperties` + `shared: SharedCookieProperties`. Keep deprecated single-cookie getters as @Deprecated thin delegates returning shared properties (only if grep finds external callers; otherwise remove).
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CookieUtil.java` — DELETE (replaced by AdminCookieWriter + SharedSessionCookieWriter). All callers re-routed.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/JwtCookieValidator.java` — re-route from `accessTokenName` to `shared.name`. Confirm no admin-cookie path goes through this validator (admin path uses Spring Resource Server's BearerTokenResolver directly).
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/enums/TokenClaim.java` — remove `UID`; access_level/authority_tier remain (still custom claim names). Subject is set via `Jwts.builder().subject(...)`, not via the claims map.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/enums/AccessLevel.java` — add `ROLE_SUPER_ADMIN`.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java` — register `AdminTokenRenewalFilter` after `BearerTokenAuthenticationFilter`; register `AdminOriginGuardFilter` before security; permitAll for `/api/v1/auth/admin/login`; new `authenticated()` for `/api/v1/auth/admin/**` (logout etc.).
- `app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/AuthController.java` — replace `cookieUtil.addAccessTokenCookie(...)` with `sharedSessionCookieWriter.write(...)`; logout clears shared cookie only (admin logout lives in `AdminAuthController`).
- `app/src/main/java/com/pfplaybackend/api/auth/application/service/AuthService.java` — build `TokenClaimsRequest` with `List.of(AccessLevel.ROLE_MEMBER)`; call `mintSharedSessionToken(...)`.
- `app/src/main/resources/application.yml` — split `app.jwt.cookie` into `app.jwt.cookie.admin.*` + `app.jwt.cookie.shared.*`; add `app.ratelimit.admin-login.*`; add `app.security.admin-origins.*`.

### Files Removed

- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CookieUtil.java` (callers re-pointed to writers).
- `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CookieUtilTest.java` (replaced by writer-specific tests).

### Files Verified-but-Not-Modified

- `app/.../administration/application/service/SuperAdminSeedService.java` — placeholder replacement is unchanged. After PR 4 is deployed, the seeded admin can log in via `/api/v1/auth/admin/login` with the env-supplied email + password.
- `app/.../administration/domain/entity/data/AdministratorData.java` — no changes; `findByUserAccountId` is sufficient.
- `app/src/main/resources/db/migration/V*.sql` — no migration in PR 4. (Lockout state lives in bucket4j memory; no DB column.)

---

## Test Strategy

| Layer | Test type | Notes |
|---|---|---|
| `JwtService.mintAdminAccessToken` | Pure unit + injected `Clock` | Subject = userAccountId; `access_level` JSON is array; TTL = 15 min; signing key not regressed |
| `JwtService.mintSharedSessionToken` | Pure unit | Same subject; `authority_tier` nullable supported; TTL = 24h |
| `CustomJwtAuthenticationConverter` | Pure unit | Builds N `GrantedAuthority` from N `access_level` entries; nullable `authority_tier` survives; missing `sub` throws |
| `AdminCookieWriter` / `SharedSessionCookieWriter` | Pure unit (Mock `HttpServletResponse`) | Verify Set-Cookie header substring matches Domain/SameSite/Max-Age/HttpOnly/Secure exactly |
| `CookieBearerTokenResolver` | Pure unit | `/api/v1/admin/foo` → returns AdminAccessToken; `/api/v1/users/me` → returns SharedSessionToken; missing cookie → null |
| `AdminTokenRenewalFilter` | Pure unit + MockHttpServletRequest/Response/MockFilterChain | Renewal happens iff (auth present) AND (token < 5 min remaining); skips on `/api/v1/auth/admin/login`; never on un-authenticated requests |
| `AdminOriginGuardFilter` | Pure unit | Allow GET regardless; deny POST when Origin not in allowlist; allow POST when `/api/v1/auth/admin/login` (login is permitAll and origin's not yet present pre-flight) |
| `AdminLoginRateLimiter` | Pure unit + injected `Clock` | IP bucket consumes 10/5min, then 11th throws RATE_LIMITED; email bucket consumes 5 fails, then 6th throws; success resets email bucket; lockout cleared after 15min |
| `AdminLoginService` | Mockito unit | Wrong email → INVALID_CREDENTIALS; wrong password → INVALID_CREDENTIALS; revoked admin → ACCOUNT_REVOKED; success without Member → AdminAccessToken only; success with Member → both tokens; rate-limit hit before bcrypt to avoid timing oracle |
| `AdminAuthController` | `@WebMvcTest` slice (or pure controller test with MockMvc) | 200 + cookies on success; 401 generic message on bad creds; 429 on rate-limit; 403 from origin guard when Origin missing |
| End-to-end smoke (Task X) | Manual `curl` against booted app | Admin login from CLI; verify two cookies; access `/api/v1/admin/...` with admin cookie → 200 (or 403 if endpoint not implemented yet — accept either, just NOT 401) |

Decisions:
- No Testcontainers for PR 4. The login flow's only DB read (UserAccount + Administrator + Member) is exercised in `AdminLoginServiceTest` via Mockito-mocked repositories. The `@SpringBootTest` flavor would add ~30s to the suite for marginal coverage gain; defer to integration tests in PR 14.
- `AdminAuthControllerTest` uses `@WebMvcTest(AdminAuthController.class)` with a stubbed `AdminLoginService` bean, so no full Spring boot.
- The IAM `Clock` (already wired in `JwtService` via `private final java.time.Clock clock;`) is reused across all unit tests for deterministic time. Bucket4j's `TimeMeter` is set to `TimeMeter.SYSTEM_MILLISECONDS` in production but injected with a custom `TimeMeter` in tests for determinism.

---

## Chunk 0: Build + Properties Skeleton

### Task 0: Add bucket4j + Caffeine dependencies to app module

**Files:**
- Modify: `app/build.gradle`

- [ ] **Step 1: Inspect current dependency block**

```bash
grep -n 'bucket4j\|caffeine\|com.github.ben-manes' app/build.gradle
```

Expected: no matches.

- [ ] **Step 2: Append bucket4j + Caffeine under the existing dependencies block**

After the JJWT block, insert:

```gradle
	// Rate limiting (admin login)
	implementation 'com.bucket4j:bucket4j-core:8.10.1'
	implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'
```

- [ ] **Step 3: Refresh dependencies**

```bash
./gradlew :app:dependencies --configuration runtimeClasspath | grep -E 'bucket4j|caffeine' | head -5
```

Expected: `com.bucket4j:bucket4j-core:8.10.1` and `com.github.ben-manes.caffeine:caffeine:3.1.8` resolve cleanly.

- [ ] **Step 4: Compile to confirm no transitive conflict**

```bash
./gradlew :app:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle
git commit -m "chore(deps): add bucket4j-core + caffeine for admin login rate limiting (PR 4)

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.5.3"
```

---

### Task 1: Restructure JwtProperties cookie config (admin + shared split)

**Files:**
- Create: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/properties/AdminCookieProperties.java`
- Create: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/properties/SharedCookieProperties.java`
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/properties/JwtProperties.java`
- Modify: `app/src/main/resources/application.yml`

- [ ] **Step 1: Write AdminCookieProperties**

```java
package com.pfplaybackend.api.common.config.security.jwt.properties;

import lombok.Data;

@Data
public class AdminCookieProperties {
    private String name = "AdminAccessToken";
    private String domain;
    private String path = "/";
    private boolean secure = true;
    private String sameSite = "Strict";
    private int maxAgeSeconds = 900;          // 15 min
    private int renewalThresholdSeconds = 300; // re-issue when < 5 min remaining
}
```

- [ ] **Step 2: Write SharedCookieProperties**

```java
package com.pfplaybackend.api.common.config.security.jwt.properties;

import lombok.Data;

@Data
public class SharedCookieProperties {
    private String name = "SharedSessionToken";
    private String domain;
    private String path = "/";
    private boolean secure = true;
    private String sameSite = "Lax";
    private int maxAgeSeconds = 86400; // 24h
}
```

- [ ] **Step 3: Refactor JwtProperties**

Replace the inner `CookieProperties` with two nested instances:

```java
package com.pfplaybackend.api.common.config.security.jwt.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private String secret;
    private long adminAccessTokenExpirationMs = 900_000L;     // 15 min
    private long sharedSessionTokenExpirationMs = 86_400_000L; // 24h

    private Cookie cookie = new Cookie();

    @Data
    public static class Cookie {
        private AdminCookieProperties admin = new AdminCookieProperties();
        private SharedCookieProperties shared = new SharedCookieProperties();
    }
}
```

- [ ] **Step 4: Update application.yml `app.jwt` block**

Replace the existing `app.jwt` section under the common profile (lines ~110-123) with:

```yaml
  jwt:
    secret: ${JWT_SECRET}
    admin-access-token-expiration-ms: 900000      # 15 min
    shared-session-token-expiration-ms: 86400000  # 24h

    cookie:
      admin:
        name: AdminAccessToken
        domain: ${ADMIN_COOKIE_DOMAIN:localhost}
        path: /
        secure: true
        same-site: Strict
        max-age-seconds: 900
        renewal-threshold-seconds: 300
      shared:
        name: SharedSessionToken
        domain: ${COOKIE_DOMAIN:localhost}
        path: /
        secure: true
        same-site: None
        max-age-seconds: 86400
```

And in the `dev` / `staging` / `prod` profile overrides, replace the single-cookie blocks (lines ~174, ~222, ~270) with:

```yaml
app:
  jwt:
    cookie:
      admin:
        domain: ${ADMIN_COOKIE_DOMAIN}
        secure: ${COOKIE_SECURE:true}
        same-site: Strict
      shared:
        domain: ${COOKIE_DOMAIN}
        secure: ${COOKIE_SECURE:true}
        same-site: ${COOKIE_SAME_SITE:Lax}
```

**Local profile** — plain HTTP, browsers silently drop cookies with `Secure=true` + `SameSite=None`. The current `application.yml` has profile sections for `common`, `dev`, `staging`, `prod` but **no `local` profile block** (verify with `grep -n 'on-profile: local' app/src/main/resources/application.yml` — should return no matches). Append a new `---` YAML document for the `local` profile at the end of the file:

```yaml
---
# 🔵 로컬 개발 환경
spring:
  config:
    activate:
      on-profile: local

app:
  jwt:
    cookie:
      admin:
        secure: false
        same-site: Lax       # Strict would block cross-tab navigation from devtools
      shared:
        secure: false
        same-site: Lax
```

If a `local` profile section turns out to exist (e.g., a teammate added one between when this plan was written and execution), merge these `app.jwt.cookie.*` settings into it rather than creating a duplicate `on-profile: local` block.

- [ ] **Step 5: Boot once to confirm property binding**

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

Wait for "Started ApiApplication". The boot will FAIL at compile time of subsequent tasks because callers of the old `JwtProperties.CookieProperties` shape don't yet exist — that's expected. For now we only need property binding to parse.

If the binding error mentions an unrecognized key or a missing setter, fix before committing.

```bash
taskkill //F //IM java.exe
```

- [ ] **Step 6: DO NOT COMMIT — Task 1 belongs to atomic commit group G1**

After Task 1's edits, `CookieUtil`, `JwtCookieValidator`, `CookieBearerTokenResolver`, and `AuthController` do not compile. Per **Atomic commit groupings (G1)**, continue straight to Tasks 7 and 8 without committing — they re-route the callers to the new properties shape. The single G1 commit happens at the END of Task 8.

Do NOT run `./gradlew build` here — it will fail. Run only the property-binding boot smoke from Step 5 to confirm YAML parses correctly, then proceed to Task 2.

---

## Chunk 1: JWT Claim Redesign

### Task 2: Add ROLE_SUPER_ADMIN to AccessLevel

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/enums/AccessLevel.java`

- [ ] **Step 1: Add SUPER_ADMIN constant**

```java
package com.pfplaybackend.api.common.config.security.enums;

public enum AccessLevel {
    ROLE_SUPER_ADMIN,
    ROLE_ADMIN,
    ROLE_MEMBER,
    ROLE_GUEST;
}
```

Order matters only for human readability — Spring's `RoleHierarchy` is not used here; `hasRole('ADMIN')` is plain authority equality.

- [ ] **Step 2: Recompile**

```bash
./gradlew :common:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/enums/AccessLevel.java
git commit -m "feat(security): add ROLE_SUPER_ADMIN to AccessLevel enum (PR 4)

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.2.1"
```

---

### Task 3: Refactor TokenClaimsRequest to carry a list of access levels

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/dto/TokenClaimsRequest.java`

- [ ] **Step 1: Replace single accessLevel with list + add nullable authority_tier**

```java
package com.pfplaybackend.api.common.config.security.jwt.dto;

import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.enums.AuthorityTier;

import java.util.List;
import java.util.Objects;

/**
 * Claims request used by JwtService to mint either an AdminAccessToken
 * or a SharedSessionToken. The {@code subject} carries the userAccountId
 * (set on the JWT subject claim, not as a custom claim).
 *
 * @param subject       userAccountId as a string (JWT {@code sub} claim)
 * @param email         user email (custom {@code email} claim)
 * @param accessLevels  one or more granted authorities (custom {@code access_level} claim, JSON array)
 * @param authorityTier optional Member tier (Amplitude integration). Null when no Member exists.
 */
public record TokenClaimsRequest(
        String subject,
        String email,
        List<AccessLevel> accessLevels,
        AuthorityTier authorityTier
) {
    public TokenClaimsRequest {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(accessLevels, "accessLevels must not be null");
        if (accessLevels.isEmpty()) {
            throw new IllegalArgumentException("accessLevels must not be empty");
        }
        accessLevels = List.copyOf(accessLevels); // defensive copy + immutability
    }
}
```

- [ ] **Step 2: Note compile breakage**

`AuthService` and any other consumer of the old constructor will fail. They are fixed in Task 6 (AuthService) and Task 11 (AdminLoginService). For now, keep going — the next task (JwtService refactor) is the bigger lift and depends on this DTO shape.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/dto/TokenClaimsRequest.java
git commit -m "refactor(jwt): TokenClaimsRequest carries access-level list + nullable tier (PR 4)

The JWT subject now carries userAccountId; access_level becomes a JSON
array; authority_tier is nullable for admins without a linked Member.

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.3.1"
```

---

### Task 4: Refactor JwtService — split into admin + shared minting paths

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/JwtService.java`
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/enums/TokenClaim.java`
- Test: `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/JwtServiceAdminTokenTest.java`
- Test: `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/JwtServiceSharedTokenTest.java`

- [ ] **Step 1: Drop UID from TokenClaim**

```java
package com.pfplaybackend.api.common.config.security.jwt.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TokenClaim {
    EMAIL("email"),
    ACCESS_LEVEL("access_level"),
    AUTHORITY_TIER("authority_tier");

    private final String value;
}
```

- [ ] **Step 2: Write the failing tests for admin token shape**

Create `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/JwtServiceAdminTokenTest.java`:

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceAdminTokenTest {

    private JwtService jwtService;
    private JwtProperties props;

    @BeforeEach
    void setup() {
        props = new JwtProperties();
        props.setSecret("test-secret-must-be-at-least-32-bytes-long-for-hs256");
        props.setAdminAccessTokenExpirationMs(900_000L);
        props.setSharedSessionTokenExpirationMs(86_400_000L);
        Clock fixed = Clock.fixed(Instant.parse("2026-04-26T00:00:00Z"), ZoneOffset.UTC);
        jwtService = new JwtService(props, fixed);
    }

    @Test
    void admin_token_has_userAccountId_subject_and_role_admin_authority() {
        var req = new TokenClaimsRequest(
                "1000000000000042",
                "admin@pfplay.xyz",
                List.of(AccessLevel.ROLE_ADMIN),
                null
        );

        String token = jwtService.mintAdminAccessToken(req);

        Claims c = parse(token);
        assertThat(c.getSubject()).isEqualTo("1000000000000042");
        assertThat(c.get("email", String.class)).isEqualTo("admin@pfplay.xyz");
        assertThat(c.get("access_level", List.class))
                .containsExactly("ROLE_ADMIN");
        assertThat(c.get("authority_tier")).isNull();
        assertThat(c.getExpiration().toInstant())
                .isEqualTo(Instant.parse("2026-04-26T00:15:00Z"));
    }

    @Test
    void super_admin_token_has_two_authorities() {
        var req = new TokenClaimsRequest(
                "1000000000000001",
                "super@pfplay.xyz",
                List.of(AccessLevel.ROLE_SUPER_ADMIN, AccessLevel.ROLE_ADMIN),
                null
        );

        String token = jwtService.mintAdminAccessToken(req);

        Claims c = parse(token);
        assertThat(c.get("access_level", List.class))
                .containsExactlyInAnyOrder("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
    }

    private Claims parse(String token) {
        SecretKey key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
```

- [ ] **Step 3: Write the failing tests for shared token shape**

Create `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/JwtServiceSharedTokenTest.java`:

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceSharedTokenTest {

    private JwtService jwtService;
    private JwtProperties props;

    @BeforeEach
    void setup() {
        props = new JwtProperties();
        props.setSecret("test-secret-must-be-at-least-32-bytes-long-for-hs256");
        props.setAdminAccessTokenExpirationMs(900_000L);
        props.setSharedSessionTokenExpirationMs(86_400_000L);
        Clock fixed = Clock.fixed(Instant.parse("2026-04-26T00:00:00Z"), ZoneOffset.UTC);
        jwtService = new JwtService(props, fixed);
    }

    @Test
    void shared_token_carries_authority_tier_when_present() {
        var req = new TokenClaimsRequest(
                "1000000000000042",
                "user@pfplay.xyz",
                List.of(AccessLevel.ROLE_MEMBER),
                AuthorityTier.FM
        );

        String token = jwtService.mintSharedSessionToken(req);
        Claims c = parse(token);

        assertThat(c.getSubject()).isEqualTo("1000000000000042");
        assertThat(c.get("authority_tier", String.class)).isEqualTo("FM");
        assertThat(c.getExpiration().toInstant())
                .isEqualTo(Instant.parse("2026-04-27T00:00:00Z"));
    }

    @Test
    void shared_token_authority_tier_omitted_when_null() {
        var req = new TokenClaimsRequest(
                "1000000000000099",
                "tierless@pfplay.xyz",
                List.of(AccessLevel.ROLE_MEMBER),
                null
        );

        String token = jwtService.mintSharedSessionToken(req);
        Claims c = parse(token);

        assertThat(c.get("authority_tier")).isNull();
    }

    private Claims parse(String token) {
        SecretKey key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
```

- [ ] **Step 4: Run the failing tests**

```bash
./gradlew :common:test --tests 'JwtServiceAdminTokenTest' --tests 'JwtServiceSharedTokenTest'
```

Expected: compile error (JwtService methods don't exist).

- [ ] **Step 5: Rewrite JwtService**

Replace the entire body of `JwtService.java` with:

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.enums.TokenClaim;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.exception.AuthenticationException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties jwtProperties;
    private final Clock clock;

    public String mintAdminAccessToken(TokenClaimsRequest claims) {
        return mint(claims, jwtProperties.getAdminAccessTokenExpirationMs());
    }

    public String mintSharedSessionToken(TokenClaimsRequest claims) {
        return mint(claims, jwtProperties.getSharedSessionTokenExpirationMs());
    }

    private String mint(TokenClaimsRequest req, long ttlMs) {
        Date now = Date.from(clock.instant());
        Date exp = new Date(now.getTime() + ttlMs);

        Map<String, Object> custom = new HashMap<>();
        custom.put(TokenClaim.EMAIL.getValue(), req.email());
        custom.put(TokenClaim.ACCESS_LEVEL.getValue(),
                req.accessLevels().stream().map(AccessLevel::name).toList());
        if (req.authorityTier() != null) {
            custom.put(TokenClaim.AUTHORITY_TIER.getValue(), req.authorityTier().name());
        }

        return Jwts.builder()
                .claims(custom)
                .subject(req.subject())
                .issuedAt(now)
                .expiration(exp)
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    public boolean validate(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (Exception e) {
            log.debug("Invalid token: {}", e.getMessage());
            return false;
        }
    }

    public Claims extractClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new AuthenticationException("Token has expired");
        } catch (MalformedJwtException e) {
            throw new AuthenticationException("Invalid token format");
        } catch (Exception e) {
            throw new AuthenticationException("Token validation failed");
        }
    }

    public long timeUntilExpiryMs(String token) {
        Date exp = extractClaims(token).getExpiration();
        return exp.getTime() - clock.millis();
    }

    public List<String> getAccessLevels(String token) {
        Object raw = extractClaims(token).get(TokenClaim.ACCESS_LEVEL.getValue());
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    public String getSubject(String token) {
        return extractClaims(token).getSubject();
    }

    public String getEmail(String token) {
        return extractClaims(token).get(TokenClaim.EMAIL.getValue(), String.class);
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }
}
```

Note: existing JwtCookieValidator uses `getUserIdFromToken`, `getEmailFromToken`, `getProviderFromToken`, `getAccessLevelFromToken`, `getAuthorityTierFromToken`, `isMemberToken`, `isGuestToken`, `isTokenNearExpiry`, `validateAccessToken`, `validateRefreshToken`, `getAccessTokenExpiration`. **Refactor JwtCookieValidator in this same task** to use only the new public surface (`getSubject`, `getEmail`, `getAccessLevels`, `validate`, `timeUntilExpiryMs`). Refresh-token paths and provider-claim paths are deleted (no refresh tokens are minted anymore in this codebase per the new model; `provider` was a dead claim).

- [ ] **Step 6: Refactor JwtCookieValidator (delete dead methods, re-route remaining)**

Open `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/JwtCookieValidator.java` and:
- Remove `extractAndValidateRefreshToken`, `hasValidRefreshToken`, `isMemberUser`, `isGuestUser`, `extractProvider`, `extractAccessLevel`, `extractAuthorityTier`, `extractAllClaims`, `needsTokenRefresh` (none have callers — verify with `grep -rn 'extractAndValidateRefreshToken\|isMemberUser\|...' .` first; if any have callers in the code base outside tests, surface to controller).
- Replace `jwtProperties.getCookie().getAccessTokenName()` with `jwtProperties.getCookie().getShared().getName()`.
- Replace `jwtService.getUserIdFromToken(token)` with `jwtService.getSubject(token)`.
- Replace `jwtService.getEmailFromToken(token)` with `jwtService.getEmail(token)`.
- Replace `jwtService.isTokenNearExpiry(token)` with a local check using `jwtService.timeUntilExpiryMs(token) < 600_000L`.
- Replace `jwtService.validateAccessToken(token)` with `jwtService.validate(token)`.

Verify with:

```bash
grep -rn 'getUserIdFromToken\|getEmailFromToken\|getProviderFromToken\|getAccessLevelFromToken\|getAuthorityTierFromToken\|isMemberToken\|isGuestToken\|validateAccessToken\|validateRefreshToken\|getAccessTokenExpiration\|isTokenNearExpiry' .
```

Expected: no remaining call sites after the refactor.

- [ ] **Step 7: Run tests**

```bash
./gradlew :common:test --tests 'JwtServiceAdminTokenTest' --tests 'JwtServiceSharedTokenTest'
```

Expected: PASS.

```bash
./gradlew :common:compileJava :common:compileTestJava
```

Expected: BUILD SUCCESSFUL. App module is still red until later tasks.

- [ ] **Step 8: DO NOT COMMIT — Task 4 belongs to atomic commit group G2**

After this task, `AuthService.processOAuthLogin` does not compile (it calls the now-deleted `jwtService.generateAccessToken`). Per **Atomic commit groupings (G2)**, continue to Task 6 without committing — Task 6 re-routes the call to `mintSharedSessionToken`. The single G2 commit happens at the END of Task 6.

Run common-module tests (`./gradlew :common:test`) to confirm common is green; skip app-module compile until Task 6 lands.

---

### Task 5: Update CustomJwtAuthenticationConverter + Token to new claim shape

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CustomJwtAuthenticationConverter.java`
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CustomJwtAuthenticationToken.java`
- Test: `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CustomJwtAuthenticationConverterTest.java`

- [ ] **Step 1: Drop `provider` from CustomJwtAuthenticationToken**

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.Objects;

@Getter
public class CustomJwtAuthenticationToken extends JwtAuthenticationToken {

    private final UserId userId;
    private final String email;
    private final AuthorityTier authorityTier;

    public CustomJwtAuthenticationToken(Jwt jwt,
                                        Collection<? extends GrantedAuthority> authorities,
                                        UserId userId,
                                        String email,
                                        AuthorityTier authorityTier) {
        super(jwt, authorities);
        this.userId = userId;
        this.email = email;
        this.authorityTier = authorityTier;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomJwtAuthenticationToken that)) return false;
        if (!super.equals(o)) return false;
        return Objects.equals(userId, that.userId)
                && Objects.equals(email, that.email)
                && authorityTier == that.authorityTier;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), userId, email, authorityTier);
    }
}
```

- [ ] **Step 2: Write failing test for converter**

Create `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CustomJwtAuthenticationConverterTest.java`:

```java
package com.pfplaybackend.api.common.config.security.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomJwtAuthenticationConverterTest {

    private final CustomJwtAuthenticationConverter sut = new CustomJwtAuthenticationConverter();

    @Test
    void array_access_level_yields_one_authority_per_entry() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .subject("1000000000000001")
                .claim("email", "super@pfplay.xyz")
                .claim("access_level", List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"))
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(900))
                .build();

        AbstractAuthenticationToken auth = sut.convert(jwt);

        assertThat(auth.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        assertThat(((CustomJwtAuthenticationToken) auth).getAuthorityTier()).isNull();
    }

    @Test
    void member_token_with_authority_tier_is_parsed() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .subject("1000000000000042")
                .claim("email", "user@pfplay.xyz")
                .claim("access_level", List.of("ROLE_MEMBER"))
                .claim("authority_tier", "FM")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(86400))
                .build();

        AbstractAuthenticationToken auth = sut.convert(jwt);

        assertThat(((CustomJwtAuthenticationToken) auth).getAuthorityTier().name()).isEqualTo("FM");
    }

    @Test
    void missing_subject_throws() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .claim("email", "x@x.com")
                .claim("access_level", List.of("ROLE_MEMBER"))
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60))
                .build();

        assertThatThrownBy(() -> sut.convert(jwt))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: Rewrite the converter**

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomJwtAuthenticationConverter
        implements org.springframework.core.convert.converter.Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw new IllegalArgumentException("JWT missing required 'sub' claim");
        }
        UserId userId = UserId.fromString(subject);
        String email = jwt.getClaim("email");

        List<String> levels = jwt.getClaim("access_level");
        if (levels == null || levels.isEmpty()) {
            throw new IllegalArgumentException("JWT missing required 'access_level' claim");
        }
        List<GrantedAuthority> authorities = levels.stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();

        String tier = jwt.getClaim("authority_tier");
        AuthorityTier authorityTier = StringUtils.hasText(tier) ? AuthorityTier.valueOf(tier) : null;

        return new CustomJwtAuthenticationToken(jwt, authorities, userId, email, authorityTier);
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :common:test --tests 'CustomJwtAuthenticationConverterTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CustomJwtAuthenticationConverter.java \
        common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CustomJwtAuthenticationToken.java \
        common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CustomJwtAuthenticationConverterTest.java
git commit -m "feat(jwt): converter reads sub + access_level array + nullable tier (PR 4)

Drops the dead 'provider' claim. Each access_level entry becomes a
GrantedAuthority — a SUPER_ADMIN gets ROLE_SUPER_ADMIN + ROLE_ADMIN.

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.3.3"
```

---

### Task 6: Update AuthService (OAuth callback) for new claim shape

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/auth/application/service/AuthService.java`

- [ ] **Step 1: Update minting call**

Replace the JWT minting block (lines ~67-73) with:

```java
            // 5. Generate Shared Session token (long-lived, apex domain)
            String token = jwtService.mintSharedSessionToken(new TokenClaimsRequest(
                    String.valueOf(member.getUserAccountId()),
                    userAccount.getEmail(),
                    java.util.List.of(AccessLevel.ROLE_MEMBER),
                    member.getAuthorityTier()
            ));
```

And update `AuthResult` construction:

```java
            return new AuthResult(token, "Cookie", jwtProperties.getSharedSessionTokenExpirationMs(), LocalDateTime.now(clock));
```

(Inject `JwtProperties` via constructor — it's already a `@Configuration` bean, so add the field and the constructor parameter.)

- [ ] **Step 2: Compile common + app**

```bash
./gradlew :common:compileJava :app:compileJava
```

Expected: BUILD SUCCESSFUL. (If Task 1 / G1 hasn't been committed yet because we're working in G1 → G2 → ... order, the implementer is on the same branch and all G1 changes are in the working tree, so app compiles. If working tree is somehow split, abort and reconcile.)

- [ ] **Step 3: Single G2 commit (closes group)**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/JwtService.java \
        common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/JwtCookieValidator.java \
        common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/enums/TokenClaim.java \
        common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/JwtServiceAdminTokenTest.java \
        common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/JwtServiceSharedTokenTest.java \
        app/src/main/java/com/pfplaybackend/api/auth/application/service/AuthService.java
git commit -m "feat(jwt): redesign claims (sub=userAccountId, access_level array, nullable tier) (PR 4)

JwtService splits into mintAdminAccessToken (15-min) and
mintSharedSessionToken (24h). Removes legacy uid/single-string
access_level/provider claims. JwtCookieValidator now reads
SharedSessionToken. AuthService.processOAuthLogin migrated to the new
claim shape in lockstep (G2 atomic group).

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.3.1, §5.3.3"
```

---

## Chunk 2: Cookie Writers + Path-Aware Resolver

### Task 7: Replace CookieUtil with AdminCookieWriter + SharedSessionCookieWriter

**Files:**
- Create: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/AdminCookieWriter.java`
- Create: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/SharedSessionCookieWriter.java`
- Test: `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/AdminCookieWriterTest.java`
- Test: `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/SharedSessionCookieWriterTest.java`
- Delete: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CookieUtil.java`
- Delete: `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CookieUtilTest.java`

- [ ] **Step 1: Write failing AdminCookieWriter test**

Create `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/AdminCookieWriterTest.java`:

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.AdminCookieProperties;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCookieWriterTest {

    @Test
    void write_emits_set_cookie_with_admin_attributes() {
        var props = new JwtProperties();
        AdminCookieProperties admin = props.getCookie().getAdmin();
        admin.setName("AdminAccessToken");
        admin.setDomain("admin.pfplay.xyz");
        admin.setSameSite("Strict");
        admin.setMaxAgeSeconds(900);
        AdminCookieWriter sut = new AdminCookieWriter(props);

        HttpServletResponse response = new MockHttpServletResponse();
        sut.write(response, "tok-abc");

        String header = response.getHeader("Set-Cookie");
        assertThat(header)
                .contains("AdminAccessToken=tok-abc")
                .contains("Domain=admin.pfplay.xyz")
                .contains("SameSite=Strict")
                .contains("Max-Age=900")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("Path=/");
    }

    @Test
    void clear_emits_zero_max_age_cookie() {
        var props = new JwtProperties();
        AdminCookieWriter sut = new AdminCookieWriter(props);

        HttpServletResponse response = new MockHttpServletResponse();
        sut.clear(response);

        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }
}
```

- [ ] **Step 2: Write failing SharedSessionCookieWriter test**

Create `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/SharedSessionCookieWriterTest.java`:

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.config.security.jwt.properties.SharedCookieProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SharedSessionCookieWriterTest {

    @Test
    void write_emits_set_cookie_with_shared_attributes() {
        var props = new JwtProperties();
        SharedCookieProperties shared = props.getCookie().getShared();
        shared.setName("SharedSessionToken");
        shared.setDomain(".pfplay.xyz");
        shared.setSameSite("Lax");
        shared.setMaxAgeSeconds(86400);
        SharedSessionCookieWriter sut = new SharedSessionCookieWriter(props);

        HttpServletResponse response = new MockHttpServletResponse();
        sut.write(response, "tok-xyz");

        String header = response.getHeader("Set-Cookie");
        assertThat(header)
                .contains("SharedSessionToken=tok-xyz")
                .contains("Domain=.pfplay.xyz")
                .contains("SameSite=Lax")
                .contains("Max-Age=86400");
    }
}
```

- [ ] **Step 3: Implement AdminCookieWriter**

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.AdminCookieProperties;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminCookieWriter {

    private final JwtProperties jwtProperties;

    public void write(HttpServletResponse response, String token) {
        AdminCookieProperties p = jwtProperties.getCookie().getAdmin();
        emit(response, p, token, p.getMaxAgeSeconds());
    }

    public void clear(HttpServletResponse response) {
        AdminCookieProperties p = jwtProperties.getCookie().getAdmin();
        emit(response, p, "", 0);
    }

    private void emit(HttpServletResponse response, AdminCookieProperties p, String value, int maxAge) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.getName()).append('=').append(value);
        sb.append("; Path=").append(p.getPath());
        sb.append("; Max-Age=").append(maxAge);
        sb.append("; HttpOnly");
        if (p.isSecure()) sb.append("; Secure");
        sb.append("; SameSite=").append(p.getSameSite());
        if (p.getDomain() != null && !p.getDomain().isBlank()) {
            sb.append("; Domain=").append(p.getDomain());
        }
        response.addHeader("Set-Cookie", sb.toString());
    }
}
```

- [ ] **Step 4: Implement SharedSessionCookieWriter**

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.config.security.jwt.properties.SharedCookieProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SharedSessionCookieWriter {

    private final JwtProperties jwtProperties;

    public void write(HttpServletResponse response, String token) {
        SharedCookieProperties p = jwtProperties.getCookie().getShared();
        emit(response, p, token, p.getMaxAgeSeconds());
    }

    public void clear(HttpServletResponse response) {
        SharedCookieProperties p = jwtProperties.getCookie().getShared();
        emit(response, p, "", 0);
    }

    private void emit(HttpServletResponse response, SharedCookieProperties p, String value, int maxAge) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.getName()).append('=').append(value);
        sb.append("; Path=").append(p.getPath());
        sb.append("; Max-Age=").append(maxAge);
        sb.append("; HttpOnly");
        if (p.isSecure()) sb.append("; Secure");
        sb.append("; SameSite=").append(p.getSameSite());
        if (p.getDomain() != null && !p.getDomain().isBlank()) {
            sb.append("; Domain=").append(p.getDomain());
        }
        response.addHeader("Set-Cookie", sb.toString());
    }
}
```

- [ ] **Step 5: Delete old CookieUtil + tests**

```bash
git rm common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CookieUtil.java \
       common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CookieUtilTest.java
```

- [ ] **Step 6: Run tests + compile**

```bash
./gradlew :common:test --tests 'AdminCookieWriterTest' --tests 'SharedSessionCookieWriterTest'
```

Expected: PASS.

- [ ] **Step 7: DO NOT COMMIT — Task 7 belongs to atomic commit group G1**

Continue to Task 8 (path-aware resolver + AuthController repoint) before committing. The single G1 commit closes at the end of Task 8.

---

### Task 8: Make CookieBearerTokenResolver path-aware + repoint AuthController

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolver.java`
- Modify: `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolverTest.java` (rename to `...PathAwareTest` — `git mv`).
- Modify: `app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/AuthController.java`

- [ ] **Step 1: Rewrite the resolver**

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

@Slf4j
@Component
@RequiredArgsConstructor
public class CookieBearerTokenResolver implements BearerTokenResolver {

    static final String ADMIN_PATH_PATTERN = "/api/v1/admin/**";

    private final JwtProperties jwtProperties;
    private final AntPathMatcher matcher = new AntPathMatcher();

    @Override
    public String resolve(HttpServletRequest request) {
        String name = pickCookieName(request);
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private String pickCookieName(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null) path = request.getRequestURI();
        boolean adminPath = matcher.match(ADMIN_PATH_PATTERN, path);
        return adminPath
                ? jwtProperties.getCookie().getAdmin().getName()
                : jwtProperties.getCookie().getShared().getName();
    }
}
```

- [ ] **Step 2: Rewrite the test (replaces existing single-cookie test)**

`git mv common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolverTest.java common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolverPathAwareTest.java`

Rewrite contents:

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class CookieBearerTokenResolverPathAwareTest {

    private CookieBearerTokenResolver sut;

    @BeforeEach
    void setup() {
        JwtProperties props = new JwtProperties();
        props.getCookie().getAdmin().setName("AdminAccessToken");
        props.getCookie().getShared().setName("SharedSessionToken");
        sut = new CookieBearerTokenResolver(props);
    }

    @Test
    void admin_path_reads_admin_cookie() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/admin/users");
        req.setCookies(
                new Cookie("AdminAccessToken", "admin-jwt"),
                new Cookie("SharedSessionToken", "shared-jwt")
        );

        assertThat(sut.resolve(req)).isEqualTo("admin-jwt");
    }

    @Test
    void non_admin_path_reads_shared_cookie() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/users/me");
        req.setCookies(
                new Cookie("AdminAccessToken", "admin-jwt"),
                new Cookie("SharedSessionToken", "shared-jwt")
        );

        assertThat(sut.resolve(req)).isEqualTo("shared-jwt");
    }

    @Test
    void admin_login_path_falls_through_to_shared_cookie_lookup() {
        // /api/v1/auth/admin/login is NOT under /api/v1/admin/**, so it goes to shared.
        // But login is permitAll anyway, so the resolver result is moot here. Document the behavior.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/auth/admin/login");
        req.setCookies(new Cookie("SharedSessionToken", "shared-jwt"));

        assertThat(sut.resolve(req)).isEqualTo("shared-jwt");
    }

    @Test
    void no_cookies_returns_null() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/admin/anything");

        assertThat(sut.resolve(req)).isNull();
    }
}
```

- [ ] **Step 3: Update AuthController to use SharedSessionCookieWriter**

Replace `private final CookieUtil cookieUtil;` with `private final SharedSessionCookieWriter sharedSessionCookieWriter;`. In `oauthCallback`:

```java
        sharedSessionCookieWriter.write(response, authResult.accessToken());
```

In `logout`:

```java
        sharedSessionCookieWriter.clear(response);
```

(Drop the `deleteRefreshTokenCookie` call — refresh tokens are gone.)

- [ ] **Step 4: Compile + test**

```bash
./gradlew :common:test --tests 'CookieBearerTokenResolverPathAwareTest'
./gradlew :app:compileJava
```

Expected: tests PASS, app compile SUCCESSFUL.

- [ ] **Step 5: Single G1 commit (closes group)**

This commit covers all of Tasks 1, 7, and 8 — properties refactor, cookie writers, CookieUtil deletion, path-aware resolver, AuthController + JwtCookieValidator repoint, and the application.yml split (incl. the local-profile cookie overrides).

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/properties/AdminCookieProperties.java \
        common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/properties/SharedCookieProperties.java \
        common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/properties/JwtProperties.java \
        common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/AdminCookieWriter.java \
        common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/SharedSessionCookieWriter.java \
        common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolver.java \
        common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/JwtCookieValidator.java \
        common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/AdminCookieWriterTest.java \
        common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/SharedSessionCookieWriterTest.java \
        common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolverPathAwareTest.java \
        app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/AuthController.java \
        app/src/main/resources/application.yml

git rm common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CookieUtil.java
git rm common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CookieUtilTest.java
git rm common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolverTest.java 2>/dev/null || true

./gradlew :common:test :app:compileJava
# Expected: BUILD SUCCESSFUL.

git commit -m "refactor(jwt+security): split admin/shared cookies + path-aware resolver (PR 4)

Single atomic commit for atomic group G1:
- JwtProperties.cookie split into admin + shared sub-properties
- AdminCookieProperties, SharedCookieProperties added
- CookieUtil deleted; replaced by AdminCookieWriter + SharedSessionCookieWriter
- CookieBearerTokenResolver becomes path-aware (admin path -> AdminAccessToken;
  else -> SharedSessionToken)
- JwtCookieValidator re-routed to shared cookie name
- AuthController OAuth callback uses SharedSessionCookieWriter
- application.yml: admin + shared cookie blocks per profile, local override
  uses Secure=false + SameSite=Lax for plain-HTTP dev

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.3.4, §5.4.2"
```

---

## Chunk 3: Admin Login API

### Task 9: AdminLoginException + AdminLoginCommand/Result DTOs

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/auth/domain/exception/AdminAuthException.java`
- Create: `app/src/main/java/com/pfplaybackend/api/auth/application/dto/command/AdminLoginCommand.java`
- Create: `app/src/main/java/com/pfplaybackend/api/auth/application/dto/result/AdminAuthResult.java`

- [ ] **Step 1: Implement AdminAuthException**

Mirror the existing `AuthException` style. Verify the existing exception with `cat app/src/main/java/com/pfplaybackend/api/auth/domain/exception/AuthException.java` and copy the pattern. Codes:

```java
INVALID_CREDENTIALS("AUTH_ADMIN_001", "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED),
RATE_LIMITED         ("AUTH_ADMIN_002", "너무 많은 요청입니다. 잠시 후 다시 시도해주세요.",   HttpStatus.TOO_MANY_REQUESTS),
ACCOUNT_REVOKED     ("AUTH_ADMIN_003", "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED),
```

Note: `ACCOUNT_REVOKED` returns the same user-facing message as `INVALID_CREDENTIALS` (do NOT leak account state per spec §5.1.3). The distinct error code is for server-side logging only.

- [ ] **Step 2: Implement AdminLoginCommand**

```java
package com.pfplaybackend.api.auth.application.dto.command;

public record AdminLoginCommand(String email, String password, String clientIp) {}
```

- [ ] **Step 3: Implement AdminAuthResult**

```java
package com.pfplaybackend.api.auth.application.dto.result;

import com.pfplaybackend.api.administration.domain.value.AdminRole;

import java.time.LocalDateTime;

public record AdminAuthResult(
        String adminAccessToken,
        String sharedSessionToken,        // null when no Member is linked
        AdminRole role,
        long adminAccessTokenTtlMs,
        long sharedSessionTokenTtlMs,    // 0 when no shared token issued
        LocalDateTime issuedAt
) {}
```

- [ ] **Step 4: Compile**

```bash
./gradlew :app:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/auth/domain/exception/AdminAuthException.java \
        app/src/main/java/com/pfplaybackend/api/auth/application/dto/command/AdminLoginCommand.java \
        app/src/main/java/com/pfplaybackend/api/auth/application/dto/result/AdminAuthResult.java
git commit -m "feat(auth): add admin login DTOs + exception types (PR 4)

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.1.2"
```

---

### Task 10: Rate limiter — bucket4j config + AdminLoginRateLimiter

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/auth/config/properties/RateLimitProperties.java`
- Create: `app/src/main/java/com/pfplaybackend/api/auth/config/RateLimitConfig.java`
- Create: `app/src/main/java/com/pfplaybackend/api/auth/application/ratelimit/AdminLoginRateLimiter.java`
- Modify: `app/src/main/resources/application.yml`
- Test: `app/src/test/java/com/pfplaybackend/api/auth/application/ratelimit/AdminLoginRateLimiterTest.java`

- [ ] **Step 1: Add RateLimitProperties**

```java
package com.pfplaybackend.api.auth.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.ratelimit.admin-login")
public class RateLimitProperties {
    private Bucket ip = new Bucket(10, 300);     // 10 attempts / 5 min
    private Bucket email = new Bucket(5, 900);  // 5 attempts / 15 min, lockout = window

    @Data
    public static class Bucket {
        private int capacity;
        private int windowSeconds;
        public Bucket() {}
        public Bucket(int capacity, int windowSeconds) {
            this.capacity = capacity;
            this.windowSeconds = windowSeconds;
        }
    }
}
```

- [ ] **Step 2: Add config block to application.yml common profile**

After the `app.jwt` block, add:

```yaml
  ratelimit:
    admin-login:
      ip:
        capacity: 10
        window-seconds: 300
      email:
        capacity: 5
        window-seconds: 900
```

- [ ] **Step 3: Add RateLimitConfig (Caffeine-backed bucket cache)**

```java
package com.pfplaybackend.api.auth.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitConfig {

    @Bean("adminLoginIpBuckets")
    public Cache<String, Bucket> adminLoginIpBuckets() {
        return Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(1))
                .maximumSize(10_000)
                .build();
    }

    @Bean("adminLoginEmailBuckets")
    public Cache<String, Bucket> adminLoginEmailBuckets() {
        return Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(1))
                .maximumSize(10_000)
                .build();
    }
}
```

- [ ] **Step 4: Implement AdminLoginRateLimiter**

```java
package com.pfplaybackend.api.auth.application.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.pfplaybackend.api.auth.config.properties.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Two-axis admin-login rate limiter:
 *   - IP-bucket: prevents broad attacker brute-force.
 *   - Email-bucket: targets account-specific attempts.
 * Either bucket exhaustion → throws {@link RateLimitedException}.
 *
 * On successful login, the email-bucket is reset (the user proved knowledge of password).
 * The IP-bucket is *not* reset on success (success doesn't redeem traffic surge).
 */
@Component
public class AdminLoginRateLimiter {

    private final RateLimitProperties props;
    private final Cache<String, Bucket> ipBuckets;
    private final Cache<String, Bucket> emailBuckets;

    /**
     * Hand-written constructor (NOT @RequiredArgsConstructor): Lombok does not propagate
     * @Qualifier annotations onto the generated constructor parameters, which causes
     * NoUniqueBeanDefinitionException at boot when two beans of type Cache<String, Bucket>
     * exist. Hand-writing the constructor lets us annotate the parameters directly.
     */
    public AdminLoginRateLimiter(
            RateLimitProperties props,
            @Qualifier("adminLoginIpBuckets") Cache<String, Bucket> ipBuckets,
            @Qualifier("adminLoginEmailBuckets") Cache<String, Bucket> emailBuckets) {
        this.props = props;
        this.ipBuckets = ipBuckets;
        this.emailBuckets = emailBuckets;
    }

    public void checkOrThrow(String clientIp, String email) {
        if (clientIp != null && !clientIp.isBlank()) {
            consume(ipBuckets, clientIp, props.getIp());
        }
        if (email != null && !email.isBlank()) {
            consume(emailBuckets, email.toLowerCase(), props.getEmail());
        }
    }

    public void onLoginSuccess(String email) {
        if (email == null) return;
        emailBuckets.invalidate(email.toLowerCase());
    }

    private void consume(Cache<String, Bucket> cache, String key, RateLimitProperties.Bucket cfg) {
        Bucket bucket = cache.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(cfg.getCapacity())
                        .refillIntervally(cfg.getCapacity(), Duration.ofSeconds(cfg.getWindowSeconds()))
                        .build())
                .build());
        if (!bucket.tryConsume(1)) {
            throw new RateLimitedException();
        }
    }

    public static class RateLimitedException extends RuntimeException {}
}
```

- [ ] **Step 5: Write tests**

Create `app/src/test/java/com/pfplaybackend/api/auth/application/ratelimit/AdminLoginRateLimiterTest.java`:

```java
package com.pfplaybackend.api.auth.application.ratelimit;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.pfplaybackend.api.auth.config.properties.RateLimitProperties;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminLoginRateLimiterTest {

    private AdminLoginRateLimiter sut;

    @BeforeEach
    void setup() {
        var props = new RateLimitProperties();
        props.setIp(new RateLimitProperties.Bucket(3, 60));
        props.setEmail(new RateLimitProperties.Bucket(2, 60));
        var ipCache = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(5)).<String, Bucket>build();
        var emailCache = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(5)).<String, Bucket>build();
        sut = new AdminLoginRateLimiter(props, ipCache, emailCache);
    }

    @Test
    void ip_bucket_exhausts_after_capacity() {
        sut.checkOrThrow("1.2.3.4", "a@x.com");
        sut.checkOrThrow("1.2.3.4", "b@x.com");
        sut.checkOrThrow("1.2.3.4", "c@x.com");
        // bucket capacity 3 reached on email side too actually — split tests below

        assertThatThrownBy(() -> sut.checkOrThrow("1.2.3.4", "d@x.com"))
                .isInstanceOf(AdminLoginRateLimiter.RateLimitedException.class);
    }

    @Test
    void email_bucket_cleared_on_success() {
        sut.checkOrThrow("9.9.9.9", "victim@x.com");
        sut.checkOrThrow("9.9.9.9", "victim@x.com");
        sut.onLoginSuccess("victim@x.com");

        // After success, email-bucket reset; only IP bucket has consumption (2/3).
        assertThatCode(() -> sut.checkOrThrow("9.9.9.9", "victim@x.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void distinct_ips_have_independent_buckets() {
        sut.checkOrThrow("1.1.1.1", "x@x.com");
        sut.checkOrThrow("1.1.1.1", "y@x.com");
        sut.checkOrThrow("1.1.1.1", "z@x.com");

        // Different IP — independent bucket; first call should succeed.
        assertThatCode(() -> sut.checkOrThrow("2.2.2.2", "fresh@x.com"))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 6: Confirm bean resolution at boot**

`AdminLoginRateLimiter` uses a hand-written constructor specifically because two `Cache<String, Bucket>` beans exist (IP + email). The hand-written constructor (above) places `@Qualifier` directly on the constructor parameters — this is required, NOT optional. Lombok's `@RequiredArgsConstructor` does not work here because it strips parameter annotations.

If you accidentally added `@RequiredArgsConstructor` while writing the class, remove it before continuing. Boot the app to confirm:

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

Expected: "Started ApiApplication" with no `NoUniqueBeanDefinitionException` for `Cache<String, Bucket>`.

- [ ] **Step 7: Run tests + boot**

```bash
./gradlew :app:test --tests 'AdminLoginRateLimiterTest'
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

Expected: tests PASS; boot succeeds (Started ApiApplication). Stop the app.

```bash
taskkill //F //IM java.exe
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/auth/config/properties/RateLimitProperties.java \
        app/src/main/java/com/pfplaybackend/api/auth/config/RateLimitConfig.java \
        app/src/main/java/com/pfplaybackend/api/auth/application/ratelimit/AdminLoginRateLimiter.java \
        app/src/test/java/com/pfplaybackend/api/auth/application/ratelimit/AdminLoginRateLimiterTest.java \
        app/src/main/resources/application.yml
git commit -m "feat(auth): in-memory IP+email rate limiter for admin login (PR 4)

Bucket4j-core 8.x with Caffeine-backed bucket cache (per-instance,
single-process). IP capacity 10/5min, email capacity 5/15min.
Success resets the email bucket only.

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.5.3, §5.5.5"
```

---

### Task 11: AdminLoginService

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository.java`
- Create: `app/src/main/java/com/pfplaybackend/api/auth/application/service/AdminLoginService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/auth/application/service/AdminLoginServiceTest.java`

- [ ] **Step 0: Add `findByUserAccountId(Long)` to MemberRepository (separate commit)**

The current `MemberRepository` exposes only `existsByUserAccountId`. AdminLoginService needs to read the linked `Member` (to grab `authorityTier` and decide whether to mint `SharedSessionToken`).

```java
package com.pfplaybackend.api.user.adapter.out.persistence;

import com.pfplaybackend.api.user.adapter.out.persistence.custom.MemberRepositoryCustom;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<MemberData, Long>, MemberRepositoryCustom {

    boolean existsByUserAccountId(Long userAccountId);

    Optional<MemberData> findByUserAccountId(Long userAccountId);
}
```

Compile to confirm Spring Data resolves the derived query:

```bash
./gradlew :user:compileJava :app:compileJava
```

Expected: BUILD SUCCESSFUL.

Commit standalone (this is *not* part of any atomic group):

```bash
git add user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository.java
git commit -m "feat(user): add MemberRepository.findByUserAccountId for admin login (PR 4)

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.1.2"
```

- [ ] **Step 1: Write failing tests**

Create `AdminLoginServiceTest.java` with 5 scenarios:

```java
package com.pfplaybackend.api.auth.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.value.AdminRole;
import com.pfplaybackend.api.auth.application.dto.command.AdminLoginCommand;
import com.pfplaybackend.api.auth.application.dto.result.AdminAuthResult;
import com.pfplaybackend.api.auth.application.ratelimit.AdminLoginRateLimiter;
import com.pfplaybackend.api.auth.domain.exception.AdminAuthException;
import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminLoginServiceTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock AdministratorRepository administratorRepository;
    @Mock MemberRepository memberRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock AdminLoginRateLimiter rateLimiter;
    @Mock JwtProperties jwtProperties;

    Clock clock = Clock.systemUTC();

    AdminLoginService sut;

    @BeforeEach
    void setup() {
        sut = new AdminLoginService(
                userAccountRepository, administratorRepository, memberRepository,
                passwordEncoder, jwtService, rateLimiter, jwtProperties, clock);
    }

    @Test
    void unknown_email_throws_invalid_credentials() {
        when(userAccountRepository.findByEmailAndProviderType("ghost@x.com", ProviderType.LOCAL))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.login(new AdminLoginCommand("ghost@x.com", "any", "1.1.1.1")))
                .isInstanceOf(AdminAuthException.class);
        verify(rateLimiter).checkOrThrow("1.1.1.1", "ghost@x.com");
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void wrong_password_throws_invalid_credentials() {
        UserAccountData ua = stubLocalAccount(42L, "admin@x.com", "$2a$12$hashedhashedhashedhashed");
        when(userAccountRepository.findByEmailAndProviderType("admin@x.com", ProviderType.LOCAL))
                .thenReturn(Optional.of(ua));
        when(passwordEncoder.matches("wrong", ua.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> sut.login(new AdminLoginCommand("admin@x.com", "wrong", "1.1.1.1")))
                .isInstanceOf(AdminAuthException.class);
    }

    @Test
    void revoked_admin_throws_account_revoked() {
        UserAccountData ua = stubLocalAccount(42L, "admin@x.com", "$2a$12$h");
        AdministratorData adm = stubRevokedAdmin();
        when(userAccountRepository.findByEmailAndProviderType("admin@x.com", ProviderType.LOCAL))
                .thenReturn(Optional.of(ua));
        when(passwordEncoder.matches("right", ua.getPasswordHash())).thenReturn(true);
        when(administratorRepository.findByUserAccountId(42L)).thenReturn(Optional.of(adm));

        assertThatThrownBy(() -> sut.login(new AdminLoginCommand("admin@x.com", "right", "1.1.1.1")))
                .isInstanceOf(AdminAuthException.class);
    }

    @Test
    void successful_login_without_member_issues_admin_token_only() {
        UserAccountData ua = stubLocalAccount(42L, "admin@x.com", "$2a$12$h");
        AdministratorData adm = stubActiveAdmin(AdminRole.ADMIN);
        when(userAccountRepository.findByEmailAndProviderType("admin@x.com", ProviderType.LOCAL))
                .thenReturn(Optional.of(ua));
        when(passwordEncoder.matches("right", ua.getPasswordHash())).thenReturn(true);
        when(administratorRepository.findByUserAccountId(42L)).thenReturn(Optional.of(adm));
        when(memberRepository.findByUserAccountId(42L)).thenReturn(Optional.empty());
        when(jwtService.mintAdminAccessToken(any())).thenReturn("admin-jwt");
        when(jwtProperties.getAdminAccessTokenExpirationMs()).thenReturn(900_000L);

        AdminAuthResult res = sut.login(new AdminLoginCommand("admin@x.com", "right", "1.1.1.1"));

        assertThat(res.adminAccessToken()).isEqualTo("admin-jwt");
        assertThat(res.sharedSessionToken()).isNull();
        verify(rateLimiter).onLoginSuccess("admin@x.com");
        verify(jwtService, never()).mintSharedSessionToken(any());
    }

    @Test
    void successful_login_with_member_issues_both_tokens() {
        UserAccountData ua = stubLocalAccount(42L, "admin@x.com", "$2a$12$h");
        AdministratorData adm = stubActiveAdmin(AdminRole.SUPER_ADMIN);
        MemberData mem = stubMember(42L);
        when(userAccountRepository.findByEmailAndProviderType("admin@x.com", ProviderType.LOCAL))
                .thenReturn(Optional.of(ua));
        when(passwordEncoder.matches("right", ua.getPasswordHash())).thenReturn(true);
        when(administratorRepository.findByUserAccountId(42L)).thenReturn(Optional.of(adm));
        when(memberRepository.findByUserAccountId(42L)).thenReturn(Optional.of(mem));
        when(jwtService.mintAdminAccessToken(any())).thenReturn("admin-jwt");
        when(jwtService.mintSharedSessionToken(any())).thenReturn("shared-jwt");
        when(jwtProperties.getAdminAccessTokenExpirationMs()).thenReturn(900_000L);
        when(jwtProperties.getSharedSessionTokenExpirationMs()).thenReturn(86_400_000L);

        AdminAuthResult res = sut.login(new AdminLoginCommand("admin@x.com", "right", "1.1.1.1"));

        assertThat(res.adminAccessToken()).isEqualTo("admin-jwt");
        assertThat(res.sharedSessionToken()).isEqualTo("shared-jwt");
        assertThat(res.role()).isEqualTo(AdminRole.SUPER_ADMIN);
    }

    // --- Helpers (stub the entity factories — adapt to actual constructors) ---

    private UserAccountData stubLocalAccount(long id, String email, String passwordHash) {
        UserAccountData ua = mock(UserAccountData.class);
        when(ua.getUserId()).thenReturn(new UserId(id));
        when(ua.getEmail()).thenReturn(email);
        when(ua.getPasswordHash()).thenReturn(passwordHash);
        return ua;
    }

    private AdministratorData stubActiveAdmin(AdminRole role) {
        AdministratorData a = mock(AdministratorData.class);
        when(a.getRole()).thenReturn(role);
        when(a.isRevoked()).thenReturn(false);
        return a;
    }

    private AdministratorData stubRevokedAdmin() {
        AdministratorData a = mock(AdministratorData.class);
        when(a.isRevoked()).thenReturn(true);
        return a;
    }

    private MemberData stubMember(long userAccountId) {
        MemberData m = mock(MemberData.class);
        when(m.getUserAccountId()).thenReturn(userAccountId);
        // when(m.getAuthorityTier()).thenReturn(AuthorityTier.FM);  // optional
        return m;
    }
}
```

(Adapt mock factories to the actual `MemberRepository.findByUserAccountId(...)` signature — verify with `grep -n 'findByUserAccountId' user/src/main/java/.../MemberRepository.java`. If the method does not exist, add it as part of this task.)

- [ ] **Step 2: Implement AdminLoginService**

```java
package com.pfplaybackend.api.auth.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.value.AdminRole;
import com.pfplaybackend.api.auth.application.dto.command.AdminLoginCommand;
import com.pfplaybackend.api.auth.application.dto.result.AdminAuthResult;
import com.pfplaybackend.api.auth.application.ratelimit.AdminLoginRateLimiter;
import com.pfplaybackend.api.auth.domain.exception.AdminAuthException;
import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLoginService {

    private final UserAccountRepository userAccountRepository;
    private final AdministratorRepository administratorRepository;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AdminLoginRateLimiter rateLimiter;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public AdminAuthResult login(AdminLoginCommand cmd) {
        // 1. Rate limit (BEFORE bcrypt to deny timing oracle).
        try {
            rateLimiter.checkOrThrow(cmd.clientIp(), cmd.email());
        } catch (AdminLoginRateLimiter.RateLimitedException e) {
            log.warn("admin_login.rate_limited ip={} email={}", cmd.clientIp(), cmd.email());
            throw ExceptionCreator.create(AdminAuthException.RATE_LIMITED);
        }

        // 2. Resolve UserAccount.
        Optional<UserAccountData> uaOpt =
                userAccountRepository.findByEmailAndProviderType(cmd.email(), ProviderType.LOCAL);
        if (uaOpt.isEmpty()) {
            log.info("admin_login.unknown_email email={}", cmd.email());
            throw ExceptionCreator.create(AdminAuthException.INVALID_CREDENTIALS);
        }
        UserAccountData ua = uaOpt.get();

        // 3. Verify password.
        if (!passwordEncoder.matches(cmd.password(), ua.getPasswordHash())) {
            log.info("admin_login.wrong_password user_id={} email={}",
                    ua.getUserId().getUid(), cmd.email());
            throw ExceptionCreator.create(AdminAuthException.INVALID_CREDENTIALS);
        }

        // 4. Resolve Administrator.
        Optional<AdministratorData> admOpt =
                administratorRepository.findByUserAccountId(ua.getUserId().getUid());
        if (admOpt.isEmpty()) {
            log.warn("admin_login.no_administrator user_id={} email={}",
                    ua.getUserId().getUid(), cmd.email());
            throw ExceptionCreator.create(AdminAuthException.INVALID_CREDENTIALS);
        }
        AdministratorData adm = admOpt.get();
        if (adm.isRevoked()) {
            log.warn("admin_login.revoked user_id={}", ua.getUserId().getUid());
            throw ExceptionCreator.create(AdminAuthException.ACCOUNT_REVOKED);
        }

        // 5. Build access levels for AdminAccessToken.
        List<AccessLevel> adminLevels = new ArrayList<>();
        adminLevels.add(AccessLevel.ROLE_ADMIN);
        if (adm.getRole() == AdminRole.SUPER_ADMIN) {
            adminLevels.add(AccessLevel.ROLE_SUPER_ADMIN);
        }

        // 6. Resolve linked Member (optional).
        Optional<MemberData> memberOpt = memberRepository.findByUserAccountId(ua.getUserId().getUid());

        // 7. Mint AdminAccessToken.
        String adminToken = jwtService.mintAdminAccessToken(new TokenClaimsRequest(
                String.valueOf(ua.getUserId().getUid()),
                ua.getEmail(),
                adminLevels,
                memberOpt.map(MemberData::getAuthorityTier).orElse(null)
        ));

        // 8. Mint SharedSessionToken only if a Member exists.
        String sharedToken = null;
        long sharedTtl = 0L;
        if (memberOpt.isPresent()) {
            MemberData m = memberOpt.get();
            sharedToken = jwtService.mintSharedSessionToken(new TokenClaimsRequest(
                    String.valueOf(ua.getUserId().getUid()),
                    ua.getEmail(),
                    List.of(AccessLevel.ROLE_MEMBER),
                    m.getAuthorityTier()
            ));
            sharedTtl = jwtProperties.getSharedSessionTokenExpirationMs();
        }

        // 9. Reset email bucket (success).
        rateLimiter.onLoginSuccess(cmd.email());

        log.info("admin_login.success user_id={} role={} member_linked={}",
                ua.getUserId().getUid(), adm.getRole(), memberOpt.isPresent());

        return new AdminAuthResult(
                adminToken,
                sharedToken,
                adm.getRole(),
                jwtProperties.getAdminAccessTokenExpirationMs(),
                sharedTtl,
                LocalDateTime.now(clock)
        );
    }
}
```

- [ ] **Step 3: Verify MemberRepository.findByUserAccountId is in place**

```bash
grep -n 'findByUserAccountId' user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository.java
```

Expected: one match (added in Step 0). If absent, return to Step 0 — Task 11 cannot proceed without it.

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:test --tests 'AdminLoginServiceTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/auth/application/service/AdminLoginService.java \
        app/src/test/java/com/pfplaybackend/api/auth/application/service/AdminLoginServiceTest.java \
        user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository.java
git commit -m "feat(auth): AdminLoginService — verify, mint, optionally issue shared session (PR 4)

Order: rate-limit → UserAccount lookup → bcrypt → Administrator lookup →
revoked check → Member lookup → mint admin + (optionally) shared tokens.
Generic INVALID_CREDENTIALS on every credential-class failure.

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.1.2, §5.1.3"
```

---

### Task 12: AdminAuthController + DTO + SecurityConfig wiring

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthController.java`
- Create: `app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/payload/request/AdminLoginRequest.java`
- Create: `app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/payload/response/AdminLoginResponse.java`
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java`
- Test: `app/src/test/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthControllerTest.java`

- [ ] **Step 1: AdminLoginRequest**

```java
package com.pfplaybackend.api.auth.adapter.in.web.payload.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class AdminLoginRequest {
    @Email
    @NotBlank
    @Size(max = 255)
    private String email;

    @NotBlank
    @Size(min = 8, max = 128)
    private String password;
}
```

- [ ] **Step 2: AdminLoginResponse**

```java
package com.pfplaybackend.api.auth.adapter.in.web.payload.response;

import com.pfplaybackend.api.administration.domain.value.AdminRole;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record AdminLoginResponse(
        String tokenType,
        long expiresIn,
        LocalDateTime issuedAt,
        AdminRole role
) {}
```

- [ ] **Step 3: AdminAuthController**

```java
package com.pfplaybackend.api.auth.adapter.in.web;

import com.pfplaybackend.api.auth.adapter.in.web.payload.request.AdminLoginRequest;
import com.pfplaybackend.api.auth.adapter.in.web.payload.response.AdminLoginResponse;
import com.pfplaybackend.api.auth.application.dto.command.AdminLoginCommand;
import com.pfplaybackend.api.auth.application.dto.result.AdminAuthResult;
import com.pfplaybackend.api.auth.application.service.AdminLoginService;
import com.pfplaybackend.api.auth.domain.exception.AdminAuthException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Admin Auth API", description = "어드민 로컬 로그인/로그아웃")
@RestController
@RequestMapping("/api/v1/auth/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminLoginService adminLoginService;
    private final AdminCookieWriter adminCookieWriter;
    private final SharedSessionCookieWriter sharedSessionCookieWriter;

    @Operation(summary = "어드민 로그인",
            description = "이메일+비밀번호 로컬 인증. 성공 시 AdminAccessToken 쿠키(15분), Member 연결 시 SharedSessionToken 쿠키(24h)도 함께 발급.")
    @ApiErrorCodes({AdminAuthException.class})
    @PostMapping("/login")
    public ResponseEntity<ApiCommonResponse<AdminLoginResponse>> login(
            @Valid @RequestBody AdminLoginRequest req,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        String clientIp = resolveClientIp(httpRequest);

        AdminAuthResult result = adminLoginService.login(
                new AdminLoginCommand(req.getEmail(), req.getPassword(), clientIp));

        adminCookieWriter.write(response, result.adminAccessToken());
        if (result.sharedSessionToken() != null) {
            sharedSessionCookieWriter.write(response, result.sharedSessionToken());
        }

        return ResponseEntity.ok(ApiCommonResponse.success(AdminLoginResponse.builder()
                .tokenType("Cookie")
                .expiresIn(result.adminAccessTokenTtlMs() / 1000)
                .issuedAt(result.issuedAt())
                .role(result.role())
                .build()));
    }

    @Operation(summary = "어드민 로그아웃", description = "AdminAccessToken과 SharedSessionToken 쿠키를 모두 만료시킵니다.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        adminCookieWriter.clear(response);
        sharedSessionCookieWriter.clear(response);
        return ResponseEntity.noContent().build();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // First entry in XFF is the original client IP.
            return xff.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

(Note on `X-Forwarded-For`: trust XFF only behind a known load balancer. Until the LB+ingress topology is final, treat this as best-effort. PR 14 (frontend rollout) revisits if XFF spoofing surfaces as a concern.)

- [ ] **Step 4: Update SecurityConfig**

In the `authorizeHttpRequests` block, add `/api/v1/auth/admin/login` to the permitAll group and add a separate `authenticated()` rule for `/api/v1/auth/admin/**`:

```java
                        .requestMatchers("/api/v1/auth/oauth/callback", "/api/v1/auth/oauth/url",
                                "/api/v1/auth/logout", "/api/v1/auth/admin/login",
                                "/api/v1/users/members/sign/**", "/api/v1/users/guests/sign/**",
                                "/api/v1/partyrooms/link/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/spec/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Admin endpoints — role-gated.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // Admin auth (logout etc.) — must be authenticated.
                        .requestMatchers("/api/v1/auth/admin/**").authenticated()
                        // Everything else under /api requires auth
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
```

- [ ] **Step 5: Write controller test**

Pattern adapted from existing `AuthControllerTest.java` (slice test with `@WebMvcTest` + `@MockBean` services + Spring Security Test's `jwt()` / `csrf()` post-processors).

Create `app/src/test/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthControllerTest.java`:

```java
package com.pfplaybackend.api.auth.adapter.in.web;

import com.pfplaybackend.api.administration.domain.value.AdminRole;
import com.pfplaybackend.api.auth.application.dto.result.AdminAuthResult;
import com.pfplaybackend.api.auth.application.service.AdminLoginService;
import com.pfplaybackend.api.auth.domain.exception.AdminAuthException;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminAuthController.class)
class AdminAuthControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean AdminLoginService adminLoginService;
    @MockBean AdminCookieWriter adminCookieWriter;
    @MockBean SharedSessionCookieWriter sharedSessionCookieWriter;
    @MockBean JwtDecoder jwtDecoder;

    private static final String VALID_BODY = """
            {"email":"super@pfplay.local","password":"DevSeed123!"}
            """;

    @Test
    @DisplayName("login — 200 + admin cookie when no Member linked")
    void login_success_admin_only() throws Exception {
        when(adminLoginService.login(any())).thenReturn(new AdminAuthResult(
                "admin-jwt", null, AdminRole.SUPER_ADMIN,
                900_000L, 0L, LocalDateTime.now()));

        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.expiresIn").value(900));

        verify(adminCookieWriter).write(any(HttpServletResponse.class), eq("admin-jwt"));
        verifyNoInteractions(sharedSessionCookieWriter);
    }

    @Test
    @DisplayName("login — 200 + both cookies when Member linked")
    void login_success_with_member_writes_both_cookies() throws Exception {
        when(adminLoginService.login(any())).thenReturn(new AdminAuthResult(
                "admin-jwt", "shared-jwt", AdminRole.ADMIN,
                900_000L, 86_400_000L, LocalDateTime.now()));

        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());

        verify(adminCookieWriter).write(any(HttpServletResponse.class), eq("admin-jwt"));
        verify(sharedSessionCookieWriter).write(any(HttpServletResponse.class), eq("shared-jwt"));
    }

    @Test
    @DisplayName("login — 401 on invalid credentials")
    void login_invalid_credentials_returns_401() throws Exception {
        when(adminLoginService.login(any()))
                .thenThrow(ExceptionCreator.create(AdminAuthException.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ghost@x.com","password":"wrongpass"}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminCookieWriter, sharedSessionCookieWriter);
    }

    @Test
    @DisplayName("login — 429 when rate-limited")
    void login_rate_limited_returns_429() throws Exception {
        when(adminLoginService.login(any()))
                .thenThrow(ExceptionCreator.create(AdminAuthException.RATE_LIMITED));

        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("login — 400 when @Valid rejects empty body")
    void login_validation_rejects_blank_password() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"super@pfplay.local","password":""}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adminLoginService);
    }

    @Test
    @DisplayName("logout — 204 + clears both cookies")
    void logout_clears_both_cookies() throws Exception {
        // /api/v1/auth/admin/** is authenticated() per SecurityConfig (Task 12 Step 4).
        // Match the existing AuthControllerTest pattern: use the jwt() post-processor
        // to inject a ROLE_ADMIN authentication into the security context.
        mockMvc.perform(post("/api/v1/auth/admin/logout")
                        .with(csrf())
                        .with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isNoContent());

        verify(adminCookieWriter).clear(any(jakarta.servlet.http.HttpServletResponse.class));
        verify(sharedSessionCookieWriter).clear(any(jakarta.servlet.http.HttpServletResponse.class));
    }
}
```

**Pattern reference:** `AuthControllerTest.logoutReturns204` uses `.with(jwt().authorities(() -> "ROLE_MEMBER"))` to satisfy the `authenticated()` rule under `@WebMvcTest`. Mirror that pattern with `ROLE_ADMIN` here. If the existing `AuthControllerTest` runs green in CI, this test will too.

- [ ] **Step 6: Boot smoke**

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

Wait for "Started ApiApplication". In another shell:

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/admin/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"super@pfplay.xyz","password":"any-password"}'
```

Expected: HTTP 401 (no super-admin seed in dev — assuming `ADMIN_SEED_EMAIL` was not set; or HTTP 200 + Set-Cookie if seeded). Confirm:
- If 401: response body contains `AUTH_ADMIN_001` (INVALID_CREDENTIALS).
- If 200: TWO `Set-Cookie` headers are present (admin + shared) only if the seeded admin's userAccountId is also a Member. SUPER_ADMIN seed by default has no Member → expect ONE cookie.

```bash
taskkill //F //IM java.exe
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthController.java \
        app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/payload/request/AdminLoginRequest.java \
        app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/payload/response/AdminLoginResponse.java \
        app/src/test/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthControllerTest.java \
        common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java
git commit -m "feat(auth): AdminAuthController — POST /admin/login + /admin/logout (PR 4)

Login is permitAll; logout requires auth. Both admin and shared cookies
are cleared on logout.

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.1.2, §5.1.4"
```

---

## Chunk 4: Sliding Renewal + Origin Guard

### Task 13: AdminTokenRenewalFilter

**Files:**
- Create: `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/AdminTokenRenewalFilter.java`
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java`
- Test: `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/AdminTokenRenewalFilterTest.java`

- [ ] **Step 1: Write failing test**

Create `common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/AdminTokenRenewalFilterTest.java`:

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminTokenRenewalFilterTest {

    private JwtProperties props;
    private JwtService jwtService;
    private AdminCookieWriter writer;
    private AdminTokenRenewalFilter sut;

    @BeforeEach
    void setup() {
        props = new JwtProperties();
        props.getCookie().getAdmin().setName("AdminAccessToken");
        props.getCookie().getAdmin().setRenewalThresholdSeconds(300); // 5 min
        jwtService = mock(JwtService.class);
        writer = mock(AdminCookieWriter.class);
        sut = new AdminTokenRenewalFilter(props, jwtService, writer);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void renews_when_authenticated_admin_path_and_token_under_threshold() throws Exception {
        givenAuthenticatedAdmin();
        var req = adminRequest("/api/v1/admin/users", "stale-jwt");
        var res = new MockHttpServletResponse();

        when(jwtService.timeUntilExpiryMs("stale-jwt")).thenReturn(60_000L); // 1 min left
        when(jwtService.getSubject("stale-jwt")).thenReturn("1000000000000042");
        when(jwtService.getEmail("stale-jwt")).thenReturn("admin@x.com");
        when(jwtService.mintAdminAccessToken(any())).thenReturn("fresh-jwt");

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer).write(eq(res), eq("fresh-jwt"));
    }

    @Test
    void no_renewal_when_token_remaining_above_threshold() throws Exception {
        givenAuthenticatedAdmin();
        var req = adminRequest("/api/v1/admin/users", "fresh-jwt");
        var res = new MockHttpServletResponse();

        when(jwtService.timeUntilExpiryMs("fresh-jwt")).thenReturn(14L * 60_000L); // 14 min

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer, never()).write(any(), any());
    }

    @Test
    void no_renewal_for_anonymous_authentication() throws Exception {
        // AnonymousAuthenticationToken.isAuthenticated() returns true — must be guarded.
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anon",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        var req = adminRequest("/api/v1/admin/users", "stale-jwt");
        var res = new MockHttpServletResponse();

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer, never()).write(any(), any());
        verifyNoInteractions(jwtService);
    }

    @Test
    void no_renewal_for_non_admin_path() throws Exception {
        givenAuthenticatedAdmin();
        var req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        req.setServletPath("/api/v1/users/me");
        req.setCookies(new Cookie("AdminAccessToken", "stale-jwt"));
        var res = new MockHttpServletResponse();

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer, never()).write(any(), any());
        verifyNoInteractions(jwtService);
    }

    @Test
    void no_renewal_on_admin_login_path() throws Exception {
        givenAuthenticatedAdmin();
        var req = new MockHttpServletRequest("POST", "/api/v1/auth/admin/login");
        req.setServletPath("/api/v1/auth/admin/login");
        var res = new MockHttpServletResponse();

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer, never()).write(any(), any());
    }

    @Test
    void no_renewal_when_no_cookie_present() throws Exception {
        givenAuthenticatedAdmin();
        var req = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        req.setServletPath("/api/v1/admin/users");
        // no cookies
        var res = new MockHttpServletResponse();

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer, never()).write(any(), any());
    }

    @Test
    void invalid_token_does_not_throw_or_renew() throws Exception {
        givenAuthenticatedAdmin();
        var req = adminRequest("/api/v1/admin/users", "garbage");
        var res = new MockHttpServletResponse();

        when(jwtService.timeUntilExpiryMs("garbage"))
                .thenThrow(new RuntimeException("malformed"));

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer, never()).write(any(), any());
    }

    private MockHttpServletRequest adminRequest(String path, String adminCookie) {
        var req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path);
        req.setCookies(new Cookie("AdminAccessToken", adminCookie));
        return req;
    }

    private void givenAuthenticatedAdmin() {
        var auth = new UsernamePasswordAuthenticationToken(
                "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
```

- [ ] **Step 2: Implement filter**

```java
package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminTokenRenewalFilter extends OncePerRequestFilter {

    private static final String ADMIN_PATH_PATTERN = "/api/v1/admin/**";
    private static final String ADMIN_LOGIN_PATH = "/api/v1/auth/admin/login";

    private final JwtProperties jwtProperties;
    private final JwtService jwtService;
    private final AdminCookieWriter adminCookieWriter;
    private final AntPathMatcher matcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(request, response);
        try {
            maybeRenew(request, response);
        } catch (Exception e) {
            log.warn("admin_token_renewal.error: {}", e.getMessage());
        }
    }

    private void maybeRenew(HttpServletRequest req, HttpServletResponse res) {
        String path = req.getServletPath();
        if (path == null) path = req.getRequestURI();
        if (!matcher.match(ADMIN_PATH_PATTERN, path)) return;
        if (ADMIN_LOGIN_PATH.equals(path)) return;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return;
        // AnonymousAuthenticationToken.isAuthenticated() returns true — must guard explicitly.
        if (auth instanceof AnonymousAuthenticationToken) return;
        if (!auth.isAuthenticated()) return;
        if (!hasRole(auth, "ROLE_ADMIN") && !hasRole(auth, "ROLE_SUPER_ADMIN")) return;

        String currentCookieName = jwtProperties.getCookie().getAdmin().getName();
        String currentToken = readCookie(req, currentCookieName);
        if (currentToken == null) return;

        long thresholdMs = jwtProperties.getCookie().getAdmin().getRenewalThresholdSeconds() * 1000L;
        long remaining;
        try {
            remaining = jwtService.timeUntilExpiryMs(currentToken);
        } catch (Exception e) {
            return; // do not renew on invalid token
        }
        if (remaining > thresholdMs) return;

        // Re-mint with the same authorities + email (sub). Defensive parsing — Spring may add
        // SCOPE_* or other non-AccessLevel authorities downstream; skip those.
        List<AccessLevel> levels = auth.getAuthorities().stream()
                .map(a -> {
                    try { return AccessLevel.valueOf(a.getAuthority()); }
                    catch (IllegalArgumentException ignored) { return null; }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        if (levels.isEmpty()) return; // nothing to mint with

        String sub;
        String email;
        try {
            sub = jwtService.getSubject(currentToken);
            email = jwtService.getEmail(currentToken);
        } catch (Exception e) {
            return; // claims unreadable — do not renew
        }
        // authority_tier is intentionally NOT propagated: AdminAccessToken doesn't need it.
        String fresh = jwtService.mintAdminAccessToken(new TokenClaimsRequest(sub, email, levels, null));
        adminCookieWriter.write(res, fresh);
    }

    private boolean hasRole(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    private String readCookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (var c : req.getCookies()) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
```

- [ ] **Step 3: Register the filter in SecurityConfig**

After `.oauth2ResourceServer(...)`, add:

```java
                .addFilterAfter(adminTokenRenewalFilter,
                        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
```

And inject `AdminTokenRenewalFilter` into the constructor field list.

- [ ] **Step 4: Run tests + boot**

```bash
./gradlew :common:test --tests 'AdminTokenRenewalFilterTest'
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

Expected: tests PASS; boot succeeds. Stop the app.

```bash
taskkill //F //IM java.exe
```

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/AdminTokenRenewalFilter.java \
        common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java \
        common/src/test/java/com/pfplaybackend/api/common/config/security/jwt/AdminTokenRenewalFilterTest.java
git commit -m "feat(security): sliding 15-min admin token renewal filter (PR 4)

Re-issues AdminAccessToken cookie when authenticated request reaches
an admin path with < 5 min remaining on the token. Skips login path
(login mints a fresh token already).

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.3.2, §11.2.4"
```

---

### Task 14: AdminOriginGuardFilter

**Files:**
- Create: `common/src/main/java/com/pfplaybackend/api/common/config/security/web/AdminOriginGuardFilter.java`
- Create: `common/src/main/java/com/pfplaybackend/api/common/config/security/web/properties/AdminOriginProperties.java`
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java`
- Modify: `app/src/main/resources/application.yml`
- Test: `common/src/test/java/com/pfplaybackend/api/common/config/security/web/AdminOriginGuardFilterTest.java`

- [ ] **Step 1: AdminOriginProperties**

```java
package com.pfplaybackend.api.common.config.security.web.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.security.admin-origin-guard")
public class AdminOriginProperties {
    private boolean enabled = true;
    private List<String> allowed = List.of();
}
```

- [ ] **Step 2: AdminOriginGuardFilter**

Skeleton:

```java
package com.pfplaybackend.api.common.config.security.web;

import com.pfplaybackend.api.common.config.security.web.properties.AdminOriginProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminOriginGuardFilter extends OncePerRequestFilter {

    private static final String ADMIN_PATH_PATTERN = "/api/v1/admin/**";
    private static final String ADMIN_AUTH_PATTERN = "/api/v1/auth/admin/**";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final AdminOriginProperties props;
    private final AntPathMatcher matcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        if (!props.isEnabled()) {
            chain.doFilter(req, res);
            return;
        }
        String path = req.getServletPath();
        if (path == null) path = req.getRequestURI();
        boolean inScope = matcher.match(ADMIN_PATH_PATTERN, path)
                || matcher.match(ADMIN_AUTH_PATTERN, path);
        if (!inScope || SAFE_METHODS.contains(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }

        String origin = req.getHeader("Origin");
        if (origin == null) origin = req.getHeader("Referer");
        if (origin == null || !isAllowed(origin)) {
            log.warn("admin_origin_guard.deny path={} origin={}", path, origin);
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"FORBIDDEN_ORIGIN\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean isAllowed(String headerValue) {
        try {
            URI uri = URI.create(headerValue);
            String origin = uri.getScheme() + "://" + uri.getAuthority();
            return props.getAllowed().contains(origin);
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 3: Register filter in SecurityConfig**

Before `.oauth2ResourceServer(...)`:

```java
                .addFilterBefore(adminOriginGuardFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
```

- [ ] **Step 4: Add config block to application.yml**

Common profile:

```yaml
  security:
    admin-origin-guard:
      enabled: true
      allowed:
        - https://localhost:3000
        - https://admin.pfplay.xyz
```

Production profile overrides as needed.

- [ ] **Step 5: Test**

Create `common/src/test/java/com/pfplaybackend/api/common/config/security/web/AdminOriginGuardFilterTest.java`:

```java
package com.pfplaybackend.api.common.config.security.web;

import com.pfplaybackend.api.common.config.security.web.properties.AdminOriginProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminOriginGuardFilterTest {

    private AdminOriginProperties props;
    private AdminOriginGuardFilter sut;

    @BeforeEach
    void setup() {
        props = new AdminOriginProperties();
        props.setEnabled(true);
        props.setAllowed(List.of("https://admin.pfplay.xyz", "https://localhost:3000"));
        sut = new AdminOriginGuardFilter(props);
    }

    @Test
    void post_admin_path_with_allowed_origin_passes() throws Exception {
        var req = post("/api/v1/admin/users", "https://admin.pfplay.xyz");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200); // default
        assertThat(chain.getRequest()).isSameAs(req); // chain advanced
    }

    @Test
    void post_admin_path_with_disallowed_origin_returns_403() throws Exception {
        var req = post("/api/v1/admin/users", "https://evil.com");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("FORBIDDEN_ORIGIN");
        assertThat(chain.getRequest()).isNull(); // chain NOT advanced
    }

    @Test
    void post_admin_path_with_no_origin_or_referer_returns_403() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/admin/users");
        req.setServletPath("/api/v1/admin/users");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void post_admin_path_falls_back_to_referer_when_origin_missing() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/admin/users");
        req.setServletPath("/api/v1/admin/users");
        req.addHeader("Referer", "https://admin.pfplay.xyz/some/page");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(req);
    }

    @Test
    void get_admin_path_passes_without_origin() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        req.setServletPath("/api/v1/admin/users");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(req);
    }

    @Test
    void post_non_admin_path_passes_with_disallowed_origin() throws Exception {
        var req = post("/api/v1/users/me", "https://evil.com");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(req);
    }

    @Test
    void post_admin_login_path_with_disallowed_origin_returns_403() throws Exception {
        // Admin auth (login) IS in scope of the guard — the frontend must always send a known Origin.
        var req = post("/api/v1/auth/admin/login", "https://evil.com");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void disabled_filter_passes_everything() throws Exception {
        props.setEnabled(false);
        var req = post("/api/v1/admin/users", "https://evil.com");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(req);
    }

    private MockHttpServletRequest post(String path, String origin) {
        var req = new MockHttpServletRequest("POST", path);
        req.setServletPath(path);
        if (origin != null) req.addHeader("Origin", origin);
        return req;
    }
}
```

- [ ] **Step 6: Run tests + boot**

```bash
./gradlew :common:test --tests 'AdminOriginGuardFilterTest'
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

Expected: tests PASS; boot succeeds.

```bash
taskkill //F //IM java.exe
```

- [ ] **Step 7: Commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/web/ \
        common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java \
        common/src/test/java/com/pfplaybackend/api/common/config/security/web/AdminOriginGuardFilterTest.java \
        app/src/main/resources/application.yml
git commit -m "feat(security): admin origin/referer allowlist filter (PR 4)

State-changing requests to /api/v1/admin/** and /api/v1/auth/admin/**
must carry an Origin (or Referer) matching app.security.admin-origin-guard.allowed.

This is option B of spec §5.4.3; option A (CSRF token + double-submit)
arrives in PR 5.

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.4.3"
```

---

## Chunk 5: End-to-End Smoke + Cleanup

### Task 15: Manual smoke + final review

**Files:**
- (verification only)

- [ ] **Step 1: Confirm the seeded super-admin can log in**

Boot:

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

Set seed env in another shell *before* boot if testing first-time finalization:

```bash
export ADMIN_SEED_EMAIL=super@pfplay.local
export ADMIN_SEED_PASSWORD=DevSeed123!ChangeMe
```

(Re-boot if it was started without these.)

Then:

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/admin/login \
  -H 'Content-Type: application/json' \
  -H 'Origin: https://localhost:3000' \
  -d '{"email":"super@pfplay.local","password":"DevSeed123!ChangeMe"}'
```

Expected: HTTP 200, response body `{ "data": { "tokenType": "Cookie", "expiresIn": 900, ..., "role": "SUPER_ADMIN" } }`. Exactly ONE `Set-Cookie: AdminAccessToken=...` header (no SharedSessionToken because the SUPER_ADMIN has no Member).

- [ ] **Step 2: Confirm origin guard rejects bad Origin**

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/admin/login \
  -H 'Content-Type: application/json' \
  -H 'Origin: https://evil.com' \
  -d '{"email":"super@pfplay.local","password":"DevSeed123!ChangeMe"}'
```

Expected: HTTP 403 + `{"error":"FORBIDDEN_ORIGIN"}`.

- [ ] **Step 3: Confirm rate limiter trips after 11 IP-bucket consumes**

Send 11 logins in a row (use a wrong password to avoid bcrypt latency dominating):

```bash
for i in $(seq 1 11); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/v1/auth/admin/login \
    -H 'Content-Type: application/json' \
    -H 'Origin: https://localhost:3000' \
    -d '{"email":"super@pfplay.local","password":"wrong"}'
done
```

Expected: first 10 attempts → 401; 11th → 429.

- [ ] **Step 4: Confirm /api/v1/admin/** still 401 without admin cookie**

```bash
curl -i -X GET http://localhost:8080/api/v1/admin/anything \
  -H 'Origin: https://localhost:3000'
```

Expected: HTTP 401 (no cookie) — NOT 503 (PR 3 maintenance mode bypass still works), NOT 403 (origin guard allows GET).

- [ ] **Step 5: Confirm logout clears both cookies**

After login (with valid cookie):

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/admin/logout \
  -H 'Origin: https://localhost:3000' \
  --cookie "AdminAccessToken=<token-from-login>"
```

Expected: HTTP 204, two `Set-Cookie` headers each with `Max-Age=0`.

- [ ] **Step 6: Stop the app**

```bash
taskkill //F //IM java.exe
```

- [ ] **Step 7: Run full test suite**

```bash
./gradlew clean test
```

Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 8: Final commit (if any test/lint fixes needed)**

If everything is green and no further code changes were made, no commit. Otherwise:

```bash
git add <fixes>
git commit -m "chore(pr4): final test/lint fixes"
```

- [ ] **Step 9: Push branch (manual — do NOT auto-push)**

(Push is a human decision per the project's git safety guidance. Surface the branch HEAD to the user and let them push.)

---

## Acceptance criteria (verify before opening PR)

- ✅ `/api/v1/auth/admin/login` returns 200 + cookies for valid credentials, 401 for invalid (with generic message), 429 after rate limit, 403 for missing/bad Origin.
- ✅ `/api/v1/admin/**` reads `AdminAccessToken` cookie; non-admin paths read `SharedSessionToken`.
- ✅ JWT subject = userAccountId, `access_level` is JSON array, `authority_tier` may be null.
- ✅ `AdminAccessToken` cookie has `Domain=admin.*`, `SameSite=Strict`, `Max-Age=900`.
- ✅ `SharedSessionToken` cookie has `Domain=.pfplay.xyz` (apex), `SameSite=Lax`, `Max-Age=86400`.
- ✅ Sliding renewal: an authenticated admin request with token < 5 min remaining gets a fresh `AdminAccessToken` cookie back.
- ✅ Logout clears both cookies.
- ✅ All PR 0–3 endpoints continue to work; existing tests pass.
- ✅ `BCryptPasswordEncoder(12)` remains the project's password encoder; `SuperAdminSeedService` continues to finalize the placeholder.
- ✅ No DB migration in this PR; no schema changes.

---

**End of PR 4 plan.**
