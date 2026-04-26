# PR 5: SecurityConfig Overhaul + `@PreAuthorize` Centralization (`adminAuth` SpEL bean) + FM Cleanup Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Activate method-level authorization globally (`@EnableMethodSecurity`), introduce a centralized `@adminAuth` SpEL bean (`AdminAuthorizationSpEL`) so admin authorization rules live in one place, retire all 11 misnamed `@PreAuthorize("hasAuthority('FM')")` admin annotations, split the URL-level admin gate so `/api/v1/admin/system/**` and `/api/v1/admin/avatar/**` require `SUPER_ADMIN` (rest stays `ADMIN`), enable CSRF token defense (Option A) for state-changing admin requests on top of PR 4's Origin/Referer guard (Option B), and ship the §5.7 권한 회귀 / 쿠키 격리 / CSRF parameterized regression tests.

**Architecture:** Method security activates via a new `@EnableMethodSecurity` annotation on `SecurityConfig` (production); test slices already enable it locally. A new `AdminAuthorizationSpEL` `@Component("adminAuth")` exposes intent-named methods (`canManageAdmins`, `canSuspendPartyroom`, `canChangeMemberTier`, `canManageAvatarResources`, `isSuperAdmin`, `isAdmin`) that today are thin `hasRole(...)` checks but can later swap to permission-table lookups without changing controllers. URL rules in `SecurityConfig` are split by sub-prefix order (most specific first): `/api/v1/admin/system/**` → SUPER_ADMIN, `/api/v1/admin/avatar/**` → SUPER_ADMIN, catch-all `/api/v1/admin/**` → ADMIN. CSRF Option A wires Spring's `CsrfFilter` with a `CookieCsrfTokenRepository` (cookie `XSRF-TOKEN`, Domain = admin cookie domain, SameSite=Strict, HttpOnly=false) scoped via `requireCsrfProtectionMatcher` to state-changing admin requests except `/api/v1/auth/admin/login` (chicken-and-egg). Origin/Referer guard from PR 4 stays — defense in depth.

**Tech Stack:** Java 21, Spring Boot 3.2 (Spring Security 6.2), Spring Security OAuth2 Resource Server, JUnit 5, Spring Security Test (`@WithMockUser`, `@WithSecurityContext`), MockMvc, Mockito, Testcontainers (already present).

**Spec sources (read once, applied throughout):**
- `docs/superpowers/specs/2026-04-19-admin-platform-security.md` §5.2.3 (URL split + `@PreAuthorize` rewrite), §5.2.4 (centralized `adminAuth` SpEL bean), §5.4.3 (CSRF Option A + B), §5.7 (security testing requirements)
- `docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md` §9.1 PR 5 row + §10.1 row #2 (CSRF) + §11.2.4 (sliding TTL — already in PR 4)
- `docs/superpowers/plans/2026-04-26-admin-platform-pr4.md` "Out of scope (deferred)" — PR 4 explicitly punted to PR 5: CSRF Option A, `SecurityConfig` decomposition, §5.7 권한 회귀 parameterized test
- `docs/superpowers/specs/2026-04-19-admin-platform-features.md` §6.I (Avatar admin uses `@adminAuth.canManageAvatarResources()`), §6.A* (admin endpoints use `hasRole('ADMIN')` — these are the catch-all)

**Decisions taken (spec leaves open):**

1. **`@EnableMethodSecurity` activates in this PR — global scope.** Spec §5.2.3 prescribes method-level `@PreAuthorize` as 중복 방어 ("URL rule 외 추가 방어"). Today, `@EnableMethodSecurity` is **only present in test slices** (`AbstractAdminWebMvcTest`, `AbstractUserWebMvcTest`, `AbstractPlaylistWebMvcTest`, etc.) — production code path silently ignores every `@PreAuthorize` annotation. Enabling it globally is the only way to deliver the spec intent. Consequence: latent SpEL bugs in non-admin controllers (see Decision 2) start firing.

2. **Latent `hasRole('ROLE_X')` double-prefix bugs are fixed in this PR.** Spring Security's `hasRole('ROLE_X')` prepends `ROLE_` and looks up authority `ROLE_ROLE_X` — a no-match, so every method using this pattern would 403 the moment `@EnableMethodSecurity` is enabled. 14 non-admin controllers carry this pattern (Decision detail in §"Verified codebase facts" below). Two correct alternatives: rewrite to `hasRole('X')` OR rewrite to `hasAuthority('ROLE_X')`. We pick **`hasRole('X')`** for non-admin controllers (matches Spring idiom; consistent with `SecurityConfig.hasRole("ADMIN")` which already drops the prefix) and **`@adminAuth.xxx()`** for admin controllers (centralized bean). Rationale: keeps non-admin controllers terse without churning their auth semantics; centralizes admin-side semantics where the spec wants centralization.

3. **`UserInfoQueryController.java:32`** uses `hasAnyRole('GUEST', 'MEMBER')` — already correct (no leading `ROLE_`). Leave as-is. (One of two non-admin annotations that already work; the other is `SecurityConfig.hasRole("ADMIN")`.)

4. **`adminAuth` bean methods are intent-named, not role-named.** Spec §5.2.4 explicitly contrasts intent (`canManageAdmins`) vs role (`hasRole('SUPER_ADMIN')`). We register: `isSuperAdmin()`, `isAdmin()`, `canManageAdmins()`, `canSuspendPartyroom()`, `canChangeMemberTier()`, `canManageMembers()`, `canHandleReports()`, `canManageAvatarResources()`. PR 5 wires only the methods needed by today's 11 admin annotations (`canSuspendPartyroom`, `canChangeMemberTier`) plus three forward-looking ones (`canManageAdmins` for PR 6, `canHandleReports` for PR 13, `canManageAvatarResources` for PR 11). Future PRs add methods as new endpoints arrive — never `hasRole(...)` directly in controller annotations.

5. **CSRF Option A: `CookieCsrfTokenRepository.withHttpOnlyFalse()` + `setCookieCustomizer`.** Spec §5.4.3 mentions either double-submit pattern or HMAC. We pick double-submit via Spring's stock `CookieCsrfTokenRepository` — minimal moving parts; widely deployed; XSRF cookie attributes mirror `AdminAccessToken` (Domain = admin domain, SameSite=Strict, Secure=true, HttpOnly=**false** so admin frontend JS can read it). HMAC pattern (custom token derivation from session) is over-engineering for MVP and lacks a request-correlated session anchor in our stateless JWT setup.

6. **CSRF scope = state-changing requests on admin paths.** Match: `(!safe-method) AND (path startsWith /api/v1/admin/ OR path is /api/v1/auth/admin/logout)`. Excluded: `POST /api/v1/auth/admin/login` (no session yet — chicken-and-egg). All non-admin paths bypass CSRF entirely (oauth callback, member/guest sign, partyroom/link, ws — these stay as today). Implementation via `requireCsrfProtectionMatcher` (matcher returns true → CSRF is enforced). The `CsrfFilter` itself runs on every request, which is what allows the cookie to be issued on safe GETs to admin paths.

7. **CSRF token issuance flow.** First admin request after login (any GET/HEAD on `/api/v1/admin/**`) triggers `CsrfFilter` → repository generates token → response sets `XSRF-TOKEN` cookie. Frontend reads cookie value (HttpOnly=false), echoes as `X-XSRF-TOKEN` header on subsequent state-changing requests. On token-mismatch, server returns **403** (Spring default `AccessDeniedException`). We do NOT add a dedicated `GET /admin/csrf-token` endpoint (spec §5.4.3 mentions one, but Spring's stock cookie issuance on every response is simpler and equivalent — frontend can hit any GET to obtain).

8. **CSRF tests: covered, but not exhaustive cross-path.** §5.7.3 asks for two scenarios — Origin spoof (already tested in PR 4) and CSRF token missing/wrong. We add three tests: (a) token-missing → 403, (b) token-wrong → 403, (c) token-correct → 200 (or whatever the endpoint returns). Cross-method matrix not needed — Spring's `CsrfFilter` handles the matrix once.

9. **`SecurityFilterChain` decomposition (multi-chain) — STAYS DEFERRED.** Roadmap row says "SecurityConfig 전체 개편" which one could read as splitting into two `SecurityFilterChain` beans (admin chain vs general chain). PR 4 shipped one path-aware `CookieBearerTokenResolver` and one filter chain. PR 5 stays single-chain — the work to truly split is structural (filter ordering, two `@Bean` chains with `securityMatcher`, dual `OAuth2ResourceServer` configs) and the path-aware resolver already delivers the runtime behavior the spec wants. Single-chain works correctly and tests prove it. Re-document this decision (it was made in PR 4 too) so future readers don't relitigate.

10. **`AdminEndpointSecurityTest` (PR 4) is expanded, not replaced.** Existing test covers `(anonymous|MEMBER|ADMIN) × POST /api/v1/admin/partyrooms`. PR 5 adds parameterized matrix `(anonymous|MEMBER|ADMIN|SUPER_ADMIN) × {generic admin endpoint, /system/** endpoint, /avatar/** endpoint}` plus method-level enforcement assertions for the migrated controllers. We keep the original three tests as parametrized cases (don't delete them).

11. **Cookie isolation test — covered by existing `CookieBearerTokenResolverPathAwareTest` (PR 4).** §5.7.2 cookie isolation is satisfied by PR 4's path-aware resolver, which has unit tests. PR 5 adds **one** integration-level slice test asserting end-to-end behavior (full SecurityFilterChain): real cookie shape, real `JwtService`-minted token, real role propagation. Avoid duplicating the unit-level resolver tests.

12. **`AdminTokenRenewalFilter`'s internal `hasRole(auth, "ROLE_ADMIN")` (`common/.../jwt/AdminTokenRenewalFilter.java:55, :91`) is NOT touched.** That helper inspects `Authentication.getAuthorities()` for the literal string `ROLE_ADMIN` — correct because the granted authority IS `ROLE_ADMIN` (from `CustomJwtAuthenticationConverter`). Only Spring SpEL `hasRole('X')` / `hasAnyRole('X')` strings have the double-prefix gotcha. Filter is unaffected.

**Lessons applied from PR 0 / 1 / 2 / 3 / 4:**
- Mechanical refactors (annotation swaps, SpEL string fixes) → `sonnet`. Architecture-touching changes (SecurityConfig, CSRF wiring, chain composition) → `opus`.
- Multi-module Gradle builds: changes in `common/` reach all modules; changes in `app/` are localized. Method-security activation in `common/.../SecurityConfig` is the single switch that flips behavior across all modules.
- `git status` clean before each task. Commits are tight and revertible.
- Subagent-driven workflow: each task is one subagent dispatch with the task's full self-contained context.
- Tests-first when feasible. For configuration changes (SecurityConfig edits), write the parameterized regression test first so the failure proves the gap, then fix.
- CI catches compile errors across modules — leverage that. Renaming a bean or removing a class produces immediate failures everywhere.
- "Atomic commit groupings" (PR 4 lesson) — when a behavior switch needs to land alongside its dependencies to keep the tree green, group them. We use one such group below for method-security activation.

**Branching:** Continue on `feature/admin-auth-iam-schema`. PR 4 HEAD: `d1452dbe` (`fix(exception): map standard Spring web exceptions to proper HTTP codes`). PR 5 builds on top.

**Out of scope (deferred):**
- `/api/v1/admin/system/**` / `/avatar/**` actual controller implementations — PR 6 (admin CRUD) and PR 11 (Avatar admin).
- HMAC-based CSRF (per-session derived tokens) — out-of-scope; not needed when `CookieCsrfTokenRepository` already binds token to cookie pair.
- Splitting `SecurityFilterChain` into multiple beans (admin chain vs general chain) — see Decision 9. Re-evaluate when admin endpoints need filter ordering different from general endpoints.
- Permission-table backed RBAC (n:n `admin_permission`) — §11.1.3, future. `adminAuth` bean is the seam where it'll plug in.
- `must_change_password_at_next_login` flow — PR 6.
- Login audit persistence to `user_activity_log` — PR 12.
- Frontend (admin UI) wiring of `X-XSRF-TOKEN` — pfplay-admin work, separate repo. PR 5 ships server-side; frontend integration is documented in coordination notes (Task 13).
- `RoleHierarchy` (Spring Security feature where `ROLE_SUPER_ADMIN > ROLE_ADMIN`) — explicitly NOT introduced; we issue both `ROLE_ADMIN` and `ROLE_SUPER_ADMIN` authorities for super admins (already the case post-PR 4) so role checks are direct equality.

---

## Atomic commit groupings

Several tasks below are intentionally broken out for readability but **must be committed together** to keep the multi-module Gradle tree compiling AND to avoid intermediate states where method security is enabled but admin annotations still reference the un-rewired `FM` authority.

| Group | Tasks | Reason |
|---|---|---|
| **G1: Method-security activation cutover** | Tasks 6 + 7 + 8 + 9 | Task 6 enables `@EnableMethodSecurity` (annotation flip). Task 7 fixes the 14 latent `hasRole('ROLE_X')` non-admin annotations that would 403 the moment Task 6 lands. Tasks 8 + 9 swap admin annotations from `hasAuthority('FM')` (dead today; would silently allow MEMBER if accidentally promoted) to `@adminAuth.xxx()`. Splitting these across commits leaves `main` in a broken or insecure state. **Single commit.** |

Within G1:
- Per-task step lists are still a checklist (write code, run tests, verify behavior).
- **Skip the `git commit` step at the end of each task in the group.**
- Run a single combined commit at the end of Task 9 with the message specified in Task 9's final step.

Tasks not listed in a group commit individually (default).

---

## Hard precondition (verify BEFORE Task 1)

PR 5 builds on PR 4 (commit `d1452dbe`). It activates method security globally — a behavior change with cross-module reach. Before Task 1:

- [ ] **Step 1: Confirm PR 4 is on HEAD**

```bash
git log --oneline -1
```

Expected: `d1452dbe fix(exception): map standard Spring web exceptions to proper HTTP codes`. (If `feature/admin-auth-iam-schema` advanced past PR 4 with unrelated commits, that's fine — but the commits referenced in this plan must still be present.)

- [ ] **Step 2: Confirm working tree is clean**

```bash
git status -s
```

Expected: empty output. Any dirty file → STOP and ask.

- [ ] **Step 3: Confirm `@EnableMethodSecurity` is absent from production code**

```bash
grep -rln "EnableMethodSecurity" common/src/main app/src/main user/src/main playlist/src/main party/src/main 2>/dev/null
```

Expected: empty output (no matches in any `src/main`). If there's already a match → the plan's premise is wrong — STOP and reconcile.

- [ ] **Step 4: Confirm `@Component("adminAuth")` does NOT yet exist**

```bash
grep -rn "AdminAuthorizationSpEL\|@Component(\"adminAuth\")" common/src/main app/src/main 2>/dev/null
```

Expected: empty output. If anything matches, the plan's premise is wrong — STOP.

- [ ] **Step 5: Confirm 11 `hasAuthority('FM')` instances live in admin controllers**

```bash
grep -rn "hasAuthority('FM')" app/src/main 2>/dev/null
```

Expected: exactly **11 matches**, distributed:
- `AdminUserController.java`: 4 (lines ~52, ~80, ~101, ~132)
- `AdminPartyroomController.java`: 2 (lines ~53, ~91)
- `AdminDemoController.java`: 5 (lines ~74, ~103, ~144, ~182, ~204)

Any other count → STOP and reconcile (the migration tasks are sized to exactly these endpoints).

- [ ] **Step 6: Confirm latent `hasRole('ROLE_X')` annotations**

```bash
grep -rn "hasRole('ROLE_\|hasAnyRole('ROLE_" common/src/main app/src/main user/src/main playlist/src/main party/src/main 2>/dev/null
```

Expected: **14 matches** across non-admin controllers (full list in §"Verified codebase facts"). All wrong-prefix; all dead today. Task 7 rewrites them.

- [ ] **Step 7: Confirm test slice base classes already enable method security (no work needed there)**

```bash
grep -rln "@EnableMethodSecurity" app/src/test user/src/test playlist/src/test 2>/dev/null
```

Expected: at least 5 matches in `Abstract*WebMvcTest.java` files. These already test annotations as if production had method security — once Task 6 enables it in production, behavior matches tests. Good.

- [ ] **Step 8: Build current HEAD as a baseline**

```bash
./gradlew clean build
```

Expected: BUILD SUCCESSFUL. If any test fails on PR 4 HEAD, stop and reconcile before starting PR 5 — we do not introduce changes on a broken baseline.

---

## Verified codebase facts (read once, applied throughout)

- **`SecurityConfig.java` (single chain):** `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java:40-73`. Single `@Bean SecurityFilterChain filterChain(HttpSecurity http)`. `csrf` disabled (line 44). URL rules at lines 52-63 — `/api/v1/admin/**` → `hasRole("ADMIN")` at line 59. Admin filters wired at lines 70-71 (`adminOriginGuardFilter` before `UsernamePasswordAuthenticationFilter`, `adminTokenRenewalFilter` after `BearerTokenAuthenticationFilter`). PR 5 will: split line 59 into three; replace line 44 CSRF disable with a configured CSRF; add `@EnableMethodSecurity` to the class.

- **`CookieBearerTokenResolver.java` (path-aware, single resolver):** `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolver.java:33-40`. `pickCookieName` matches `/api/v1/admin/**` → admin cookie else shared. PR 5 does NOT touch it.

- **`AccessLevel` enum:** `common/src/main/java/com/pfplaybackend/api/common/config/security/enums/AccessLevel.java`. Values: `ROLE_SUPER_ADMIN, ROLE_ADMIN, ROLE_MEMBER, ROLE_GUEST`. `ROLE_SUPER_ADMIN` was added in PR 4 — confirmed.

- **`CustomJwtAuthenticationConverter`:** `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CustomJwtAuthenticationConverter.java:31-37`. Reads `access_level` claim (List<String>), maps each to `SimpleGrantedAuthority(level)`. So a SUPER_ADMIN principal carries authorities `[ROLE_ADMIN, ROLE_SUPER_ADMIN]`, an ADMIN carries `[ROLE_ADMIN]`, a Member carries `[ROLE_MEMBER]`. **Spring's `hasRole('ADMIN')`** prepends `ROLE_` and matches authority `ROLE_ADMIN` — correct. **`hasRole('ROLE_ADMIN')`** prepends `ROLE_` to look up `ROLE_ROLE_ADMIN` — wrong. **`hasAuthority('ROLE_ADMIN')`** matches authority directly — also correct.

- **`AdminTokenRenewalFilter.java:55, :91`:** Uses a private `hasRole(Authentication, String)` helper that does direct authority-name equality (NOT Spring SpEL). Correct as-is. PR 5 does NOT touch it.

- **`AdminOriginGuardFilter.java`:** `common/src/main/java/com/pfplaybackend/api/common/config/security/web/AdminOriginGuardFilter.java`. Scope: `/api/v1/admin/**` + `/api/v1/auth/admin/**`. Bypasses safe methods. Stays in PR 5 — defense in depth alongside CSRF.

- **`application.yml` cookie config (lines 115-130):** `app.jwt.cookie.admin.{name, domain, path, secure, same-site, max-age-seconds, renewal-threshold-seconds}` and `app.jwt.cookie.shared.{name, domain, path, secure, same-site, max-age-seconds}`. Admin cookie domain pulls from `${ADMIN_COOKIE_DOMAIN}`. We will reuse `ADMIN_COOKIE_DOMAIN` for the CSRF cookie domain.

- **`application.yml` admin-origin-guard (lines 142-147):** Allowlist `https://localhost:3000, http://localhost:3000, https://admin.pfplay.xyz`. Stays.

- **The 11 `@PreAuthorize("hasAuthority('FM')")` admin annotations** (all under `app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/`):
  - `AdminUserController.java:52` `createVirtualMember` (POST `/api/v1/admin/users/virtual`) → migrate to `@adminAuth.canChangeMemberTier()`
  - `AdminUserController.java:80` `getVirtualMember` (GET `/api/v1/admin/users/virtual/{userId}`) → `@adminAuth.canChangeMemberTier()`
  - `AdminUserController.java:101` `updateVirtualMemberAvatar` (PUT `/api/v1/admin/users/virtual/{userId}/avatar`) → `@adminAuth.canChangeMemberTier()`
  - `AdminUserController.java:132` `deleteVirtualMember` (DELETE `/api/v1/admin/users/virtual/{userId}`) → `@adminAuth.canChangeMemberTier()`
  - `AdminPartyroomController.java:53` `createPartyroom` (POST `/api/v1/admin/partyrooms`) → `@adminAuth.canSuspendPartyroom()` (semantically: any admin can manage demo/preview partyrooms)
  - `AdminPartyroomController.java:91` `createBulkPreviewEnvironment` (POST `/api/v1/admin/partyrooms/bulk-preview`) → `@adminAuth.canSuspendPartyroom()`
  - `AdminDemoController.java:74` `getPartyrooms` (GET `/api/v1/admin/demo/partyrooms`) → `@adminAuth.isAdmin()` (read-only listing)
  - `AdminDemoController.java:103` `initializeDemoEnvironment` (POST `/api/v1/admin/demo/init`) → `@adminAuth.isAdmin()`
  - `AdminDemoController.java:144` `simulateReactions` (POST `/api/v1/admin/demo/partyrooms/{partyroomId}/reactions`) → `@adminAuth.isAdmin()`
  - `AdminDemoController.java:182` `startChatSimulation` (POST `/api/v1/admin/demo/partyrooms/{partyroomId}/chat`) → `@adminAuth.isAdmin()`
  - `AdminDemoController.java:204` `stopChatSimulation` (DELETE `/api/v1/admin/demo/partyrooms/{partyroomId}/chat`) → `@adminAuth.isAdmin()`

- **The 14 latent `hasRole('ROLE_X')` / `hasAnyRole('ROLE_X', ...)` annotations** (all non-admin; all double-prefix bugs; rewrite to `hasRole('X')` / `hasAnyRole('X', ...)`):
  - `playlist/.../TrackQueryController.java:29` — `hasRole('ROLE_MEMBER')` → `hasRole('MEMBER')`
  - `playlist/.../TrackCommandController.java:40` — `hasRole('ROLE_MEMBER')` → `hasRole('MEMBER')`
  - `playlist/.../TrackCommandController.java:68` — `hasRole('ROLE_MEMBER')` → `hasRole('MEMBER')`
  - `playlist/.../search/MusicSearchController.java:31` — `hasRole('ROLE_MEMBER')` → `hasRole('MEMBER')`
  - `playlist/.../PlaylistCommandController.java:36` — `hasRole('ROLE_MEMBER')` → `hasRole('MEMBER')`
  - `playlist/.../PlaylistCommandController.java:48` — `hasRole('ROLE_MEMBER')` → `hasRole('MEMBER')`
  - `playlist/.../PlaylistCommandController.java:59` — `hasRole('ROLE_MEMBER')` → `hasRole('MEMBER')`
  - `playlist/.../PlaylistQueryController.java:27` — `hasRole('ROLE_MEMBER')` → `hasRole('MEMBER')`
  - `user/.../UserWalletCommandController.java:42` — `hasRole('ROLE_MEMBER')` → `hasRole('MEMBER')`
  - `user/.../UserBioCommandController.java:31` — `hasRole('ROLE_MEMBER')` → `hasRole('MEMBER')`
  - `user/.../UserAvatarCommandController.java:35` — `hasRole('ROLE_MEMBER')` → `hasRole('MEMBER')`
  - `user/.../UserAvatarQueryController.java:30` — `hasAnyRole('ROLE_GUEST', 'ROLE_MEMBER')` → `hasAnyRole('GUEST', 'MEMBER')`
  - `user/.../UserAvatarQueryController.java:39` — `hasAnyRole('ROLE_GUEST', 'ROLE_MEMBER')` → `hasAnyRole('GUEST', 'MEMBER')`
  - `user/.../UserProfileQueryController.java:28` — `hasAnyRole('ROLE_GUEST', 'ROLE_MEMBER')` → `hasAnyRole('GUEST', 'MEMBER')`
  - `app/.../party/.../CrewQueryController.java:32` — `hasAnyRole('ROLE_GUEST', 'ROLE_MEMBER')` → `hasAnyRole('GUEST', 'MEMBER')`
  - `app/.../party/.../DjCommandController.java:41` — `hasAnyRole('ROLE_MEMBER')` → `hasAnyRole('MEMBER')` (or simpler `hasRole('MEMBER')`)

  (Counted 16, not 14 — the report combined a few. Use the grep in Step 6 of preconditions to get the exact list at execution time. Do not skip any.)

- **Already-correct annotation:** `user/.../UserInfoQueryController.java:32` — `hasAnyRole('GUEST', 'MEMBER')`. Leave as-is.

- **Test slice base classes** — they already register `@EnableMethodSecurity`. Production activation in Task 6 makes runtime behavior match these tests. Slice tests need no modification:
  - `app/src/test/.../admin/.../AbstractAdminWebMvcTest.java`
  - `app/src/test/.../party/.../AbstractPartyCommandWebMvcTest.java`
  - `app/src/test/.../party/.../AbstractPartyQueryWebMvcTest.java`
  - `user/src/test/.../user/.../AbstractUserWebMvcTest.java`
  - `playlist/src/test/.../playlist/.../AbstractPlaylistWebMvcTest.java`

- **Existing parameterized-style admin security test (PR 4):** `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminEndpointSecurityTest.java`. Three tests (anonymous, MEMBER, ADMIN) against POST `/api/v1/admin/partyrooms`. Task 11 expands this into a real `@ParameterizedTest` matrix.

- **No `/api/v1/admin/system/**` controllers exist today.** PR 5 wires the URL rule but cannot integration-test it via real endpoints. Use a deliberately-non-existent path (`/api/v1/admin/system/__test_probe__`) for the security regression — Spring's filter chain runs before request mapping, so 401/403 fires regardless of whether a controller exists. (Same trick PR 4 used implicitly.)

- **No `/api/v1/admin/avatar/**` controllers exist today.** Same pattern: probe path `/api/v1/admin/avatar/__test_probe__`.

- **`pfplay-admin` (frontend repo)** — separate repo, not in this codebase. PR 5 ships server-side CSRF + `XSRF-TOKEN` cookie. Frontend wiring (axios interceptor that reads cookie + sets `X-XSRF-TOKEN` header) lives in pfplay-admin and is out of scope. Task 13 documents the contract for the frontend team.

---

## File Structure

### Files Created

**Common module — adminAuth bean + CSRF wiring + CSRF properties:**
- `common/src/main/java/com/pfplaybackend/api/common/config/security/authorization/AdminAuthorizationSpEL.java` — `@Component("adminAuth")` with intent-named methods.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfTokenRepositoryFactory.java` — builds the `CookieCsrfTokenRepository` with admin-cookie attributes.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfRequestMatcher.java` — `RequestMatcher` for "state-changing admin requests except `/api/v1/auth/admin/login`".
- `common/src/main/java/com/pfplaybackend/api/common/config/security/csrf/properties/AdminCsrfProperties.java` — `app.security.admin-csrf.{enabled, cookie-name, header-name, cookie-domain, same-site, secure}`.

**Common module — tests:**
- `common/src/test/java/com/pfplaybackend/api/common/config/security/authorization/AdminAuthorizationSpELTest.java`
- `common/src/test/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfRequestMatcherTest.java`
- `common/src/test/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfTokenRepositoryFactoryTest.java`

**App module — integration tests for the SecurityConfig overhaul:**
- `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminAuthorizationMatrixTest.java` — parameterized §5.7.1 권한 회귀 ((anonymous|MEMBER|ADMIN|SUPER_ADMIN) × {generic, /system, /avatar} → expected status).
- `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminCookieIsolationIntegrationTest.java` — §5.7.2 쿠키 격리 end-to-end (real `JwtService`, real cookies on request).
- `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminCsrfIntegrationTest.java` — §5.7.3 CSRF token presence/absence/mismatch.

**Documentation:**
- `docs/adr/012-admin-csrf-token.md` — ADR explaining choice of `CookieCsrfTokenRepository` (Option A), scope, and frontend contract for pfplay-admin team.

### Files Modified

- `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java`:
  - Add class-level `@EnableMethodSecurity`.
  - Replace `csrf(AbstractHttpConfigurer::disable)` with configured CSRF (matcher-scoped to admin paths).
  - Split `requestMatchers("/api/v1/admin/**").hasRole("ADMIN")` into three rules in spec order.
  - Inject `AdminCsrfTokenRepositoryFactory` + `AdminCsrfRequestMatcher`.

- `app/src/main/resources/application.yml` — add `app.security.admin-csrf.*` block under `app.security` (sibling of `admin-origin-guard`); add `enabled: true` and cookie attributes for each profile (default + dev + stg + prod + test profile sets `secure: false`).

- **Admin controllers (annotation rewrites — Task 8 and 9 — committed in G1):**
  - `app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/AdminUserController.java` (4 methods)
  - `app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/AdminPartyroomController.java` (2 methods)
  - `app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/AdminDemoController.java` (5 methods)

- **Non-admin controllers (latent SpEL bug fixes — Task 7 — committed in G1):**
  - `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/TrackQueryController.java`
  - `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/TrackCommandController.java`
  - `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/search/MusicSearchController.java`
  - `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/PlaylistCommandController.java`
  - `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/PlaylistQueryController.java`
  - `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserWalletCommandController.java`
  - `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserBioCommandController.java`
  - `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserAvatarCommandController.java`
  - `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserAvatarQueryController.java`
  - `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserProfileQueryController.java`
  - `app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/CrewQueryController.java`
  - `app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/DjCommandController.java`

- `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminEndpointSecurityTest.java` — Task 11 expands the existing test into the parameterized matrix; PR 4's three tests become the `MEMBER` / `ADMIN` rows of the matrix. Don't delete; refactor in place.

### Files Removed

(none — PR 5 is purely additive in terms of files; the only deletion candidate is the inline `csrf.disable` line, which is replaced not removed.)

### Files Verified-but-Not-Modified

- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolver.java` — path-aware single resolver works correctly (Decision 9).
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/AdminTokenRenewalFilter.java` — direct authority-name equality, not affected by SpEL rules.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/web/AdminOriginGuardFilter.java` — defense-in-depth alongside CSRF.
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/CustomJwtAuthenticationConverter.java` — claim shape is correct for `hasRole('ADMIN')`/`hasRole('SUPER_ADMIN')`/`hasRole('MEMBER')`.
- `app/src/test/java/com/pfplaybackend/api/common/config/security/jwt/CookieBearerTokenResolverPathAwareTest.java` (PR 4) — covers cookie isolation at unit level.
- All test slice base classes (`AbstractAdminWebMvcTest` etc.) — already enable `@EnableMethodSecurity` in their slice; no edits.
- `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserInfoQueryController.java:32` — already uses correct `hasAnyRole('GUEST', 'MEMBER')`.

---

## Chunk 1: Adminauth bean + CSRF infrastructure (Tasks 1–5)

### Task 1: Create `AdminAuthorizationSpEL` component (the `@adminAuth` bean)

**Files:**
- Create: `common/src/main/java/com/pfplaybackend/api/common/config/security/authorization/AdminAuthorizationSpEL.java`
- Test: `common/src/test/java/com/pfplaybackend/api/common/config/security/authorization/AdminAuthorizationSpELTest.java`

**Why this is first:** Every subsequent admin-controller annotation references `@adminAuth.xxx()`. If the bean isn't registered, controllers fail to start. Plus, this is mechanical and self-contained — easy first task.

- [ ] **Step 1: Write the failing test**

```java
// common/src/test/java/com/pfplaybackend/api/common/config/security/authorization/AdminAuthorizationSpELTest.java
package com.pfplaybackend.api.common.config.security.authorization;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminAuthorizationSpEL}, the @adminAuth SpEL bean.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.2.4
 *
 * <p>The bean is intent-named (canManageAdmins, canSuspendPartyroom, ...). Today
 * each method is a thin role check; future RBAC re-evaluation will swap to
 * permission-table lookups without changing controllers.
 */
class AdminAuthorizationSpELTest {

    private final AdminAuthorizationSpEL adminAuth = new AdminAuthorizationSpEL();

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWith(String... authorities) {
        var auths = java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        var token = new UsernamePasswordAuthenticationToken("user", "n/a", auths);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(token);
        SecurityContextHolder.setContext(ctx);
    }

    @Test
    void isSuperAdmin_returnsTrue_whenPrincipalHasRoleSuperAdmin() {
        authenticateWith("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        assertThat(adminAuth.isSuperAdmin()).isTrue();
    }

    @Test
    void isSuperAdmin_returnsFalse_forPlainAdmin() {
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.isSuperAdmin()).isFalse();
    }

    @Test
    void isAdmin_returnsTrue_forAdmin() {
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.isAdmin()).isTrue();
    }

    @Test
    void isAdmin_returnsTrue_forSuperAdmin() {
        // Super admin carries both ROLE_ADMIN and ROLE_SUPER_ADMIN authorities (post-PR 4 issuance).
        authenticateWith("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        assertThat(adminAuth.isAdmin()).isTrue();
    }

    @Test
    void isAdmin_returnsFalse_forMember() {
        authenticateWith("ROLE_MEMBER");
        assertThat(adminAuth.isAdmin()).isFalse();
    }

    @Test
    void canManageAdmins_requiresSuperAdmin() {
        authenticateWith("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        assertThat(adminAuth.canManageAdmins()).isTrue();
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.canManageAdmins()).isFalse();
    }

    @Test
    void canSuspendPartyroom_requiresAdmin() {
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.canSuspendPartyroom()).isTrue();
        authenticateWith("ROLE_MEMBER");
        assertThat(adminAuth.canSuspendPartyroom()).isFalse();
    }

    @Test
    void canChangeMemberTier_requiresAdmin() {
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.canChangeMemberTier()).isTrue();
        authenticateWith("ROLE_MEMBER");
        assertThat(adminAuth.canChangeMemberTier()).isFalse();
    }

    @Test
    void canManageMembers_requiresAdmin() {
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.canManageMembers()).isTrue();
        authenticateWith("ROLE_MEMBER");
        assertThat(adminAuth.canManageMembers()).isFalse();
    }

    @Test
    void canHandleReports_requiresAdmin() {
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.canHandleReports()).isTrue();
        authenticateWith("ROLE_MEMBER");
        assertThat(adminAuth.canHandleReports()).isFalse();
    }

    @Test
    void canManageAvatarResources_requiresSuperAdmin() {
        authenticateWith("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        assertThat(adminAuth.canManageAvatarResources()).isTrue();
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.canManageAvatarResources()).isFalse();
    }

    @Test
    void allMethods_returnFalse_whenNoAuthentication() {
        SecurityContextHolder.clearContext();
        assertThat(adminAuth.isAdmin()).isFalse();
        assertThat(adminAuth.isSuperAdmin()).isFalse();
        assertThat(adminAuth.canManageAdmins()).isFalse();
        assertThat(adminAuth.canSuspendPartyroom()).isFalse();
        assertThat(adminAuth.canChangeMemberTier()).isFalse();
        assertThat(adminAuth.canManageMembers()).isFalse();
        assertThat(adminAuth.canHandleReports()).isFalse();
        assertThat(adminAuth.canManageAvatarResources()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails (compile error — class doesn't exist)**

```bash
./gradlew :common:test --tests "*AdminAuthorizationSpELTest*"
```

Expected: compile failure — `AdminAuthorizationSpEL` symbol unknown.

- [ ] **Step 3: Write the minimal implementation**

```java
// common/src/main/java/com/pfplaybackend/api/common/config/security/authorization/AdminAuthorizationSpEL.java
package com.pfplaybackend.api.common.config.security.authorization;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Centralized authorization SpEL bean — exposed under the bean name {@code adminAuth}
 * so controllers can declare {@code @PreAuthorize("@adminAuth.canSuspendPartyroom()")}.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-04-19-admin-platform-security.md} §5.2.4.
 *
 * <p>Each method is intent-named (vs. role-named) so a future RBAC migration to a
 * permission table can change the bodies without touching controllers. Today, each
 * method is a thin authority lookup against the {@link SecurityContextHolder}.
 */
@Component("adminAuth")
public class AdminAuthorizationSpEL {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    public boolean isAdmin() {
        return hasAuthority(ROLE_ADMIN);
    }

    public boolean isSuperAdmin() {
        return hasAuthority(ROLE_SUPER_ADMIN);
    }

    public boolean canManageAdmins() {
        // Wired by PR 6 (admin CRUD endpoints).
        return isSuperAdmin();
    }

    public boolean canSuspendPartyroom() {
        // PR 8 (partyroom admin actions). PR 5 also points 2 partyroom-flavored
        // admin endpoints here.
        return isAdmin();
    }

    public boolean canChangeMemberTier() {
        // PR 12 (member admin). PR 5 also points the 4 virtual-member endpoints here.
        return isAdmin();
    }

    public boolean canManageMembers() {
        // PR 12 (member admin listings, etc.).
        return isAdmin();
    }

    public boolean canHandleReports() {
        // PR 13 (partyroom_report admin queue).
        return isAdmin();
    }

    public boolean canManageAvatarResources() {
        // PR 11 (Avatar admin CRUD). Spec §5.2.4 explicitly: SUPER_ADMIN gate.
        return isSuperAdmin();
    }

    private boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (authority.equals(ga.getAuthority())) return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :common:test --tests "*AdminAuthorizationSpELTest*"
```

Expected: all 11 tests pass.

- [ ] **Step 5: Smoke — confirm no other module broke**

```bash
./gradlew :common:compileJava :common:compileTestJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/authorization/AdminAuthorizationSpEL.java \
        common/src/test/java/com/pfplaybackend/api/common/config/security/authorization/AdminAuthorizationSpELTest.java
git commit -m "feat(security): @adminAuth SpEL bean for centralized admin authorization (PR 5)"
```

---

### Task 2: Create `AdminCsrfProperties`

**Files:**
- Create: `common/src/main/java/com/pfplaybackend/api/common/config/security/csrf/properties/AdminCsrfProperties.java`

**Why now:** CSRF wiring (Tasks 3, 4, 10) depends on these properties. Property class first → properties bind cleanly when wiring is added.

- [ ] **Step 1: Write the implementation (no test needed — it's a `@Data` bag)**

```java
// common/src/main/java/com/pfplaybackend/api/common/config/security/csrf/properties/AdminCsrfProperties.java
package com.pfplaybackend.api.common.config.security.csrf.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Properties for admin CSRF token defense (Option A).
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-04-19-admin-platform-security.md} §5.4.3.
 *
 * <p>Cookie attributes mirror {@code AdminAccessToken} (admin subdomain, SameSite=Strict,
 * Secure=true) — the only exception is {@code httpOnly=false} so the admin frontend's
 * JS can read the cookie value and echo it as the {@code X-XSRF-TOKEN} header.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.security.admin-csrf")
public class AdminCsrfProperties {

    /** Master switch — disable in tests that need to bypass CSRF entirely. */
    private boolean enabled = true;

    /** Cookie name read by the admin frontend. */
    private String cookieName = "XSRF-TOKEN";

    /** Header name the frontend echoes back. */
    private String headerName = "X-XSRF-TOKEN";

    /** Cookie domain — must match {@code app.jwt.cookie.admin.domain} for cookie scoping. */
    private String cookieDomain;

    /** SameSite attribute — {@code Strict} aligns with AdminAccessToken. */
    private String sameSite = "Strict";

    /** Secure attribute — true everywhere except the {@code test} profile. */
    private boolean secure = true;
}
```

- [ ] **Step 2: Add property block to `application.yml` (default + dev/stg/prod + test profile)**

Open `app/src/main/resources/application.yml` and add `admin-csrf` under each `app.security` section:

```yaml
# Default profile (top of file, ~line 141 area, sibling of admin-origin-guard)
  security:
    admin-origin-guard:
      enabled: true
      allowed:
        - https://localhost:3000
        - http://localhost:3000
        - https://admin.pfplay.xyz
    admin-csrf:
      enabled: true
      cookie-name: XSRF-TOKEN
      header-name: X-XSRF-TOKEN
      cookie-domain: ${ADMIN_COOKIE_DOMAIN:localhost}
      same-site: Strict
      secure: true
```

For each environment-specific profile (dev/stg/prod) where `app.security` already exists, ensure the `admin-csrf` block is present (or rely on default — confirm by reading the profile). For the `test` profile, set `secure: false` (Testcontainers don't terminate TLS):

```yaml
# test profile (~line 333 area, alongside cookie.admin.secure: false)
  security:
    admin-csrf:
      enabled: true
      cookie-name: XSRF-TOKEN
      header-name: X-XSRF-TOKEN
      cookie-domain: localhost
      same-site: Lax
      secure: false
```

- [ ] **Step 3: Verify property binding compiles**

```bash
./gradlew :common:compileJava
```

Expected: BUILD SUCCESSFUL. (Properties don't get validated until app boot — test that in Task 10.)

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/csrf/properties/AdminCsrfProperties.java \
        app/src/main/resources/application.yml
git commit -m "feat(security): admin CSRF properties scaffold (PR 5)"
```

---

### Task 3: Create `AdminCsrfRequestMatcher`

**Files:**
- Create: `common/src/main/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfRequestMatcher.java`
- Test: `common/src/test/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfRequestMatcherTest.java`

**Behavior:** matches when the request is state-changing (NOT GET/HEAD/OPTIONS/TRACE) AND path is `/api/v1/admin/**` OR exactly `/api/v1/auth/admin/logout`. Excludes `/api/v1/auth/admin/login` (no session yet).

- [ ] **Step 1: Write the failing test**

```java
// common/src/test/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfRequestMatcherTest.java
package com.pfplaybackend.api.common.config.security.csrf;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCsrfRequestMatcherTest {

    private final AdminCsrfRequestMatcher matcher = new AdminCsrfRequestMatcher();

    @Test
    void matches_postToAdminPath() {
        assertThat(matcher.matches(req("POST", "/api/v1/admin/partyrooms"))).isTrue();
    }

    @Test
    void matches_putToAdminSubpath() {
        assertThat(matcher.matches(req("PUT", "/api/v1/admin/users/virtual/42/avatar"))).isTrue();
    }

    @Test
    void matches_deleteToAdminPath() {
        assertThat(matcher.matches(req("DELETE", "/api/v1/admin/demo/partyrooms/7/chat"))).isTrue();
    }

    @Test
    void matches_postToAdminLogout() {
        assertThat(matcher.matches(req("POST", "/api/v1/auth/admin/logout"))).isTrue();
    }

    @Test
    void doesNotMatch_postToAdminLogin() {
        // Login has no session yet — chicken-and-egg.
        assertThat(matcher.matches(req("POST", "/api/v1/auth/admin/login"))).isFalse();
    }

    @Test
    void doesNotMatch_safeMethodsOnAdminPath() {
        assertThat(matcher.matches(req("GET", "/api/v1/admin/partyrooms"))).isFalse();
        assertThat(matcher.matches(req("HEAD", "/api/v1/admin/partyrooms"))).isFalse();
        assertThat(matcher.matches(req("OPTIONS", "/api/v1/admin/partyrooms"))).isFalse();
        assertThat(matcher.matches(req("TRACE", "/api/v1/admin/partyrooms"))).isFalse();
    }

    @Test
    void doesNotMatch_postToNonAdminPath() {
        assertThat(matcher.matches(req("POST", "/api/v1/auth/oauth/callback"))).isFalse();
        assertThat(matcher.matches(req("POST", "/api/v1/users/members/sign/up"))).isFalse();
        assertThat(matcher.matches(req("POST", "/api/v1/partyrooms"))).isFalse();
    }

    @Test
    void doesNotMatch_pathOutsideApiPrefix() {
        assertThat(matcher.matches(req("POST", "/actuator/health"))).isFalse();
        assertThat(matcher.matches(req("POST", "/ws/handshake"))).isFalse();
    }

    private static HttpServletRequest req(String method, String path) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setServletPath(path);
        return r;
    }
}
```

- [ ] **Step 2: Run test to verify failure**

```bash
./gradlew :common:test --tests "*AdminCsrfRequestMatcherTest*"
```

Expected: compile failure — `AdminCsrfRequestMatcher` symbol unknown.

- [ ] **Step 3: Write the minimal implementation**

```java
// common/src/main/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfRequestMatcher.java
package com.pfplaybackend.api.common.config.security.csrf;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.AntPathMatcher;

/**
 * Matches state-changing admin requests that require CSRF token validation.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-04-19-admin-platform-security.md} §5.4.3
 * (CSRF Option A) + §5.1.4 (logout requires CSRF).
 *
 * <p>Match rule:
 * <pre>
 *   (method NOT IN {GET, HEAD, OPTIONS, TRACE})
 *   AND (path startsWith "/api/v1/admin/" OR path == "/api/v1/auth/admin/logout")
 * </pre>
 *
 * <p>Login (`POST /api/v1/auth/admin/login`) is excluded — there is no session
 * to bind a CSRF token to before login completes.
 */
public class AdminCsrfRequestMatcher implements RequestMatcher {

    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin/";
    private static final String ADMIN_LOGOUT_PATH = "/api/v1/auth/admin/logout";

    private final AntPathMatcher antMatcher = new AntPathMatcher();

    @Override
    public boolean matches(HttpServletRequest request) {
        if (isSafeMethod(request.getMethod())) return false;
        String path = request.getServletPath();
        if (path == null) path = request.getRequestURI();
        if (path == null) return false;
        if (path.startsWith(ADMIN_PATH_PREFIX)) return true;
        if (ADMIN_LOGOUT_PATH.equals(path)) return true;
        return false;
    }

    private boolean isSafeMethod(String method) {
        return HttpMethod.GET.matches(method)
                || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method)
                || HttpMethod.TRACE.matches(method);
    }
}
```

- [ ] **Step 4: Run test to verify pass**

```bash
./gradlew :common:test --tests "*AdminCsrfRequestMatcherTest*"
```

Expected: all 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfRequestMatcher.java \
        common/src/test/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfRequestMatcherTest.java
git commit -m "feat(security): admin CSRF request matcher (PR 5)"
```

---

### Task 4: Create `AdminCsrfTokenRepositoryFactory`

**Files:**
- Create: `common/src/main/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfTokenRepositoryFactory.java`
- Test: `common/src/test/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfTokenRepositoryFactoryTest.java`

**Behavior:** Factory builds a `CookieCsrfTokenRepository` whose cookie attributes mirror `AdminAccessToken` except `HttpOnly=false` (frontend must read the value). Uses `setCookieCustomizer` (Spring Security 6.1+) to set Domain / SameSite / Secure / Path.

- [ ] **Step 1: Write the failing test**

```java
// common/src/test/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfTokenRepositoryFactoryTest.java
package com.pfplaybackend.api.common.config.security.csrf;

import com.pfplaybackend.api.common.config.security.csrf.properties.AdminCsrfProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCsrfTokenRepositoryFactoryTest {

    @Test
    void buildsRepositoryWithAdminCookieAttributes() {
        AdminCsrfProperties props = new AdminCsrfProperties();
        props.setCookieDomain("admin.pfplay.xyz");
        props.setCookieName("XSRF-TOKEN");
        props.setHeaderName("X-XSRF-TOKEN");
        props.setSameSite("Strict");
        props.setSecure(true);

        CookieCsrfTokenRepository repo = new AdminCsrfTokenRepositoryFactory(props).build();

        // Generate + save a token, then assert the cookie attributes on the response.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CsrfToken token = repo.generateToken(req);
        repo.saveToken(token, req, res);

        Cookie cookie = res.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo(token.getToken());
        assertThat(cookie.getDomain()).isEqualTo("admin.pfplay.xyz");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isFalse(); // frontend must read it
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
    }

    @Test
    void buildsRepository_withCustomHeaderName() {
        AdminCsrfProperties props = new AdminCsrfProperties();
        props.setCookieDomain("admin.pfplay.xyz");
        props.setHeaderName("X-CUSTOM-XSRF");

        CookieCsrfTokenRepository repo = new AdminCsrfTokenRepositoryFactory(props).build();

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/admin/x");
        // Generate a token + put it in request param (the way CsrfFilter looks up).
        CsrfToken token = repo.generateToken(req);
        req.setParameter("_csrf", token.getToken());

        // The repo should expose the configured header name on the token.
        assertThat(token.getHeaderName()).isEqualTo("X-CUSTOM-XSRF");
    }

    @Test
    void buildsRepository_withSecureFalseForTestProfile() {
        AdminCsrfProperties props = new AdminCsrfProperties();
        props.setCookieDomain("localhost");
        props.setSecure(false);

        CookieCsrfTokenRepository repo = new AdminCsrfTokenRepositoryFactory(props).build();

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        repo.saveToken(repo.generateToken(req), req, res);

        Cookie cookie = res.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getSecure()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify failure**

```bash
./gradlew :common:test --tests "*AdminCsrfTokenRepositoryFactoryTest*"
```

Expected: compile failure — class doesn't exist.

- [ ] **Step 3: Write the minimal implementation**

```java
// common/src/main/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfTokenRepositoryFactory.java
package com.pfplaybackend.api.common.config.security.csrf;

import com.pfplaybackend.api.common.config.security.csrf.properties.AdminCsrfProperties;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link CookieCsrfTokenRepository} used by Spring Security's
 * {@code CsrfFilter} for admin endpoints.
 *
 * <p>Cookie attributes mirror {@code AdminAccessToken} (admin subdomain, SameSite=Strict,
 * Secure=true), with one exception: {@code httpOnly=false} so the admin frontend's JS
 * can read the value and echo it as the {@code X-XSRF-TOKEN} header.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-04-19-admin-platform-security.md} §5.4.2 + §5.4.3.
 */
@Component
public class AdminCsrfTokenRepositoryFactory {

    private final AdminCsrfProperties props;

    public AdminCsrfTokenRepositoryFactory(AdminCsrfProperties props) {
        this.props = props;
    }

    public CookieCsrfTokenRepository build() {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookieName(props.getCookieName());
        repo.setHeaderName(props.getHeaderName());
        // Single sink for cookie attributes — frontend reads .value, server validates header echo.
        repo.setCookieCustomizer(cookie -> cookie
                .domain(props.getCookieDomain())
                .path("/")
                .secure(props.isSecure())
                .httpOnly(false)
                .sameSite(props.getSameSite())
        );
        return repo;
    }
}
```

- [ ] **Step 4: Run test to verify pass**

```bash
./gradlew :common:test --tests "*AdminCsrfTokenRepositoryFactoryTest*"
```

Expected: all 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfTokenRepositoryFactory.java \
        common/src/test/java/com/pfplaybackend/api/common/config/security/csrf/AdminCsrfTokenRepositoryFactoryTest.java
git commit -m "feat(security): admin CSRF token repository factory (PR 5)"
```

---

### Task 5: Document the admin-side CSRF contract for pfplay-admin (ADR)

**Files:**
- Create: `docs/adr/012-admin-csrf-token.md`

**Why now:** ADRs are cheap; capture the why before the implementation lands so future readers (and the pfplay-admin frontend team) understand the contract. Treat this as a single-task commit, not bundled with code.

- [ ] **Step 1: Write the ADR**

```markdown
<!-- docs/adr/012-admin-csrf-token.md -->
# ADR-012: Admin CSRF Token Defense (Option A)

**Date:** 2026-04-27
**Status:** Accepted
**Related spec:** docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.4.3
**Related plan:** docs/superpowers/plans/2026-04-27-admin-platform-pr5.md

## Context

PR 4 shipped Option B (Origin/Referer header allowlist) for admin CSRF defense. Option A
(token-based double-submit) was deferred to PR 5 because it required a `SecurityConfig`
overhaul (CSRF cannot be selectively re-enabled while staying compatible with stateless
JWT for non-admin paths).

Spec §5.4.3 recommends A + B as defense in depth. Option A defends against attackers
who can spoof the `Origin` header (rare; requires browser bug or non-browser client
hitting an XSS in admin.pfplay.xyz).

## Decision

Use Spring Security's stock `CookieCsrfTokenRepository.withHttpOnlyFalse()` configured
via `setCookieCustomizer` to issue an `XSRF-TOKEN` cookie scoped to `admin.pfplay.xyz`.
The admin frontend (pfplay-admin) reads the cookie value and echoes it as the
`X-XSRF-TOKEN` header on every state-changing request.

### Scope

CSRF validation is enforced when ALL of:
1. HTTP method is one of {POST, PUT, PATCH, DELETE} (state-changing)
2. Path matches `/api/v1/admin/**` OR is exactly `/api/v1/auth/admin/logout`

CSRF validation is BYPASSED for `/api/v1/auth/admin/login` (no session yet) and for
all non-admin paths (today's behavior — OAuth flows, member sign-in, etc.).

### Cookie shape

| Attribute | Value | Rationale |
|---|---|---|
| Name | `XSRF-TOKEN` | Spring default; pfplay-admin axios stock config picks it up. |
| Domain | `admin.pfplay.xyz` (no leading dot) | Matches `AdminAccessToken` — same isolation. |
| Path | `/` | Admin frontend hits paths under `/`. |
| Secure | true | TLS-only. |
| HttpOnly | **false** | Frontend JS must read the value. |
| SameSite | Strict | Aligns with `AdminAccessToken`. |

### Frontend contract (for pfplay-admin team)

1. After admin login (`POST /api/v1/auth/admin/login`), the first GET against any
   `/api/v1/admin/**` endpoint will set the `XSRF-TOKEN` cookie.
2. Frontend reads `XSRF-TOKEN` from `document.cookie` (it's not HttpOnly).
3. On every state-changing request (POST/PUT/PATCH/DELETE) to `/api/v1/admin/**` or
   `/api/v1/auth/admin/logout`, frontend includes header `X-XSRF-TOKEN: <cookie value>`.
4. Server-side `CsrfFilter` compares header to cookie. Mismatch or missing → 403.

### What we did NOT pick

- HMAC-derived per-session tokens — overkill for MVP; cookie pair already binds.
- Custom `GET /admin/csrf-token` endpoint (per spec §5.4.3) — Spring's stock cookie
  issuance on every safe response is equivalent and simpler.
- Separate `SecurityFilterChain` for admin vs general — see plan Decision 9; deferred.

## Consequences

- Admin frontend must wire an axios interceptor (one-liner — Axios reads `XSRF-TOKEN` and
  sets `X-XSRF-TOKEN` automatically when configured with `xsrfCookieName` + `xsrfHeaderName`).
- Local dev: `secure: false` in `test` and `dev:local` profiles — see `application.yml`.
- Origin/Referer guard (PR 4) stays — defense in depth.

## Verification

- `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminCsrfIntegrationTest.java`
  proves: token missing → 403, token mismatched → 403, token correct → 200/expected.
- Frontend integration is verified by pfplay-admin's CI; not in scope for this repo.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/012-admin-csrf-token.md
git commit -m "docs(adr): admin CSRF token defense Option A (PR 5)"
```

---

## Chunk 2: Method security activation cutover (Tasks 6–9 — single G1 commit)

> **G1 atomic commit grouping:** Tasks 6, 7, 8, 9 are committed together at the end of Task 9. Skip the per-task commit step — the combined commit message is in Task 9 Step 4.

### Task 6: Enable `@EnableMethodSecurity` in `SecurityConfig`

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java`

**Why first in G1:** This is the actual behavior switch. Tasks 7, 8, 9 fix the consequences. The build will be RED between Task 6 and end of Task 9 — that's why they're committed together.

- [ ] **Step 1: Add `@EnableMethodSecurity` to `SecurityConfig`**

Open `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java`. Add the import:

```java
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
```

Add the annotation to the class declaration:

```java
@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {
    // ... existing body unchanged
}
```

NOTE: Default `@EnableMethodSecurity` activates `@PreAuthorize` and `@PostAuthorize`. We don't need `securedEnabled=true` (no `@Secured`) or `jsr250Enabled=true` (no `@RolesAllowed`).

- [ ] **Step 2: Run the existing admin endpoint test — confirm baseline still passes**

```bash
./gradlew :app:test --tests "*AdminEndpointSecurityTest*"
```

Expected: 3 tests pass. URL-level rules are still the gate; method-level annotations are now ALSO active but on URL hits, the URL rule fires first.

- [ ] **Step 3: Run `app` and `user` and `playlist` and `party` slice tests**

```bash
./gradlew :app:test :user:test :playlist:test
```

Expected: SOME TESTS FAIL — specifically those exercising endpoints whose `@PreAuthorize` SpEL has the double-prefix bug. Capture the failure list (Task 7 fixes them all).

(NOTE: The party module's MVC tests live in `:app` because it's Hexagonal — confirmed by directory structure.)

If failures don't include any of the 14 latent annotations from §"Verified codebase facts", STOP — the test slices may not be exercising those code paths yet. Proceed to Task 7 anyway; they'll be re-validated then.

**Do NOT commit.** Continue to Task 7.

---

### Task 7: Fix all 14 latent `hasRole('ROLE_X')` double-prefix annotations (non-admin)

**Files (all `Modify`):**
- `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/TrackQueryController.java:29`
- `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/TrackCommandController.java:40`
- `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/TrackCommandController.java:68`
- `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/search/MusicSearchController.java:31`
- `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/PlaylistCommandController.java:36`
- `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/PlaylistCommandController.java:48`
- `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/PlaylistCommandController.java:59`
- `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/PlaylistQueryController.java:27`
- `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserWalletCommandController.java:42`
- `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserBioCommandController.java:31`
- `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserAvatarCommandController.java:35`
- `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserAvatarQueryController.java:30`
- `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserAvatarQueryController.java:39`
- `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserProfileQueryController.java:28`
- `app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/CrewQueryController.java:32`
- `app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/DjCommandController.java:41`

**Mechanical change for each:**

| Old (buggy) | New (correct) |
|---|---|
| `@PreAuthorize("hasRole('ROLE_MEMBER')")` | `@PreAuthorize("hasRole('MEMBER')")` |
| `@PreAuthorize("hasAnyRole('ROLE_MEMBER')")` | `@PreAuthorize("hasRole('MEMBER')")` (single value → use `hasRole`) |
| `@PreAuthorize("hasAnyRole('ROLE_GUEST', 'ROLE_MEMBER')")` | `@PreAuthorize("hasAnyRole('GUEST', 'MEMBER')")` |

**Reminder of why:** Spring Security 6 `hasRole('X')` SpEL prepends `ROLE_` and looks up authority `ROLE_X`. So `hasRole('ROLE_MEMBER')` becomes `ROLE_ROLE_MEMBER` — a no-match. Drop the `ROLE_` prefix.

- [ ] **Step 1: Apply each rewrite**

For each file in the list above, perform the substitution. Use grep + sed in a single pass for safety (or 14 individual `Edit` calls if more comfortable):

```bash
# Pattern 1: hasRole('ROLE_MEMBER') → hasRole('MEMBER')
find playlist/src/main user/src/main app/src/main -name "*.java" \
  -exec sed -i.bak "s/hasRole('ROLE_MEMBER')/hasRole('MEMBER')/g" {} \;

# Pattern 2: hasAnyRole('ROLE_MEMBER') → hasRole('MEMBER')
find playlist/src/main user/src/main app/src/main -name "*.java" \
  -exec sed -i.bak "s/hasAnyRole('ROLE_MEMBER')/hasRole('MEMBER')/g" {} \;

# Pattern 3: hasAnyRole('ROLE_GUEST', 'ROLE_MEMBER') → hasAnyRole('GUEST', 'MEMBER')
find playlist/src/main user/src/main app/src/main -name "*.java" \
  -exec sed -i.bak "s/hasAnyRole('ROLE_GUEST', 'ROLE_MEMBER')/hasAnyRole('GUEST', 'MEMBER')/g" {} \;

# Clean up .bak files
find playlist/src/main user/src/main app/src/main -name "*.java.bak" -delete
```

(If `sed -i.bak` syntax fails on this platform, fall back to per-file `Edit` calls.)

- [ ] **Step 2: Re-run the grep precondition (Step 6 of Hard precondition) to confirm zero remaining matches**

```bash
grep -rn "hasRole('ROLE_\|hasAnyRole('ROLE_" common/src/main app/src/main user/src/main playlist/src/main 2>/dev/null
```

Expected: empty output.

- [ ] **Step 3: Run all module tests to confirm fix**

```bash
./gradlew :app:test :user:test :playlist:test
```

Expected: tests that failed in Task 6 Step 3 now pass. If any tests still fail with `403 Forbidden` for legit MEMBER/GUEST roles, audit those test annotations and the controller annotations together (likely a `@WithMockUser(roles="MEMBER")` test exists for an endpoint that uses `hasRole('MEMBER')` — should match now).

If new failures appear (e.g., tests that were silently passing before because annotations were ignored, but now enforce wrong roles), surface to user — this is the "latent" surface and may need a per-failure judgment call.

**Do NOT commit.** Continue to Task 8.

---

### Task 8: Migrate `AdminUserController` + `AdminPartyroomController` from `hasAuthority('FM')` to `@adminAuth.xxx()`

**Files (all `Modify`):**
- `app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/AdminUserController.java`
- `app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/AdminPartyroomController.java`

**Mapping:**

| File:Line | Method | Old | New |
|---|---|---|---|
| `AdminUserController.java:52` | `createVirtualMember` | `@PreAuthorize("hasAuthority('FM')")` | `@PreAuthorize("@adminAuth.canChangeMemberTier()")` |
| `AdminUserController.java:80` | `getVirtualMember` | `@PreAuthorize("hasAuthority('FM')")` | `@PreAuthorize("@adminAuth.canChangeMemberTier()")` |
| `AdminUserController.java:101` | `updateVirtualMemberAvatar` | `@PreAuthorize("hasAuthority('FM')")` | `@PreAuthorize("@adminAuth.canChangeMemberTier()")` |
| `AdminUserController.java:132` | `deleteVirtualMember` | `@PreAuthorize("hasAuthority('FM')")` | `@PreAuthorize("@adminAuth.canChangeMemberTier()")` |
| `AdminPartyroomController.java:53` | `createPartyroom` | `@PreAuthorize("hasAuthority('FM')")` | `@PreAuthorize("@adminAuth.canSuspendPartyroom()")` |
| `AdminPartyroomController.java:91` | `createBulkPreviewEnvironment` | `@PreAuthorize("hasAuthority('FM')")` | `@PreAuthorize("@adminAuth.canSuspendPartyroom()")` |

- [ ] **Step 1: Apply each rewrite using `Edit` per file**

For `AdminUserController.java`, replace all four occurrences:
- old: `@PreAuthorize("hasAuthority('FM')")`
- new: `@PreAuthorize("@adminAuth.canChangeMemberTier()")`

(Use `Edit` with `replace_all=true` since the exact string is unique within this file's scope and identical across the four methods.)

For `AdminPartyroomController.java`, same approach with `@adminAuth.canSuspendPartyroom()`.

- [ ] **Step 2: Run admin slice tests**

```bash
./gradlew :app:test --tests "*Admin*ControllerTest*"
```

Expected: all admin-controller MVC slice tests pass. If a slice test was using `@WithMockUser(authorities = "FM")`, it'll fail — those tests must be updated to `@WithMockUser(roles = "ADMIN")`. Note the failures and fix in the same task before moving on. (Reading the existing `AbstractAdminWebMvcTest.java` should reveal the convention.)

- [ ] **Step 3: Confirm no remaining `FM` references in admin controllers**

```bash
grep -rn "hasAuthority('FM')" app/src/main/java/com/pfplaybackend/api/admin/ 2>/dev/null
```

Expected: empty output. (Task 9 handles `AdminDemoController.java`.)

**Do NOT commit.** Continue to Task 9.

---

### Task 9: Migrate `AdminDemoController` (5 methods) and finalize G1 commit

**Files (`Modify`):**
- `app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/AdminDemoController.java` (5 methods)

**Mapping:**

| Line | Method | New |
|---|---|---|
| 74 | `getPartyrooms` | `@PreAuthorize("@adminAuth.isAdmin()")` |
| 103 | `initializeDemoEnvironment` | `@PreAuthorize("@adminAuth.isAdmin()")` |
| 144 | `simulateReactions` | `@PreAuthorize("@adminAuth.isAdmin()")` |
| 182 | `startChatSimulation` | `@PreAuthorize("@adminAuth.isAdmin()")` |
| 204 | `stopChatSimulation` | `@PreAuthorize("@adminAuth.isAdmin()")` |

(All 5 use `isAdmin()` — demo endpoints aren't role-discriminating; any admin can drive demo flows.)

- [ ] **Step 1: Apply rewrites with `Edit` `replace_all=true`**

In `AdminDemoController.java`, replace all 5 occurrences:
- old: `@PreAuthorize("hasAuthority('FM')")`
- new: `@PreAuthorize("@adminAuth.isAdmin()")`

- [ ] **Step 2: Run admin demo tests + run the full admin tier**

```bash
./gradlew :app:test --tests "*AdminDemo*"
./gradlew :app:test --tests "*Admin*"
```

Expected: pass. Same caveat as Task 8 Step 2 — if a slice test was using `authorities = "FM"`, fix it.

- [ ] **Step 3: Confirm zero `FM` references anywhere in main sources**

```bash
grep -rn "hasAuthority('FM')" common/src/main app/src/main user/src/main playlist/src/main 2>/dev/null
```

Expected: empty output. **No FM hold-outs.**

- [ ] **Step 4: Final G1 build — full project**

```bash
./gradlew clean build
```

Expected: BUILD SUCCESSFUL. All tests pass across all modules.

If anything fails: STOP and fix before committing. Do NOT push G1 in a broken state.

- [ ] **Step 5: G1 atomic commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java \
        app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/AdminUserController.java \
        app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/AdminPartyroomController.java \
        app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/AdminDemoController.java \
        playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/TrackQueryController.java \
        playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/TrackCommandController.java \
        playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/search/MusicSearchController.java \
        playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/PlaylistCommandController.java \
        playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/in/web/PlaylistQueryController.java \
        user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserWalletCommandController.java \
        user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserBioCommandController.java \
        user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserAvatarCommandController.java \
        user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserAvatarQueryController.java \
        user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserProfileQueryController.java \
        app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/CrewQueryController.java \
        app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/DjCommandController.java

# If admin slice tests were updated in Tasks 8/9 Step 2, also stage those test files.
# Run `git status` and stage anything else relevant.

git commit -m "feat(security): activate @EnableMethodSecurity + retire FM annotations (PR 5)

- Enable @EnableMethodSecurity on common/.../SecurityConfig.java
- Replace 11 @PreAuthorize(\"hasAuthority('FM')\") admin annotations with
  @adminAuth.xxx() bean references (4 in AdminUserController,
  2 in AdminPartyroomController, 5 in AdminDemoController)
- Fix 14 latent hasRole('ROLE_X') / hasAnyRole('ROLE_X', ...) double-prefix
  bugs across non-admin controllers (playlist, user, party). These
  annotations were silently ignored before activation; activation would
  cause regressions without the rewrites
- All four changes land together — splitting them would leave main in
  either a broken or insecure state (per Atomic commit grouping G1)

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md
      §5.2.3 (URL split + @PreAuthorize rewrite)
      §5.2.4 (@adminAuth centralized SpEL bean)"
```

---

## Chunk 3: SecurityConfig URL split + CSRF wiring (Tasks 10–12)

### Task 10: Split URL rules — `/admin/system/**` + `/admin/avatar/**` → `SUPER_ADMIN`

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java`

**Why now:** G1 is committed; the build is green. Now we apply the URL gate split per §5.2.3. This is independent from G1 because the additional rules don't break anything if no controllers exist under `/system` or `/avatar` (they will return 404 today; rules just enforce when present).

- [ ] **Step 1: Update `authorizeHttpRequests` rules in `SecurityConfig.java:52-63`**

Replace this block:

```java
.authorizeHttpRequests(request -> request
        .requestMatchers("/api/v1/auth/oauth/callback", "/api/v1/auth/oauth/url", "/api/v1/auth/logout",
                "/api/v1/auth/admin/login",
                "/api/v1/users/members/sign/**", "/api/v1/users/guests/sign/**", "/api/v1/partyrooms/link/**").permitAll()
        .requestMatchers("/actuator/health").permitAll()
        .requestMatchers("/ws/**").permitAll()
        .requestMatchers("/spec/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
        .requestMatchers("/api/v1/auth/admin/**").authenticated()
        .requestMatchers("/api/**").authenticated()
        .anyRequest().denyAll()
)
```

with:

```java
.authorizeHttpRequests(request -> request
        .requestMatchers("/api/v1/auth/oauth/callback", "/api/v1/auth/oauth/url", "/api/v1/auth/logout",
                "/api/v1/auth/admin/login",
                "/api/v1/users/members/sign/**", "/api/v1/users/guests/sign/**", "/api/v1/partyrooms/link/**").permitAll()
        .requestMatchers("/actuator/health").permitAll()
        .requestMatchers("/ws/**").permitAll()
        .requestMatchers("/spec/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
        // Spec §5.2.3 — order matters: most specific first.
        .requestMatchers("/api/v1/admin/system/**").hasRole("SUPER_ADMIN")
        .requestMatchers("/api/v1/admin/avatar/**").hasRole("SUPER_ADMIN")
        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
        .requestMatchers("/api/v1/auth/admin/**").authenticated()
        .requestMatchers("/api/**").authenticated()
        .anyRequest().denyAll()
)
```

(Spring's `requestMatchers` rules evaluate in declaration order — first match wins. `/api/v1/admin/system/**` MUST come before `/api/v1/admin/**` or it'll never match.)

- [ ] **Step 2: Run existing admin endpoint tests**

```bash
./gradlew :app:test --tests "*AdminEndpointSecurityTest*"
```

Expected: 3 tests still pass (PR 4's test exercises a `/api/v1/admin/partyrooms` endpoint which falls under the catch-all `ADMIN` rule — unchanged).

- [ ] **Step 3: Run full build smoke**

```bash
./gradlew :app:test
```

Expected: pass.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java
git commit -m "feat(security): split admin URL rules — /system/** + /avatar/** → SUPER_ADMIN (PR 5)"
```

---

### Task 11: Wire CSRF Option A into `SecurityConfig`

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java`

**Why now:** All scaffolding (Tasks 2, 3, 4) is committed. URL rules are split (Task 10). This task glues CSRF onto the chain.

- [ ] **Step 1: Replace `csrf.disable` with the configured CSRF**

In `SecurityConfig.java`:

1. Add fields to the class (Lombok `@RequiredArgsConstructor` will inject them):

```java
private final AdminCsrfTokenRepositoryFactory adminCsrfTokenRepositoryFactory;
private final AdminCsrfProperties adminCsrfProperties;
```

2. Add imports:

```java
import com.pfplaybackend.api.common.config.security.csrf.AdminCsrfRequestMatcher;
import com.pfplaybackend.api.common.config.security.csrf.AdminCsrfTokenRepositoryFactory;
import com.pfplaybackend.api.common.config.security.csrf.properties.AdminCsrfProperties;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
```

3. Replace `.csrf(AbstractHttpConfigurer::disable)` (currently line 44) with:

```java
.csrf(csrf -> {
    if (!adminCsrfProperties.isEnabled()) {
        csrf.disable();
        return;
    }
    CookieCsrfTokenRepository repo = adminCsrfTokenRepositoryFactory.build();
    CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
    csrf
        .csrfTokenRepository(repo)
        .csrfTokenRequestHandler(handler)
        .requireCsrfProtectionMatcher(new AdminCsrfRequestMatcher());
})
```

(The `enabled=false` branch is for the test profile that needs to bypass CSRF — useful for the existing `AdminEndpointSecurityTest` which doesn't carry CSRF tokens.)

Wait — that's not quite right. We want CSRF to be enforced in the test profile too (otherwise the CSRF tests in Task 14 can't validate end-to-end). Let me reconsider:

- Default profile: `enabled=true`, CSRF enforced on admin paths.
- `test` profile: `enabled=true`, but with `secure=false` cookie (Testcontainers).
- For tests that DON'T want CSRF (PR 4's `AdminEndpointSecurityTest`), they must include the `X-XSRF-TOKEN` header — OR we can use Spring's MockMvc `with(csrf())` post-processor (the standard pattern) which adds the token automatically.

Decision: Keep CSRF enforced in test profile. Update PR 4's `AdminEndpointSecurityTest` to use `with(csrf())` (Task 11 Step 3). The `enabled` switch stays as a hard kill in case someone needs to disable globally.

- [ ] **Step 2: Update PR 4's `AdminEndpointSecurityTest` to include `csrf()` post-processor**

Open `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminEndpointSecurityTest.java`. Add the import:

```java
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
```

Update each `mockMvc.perform(post(...))` to chain `.with(csrf())`:

```java
mockMvc.perform(post("/api/v1/admin/partyrooms")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
```

Apply to all 3 tests in the class.

(For `@WithAnonymousUser` test: anonymous + missing CSRF would BOTH cause failure. With `csrf()` post-processor, the test cleanly isolates "no auth" as the failure cause — 401 from auth gate beats 403 from CSRF.)

- [ ] **Step 3: Run the full app:test**

```bash
./gradlew :app:test
```

Expected: BUILD SUCCESSFUL. Watch for any other admin POST tests that fail with 403 because they're missing `csrf()` — search and add as needed:

```bash
grep -rn "post(\"/api/v1/admin\|put(\"/api/v1/admin\|delete(\"/api/v1/admin\|patch(\"/api/v1/admin" app/src/test/java
```

For each match: ensure the test does `.with(csrf())`. Files likely affected: `AdminUserControllerTest.java`, `AdminPartyroomControllerTest.java`, `AdminDemoControllerTest.java` (if they exist). Also any admin auth tests touching `POST /api/v1/auth/admin/logout`.

(The login endpoint `POST /api/v1/auth/admin/login` is excluded by `AdminCsrfRequestMatcher` — does NOT need `with(csrf())`. Confirm any login test still passes.)

- [ ] **Step 4: Run full project build**

```bash
./gradlew clean build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java \
        app/src/test/java/com/pfplaybackend/api/common/config/security/AdminEndpointSecurityTest.java
# Stage any other test files you updated to add with(csrf()):
# git add app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/...

git commit -m "feat(security): wire CSRF Option A on admin paths (PR 5)

- Replace csrf.disable with CookieCsrfTokenRepository scoped via
  AdminCsrfRequestMatcher to state-changing /api/v1/admin/** + admin logout.
- Login endpoint excluded (chicken-and-egg).
- Update existing admin MVC tests to use Spring Security Test's csrf()
  post-processor.

Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.4.3"
```

---

### Task 12: Verify CSRF doesn't leak onto non-admin paths (sanity)

**Files:** none modified — this task is a verification only.

**Why now:** A misconfigured CSRF matcher could accidentally enforce CSRF on member sign-up or OAuth callback, breaking customer flows. Quick smoke check before moving to integration tests.

- [ ] **Step 1: Run user/playlist/oauth tests**

```bash
./gradlew :app:test --tests "*OAuth*"
./gradlew :app:test --tests "*MemberSign*"
./gradlew :user:test
./gradlew :playlist:test
```

Expected: BUILD SUCCESSFUL. No 403 regressions. (If any test fails with 403 on a non-admin POST and the matcher is correct, the failure is unrelated; proceed.)

- [ ] **Step 2: Manual matcher unit-level smoke (already covered by `AdminCsrfRequestMatcherTest` — re-run for confidence)**

```bash
./gradlew :common:test --tests "*AdminCsrfRequestMatcherTest*"
```

Expected: pass.

- [ ] **Step 3: No commit (verification only)**

If both checks pass, proceed to Chunk 4. If either fails, fix root cause before continuing.

---

## Chunk 4: Integration tests (Tasks 13–15)

### Task 13: §5.7.1 권한 회귀 parameterized matrix — `AdminAuthorizationMatrixTest`

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminAuthorizationMatrixTest.java`

**Behavior:** Parameterized integration test using `@SpringBootTest` + MockMvc. Matrix:
- Roles: `(anonymous|MEMBER|ADMIN|SUPER_ADMIN)`
- Endpoints: `(/api/v1/admin/__test_probe__|/api/v1/admin/system/__test_probe__|/api/v1/admin/avatar/__test_probe__)`
- Expected:
  - anonymous → 401 (regardless of probe)
  - MEMBER → 403 (regardless of probe)
  - ADMIN → 403 on `/system/**`, 403 on `/avatar/**`, [other] on catch-all
  - SUPER_ADMIN → [other] on `/system/**`, [other] on `/avatar/**`, [other] on catch-all (since SUPER_ADMIN holds both ROLE_ADMIN + ROLE_SUPER_ADMIN authorities)

(`[other]` = NOT 401 AND NOT 403. Most likely 404 because no controller exists at the probe path. The point is the URL-level gate doesn't reject.)

- [ ] **Step 1: Write the failing test**

```java
// app/src/test/java/com/pfplaybackend/api/common/config/security/AdminAuthorizationMatrixTest.java
package com.pfplaybackend.api.common.config.security;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §5.7.1 — 권한 회귀 parameterized matrix.
 *
 * <p>Matrix: (role) × (probe path) → expected status.
 *
 * <p>Probe paths are deliberately non-existent — the URL-level filter chain runs
 * before request mapping, so the gate fires regardless of whether a controller exists.
 * "Allow-through" responses look like 404 (no controller) which the test asserts is
 * NOT 401 / NOT 403.
 *
 * <p>Expected matrix:
 * <pre>
 *                  /admin/probe    /admin/system/probe   /admin/avatar/probe
 *   anonymous          401              401                  401
 *   MEMBER             403              403                  403
 *   ADMIN              not(401|403)     403                  403
 *   SUPER_ADMIN        not(401|403)     not(401|403)         not(401|403)
 * </pre>
 */
@AutoConfigureMockMvc
class AdminAuthorizationMatrixTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private static final String GENERIC_ADMIN = "/api/v1/admin/__test_probe__";
    private static final String SYSTEM_ADMIN = "/api/v1/admin/system/__test_probe__";
    private static final String AVATAR_ADMIN = "/api/v1/admin/avatar/__test_probe__";

    // ----- Anonymous: always 401 -----
    @ParameterizedTest
    @MethodSource("allAdminPaths")
    @WithAnonymousUser
    void anonymousRequest_returns401(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ----- MEMBER: always 403 -----
    @ParameterizedTest
    @MethodSource("allAdminPaths")
    @WithMockUser(roles = "MEMBER")
    void memberRequest_returns403(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    // ----- ADMIN: 403 on /system/**, 403 on /avatar/**, allow on catch-all -----
    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRequest_genericAdminPath_isAllowedThroughGate() throws Exception {
        mockMvc.perform(post(GENERIC_ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(Matchers.not(Matchers.isOneOf(401, 403))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRequest_systemAdminPath_returns403() throws Exception {
        mockMvc.perform(post(SYSTEM_ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRequest_avatarAdminPath_returns403() throws Exception {
        mockMvc.perform(post(AVATAR_ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    // ----- SUPER_ADMIN: allowed everywhere (carries both ROLE_ADMIN + ROLE_SUPER_ADMIN) -----
    @ParameterizedTest
    @MethodSource("allAdminPaths")
    @WithMockUser(roles = {"SUPER_ADMIN", "ADMIN"})
    void superAdminRequest_allowedThroughGate(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(Matchers.not(Matchers.isOneOf(401, 403))));
    }

    private static Stream<Arguments> allAdminPaths() {
        return Stream.of(
                Arguments.of(GENERIC_ADMIN),
                Arguments.of(SYSTEM_ADMIN),
                Arguments.of(AVATAR_ADMIN)
        );
    }
}
```

- [ ] **Step 2: Run test**

```bash
./gradlew :app:test --tests "*AdminAuthorizationMatrixTest*"
```

Expected: all matrix cases pass. If any fail:
- 401 expected but 403: URL gate didn't see anonymous — likely a MockMvc / SecurityContext issue. Check `@WithAnonymousUser` is applied.
- 403 expected but ALLOWED for MEMBER: URL gate is broken — re-verify Task 10's rule order.
- ALLOWED expected but 403 for SUPER_ADMIN on `/avatar/**`: confirm `@WithMockUser(roles = {"SUPER_ADMIN", "ADMIN"})` issues both authorities.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/pfplaybackend/api/common/config/security/AdminAuthorizationMatrixTest.java
git commit -m "test(security): §5.7.1 권한 회귀 matrix for /admin/** + /system/** + /avatar/** (PR 5)"
```

---

### Task 14: §5.7.3 CSRF token validation integration test

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminCsrfIntegrationTest.java`

**Behavior:** Three scenarios on a state-changing admin endpoint:
1. CSRF cookie + matching header → status NOT 403 (likely 401 because no real auth, but NOT 403 from CSRF)
2. CSRF cookie + missing header → 403
3. CSRF cookie + wrong header value → 403
4. Login endpoint → CSRF NOT enforced (POST without token succeeds-or-fails on auth, not on CSRF)

For 1+3: simulate via Spring Security Test's `csrf()` helper (success) and `csrf().useInvalidToken()` (mismatch).

- [ ] **Step 1: Write the test**

```java
// app/src/test/java/com/pfplaybackend/api/common/config/security/AdminCsrfIntegrationTest.java
package com.pfplaybackend.api.common.config.security;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §5.7.3 — CSRF token validation on admin endpoints.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.4.3 (Option A).
 *
 * <p>The CSRF gate is independent of the auth gate. We use a probe path under
 * /api/v1/admin/** so URL-level auth (hasRole(ADMIN)) and method-level CSRF
 * are exercised as orthogonal layers. With @WithMockUser(roles="ADMIN"), the
 * auth gate passes; the failure (or success) is attributable to CSRF.
 */
@AutoConfigureMockMvc
class AdminCsrfIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private static final String ADMIN_PROBE = "/api/v1/admin/__test_probe__";
    private static final String LOGIN = "/api/v1/auth/admin/login";

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWithValidCsrf_passesGate() throws Exception {
        mockMvc.perform(post(ADMIN_PROBE).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(Matchers.not(Matchers.is(403))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWithoutCsrfHeader_returns403() throws Exception {
        // No `.with(csrf())` — Spring's CsrfFilter sees the matcher fire, finds no token, denies.
        mockMvc.perform(post(ADMIN_PROBE)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWithInvalidCsrfToken_returns403() throws Exception {
        mockMvc.perform(post(ADMIN_PROBE).with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginEndpoint_doesNotRequireCsrf() throws Exception {
        // Login is excluded from the matcher; no `.with(csrf())` should still pass the CSRF gate.
        // Auth itself will fail (no real credentials), but the failure must NOT be CSRF (403 with
        // "Invalid CSRF token" body). A 4xx other than 403 is acceptable here.
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@x.x\",\"password\":\"y\"}"))
                .andExpect(status().is(Matchers.not(Matchers.is(403))));
    }
}
```

- [ ] **Step 2: Run test**

```bash
./gradlew :app:test --tests "*AdminCsrfIntegrationTest*"
```

Expected: 4 tests pass. If `postWithoutCsrfHeader_returns403` fails (got 200 not 403), the matcher isn't enforcing — re-check Task 11 wiring.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/pfplaybackend/api/common/config/security/AdminCsrfIntegrationTest.java
git commit -m "test(security): §5.7.3 CSRF token enforcement on admin endpoints (PR 5)"
```

---

### Task 15: §5.7.2 cookie isolation end-to-end integration test

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminCookieIsolationIntegrationTest.java`

**Behavior:** End-to-end cookie test using real `JwtService`-minted tokens. Two scenarios:
1. Only `SharedSessionToken` (member) attached → request to `/api/v1/admin/**` → 401 (admin path didn't see admin cookie).
2. Only `AdminAccessToken` (admin) attached → request to `/api/v1/admin/**` → not-401 + not-403 (URL gate passes); request to `/api/v1/users/me/...` → 401 (general path didn't see shared cookie).

(Unit-level resolver tests already exist from PR 4. This is the integration-level slice — full chain.)

- [ ] **Step 1: Write the test**

```java
// app/src/test/java/com/pfplaybackend/api/common/config/security/AdminCookieIsolationIntegrationTest.java
package com.pfplaybackend.api.common.config.security;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §5.7.2 — Cookie isolation end-to-end.
 *
 * <p>Verifies that the path-aware {@code CookieBearerTokenResolver} (PR 4) selects the
 * correct cookie at the SecurityFilterChain level: admin path picks AdminAccessToken,
 * non-admin path picks SharedSessionToken.
 */
@AutoConfigureMockMvc
class AdminCookieIsolationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private JwtProperties jwtProperties;

    private static final long ADMIN_USER_ACCOUNT_ID = 1_000_000_000_000_000_001L;

    @Test
    void onlySharedToken_adminPath_returns401() throws Exception {
        String sharedToken = jwtService.mintSharedSessionToken(memberClaims());
        Cookie sharedCookie = cookie(jwtProperties.getCookie().getShared().getName(), sharedToken);

        mockMvc.perform(post("/api/v1/admin/__test_probe__")
                        .cookie(sharedCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminToken_adminPath_passesUrlGate() throws Exception {
        String adminToken = jwtService.mintAdminAccessToken(adminClaims());
        Cookie adminCookie = cookie(jwtProperties.getCookie().getAdmin().getName(), adminToken);

        mockMvc.perform(post("/api/v1/admin/__test_probe__")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(Matchers.not(Matchers.isOneOf(401, 403))));
    }

    @Test
    void onlyAdminToken_nonAdminPath_returns401() throws Exception {
        String adminToken = jwtService.mintAdminAccessToken(adminClaims());
        Cookie adminCookie = cookie(jwtProperties.getCookie().getAdmin().getName(), adminToken);

        // /api/v1/users/me/... is a general API path — resolver picks the SHARED cookie,
        // doesn't find it (only the admin cookie was set), and the request is unauthenticated.
        mockMvc.perform(get("/api/v1/users/me/__test_probe__")
                        .cookie(adminCookie))
                .andExpect(status().isUnauthorized());
    }

    private TokenClaimsRequest memberClaims() {
        return new TokenClaimsRequest(
                ADMIN_USER_ACCOUNT_ID,
                "member@pfplay.xyz",
                List.of(AccessLevel.ROLE_MEMBER),
                /* authorityTier */ null
        );
    }

    private TokenClaimsRequest adminClaims() {
        return new TokenClaimsRequest(
                ADMIN_USER_ACCOUNT_ID,
                "admin@pfplay.xyz",
                List.of(AccessLevel.ROLE_ADMIN),
                /* authorityTier */ null
        );
    }

    private Cookie cookie(String name, String value) {
        Cookie c = new Cookie(name, value);
        c.setPath("/");
        return c;
    }
}
```

(Verify `TokenClaimsRequest` constructor signature against `common/.../TokenClaimsRequest.java` — PR 4's record has this field order. If signatures changed, adjust.)

- [ ] **Step 2: Run test**

```bash
./gradlew :app:test --tests "*AdminCookieIsolationIntegrationTest*"
```

Expected: 3 tests pass. If `mintAdminAccessToken` / `mintSharedSessionToken` signatures don't match this plan's assumption, fix before continuing.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/pfplaybackend/api/common/config/security/AdminCookieIsolationIntegrationTest.java
git commit -m "test(security): §5.7.2 admin/shared cookie isolation end-to-end (PR 5)"
```

---

## Chunk 5: Final smoke + close out (Task 16)

### Task 16: Full project build smoke + roadmap update + handoff notes

**Files:**
- Modify (if needed): `docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md` — mark PR 5 row as ✅ in §10.1 (review resolutions table) when applicable.

- [ ] **Step 1: Run the full project from a clean state**

```bash
./gradlew clean build
```

Expected: BUILD SUCCESSFUL. All tests pass.

- [ ] **Step 2: Confirm zero `hasAuthority('FM')` in main sources**

```bash
grep -rn "hasAuthority('FM')" common/src/main app/src/main user/src/main playlist/src/main 2>/dev/null
```

Expected: empty.

- [ ] **Step 3: Confirm zero `hasRole('ROLE_X')` / `hasAnyRole('ROLE_X', ...)` double-prefix patterns**

```bash
grep -rn "hasRole('ROLE_\|hasAnyRole('ROLE_" common/src/main app/src/main user/src/main playlist/src/main 2>/dev/null
```

Expected: empty.

- [ ] **Step 4: Confirm `@adminAuth` is the only mechanism for admin method-level gates**

```bash
grep -rn "@PreAuthorize" app/src/main/java/com/pfplaybackend/api/admin/ 2>/dev/null
```

Expected: every match is `@PreAuthorize("@adminAuth.xxx()")` — never `hasRole(...)` or `hasAuthority(...)` directly.

- [ ] **Step 5: Run the §5.7 test suite as a single batch (sanity)**

```bash
./gradlew :app:test --tests "*AdminEndpointSecurityTest*" \
                   --tests "*AdminAuthorizationMatrixTest*" \
                   --tests "*AdminCsrfIntegrationTest*" \
                   --tests "*AdminCookieIsolationIntegrationTest*"
```

Expected: all pass. These four classes deliver §5.7.1, §5.7.2, §5.7.3.

- [ ] **Step 6: Update roadmap §10.1 (optional)**

If `docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md` §10.1 has a row hinting at PR 5's deliverables and you want to mark it ✅, do so in a small docs commit. Otherwise skip.

- [ ] **Step 7: Final commit (if anything was updated in Step 6)**

```bash
git add docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md
git commit -m "docs(roadmap): mark PR 5 deliverables resolved (PR 5)"
```

(If Step 6 was skipped, no commit here.)

- [ ] **Step 8: Print final summary for handoff**

Print to console:
```
PR 5 complete.

Commits added since PR 4 HEAD (d1452dbe):
$(git log d1452dbe..HEAD --oneline)

Next: PR 6 — Admin CRUD API (administrator create/read/update/revoke/reset-password).
PR 6 will be the first to actually hit the /api/v1/admin/system/** SUPER_ADMIN gate
established in this PR. The @adminAuth.canManageAdmins() bean method is ready.
```

---

## Final state — what PR 5 delivered

| Capability | Before PR 5 | After PR 5 |
|---|---|---|
| Method-level `@PreAuthorize` enforcement (production) | Silently ignored | Active globally via `@EnableMethodSecurity` |
| Admin authorization SpEL | 11 misnamed `hasAuthority('FM')` (dead) | Centralized `@adminAuth` bean with intent-named methods |
| `/api/v1/admin/system/**` gate | Same as catch-all (`ROLE_ADMIN`) | `ROLE_SUPER_ADMIN` |
| `/api/v1/admin/avatar/**` gate | Same as catch-all (`ROLE_ADMIN`) | `ROLE_SUPER_ADMIN` |
| CSRF defense on admin paths | Option B (Origin/Referer) only — PR 4 | Option B + Option A (token double-submit) |
| Latent `hasRole('ROLE_X')` SpEL bugs | 14 silent | All fixed |
| §5.7.1 권한 회귀 parameterized test | Three single tests on one path (PR 4) | 4×3 matrix across catch-all/system/avatar |
| §5.7.2 cookie isolation end-to-end | Unit-level only (PR 4) | Integration-level slice |
| §5.7.3 CSRF token enforcement | Not present | Three scenarios (missing/wrong/correct) |

**Out of scope and intentionally deferred:**
- Multi-`SecurityFilterChain` decomposition — Decision 9.
- Admin CRUD endpoints (`/api/v1/admin/system/administrators/...`) — PR 6.
- Avatar admin endpoints (`/api/v1/admin/avatar/...`) — PR 11.
- Permission-table RBAC — §11.1.3.
- Frontend (pfplay-admin) CSRF wiring — separate repo.

---

**(end of plan)**
