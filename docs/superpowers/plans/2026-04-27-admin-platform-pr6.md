# PR 6: Administrator CRUD API (F-1) + Self Password Change + `must_change_password` Flow Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver F-1 어드민 거버넌스 — slim CRUD over the V5 `administrator` aggregate (list / create / patch / revoke / reset-password), wired with a server-side temporary-password generator, a `must_change_password_at_next_login` enforcement flag on `user_account` (V13 migration), an admin self-service password-change endpoint, and login-time signaling that drives the admin-frontend forced-change redirect. Authorization centralized via `@adminAuth.canManageAdmins()` (other-admin) and `@adminAuth.isAdmin()` (self-change). All endpoints exercise the PR 5 CSRF / cookie / origin guards as-is.

**Architecture:** A new `administration/adapter/in/web/AdministratorManagementController` owns `/api/v1/admin/system/administrators/**` (super-admin only). A second new controller `administration/adapter/in/web/AdminPasswordController` owns `/api/v1/admin/password/change` (any admin, self). Service `AdministratorManagementService` brokers UserAccount + Administrator + Member lifecycle; it reuses `MemberSignService.getMemberOrCreate` only on the existing-account path and adopts a new `UserAccountData.createForLocalWithMandatoryChange(...)` factory for new admin invitations (so the `must_change_password=1` is set at construction). Login response carries `mustChangePassword`; `AdminLoginService` reads `UserAccountData.isMustChangePassword()` post-auth and threads it through `AdminAuthResult` → `AdminLoginResponse`. Self-change clears the flag. Reset-password (super-admin → other admin) sets it. Permission, cookie-isolation, and CSRF regressions extend the §5.7 parameterized matrix added in PR 5.

**Tech Stack:** Java 21, Spring Boot 3.2 (Spring Security 6.2 with method security from PR 5), Flyway 9, JPA/Hibernate, JUnit 5, Spring Security Test (`@WithMockUser`, `csrf()` post-processor), MockMvc, Mockito, Testcontainers.

**Spec sources (read once, applied throughout):**
- `docs/superpowers/specs/2026-04-19-admin-platform-features.md` §6.F-1 (어드민 거버넌스 — 목록/생성/수정/회수/리셋), §6.F-2 (MVP 2-role)
- `docs/superpowers/specs/2026-04-19-admin-platform-security.md` §5.6 (Admin Password Reset Flow — self change + other-admin reset + `must_change_password_at_next_login`), §5.5.1 (BCrypt cost 12 — already in `SecurityConfig.passwordEncoder()`), §5.5.5 (lockout — explicitly OUT-of-scope for PR 6, see "Out of scope" below)
- `docs/superpowers/specs/2026-04-19-admin-platform-schema.md` §4.2.1 (administrator DDL — already shipped V5)
- `docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md` §9.1 PR 6 row + §9.4 M2 (어드민 자체 관리)
- `docs/superpowers/plans/2026-04-27-admin-platform-pr5.md` "Out of scope (deferred)" — PR 5 explicitly punted to PR 6: `must_change_password_at_next_login` flow

**Decisions taken (spec leaves open):**

1. **Scope = F-1 (5 endpoints) + §5.6 self-change (1 endpoint) + `must_change_password` flag end-to-end (V13 + login signal + reset/self-change wiring).** §5.5.5 lockout-clear endpoint stays deferred — there is no persistent lockout column today (PR 4's `AdminLoginRateLimiter` is in-memory bucket4j only) so a `DELETE .../lockout` would have nothing to clear. A future PR adds `lockout_until` + the endpoint together.

2. **`must_change_password` lives on `user_account` (NOT `administrator`).** Rationale: §5.6 frames the flag as "next login forces password change" — login is `UserAccount`-scoped and the password lives on `UserAccount` (not Administrator). The Administrator aggregate stays purely about role/grant lifecycle. Single new column `must_change_password BOOLEAN NOT NULL DEFAULT 0`. New V13 migration adds it. The V5-seeded super admin (user_id=1) is unaffected — operator-supplied env password is operator-chosen, so default 0 is correct; we do NOT backfill 1 for the seed row.

3. **Login response signals must-change; no JWT claim, no filter-level lockout.** When `mustChangePassword=true`, `AdminLoginService` still mints a normal `AdminAccessToken` (so the admin can call `/api/v1/admin/password/change`), and the response body adds `mustChangePassword: true`. Frontend honors it (admin-side cooperation per §5.6 "변경 화면으로 강제 유도" — *guide*, not *block*). We do NOT put the flag in the JWT claim; we do NOT add a server-side filter that rejects all admin paths until cleared. Both are scope creep — admin discipline + the flag-at-login-keep-showing fallback is sufficient for MVP. If an attacker who steals the temp password tries to bypass: there's nothing to bypass — the flag is a UX hint, the actual security is the admin needing the temp pwd in the first place.

4. **Self-change endpoint moves to `POST /api/v1/admin/password/change` (NOT `/api/v1/auth/admin/password/change` from §5.6).** Rationale: `CookieBearerTokenResolver.ADMIN_PATH_PATTERN = "/api/v1/admin/**"` (PR 4) — only that prefix triggers admin-cookie selection. `/api/v1/auth/admin/**` selects the *shared* cookie (a member token), so the spec-wording path would silently 401 for any admin trying to change their own password. Two ways to fix: (a) widen the resolver pattern to include `/api/v1/auth/admin/password/**`, or (b) move the path under the prefix the resolver already covers. We pick (b) — minimal blast radius (no PR 4 surface change), matches the architectural rule "admin operations live at `/api/v1/admin/**`; `/api/v1/auth/admin/**` is the credential-handshake namespace (login/logout)". URL rule already at SecurityConfig.java:98 (`/api/v1/admin/**` → ROLE_ADMIN) covers it.

5. **Temp password generator: 12 chars, mandatory-class.** Spec example says "랜덤 8자" — example, not requirement. 8 chars from a 64-char alphabet is ~48 bits; 12 chars from the same alphabet is ~72 bits. Generation cost identical, attack cost meaningfully different, and the temp travels through low-trust channels (Slack DM per §5.6) where it may sit briefly in transit / device clipboard. We use 12 chars from `[A-Za-z0-9]` plus a guarantee of at least one upper, one lower, one digit, and one symbol from `!@#$%^&*` (post-shuffle). Generated server-side via `SecureRandom`, returned exactly once in the create/reset response, never persisted plain. Hash via the existing PR 5 `BCryptPasswordEncoder(12)` bean.

6. **`PATCH /administrators/{id}` scope = nickname only.** The Administrator entity is intentionally narrow (role/grantedBy/grantedAt/revokedAt). Email is the UserAccount identity (UNIQUE) — changing it across providers is a separate flow we'd do via account merge / re-invite, out of scope. Role can't be changed (spec: "role 변경 등은 제한"). `granted_*` is audit. The only operationally useful PATCH target is the Member's `nickname` (attached to ProfileData). If the target Administrator has no Member yet (created with `includeMemberProfile=false`), PATCH responds `409 Conflict` with `MEMBER_PROFILE_REQUIRED` — caller must first POST a member-profile attach (we add `POST /administrators/{id}/member-profile` in this PR; small, reuses `MemberSignService.getMemberOrCreate`).

7. **List response shape — flat composite.** Each row carries Administrator identity + UserAccount basics + Member basics (when present): `administratorId`, `role`, `grantedAt`, `grantedByAdministratorId`, `revokedAt`, `userAccountId`, `email`, `lastLoginAt`, `mustChangePassword`, `memberId` (nullable), `nickname` (nullable). Filter via query params: `role` (`SUPER_ADMIN|ADMIN`, optional), `includeRevoked` (default `false`). Sort: `grantedAt DESC`. **No pagination** for MVP — admin count <50; if it grows, add `Pageable`. List query lives in service via 3 repository calls (`findAll` from `AdministratorRepository` + bulk `findAllById` on `UserAccountRepository` + bulk `findAllByUserAccountIdIn` on `MemberRepository`); no JPA join — keeps cross-context boundary clean (Administration BC reads its own table only).

8. **`POST /administrators` always optionally creates Member.** Request body `includeMemberProfile` (default `true`). When `true`, we create `UserAccountData` (`createForLocalWithMandatoryChange`) → `AdministratorData.createAdmin(userId, grantedById)` → `MemberData` via `MemberSignService` (with a new local-account-aware path; see Decision 9). When `false`, only UserAccount + Administrator are created; the admin can later attach via the new `POST /administrators/{id}/member-profile`. Default `true` because admins need Member access to the platform itself (partyroom presence, etc.), per `AdminLoginService.java:80-82`'s lazy-create comment.

9. **`MemberSignService` refactor — extract `getOrCreateMemberFor(UserAccountData ua)`.** Reusing today's `getMemberOrCreate(email, providerType)` for the invite path has a hidden side effect: `MemberSignService.java:57` calls `userAccount.recordLogin()` *unconditionally*, bumping `last_login_at` of the freshly-invited admin to "now" — which is wrong (they have not logged in yet) and pollutes the list-endpoint `lastLoginAt` field. We extract the Member-only side effects into `getOrCreateMemberFor(UserAccountData ua)` (lookup-or-create Member, no `recordLogin`). The existing `getMemberOrCreate(email, providerType)` becomes a thin wrapper: lookup UA → `recordLogin` → delegate to `getOrCreateMemberFor`. Same JavaDoc notes that the `createForSocial` fallback in the wrapper still throws on LOCAL — invite path uses the extracted method directly with a UA the caller has already saved. PR 6 owns this refactor (small, blast radius confined to login path which already passes-through the same logic).

10. **`UserAccountData` mutations added in this PR (minimal API surface, intent-named):** `createForLocalWithMandatoryChange(userId, email, passwordHash)` — factory mirroring `createForLocal` but with `mustChangePassword=true`; `completePasswordChange(String newHash)` — caller has just completed their own password change, so set new hash AND clear the must-change flag in one call (used by self-change endpoint); `requirePasswordChange(String newHash)` — admin-driven reset, sets new hash AND `mustChangePassword=true` (used by reset-password endpoint). Names are intent-shaped (caller's purpose), not state-shaped (what fields they touch) — avoids the side-effect-hiding gotcha of e.g. `setPasswordHash`. The existing `replacePlaceholderCredentials(email, hash)` (V5 seed) does NOT touch the flag — operator-chosen pwd, default 0 is correct.

11. **Last-super-admin protection — application-level guard, NOT DB.** The functional unique index `uk_administrator_super_admin` (V5) prevents creating a *second* SUPER_ADMIN; it does NOT prevent revoking the only one. Service-side check: a SUPER_ADMIN cannot revoke themselves; AND `revoke(superAdmin)` returns 409 if they're the last unrevoked super admin (always true at MVP — single super admin invariant). Similarly, `reset-password` on a SUPER_ADMIN by anyone other than themselves is allowed (a super admin has only themselves to call it with) — but the spec calls this out only for self-change, so `reset-password` for SUPER_ADMIN is permitted but logged. Self-revoke prevention applies regardless of role. **Race-window note:** two concurrent revoke calls against two distinct super-admins could each see `count=2` and both succeed, leaving zero. Vanishingly improbable at MVP (single super admin always; no concurrent revoke targets), but a future PR can add `@Lock(LockModeType.PESSIMISTIC_WRITE)` on the count query or move the guard to a DB CHECK constraint.

12. **Revoke is one-way for MVP.** No `un-revoke` endpoint. If operator needs to restore: DB intervention or new admin invite. Spec doesn't mention restore, and the audit story (granted_at/revoked_at) is cleaner if revoke is monotonic. If the need arises, we add `POST /administrators/{id}/restore` in a follow-up — the entity already supports it (just clear `revokedAt`).

13. **Audit logging — SLF4J only for PR 6; DB audit deferred to PR 12.** `user_activity_log` is added in PR 12 (V10 migration). PR 6 endpoints emit structured `log.info`/`log.warn` at every state mutation: `admin_management.create user_id=X actor_super_admin_id=Y`, `admin_management.revoke target_id=X actor_id=Y`, `admin_management.reset_password target_id=X actor_id=Y`, `admin_password.self_change user_id=X`. PR 12 will replay these into rows.

14. **Email duplicate detection — DB unique violation + early lookup.** `user_account` has `UNIQUE KEY uk_user_account_email (email)` (V4). Service does `userAccountRepository.findByEmail(email).isPresent()` early-check returning `409 Conflict EMAIL_ALREADY_REGISTERED` for clean error UX, AND wraps the `save` in a try/catch for `DataIntegrityViolationException` as defense against TOCTOU. Concurrent admin-create races are vanishingly rare (super admin singleton, low rate) but the catch is one line.

15. **DTOs use Lombok-Builder records or `@Builder`-classes following the AdminAuth precedent.** `AdminLoginResponse` (PR 4) is a `@Builder record`; we mirror it for the new responses. Requests use `@Getter @NoArgsConstructor @AllArgsConstructor` classes with `@Valid`/Bean Validation per PR 4's `AdminLoginRequest`. No new conventions invented.

16. **Self password change response shape — no token rotation.** `POST /api/v1/admin/password/change` returns `204 No Content`. We do NOT mint a new access token (the JWT was already valid before the call; rotation would force a re-login UX). The current AdminAccessToken stays alive until its own TTL ends. The mustChangePassword flag flip happens DB-side; the *next* login sees it cleared.

17. **Plan does NOT introduce a separate `feature/admin-pr6` branch.** Continue on `feature/admin-auth-iam-schema` (matches PR 0–5 convention). PR 5 HEAD: `9eff34cf` (`test(security): fix flaky postWithValidCsrf_passesGate via csrf() post-processor (PR 5)`). PR 6 builds atop.

**Lessons applied from PR 0 / 1 / 2 / 3 / 4 / 5:**
- Mechanical changes (DTOs, controller wiring, derived repository queries) → `sonnet`. Touch-multiple-modules / decision-laden changes (V13 migration choice, login flow, service coordination) → `opus`.
- Method security from PR 5 means `@PreAuthorize` annotations actually enforce in production — no need to re-prove the gate in this PR's tests; instead, reuse the §5.7.1 parameterized regression matrix and ADD rows for the new endpoints.
- CSRF Option A from PR 5 covers the new POSTs automatically (matcher: state-changing under `/api/v1/admin/**`). PR 6's POSTs need no extra CSRF wiring; tests assert that missing token still 403s.
- Cookie isolation from PR 5 — the existing `AdminCookieIsolationIntegrationTest` already covers `/api/v1/admin/**` paths. PR 6's `/api/v1/admin/system/administrators/**` benefits automatically; we add one row to the matrix.
- `AbstractAdminWebMvcTest` extension pattern: when adding a new admin controller to the `@WebMvcTest` slice list, we MUST also add the corresponding `@MockBean` for its service collaborators or method security will fail to wire. PR 5 plan documented this; PR 6 follows.
- Subagent-driven workflow: each task is one self-contained dispatch.
- TDD where feasible — service tests first (where logic lives), controller tests for HTTP shape and security, integration tests for end-to-end flows that span filter chain.
- Atomic commits per task EXCEPT where listed in the "Atomic commit groupings" table below.
- `git status` clean before each task. Commits are tight and revertible.

**Branching:** Continue on `feature/admin-auth-iam-schema`. PR 5 HEAD: `9eff34cf`. PR 6 builds on top.

**Out of scope (deferred):**
- §5.5.5 lockout-clear endpoint (`DELETE .../lockout`) — needs persistent lockout state first. Future PR.
- Hard delete of administrator row — revoke is the lifecycle ending state.
- `un-revoke` / restore endpoint — Decision 12.
- Pagination on list — Decision 7.
- DB audit log — PR 12.
- Filter-level enforcement of `must_change_password` — Decision 3.
- JWT claim for must-change — Decision 3.
- `RoleHierarchy` (we keep dual-authority issuance from PR 4).
- Admin self-update of email/profile-image — out of F-1 scope.
- Bulk operations (bulk revoke, bulk invite) — not in spec.
- Admin invite via signed token email — spec uses temp-password-on-create model; email infra not present.

---

## Atomic commit groupings

Per-task commits are the default. The following groups MUST land as a single commit so the tree stays green:

| Group | Tasks | Reason |
|---|---|---|
| **G1: V13 + UserAccountData mutations + factory** | Tasks 1 + 2 | V13 adds the column. `UserAccountData` adds factory + mutations referencing the column. Independently the column-without-mutations (Task 1 alone) is dead weight; the mutations-without-column (Task 2 alone) fail to flush at boot because Hibernate sees a field with no DDL. **Single commit.** |
| **G2: AdminLoginService + AdminAuthResult + AdminLoginResponse** | Tasks 11 + 12 | Adding `mustChangePassword` to `AdminAuthResult` (immutable record) breaks `AdminAuthController`'s call site; adding it to the response without sourcing it from the service leaves it null. Both shifts must land together. **Single commit.** |

Within each group:
- Per-task step lists remain a checklist (write code, run tests, verify behavior).
- **Skip the `git commit` step at the end of each task in the group.**
- Single combined commit at the end of the group's last task with the message specified there.

Tasks not listed in a group commit individually (default).

---

## Hard precondition (verify BEFORE Task 1)

PR 6 builds on PR 5 (HEAD `9eff34cf`). Before Task 1:

- [ ] **Step 1: Confirm PR 5 is on HEAD**

```bash
git log --oneline -1
```

Expected: `9eff34cf test(security): fix flaky postWithValidCsrf_passesGate via csrf() post-processor (PR 5)`. (Subsequent unrelated commits on `feature/admin-auth-iam-schema` are fine, but the file paths and line numbers in this plan are anchored at the PR 5 HEAD.)

- [ ] **Step 2: Confirm working tree is clean**

```bash
git status -s
```

Expected: empty output. Any dirty file → STOP and ask.

- [ ] **Step 3: Confirm `administrator` table exists in V5 (no V13 yet)**

```bash
ls app/src/main/resources/db/migration/
```

Expected: includes `V5__create_administrator.sql` and does NOT include any `V13_*` file. If V13 already exists → STOP, the plan's premise is wrong.

- [ ] **Step 4: Confirm `must_change_password` is NOT a known UserAccountData field**

```bash
grep -n "must_change_password\|mustChangePassword" user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java
```

Expected: empty output. If matches → STOP.

- [ ] **Step 5: Confirm administrator-management endpoints don't exist yet**

```bash
grep -rn "/api/v1/admin/system/administrators\|AdministratorManagementController\|AdministratorManagementService" app/src/main 2>/dev/null
```

Expected: empty output.

- [ ] **Step 6: Confirm `@adminAuth.canManageAdmins()` is wired (PR 5 deliverable)**

```bash
grep -n "canManageAdmins" common/src/main/java/com/pfplaybackend/api/common/config/security/authorization/AdminAuthorizationSpEL.java
```

Expected: 1 method declaration `public boolean canManageAdmins() { return isSuperAdmin(); }`. If missing → STOP, PR 5 invariant violated.

- [ ] **Step 7: Build current HEAD as a baseline**

Use the JAVA_HOME prefix per `reference_pfplay_platform_jdk.md`:

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew clean build
```

Expected: BUILD SUCCESSFUL. Any failure → reconcile before starting PR 6 — we do not introduce changes on a broken baseline.

---

## Verified codebase facts (read once, applied throughout)

- **`SecurityConfig.java`** (`common/.../SecurityConfig.java:88-101`) URL rule order: `/error`, `/api/v1/auth/oauth/**`, `/api/v1/auth/admin/login`, member/guest sign, `/actuator/health`, `/ws/**`, swagger paths → permitAll; `/api/v1/admin/system/**` → SUPER_ADMIN; `/api/v1/admin/avatar/**` → SUPER_ADMIN; `/api/v1/admin/**` → ADMIN; `/api/v1/auth/admin/**` → authenticated; `/api/**` → authenticated; default denyAll. PR 6 adds NO new URL rules — `/api/v1/admin/system/administrators/**` falls under the SUPER_ADMIN catch-all (line 96) and `/api/v1/admin/password/change` falls under the ADMIN catch-all (line 98).

- **`CookieBearerTokenResolver.ADMIN_PATH_PATTERN`** (`common/.../CookieBearerTokenResolver.java:17`) = `/api/v1/admin/**`. Picks admin cookie ONLY for that prefix. `/api/v1/auth/admin/**` falls back to shared cookie. → Decision 4.

- **`AdminAuthorizationSpEL`** (`common/.../authorization/AdminAuthorizationSpEL.java:18-64`) bean `@Component("adminAuth")`. Methods needed by PR 6: `canManageAdmins()` (= `isSuperAdmin()`, line 32-34) for super-admin endpoints; `isAdmin()` (line 24-26) for self password change. Both present from PR 5.

- **`AdministratorData`** (`app/.../administration/domain/entity/data/AdministratorData.java:21-81`): JPA entity with factories `createSuperAdmin(userAccountId)` and `createAdmin(userAccountId, grantedById)`, both setting `grantedAt = LocalDateTime.now()`. Mutator `revoke()` is idempotent (line 71-76). No `restore()` — Decision 12.

- **`AdministratorRepository`** (`app/.../administration/adapter/out/persistence/AdministratorRepository.java:9-13`): JpaRepository with `findByUserAccountId`, `findFirstByRoleAndRevokedAtIsNull`, `existsByUserAccountId`. PR 6 adds: `findAllByOrderByGrantedAtDesc()`, `findAllByRoleAndRevokedAtIsNullOrderByGrantedAtDesc(AdminRole)` (used for last-super-admin guard), and `countByRoleAndRevokedAtIsNull(AdminRole)`.

- **`AdminRole`** (`app/.../administration/domain/value/AdminRole.java`): enum `SUPER_ADMIN`, `ADMIN`. Stored as VARCHAR(32) (V5 DDL line 161).

- **`UserAccountData`** (`user/.../domain/entity/data/UserAccountData.java:24-105`): `@EmbeddedId UserId userId`, `email` (UNIQUE in V4), `providerType` (VARCHAR(16)), `passwordHash` (length 255, nullable for social), `lastLoginAt`, `withdrawnAt`. Factories: `createForSocial`, `createForLocal`. Mutations: `recordLogin`, `withdraw`, `replacePlaceholderCredentials` (V5 seed only). PR 6 adds: a new column `mustChangePassword` (V13), factory `createForLocalWithMandatoryChange`, mutations `completePasswordChange`, `requirePasswordChange`.

- **`UserAccountRepository`** (`user/.../adapter/out/persistence/UserAccountRepository.java`): has `findByEmailAndProviderType` AND already has `findByEmail(String email)`. **Decision (closed by Task 3):** use the inherited `JpaRepository.findAllById(Iterable<UserId>)` for bulk loading by PK — `UserId` is the `@EmbeddedId`, so no derived method is needed. No changes to `UserAccountRepository` for PR 6.

- **`MemberData`** (`user/.../domain/entity/data/MemberData.java:41`): factories include `createForUserAccount(userAccountId)`. `MemberRepository.findByUserAccountId(Long)`. We need `findAllByUserAccountIdIn(Collection<Long>)` for bulk list (added in Task 3). **Important:** `MemberData.profileData` is a `@OneToOne` cascade-ALL association — `member.getProfileData()` returns the ProfileData entity directly (no separate repo fetch needed). The plan's service code calls `member.getProfileData().updateNickname(nickname)` from within an `@Transactional` boundary; LAZY init is fine because the call is inside the transaction. **Do NOT inject `UserProfileRepository`** — the plan's earlier pseudocode was wrong about needing it.

- **`MemberSignService`** (`user/.../application/service/MemberSignService.java:51-61`): `@Transactional getMemberOrCreate(email, providerType)` — `findByEmailAndProviderType`-or-`createForSocial`-via-`orElseGet`. → Decision 9. PR 6 calls it AFTER persisting a fresh `UserAccountData` for LOCAL — `findByEmailAndProviderType(email, LOCAL)` then succeeds and the social-only fallback never fires.

- **`PasswordEncoder`** (`common/.../SecurityConfig.java:142-145`) bean = `BCryptPasswordEncoder(12)`. Used by `AdminLoginService` for verify; PR 6 uses it for hash on create / reset / self-change.

- **`AdminLoginService`** (`app/.../auth/application/service/AdminLoginService.java:34-118`): rate-limit → email lookup → password verify → administrator lookup + revoked check → MemberSignService.getMemberOrCreate → mint admin/shared tokens → return `AdminAuthResult`. PR 6 inserts a `mustChangePassword` read after password verify (line 65) and threads through the result.

- **`AdminAuthResult`** (`app/.../auth/application/dto/result/AdminAuthResult.java`): immutable record. PR 6 adds `boolean mustChangePassword` field.

- **`AdminLoginResponse`** (`app/.../auth/adapter/in/web/payload/response/AdminLoginResponse.java:1-14`): `@Builder record(tokenType, expiresIn, issuedAt, role)`. PR 6 adds `boolean mustChangePassword`.

- **`AdminAuthController`** (`app/.../auth/adapter/in/web/AdminAuthController.java:31-79`): `/api/v1/auth/admin` mapping; login + logout. PR 6 does NOT add password-change to this controller (Decision 4) — new controller `AdminPasswordController` handles it.

- **`AdminCookieWriter` + `SharedSessionCookieWriter`** (`common/.../jwt/`): write/clear admin/shared cookies. PR 6 reuses; no changes.

- **`AbstractAdminWebMvcTest`** (`app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java:22-48`): `@WebMvcTest` slice with `@Import` for `SharedMethodSecurityConfig` + `AdminAuthorizationSpEL`. `@MockBean`: services + `UserAccountRepository`, `JwtDecoder`, `JwtService`, `JwtProperties`, cookie writers. PR 6: extend `@WebMvcTest` controller list to include the new `AdministratorManagementController` and `AdminPasswordController`; add their service collaborators as `@MockBean`s.

- **`AdminEndpointSecurityTest`** (PR 4 + extended PR 5): parameterized matrix of `(role, path) → status`. PR 6 adds rows for new endpoints. Path: `app/src/test/java/com/pfplaybackend/api/admin/security/AdminEndpointSecurityTest.java` (verify exact at task time).

- **Flyway migrations** (`app/src/main/resources/db/migration/`): V1, V2, V3, V4, V5, V9. Next slot for PR 6 is V13. (V6/V7/V8/V10/V11/V12 reserved for later PRs per roadmap §9.1; V13 is the next unreserved.)

- **PR 5 CSRF**: `AdminCsrfRequestMatcher` matches state-changing methods on `/api/v1/admin/**` and excludes `/api/v1/auth/admin/login`. `/api/v1/admin/password/change` is therefore CSRF-protected automatically. `/api/v1/admin/system/administrators/**` POST/PATCH likewise.

- **`ExceptionCreator`** (`common/.../exception/ExceptionCreator.java:5-21`): `static AbstractHTTPException create(DomainException)` switches on `ErrorType` enum (`BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, TOO_MANY_REQUESTS`) and returns the matching HTTP exception. `DomainException` interface requires `(errorCode, message, errorType)` triple. `AdminAuthException` enum (`app/.../auth/domain/exception/AdminAuthException.java:8-22`) is the precedent: codes shaped as `AUTH_ADMIN_001`. PR 6 introduces `AdministratorManagementException` following the SAME pattern: `implements DomainException`, codes `ADM_MGT_001..ADM_MGT_007`, ErrorType-typed.

- **`CustomJwtAuthenticationToken`** (`common/.../security/jwt/CustomJwtAuthenticationToken.java:14-50`): exposes `getUserId() : UserId`, `getEmail()`, `getAuthorityTier()`. Existing precedent for "current user from SecurityContext": `LogoutService.java:20-21` casts `Authentication` to `CustomJwtAuthenticationToken` then reads `token.getUserId()`. PR 6 codifies this pattern in a small helper bean `AdminContext` (Task 4b) — services use `adminContext.currentUserId()` and `adminContext.currentAdministratorId()`.

- **`AbstractIntegrationTest`** (`app/src/test/java/com/pfplaybackend/api/common/AbstractIntegrationTest.java`): project Testcontainers base. Repository tests + integration tests extend this (precedent: `IamRepositoryIntegrationTest`, `AdminCookieIsolationIntegrationTest`, `AdminAuthorizationMatrixTest`). PR 6 uses this base for repo + integration tests — NOT `@DataJpaTest`.

- **`AdminAuthorizationMatrixTest`** (`app/src/test/java/com/pfplaybackend/api/common/config/security/AdminAuthorizationMatrixTest.java:42-112`): the §5.7.1 parameterized matrix. Probes deliberately use NON-EXISTENT paths (`__test_probe__`) to isolate URL-level gating from controller behavior. PR 6 does NOT add real-endpoint rows here — the matrix is path-prefix-shaped and PR 6 introduces no new prefix. Per-controller tests cover the new endpoints' security.

- **`ProfileData.updateBio(String nickname, String introduction)`** (`user/.../domain/entity/data/ProfileData.java:93-99`): existing nickname mutation pathway. Requires both nickname AND introduction — overwrites both. For the PR 6 PATCH-nickname-only flow we add a thin `Bio.updateNickname(String)` + `ProfileData.updateNickname(String)` that preserves the current introduction (Task 8a).

- **Other `new AdminAuthResult(...)` call sites** (besides `AdminLoginService.java:109`): `app/src/test/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthControllerTest.java:57, :75`. Both must be updated when the record adds the 7th field (Task 12).

---

## File structure

**Will create:**
- `app/src/main/resources/db/migration/V13__add_must_change_password_to_user_account.sql` — V13 migration
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdministratorManagementController.java` — F-1 controller (5 endpoints + 1 helper)
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPasswordController.java` — self-change controller (1 endpoint)
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/CreateAdministratorRequest.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/UpdateAdministratorRequest.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/AttachMemberProfileRequest.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/ChangeAdminPasswordRequest.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/CreateAdministratorResponse.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/AdministratorView.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/AdministratorListResponse.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/ResetPasswordResponse.java`
- `app/src/main/java/com/pfplaybackend/api/administration/application/AdminContext.java` — current-user resolver helper (Task 4b)
- `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementService.java`
- `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminPasswordService.java`
- `app/src/main/java/com/pfplaybackend/api/administration/application/util/TempPasswordGenerator.java`
- `app/src/main/java/com/pfplaybackend/api/administration/application/util/AdminPasswordPolicy.java`
- `app/src/main/java/com/pfplaybackend/api/administration/domain/exception/AdministratorManagementException.java`
- `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdministratorManagementControllerTest.java`
- `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPasswordControllerTest.java`
- `app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdministratorRepositoryIntegrationTest.java`
- `app/src/test/java/com/pfplaybackend/api/administration/application/AdminContextTest.java`
- `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementServiceTest.java`
- `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminPasswordServiceTest.java`
- `app/src/test/java/com/pfplaybackend/api/administration/application/util/TempPasswordGeneratorTest.java`
- `app/src/test/java/com/pfplaybackend/api/administration/application/util/AdminPasswordPolicyTest.java`
- `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminPasswordChangeIntegrationTest.java` — end-to-end: login (must_change=1) → self-change → login again (must_change cleared)

**Will modify:**
- `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java` — add `mustChangePassword` field + factory + mutations (Task 2)
- `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/ProfileData.java` — add `updateNickname(String)` mutator (Task 5c)
- `user/src/main/java/com/pfplaybackend/api/user/domain/value/Bio.java` — add `updateNickname(String)` mutator (Task 5c)
- `user/src/main/java/com/pfplaybackend/api/user/application/service/MemberSignService.java` — extract `getOrCreateMemberFor(UserAccountData)` (Task 5b)
- `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/UserAccountRepository.java` — add `findByEmail` + bulk loaders if missing (Task 3)
- `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository.java` — add `findAllByUserAccountIdIn` if missing (Task 3)
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdministratorRepository.java` — add list/count queries (Task 3)
- `app/src/main/java/com/pfplaybackend/api/auth/application/dto/result/AdminAuthResult.java` — add `mustChangePassword` (Task 11)
- `app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/payload/response/AdminLoginResponse.java` — add `mustChangePassword` (Task 12)
- `app/src/main/java/com/pfplaybackend/api/auth/application/service/AdminLoginService.java` — read flag, thread to result (Task 11)
- `app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthController.java` — propagate flag from result to response (Task 12)
- `app/src/test/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthControllerTest.java` — fix 2 `new AdminAuthResult(...)` constructions at lines 57, 75 (Task 12)
- `app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java` — extend `@WebMvcTest` list and `@MockBean`s incrementally (Tasks 7 + 14)

---

## Chunk 1 — Foundation: V13 migration, UserAccountData mutations, repository extensions, password utilities

### Task 1: V13 migration — `must_change_password` column on `user_account` (Group G1)

**Files:**
- Create: `app/src/main/resources/db/migration/V13__add_must_change_password_to_user_account.sql`

- [ ] **Step 1: Write the migration**

```sql
-- =====================================================
-- V13: Add must_change_password flag to user_account
--
-- Drives §5.6 forced password change flow. New admins (via POST /administrators)
-- and reset-password targets land with must_change_password=1; the flag clears
-- on successful self-change. Default 0 for all existing rows.
--
-- The V5-seeded super admin (user_id=1) keeps the default 0 — operator-supplied
-- ADMIN_SEED_PASSWORD is operator-chosen, so no forced-change is appropriate.
-- =====================================================

ALTER TABLE user_account
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT 0
        COMMENT 'Forces self-service password change at next login (§5.6).';
```

- [ ] **Step 2: Verify Flyway picks it up via dry-run config check**

```bash
ls app/src/main/resources/db/migration/V13*
```

Expected: `V13__add_must_change_password_to_user_account.sql` exists and is the only V13 file.

- [ ] **Step 3: Skip commit — Group G1, deferred to Task 2.**

---

### Task 2: `UserAccountData` field + factory + mutations (Group G1)

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java`
- Test: `user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountDataTest.java` (new or extend existing)

- [ ] **Step 1: Write the failing tests**

Add to (or create) `UserAccountDataTest.java`:

```java
@Test
void createForLocalWithMandatoryChange_setsFlagTrue() {
    UserAccountData ua = UserAccountData.createForLocalWithMandatoryChange(
            new UserId(123L), "x@y.z", "hash");
    assertThat(ua.isMustChangePassword()).isTrue();
    assertThat(ua.getProviderType()).isEqualTo(ProviderType.LOCAL);
    assertThat(ua.getPasswordHash()).isEqualTo("hash");
}

@Test
void createForLocal_keepsFlagFalseByDefault() {
    UserAccountData ua = UserAccountData.createForLocal(
            new UserId(123L), "x@y.z", "hash");
    assertThat(ua.isMustChangePassword()).isFalse();
}

@Test
void completePasswordChange_clearsFlag() {
    UserAccountData ua = UserAccountData.createForLocalWithMandatoryChange(
            new UserId(123L), "x@y.z", "old");
    ua.completePasswordChange("new");
    assertThat(ua.getPasswordHash()).isEqualTo("new");
    assertThat(ua.isMustChangePassword()).isFalse();
}

@Test
void requirePasswordChange_setsHashAndFlag() {
    UserAccountData ua = UserAccountData.createForLocal(
            new UserId(123L), "x@y.z", "old");
    ua.requirePasswordChange("temp");
    assertThat(ua.getPasswordHash()).isEqualTo("temp");
    assertThat(ua.isMustChangePassword()).isTrue();
}

@Test
void replacePlaceholderCredentials_doesNotTouchMustChangeFlag() {
    UserAccountData ua = UserAccountData.createForLocal(
            new UserId(1L), "__SUPER_ADMIN_PLACEHOLDER_EMAIL__", "__placeholder__");
    ua.replacePlaceholderCredentials("ops@pfplay.xyz", "real-bcrypt");
    assertThat(ua.isMustChangePassword()).isFalse();
}
```

- [ ] **Step 2: Run tests — expect compile failure (factory + mutations don't exist yet)**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :user:test --tests "*UserAccountDataTest*"
```

Expected: COMPILE failure — `createForLocalWithMandatoryChange`, `completePasswordChange`, `requirePasswordChange`, `isMustChangePassword` not found.

- [ ] **Step 3: Add field + factory + mutations**

Edit `UserAccountData.java`:

```java
@Column(name = "must_change_password", nullable = false)
private boolean mustChangePassword;

@Builder(access = AccessLevel.PRIVATE)
private UserAccountData(UserId userId, String email, ProviderType providerType,
                        String passwordHash, LocalDateTime lastLoginAt,
                        LocalDateTime withdrawnAt, boolean mustChangePassword) {
    this.userId = userId;
    this.email = email;
    this.providerType = providerType;
    this.passwordHash = passwordHash;
    this.lastLoginAt = lastLoginAt;
    this.withdrawnAt = withdrawnAt;
    this.mustChangePassword = mustChangePassword;
}

// existing factories unchanged — leave them passing default false through Lombok builder

public static UserAccountData createForLocalWithMandatoryChange(
        UserId userId, String email, String passwordHash) {
    return UserAccountData.builder()
        .userId(userId)
        .email(email)
        .providerType(ProviderType.LOCAL)
        .passwordHash(passwordHash)
        .mustChangePassword(true)
        .build();
}

public void completePasswordChange(String newHash) {
    Objects.requireNonNull(newHash, "newHash must not be null");
    this.passwordHash = newHash;
    this.mustChangePassword = false;
}

public void requirePasswordChange(String newHash) {
    Objects.requireNonNull(newHash, "newHash must not be null");
    this.passwordHash = newHash;
    this.mustChangePassword = true;
}
```

Add `@Getter` is already on the class — `isMustChangePassword()` is generated by Lombok for the `boolean` field.

- [ ] **Step 4: Run tests — expect PASS**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :user:test --tests "*UserAccountDataTest*"
```

Expected: all five tests PASS.

- [ ] **Step 5: Run full module build to surface any flush/Hibernate breakage**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :user:build :app:build
```

Expected: BUILD SUCCESSFUL (Testcontainers may exercise the V13 migration during integration tests — they should pass).

- [ ] **Step 6: G1 commit**

```bash
git add app/src/main/resources/db/migration/V13__add_must_change_password_to_user_account.sql \
        user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java \
        user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountDataTest.java
git commit -m "feat(user): add must_change_password flag + factory/mutations on UserAccountData (PR 6)"
```

---

### Task 3: Repository extensions

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdministratorRepository.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/UserAccountRepository.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository.java`

- [ ] **Step 1: Write integration tests for derived queries**

Create `app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdministratorRepositoryIntegrationTest.java`. Extends `AbstractIntegrationTest` (project Testcontainers base — precedent: `IamRepositoryIntegrationTest`). Do NOT use `@DataJpaTest` — the project's repos are exercised against MySQL Testcontainers, and `@DataJpaTest` would fall back to H2 and miss the functional unique index on V5 administrator.

```java
class AdministratorRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired AdministratorRepository repository;

    @Test
    void findAllByOrderByGrantedAtDesc_returnsNewestFirst() {
        // seed 3 administrators with varied granted_at and one revoked
        // assertThat(repository.findAllByOrderByGrantedAtDesc()).extracting(...).containsExactly(...);
    }

    @Test
    void countByRoleAndRevokedAtIsNull_excludesRevoked() {
        // seed: 1 SUPER_ADMIN active, 1 SUPER_ADMIN revoked, 2 ADMIN active
        // assertThat(repository.countByRoleAndRevokedAtIsNull(AdminRole.SUPER_ADMIN)).isEqualTo(1);
    }
}
```

(Concrete seed code mirrors the project's test fixture style — check `AbstractAdminWebMvcTest`'s neighbor tests.)

- [ ] **Step 2: Run — expect compile failure (methods missing)**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdministratorRepositoryTest*"
```

- [ ] **Step 3: Extend repositories**

`AdministratorRepository.java`:

```java
public interface AdministratorRepository extends JpaRepository<AdministratorData, Long> {
    Optional<AdministratorData> findByUserAccountId(Long userAccountId);
    Optional<AdministratorData> findFirstByRoleAndRevokedAtIsNull(AdminRole role);
    boolean existsByUserAccountId(Long userAccountId);

    List<AdministratorData> findAllByOrderByGrantedAtDesc();
    long countByRoleAndRevokedAtIsNull(AdminRole role);
}
```

`UserAccountRepository.java` — add (verify it doesn't already exist):

```java
Optional<UserAccountData> findByEmail(String email);
List<UserAccountData> findAllByUserIdIn(Collection<UserId> userIds);
```

`MemberRepository.java` — add (verify):

```java
List<MemberData> findAllByUserAccountIdIn(Collection<Long> userAccountIds);
```

- [ ] **Step 4: Run — expect PASS**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdministratorRepositoryTest*"
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdministratorRepository.java \
        user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/UserAccountRepository.java \
        user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdministratorRepositoryIntegrationTest.java
git commit -m "feat(administration): repository extensions for administrator list + bulk loads (PR 6)"
```

---

### Task 4: `TempPasswordGenerator` + `AdminPasswordPolicy`

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/util/TempPasswordGenerator.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/util/AdminPasswordPolicy.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/application/util/TempPasswordGeneratorTest.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/application/util/AdminPasswordPolicyTest.java`

- [ ] **Step 1: Write the failing tests**

`TempPasswordGeneratorTest.java`:

```java
class TempPasswordGeneratorTest {

    private final TempPasswordGenerator generator = new TempPasswordGenerator(new SecureRandom(0L));

    @Test
    void generate_is12CharsLong() {
        assertThat(generator.generate()).hasSize(12);
    }

    @Test
    void generate_containsAtLeastOneOfEachClass() {
        for (int i = 0; i < 100; i++) {
            String pwd = generator.generate();
            assertThat(pwd).matches(".*[A-Z].*");
            assertThat(pwd).matches(".*[a-z].*");
            assertThat(pwd).matches(".*[0-9].*");
            assertThat(pwd).matches(".*[!@#$%^&*].*");
        }
    }

    @Test
    void generate_isReasonablyUnique() {
        Set<String> samples = new HashSet<>();
        for (int i = 0; i < 1000; i++) samples.add(generator.generate());
        assertThat(samples).hasSizeGreaterThan(995); // collision practically impossible
    }
}
```

`AdminPasswordPolicyTest.java`:

```java
class AdminPasswordPolicyTest {

    private final AdminPasswordPolicy policy = new AdminPasswordPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
            "Aa1!aaaaaa",       // 10 chars OK
            "P@ssw0rd1234",     // 12 chars OK
            "Xx1!Yy2@Zz3#"      // mixed
    })
    void accepts_validPassword(String pwd) {
        assertThatNoException().isThrownBy(() -> policy.requireValid(pwd));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short!",           // < 10 chars
            "alllowercase1!",   // missing upper
            "ALLUPPERCASE1!",   // missing lower
            "NoDigitsHere!",    // missing digit
            "NoSymbolsHere1"    // missing symbol
    })
    void rejects_weakPassword(String pwd) {
        assertThatThrownBy(() -> policy.requireValid(pwd))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run tests — expect compile failure**

- [ ] **Step 3: Implement**

`TempPasswordGenerator.java`:

```java
package com.pfplaybackend.api.administration.application.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server-side temp password generator for admin invite + reset flows (§5.6).
 * 12-char output; guarantees at least one upper, lower, digit, and symbol.
 * Decision 5 in PR 6 plan: 12 chars (vs spec example "8자") for ~74-bit entropy.
 */
@Component
public class TempPasswordGenerator {

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"; // I, O removed
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz"; // l, o removed
    private static final String DIGIT = "23456789";                 // 0, 1 removed
    private static final String SYMBOL = "!@#$%^&*";
    private static final String ALL = UPPER + LOWER + DIGIT + SYMBOL;
    private static final int LENGTH = 12;

    private final SecureRandom random;

    public TempPasswordGenerator() { this(new SecureRandom()); }
    TempPasswordGenerator(SecureRandom random) { this.random = random; }

    public String generate() {
        List<Character> chars = new ArrayList<>(LENGTH);
        chars.add(pick(UPPER));
        chars.add(pick(LOWER));
        chars.add(pick(DIGIT));
        chars.add(pick(SYMBOL));
        for (int i = 4; i < LENGTH; i++) chars.add(pick(ALL));
        Collections.shuffle(chars, random);
        StringBuilder sb = new StringBuilder(LENGTH);
        chars.forEach(sb::append);
        return sb.toString();
    }

    private Character pick(String alphabet) {
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }
}
```

`AdminPasswordPolicy.java`:

```java
package com.pfplaybackend.api.administration.application.util;

import org.springframework.stereotype.Component;

@Component
public class AdminPasswordPolicy {

    private static final int MIN_LENGTH = 10;

    public void requireValid(String pwd) {
        if (pwd == null || pwd.length() < MIN_LENGTH)
            throw new IllegalArgumentException("password too short");
        if (!pwd.matches(".*[A-Z].*"))
            throw new IllegalArgumentException("password missing uppercase");
        if (!pwd.matches(".*[a-z].*"))
            throw new IllegalArgumentException("password missing lowercase");
        if (!pwd.matches(".*[0-9].*"))
            throw new IllegalArgumentException("password missing digit");
        if (!pwd.matches(".*[!@#$%^&*].*"))
            throw new IllegalArgumentException("password missing symbol");
    }
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*TempPasswordGeneratorTest*" --tests "*AdminPasswordPolicyTest*"
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/application/util/TempPasswordGenerator.java \
        app/src/main/java/com/pfplaybackend/api/administration/application/util/AdminPasswordPolicy.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/util/TempPasswordGeneratorTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/util/AdminPasswordPolicyTest.java
git commit -m "feat(administration): temp password generator + password policy (PR 6)"
```

---

### Task 4b: `AdminContext` — current-user resolver helper

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/AdminContext.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/application/AdminContextTest.java`

Rationale: Tasks 7, 9, 10, 13, 14 all need to read the current authenticated administrator's `userId` (for self-change) or `administratorId` (for `grantedBy` audit / self-revoke check). The existing pattern (`LogoutService.java:20-21`, `PartyContextAspect.java:25-26`) casts `Authentication` to `CustomJwtAuthenticationToken`. We codify it once.

- [ ] **Step 1: Failing tests**

```java
class AdminContextTest {

    private final AdministratorRepository administratorRepository = mock(AdministratorRepository.class);
    private final AdminContext adminContext = new AdminContext(administratorRepository);

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void currentUserId_authenticated_returnsUserId() {
        UserId expected = new UserId(42L);
        SecurityContextHolder.getContext().setAuthentication(stubToken(expected));
        assertThat(adminContext.currentUserId()).isEqualTo(expected);
    }

    @Test
    void currentUserId_anonymous_throws() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("k", "anon", List.of(new SimpleGrantedAuthority("ROLE_ANON"))));
        assertThatThrownBy(adminContext::currentUserId)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void currentAdministratorId_resolvesViaRepository() {
        UserId userId = new UserId(42L);
        AdministratorData admin = mock(AdministratorData.class);
        given(admin.getAdministratorId()).willReturn(7L);
        given(administratorRepository.findByUserAccountId(42L)).willReturn(Optional.of(admin));
        SecurityContextHolder.getContext().setAuthentication(stubToken(userId));

        assertThat(adminContext.currentAdministratorId()).isEqualTo(7L);
    }

    @Test
    void currentAdministratorId_noAdminLink_throws() {
        SecurityContextHolder.getContext().setAuthentication(stubToken(new UserId(42L)));
        given(administratorRepository.findByUserAccountId(42L)).willReturn(Optional.empty());
        assertThatThrownBy(adminContext::currentAdministratorId)
                .isInstanceOf(IllegalStateException.class);
    }

    private static CustomJwtAuthenticationToken stubToken(UserId userId) {
        Jwt jwt = Jwt.withTokenValue("x").header("alg", "none")
                .subject(String.valueOf(userId.getUid())).build();
        return new CustomJwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                userId, "x@y.z", null);
    }
}
```

- [ ] **Step 2: Implement**

```java
package com.pfplaybackend.api.administration.application;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.common.config.security.jwt.CustomJwtAuthenticationToken;
import com.pfplaybackend.api.common.domain.value.UserId;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminContext {

    private final AdministratorRepository administratorRepository;

    public UserId currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof CustomJwtAuthenticationToken token) {
            return token.getUserId();
        }
        throw new IllegalStateException("admin context: no authenticated principal");
    }

    public Long currentAdministratorId() {
        UserId userId = currentUserId();
        AdministratorData admin = administratorRepository.findByUserAccountId(userId.getUid())
                .orElseThrow(() -> new IllegalStateException(
                        "admin context: no administrator row for user_id=" + userId.getUid()));
        return admin.getAdministratorId();
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminContextTest*"
git add app/src/main/java/com/pfplaybackend/api/administration/application/AdminContext.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/AdminContextTest.java
git commit -m "feat(administration): AdminContext helper for current-user resolution (PR 6)"
```

---

## Chunk 2 — List + Create endpoints

### Task 5: `AdministratorManagementException` + DTOs (List/Create)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/exception/AdministratorManagementException.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/CreateAdministratorRequest.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/CreateAdministratorResponse.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/AdministratorView.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/AdministratorListResponse.java`

- [ ] **Step 1: Define the exception enum (mirrors `AdminAuthException`)**

```java
package com.pfplaybackend.api.administration.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum AdministratorManagementException implements DomainException {
    NOT_FOUND("ADM_MGT_001", "어드민을 찾을 수 없습니다.", ErrorType.NOT_FOUND),
    EMAIL_ALREADY_REGISTERED("ADM_MGT_002", "이미 등록된 이메일입니다.", ErrorType.CONFLICT),
    CANNOT_REVOKE_LAST_SUPER_ADMIN("ADM_MGT_003", "마지막 슈퍼어드민은 회수할 수 없습니다.", ErrorType.CONFLICT),
    CANNOT_REVOKE_SELF("ADM_MGT_004", "본인 권한은 회수할 수 없습니다.", ErrorType.CONFLICT),
    MEMBER_PROFILE_REQUIRED("ADM_MGT_005", "멤버 프로필이 먼저 연결되어야 합니다.", ErrorType.CONFLICT),
    INVALID_CURRENT_PASSWORD("ADM_MGT_006", "현재 비밀번호가 일치하지 않습니다.", ErrorType.UNAUTHORIZED),
    INVALID_NEW_PASSWORD("ADM_MGT_007", "비밀번호 정책을 충족하지 않습니다.", ErrorType.BAD_REQUEST);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    AdministratorManagementException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
```

Throwing pattern (matches PR 4 convention): `throw ExceptionCreator.create(AdministratorManagementException.NOT_FOUND);`. `ExceptionCreator.create` returns `AbstractHTTPException` which Spring's exception resolver maps to the right HTTP status.

- [ ] **Step 2: Define request/response DTOs**

`CreateAdministratorRequest.java`:

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateAdministratorRequest {
    @NotBlank @Email @Size(max = 255)
    private String email;

    @NotBlank @Size(max = 64)
    private String nickname;

    private Boolean includeMemberProfile;  // null → service treats as true (default-on); see Decision 8
}
```

> **Lombok primitive default gotcha:** `private boolean includeMemberProfile = true;` looks like a default but `@NoArgsConstructor` + Jackson re-init it to `false` when the field is omitted from the JSON body. Box it (`Boolean`) and let the service treat `null` as the default — matches the project's existing nullable-boolean idiom.

`CreateAdministratorResponse.java`:

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import lombok.Builder;

@Builder
public record CreateAdministratorResponse(
        Long administratorId,
        Long userAccountId,
        Long memberId,           // null when includeMemberProfile=false
        String tempPassword,
        String message
) {}
```

`AdministratorView.java`:

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import com.pfplaybackend.api.administration.domain.value.AdminRole;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record AdministratorView(
        Long administratorId,
        AdminRole role,
        LocalDateTime grantedAt,
        Long grantedByAdministratorId,
        LocalDateTime revokedAt,
        Long userAccountId,
        String email,
        LocalDateTime lastLoginAt,
        boolean mustChangePassword,
        Long memberId,
        String nickname
) {}
```

`AdministratorListResponse.java`:

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import lombok.Builder;

import java.util.List;

@Builder
public record AdministratorListResponse(
        int totalCount,
        List<AdministratorView> items
) {}
```

- [ ] **Step 3: Compile**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/domain/exception/AdministratorManagementException.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/
git commit -m "feat(administration): exception enum + List/Create DTOs (PR 6)"
```

---

### Task 5b: Extract `MemberSignService.getOrCreateMemberFor(UserAccountData)` (refactor)

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/application/service/MemberSignService.java`
- Modify: `user/src/test/java/com/pfplaybackend/api/user/application/service/MemberSignServiceTest.java` (or whatever the existing test file is)

Rationale: see Decision 9 above. Today's `getMemberOrCreate(email, providerType)` calls `recordLogin()` unconditionally — wrong for the invite path. We extract the Member-only side effects so the invite path can skip the login bump.

- [ ] **Step 1: Failing test — `getOrCreateMemberFor` does NOT touch lastLoginAt**

```java
@Test
void getOrCreateMemberFor_existingUserAccount_doesNotRecordLogin() {
    UserAccountData ua = mock(UserAccountData.class);
    given(ua.getUserId()).willReturn(new UserId(42L));
    given(ua.getEmail()).willReturn("a@x");
    given(ua.getProviderType()).willReturn(ProviderType.LOCAL);
    given(memberRepository.findByUserAccountId(42L)).willReturn(Optional.of(memberFixture));

    MemberData result = service.getOrCreateMemberFor(ua);

    verify(ua, never()).recordLogin();
    assertThat(result).isEqualTo(memberFixture);
}

@Test
void getOrCreateMemberFor_noMember_createsOneAndRunsOnboarding() {
    UserAccountData ua = mock(UserAccountData.class);
    given(ua.getUserId()).willReturn(new UserId(42L));
    given(ua.getEmail()).willReturn("a@x");
    given(ua.getProviderType()).willReturn(ProviderType.LOCAL);
    given(memberRepository.findByUserAccountId(42L)).willReturn(Optional.empty());
    // ... mock onboarding side effects

    service.getOrCreateMemberFor(ua);

    verify(ua, never()).recordLogin();
    verify(memberRepository).save(any(MemberData.class));
    verify(playlistSetupPort).createDefaultPlaylist(new UserId(42L));
}

@Test
void getMemberOrCreate_existingUa_stillRecordsLogin() {
    // existing behavior preserved — wrapper bumps last_login_at.
}
```

- [ ] **Step 2: Implement extraction**

```java
@Transactional
public MemberData getOrCreateMemberFor(UserAccountData userAccount) {
    return memberRepository.findByUserAccountId(userAccount.getUserId().getUid())
            .orElseGet(() -> initializeNewMember(userAccount));
}

@Transactional
public MemberData getMemberOrCreate(String email, ProviderType providerType) {
    UserAccountData userAccount = userAccountRepository
            .findByEmailAndProviderType(email, providerType)
            .orElseGet(() -> userAccountRepository.save(
                    UserAccountData.createForSocial(new UserId(), email, providerType)));
    userAccount.recordLogin();
    return getOrCreateMemberFor(userAccount);
}
```

JavaDoc on `getOrCreateMemberFor`: "Looks up the Member for `userAccount`; creates one with onboarding side effects if missing. Does NOT touch `last_login_at` — caller's responsibility (invite paths skip; login paths call `recordLogin` first)."

- [ ] **Step 3: Run + commit**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :user:test --tests "*MemberSignServiceTest*"
git add user/src/main/java/com/pfplaybackend/api/user/application/service/MemberSignService.java \
        user/src/test/java/com/pfplaybackend/api/user/application/service/MemberSignServiceTest.java
git commit -m "refactor(user): extract MemberSignService.getOrCreateMemberFor (PR 6 dep)"
```

---

### Task 5c: `Bio.updateNickname` + `ProfileData.updateNickname` mutators

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/value/Bio.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/ProfileData.java`
- Test: `user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/ProfileDataTest.java` (or `BioTest.java`)

Rationale: existing `Bio.update(String, String)` overwrites both nickname and introduction. PR 6 PATCH-nickname-only flow needs a nickname-only mutator that preserves introduction. Tasks 6, 9 use it.

- [ ] **Step 1: Failing tests**

```java
class BioTest {
    @Test
    void updateNickname_preservesIntroduction() {
        Bio bio = new Bio(new Nickname("old"), "intro-text");
        bio.updateNickname("new");
        assertThat(bio.getNicknameValue()).isEqualTo("new");
        assertThat(bio.getIntroduction()).isEqualTo("intro-text");
    }
}

class ProfileDataTest {
    @Test
    void updateNickname_whenBioPresent_delegates() {
        ProfileData profile = ProfileData.builder()
                .nickname(new Nickname("old"))
                .introduction("intro")
                .build();
        profile.updateNickname("new");
        assertThat(profile.getNicknameValue()).isEqualTo("new");
        assertThat(profile.getIntroduction()).isEqualTo("intro");
    }

    @Test
    void updateNickname_whenBioNull_initializes() {
        ProfileData profile = new ProfileData();  // protected ctor — call via reflection or builder w/ null bio
        profile.updateNickname("new");
        assertThat(profile.getNicknameValue()).isEqualTo("new");
    }
}
```

- [ ] **Step 2: Implement**

`Bio.java`:

```java
public void updateNickname(String nickname) {
    this.nickname = new Nickname(nickname);
}
```

`ProfileData.java`:

```java
public void updateNickname(String nickname) {
    if (this.bio == null) {
        this.bio = new Bio(new Nickname(nickname), null);
    } else {
        this.bio.updateNickname(nickname);
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :user:test --tests "*BioTest*" --tests "*ProfileDataTest*"
git add user/src/main/java/com/pfplaybackend/api/user/domain/value/Bio.java \
        user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/ProfileData.java \
        user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/ProfileDataTest.java \
        user/src/test/java/com/pfplaybackend/api/user/domain/value/BioTest.java
git commit -m "feat(user): add nickname-only mutator on Bio + ProfileData (PR 6 dep)"
```

---

### Task 6: `AdministratorManagementService` — list + create

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementServiceTest.java`

- [ ] **Step 1: Write the failing test for `list`**

```java
@Test
void list_returnsRowsSortedByGrantedAtDesc_includesMemberFieldsWhenPresent() {
    // arrange: 3 administrators; 2 have linked members; 1 doesn't
    // when filterRevoked=false → only active
    // when filterRevoked=true → include revoked
    // verify view fields populated
}

@Test
void list_filtersByRoleWhenProvided() { ... }
```

- [ ] **Step 2: Write the failing test for `create`**

```java
@Test
void create_includeMemberProfileTrue_persistsAllThreeAggregates_returnsTempPassword() {
    given(userAccountRepository.findByEmail("new@x")).willReturn(Optional.empty());
    given(tempPasswordGenerator.generate()).willReturn("Xk9@aB2zCdEf");
    given(passwordEncoder.encode("Xk9@aB2zCdEf")).willReturn("BCRYPT$$$");
    given(userAccountRepository.save(any())).willAnswer(inv -> {
        UserAccountData ua = inv.getArgument(0);
        // simulate id assignment (UserId already set in factory; just return)
        return ua;
    });
    given(administratorRepository.save(any())).willAnswer(inv -> { /* simulate id */ });
    given(memberSignService.getMemberOrCreate("new@x", ProviderType.LOCAL))
            .willReturn(memberFixture);

    CreateAdministratorResponse resp = service.create(
            new CreateAdministratorRequest("new@x", "newbie", true),
            actorAdministratorId);

    assertThat(resp.tempPassword()).isEqualTo("Xk9@aB2zCdEf");
    assertThat(resp.administratorId()).isEqualTo(...);
    assertThat(resp.memberId()).isNotNull();
    verify(userAccountRepository).save(argThat(ua ->
            ua.getProviderType() == ProviderType.LOCAL
                && ua.isMustChangePassword()
                && ua.getPasswordHash().equals("BCRYPT$$$")));
    verify(administratorRepository).save(argThat(a ->
            a.getRole() == AdminRole.ADMIN
                && a.getGrantedByAdministratorId() == actorAdministratorId));
}

@Test
void create_emailAlreadyRegistered_throwsConflict() {
    given(userAccountRepository.findByEmail("dup@x"))
            .willReturn(Optional.of(existingUa));
    assertThatThrownBy(() -> service.create(
            new CreateAdministratorRequest("dup@x", "n", true), 1L))
        .isInstanceOf(CustomRuntimeException.class)
        .extracting("exception")
        .isEqualTo(AdministratorManagementException.EMAIL_ALREADY_REGISTERED);
}

@Test
void create_includeMemberProfileFalse_skipsMemberCreation() { ... }
```

- [ ] **Step 3: Run — expect compile failure**

- [ ] **Step 4: Implement service (list + create skeleton)**

```java
package com.pfplaybackend.api.administration.application.service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdministratorManagementService {

    private final AdministratorRepository administratorRepository;
    private final UserAccountRepository userAccountRepository;
    private final MemberRepository memberRepository;
    private final ProfileRepository profileRepository; // for nickname; verify exact name
    private final MemberSignService memberSignService;
    private final PasswordEncoder passwordEncoder;
    private final TempPasswordGenerator tempPasswordGenerator;

    @Transactional(readOnly = true)
    public AdministratorListResponse list(AdminRole roleFilter, boolean includeRevoked) {
        List<AdministratorData> all = administratorRepository.findAllByOrderByGrantedAtDesc();
        Stream<AdministratorData> stream = all.stream();
        if (roleFilter != null) stream = stream.filter(a -> a.getRole() == roleFilter);
        if (!includeRevoked) stream = stream.filter(a -> !a.isRevoked());
        List<AdministratorData> filtered = stream.toList();

        // bulk-load UserAccount + Member + Profile for nickname
        Set<Long> userAccountIds = filtered.stream()
                .map(AdministratorData::getUserAccountId).collect(Collectors.toSet());
        Map<Long, UserAccountData> uaMap = userAccountRepository
                .findAllById(userAccountIds.stream().map(UserId::new).toList())
                .stream().collect(Collectors.toMap(ua -> ua.getUserId().getUid(), ua -> ua));
        Map<Long, MemberData> memberMap = memberRepository
                .findAllByUserAccountIdIn(userAccountIds)
                .stream().collect(Collectors.toMap(MemberData::getUserAccountId, m -> m));
        // nickname lives on ProfileData → load for memberMap.values().getProfileId()
        // (concrete lookup mirrors existing profile read pattern)

        List<AdministratorView> views = filtered.stream().map(a -> toView(a, uaMap, memberMap, ...)).toList();
        return AdministratorListResponse.builder()
                .totalCount(views.size())
                .items(views)
                .build();
    }

    @Transactional
    public CreateAdministratorResponse create(CreateAdministratorRequest req, Long actorAdministratorId) {
        if (userAccountRepository.findByEmail(req.getEmail()).isPresent()) {
            throw ExceptionCreator.create(AdministratorManagementException.EMAIL_ALREADY_REGISTERED);
        }
        String tempPwd = tempPasswordGenerator.generate();
        String hash = passwordEncoder.encode(tempPwd);

        UserId newUserId = new UserId(); // UUID-shaped id; mirror existing usage
        UserAccountData ua = userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(newUserId, req.getEmail(), hash));

        AdministratorData admin = administratorRepository.save(
                AdministratorData.createAdmin(ua.getUserId().getUid(), actorAdministratorId));

        Long memberId = null;
        boolean includeMember = req.getIncludeMemberProfile() == null || req.getIncludeMemberProfile();
        if (includeMember) {
            MemberData member = memberSignService.getOrCreateMemberFor(ua);  // Task 5b — no recordLogin
            memberId = member.getMemberId();
            member.getProfileData().updateNickname(req.getNickname());  // Task 5c mutator via @OneToOne cascade
        }

        log.info("admin_management.create administrator_id={} user_id={} actor={}",
                admin.getAdministratorId(), ua.getUserId().getUid(), actorAdministratorId);

        return CreateAdministratorResponse.builder()
                .administratorId(admin.getAdministratorId())
                .userAccountId(ua.getUserId().getUid())
                .memberId(memberId)
                .tempPassword(tempPwd)
                .message("임시 비번은 첫 로그인 후 반드시 변경하세요.")
                .build();
    }

    private AdministratorView toView(AdministratorData a, Map<Long, UserAccountData> ua,
                                     Map<Long, MemberData> mem /*, profileMap */) {
        UserAccountData u = ua.get(a.getUserAccountId());
        MemberData m = mem.get(a.getUserAccountId());
        return AdministratorView.builder()
                .administratorId(a.getAdministratorId())
                .role(a.getRole())
                .grantedAt(a.getGrantedAt())
                .grantedByAdministratorId(a.getGrantedByAdministratorId())
                .revokedAt(a.getRevokedAt())
                .userAccountId(a.getUserAccountId())
                .email(u != null ? u.getEmail() : null)
                .lastLoginAt(u != null ? u.getLastLoginAt() : null)
                .mustChangePassword(u != null && u.isMustChangePassword())
                .memberId(m != null ? m.getMemberId() : null)
                .nickname(/* resolve from profile */ null)
                .build();
    }
}
```

NOTE: nickname resolution must mirror the project's existing pattern (likely `ProfileRepository.findById(profileId)`). Concrete lookup is filled in at task time after reading `ProfileData` and the existing nickname-write flow. Service test mocks the profile repo accordingly.

- [ ] **Step 5: Run — expect PASS**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdministratorManagementServiceTest*"
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementService.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementServiceTest.java
git commit -m "feat(administration): list + create administrator service (PR 6)"
```

---

### Task 7: `AdministratorManagementController` — list + create endpoints

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdministratorManagementController.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java` — add controller to `@WebMvcTest` list and `@MockBean` the new service
- Test: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdministratorManagementControllerTest.java`

- [ ] **Step 1: Extend `AbstractAdminWebMvcTest` (incremental — Task 7 adds only the controller it needs)**

In `AbstractAdminWebMvcTest.java`, add `AdministratorManagementController.class` to the `@WebMvcTest` list and add `@MockBean AdministratorManagementService administratorManagementService;` (plus `@MockBean AdminContext adminContext;` so the controller can resolve the actor). Also add `@MockBean ProfileRepository profileRepository;` if it isn't already a mock — the service depends on it. Do NOT pre-add `AdminPasswordController` here; Task 14 adds it incrementally when the controller class exists. This avoids referring to types that don't exist yet.

- [ ] **Step 2: Write the failing controller test**

```java
class AdministratorManagementControllerTest extends AbstractAdminWebMvcTest {

    @Test @WithMockUser(authorities = {"ROLE_ADMIN", "ROLE_SUPER_ADMIN"})
    void list_superAdmin_returns200WithItems() throws Exception {
        given(administratorManagementService.list(null, false))
                .willReturn(AdministratorListResponse.builder()
                        .totalCount(1)
                        .items(List.of(AdministratorView.builder()
                                .administratorId(1L).role(AdminRole.SUPER_ADMIN)
                                .build()))
                        .build());

        mockMvc.perform(get("/api/v1/admin/system/administrators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].role").value("SUPER_ADMIN"));
    }

    @Test @WithMockUser(authorities = {"ROLE_ADMIN"})
    void list_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/system/administrators"))
                .andExpect(status().isForbidden());
    }

    @Test @WithMockUser(authorities = {"ROLE_ADMIN", "ROLE_SUPER_ADMIN"})
    void create_superAdmin_returnsTempPassword() throws Exception {
        given(administratorManagementService.create(any(), any()))
                .willReturn(CreateAdministratorResponse.builder()
                        .administratorId(2L).userAccountId(20L).memberId(30L)
                        .tempPassword("Xk9@aB2zCdEf")
                        .message("임시 비번은 첫 로그인 후 반드시 변경하세요.")
                        .build());

        mockMvc.perform(post("/api/v1/admin/system/administrators")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"email":"new@x","nickname":"n","includeMemberProfile":true}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tempPassword").value("Xk9@aB2zCdEf"));
    }

    @Test @WithMockUser(authorities = {"ROLE_ADMIN", "ROLE_SUPER_ADMIN"})
    void create_missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/administrators")
                        .contentType(APPLICATION_JSON)
                        .content("""{"email":"new@x","nickname":"n"}"""))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 3: Implement controller**

```java
package com.pfplaybackend.api.administration.adapter.in.web;

@Slf4j
@Tag(name = "Admin Administrator Management API", description = "F-1 어드민 거버넌스 (슈퍼어드민 전용)")
@RestController
@RequestMapping("/api/v1/admin/system/administrators")
@RequiredArgsConstructor
public class AdministratorManagementController {

    private final AdministratorManagementService service;
    private final AdminContext adminContext; // resolves current administratorId from SecurityContext (verify exact bean name; see PR 4 for similar usage)

    @Operation(summary = "어드민 목록")
    @PreAuthorize("@adminAuth.canManageAdmins()")
    @GetMapping
    public ResponseEntity<ApiCommonResponse<AdministratorListResponse>> list(
            @RequestParam(required = false) AdminRole role,
            @RequestParam(defaultValue = "false") boolean includeRevoked) {
        return ResponseEntity.ok(ApiCommonResponse.success(service.list(role, includeRevoked)));
    }

    @Operation(summary = "어드민 생성")
    @PreAuthorize("@adminAuth.canManageAdmins()")
    @PostMapping
    public ResponseEntity<ApiCommonResponse<CreateAdministratorResponse>> create(
            @Valid @RequestBody CreateAdministratorRequest req) {
        Long actorId = adminContext.currentAdministratorId();
        return ResponseEntity.ok(ApiCommonResponse.success(service.create(req, actorId)));
    }
}
```

NOTE: `AdminContext` (or equivalent) — the helper that resolves the current admin's `administratorId` from `SecurityContextHolder` JWT claims. Verify exact bean name from PR 4's controllers. If absent, add a small helper in `common/.../security/authorization/` that reads `Authentication.getName()` (= JWT subject = `userId`) and looks up `AdministratorRepository.findByUserAccountId(userId)`. (Alternative: pass userId from JWT directly + look up administratorId in service — either works; pick the lighter touch on review.)

- [ ] **Step 4: Run — expect PASS**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdministratorManagementControllerTest*"
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdministratorManagementController.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdministratorManagementControllerTest.java \
        app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java
git commit -m "feat(administration): list + create administrator HTTP endpoints (PR 6)"
```

---

## Chunk 3 — PATCH + Revoke + member-profile attach

### Task 8: `UpdateAdministratorRequest` + service-side `updateNickname`

**Files:**
- Create: `app/.../payload/request/UpdateAdministratorRequest.java`
- Modify: `AdministratorManagementService.java`
- Modify: `AdministratorManagementServiceTest.java`

- [ ] **Step 1: Failing test — service updates nickname when Member exists**

```java
@Test
void updateNickname_whenMemberExists_writesProfileNickname() {
    given(administratorRepository.findById(7L)).willReturn(Optional.of(adminFixture));
    given(memberRepository.findByUserAccountId(adminFixture.getUserAccountId()))
            .willReturn(Optional.of(memberFixture));
    // existing profile-update mechanism mocked

    service.updateNickname(7L, "renamed");

    // verify profile/nickname mutation went through
}

@Test
void updateNickname_whenMemberMissing_throwsConflict() {
    given(administratorRepository.findById(7L)).willReturn(Optional.of(adminFixture));
    given(memberRepository.findByUserAccountId(adminFixture.getUserAccountId()))
            .willReturn(Optional.empty());
    assertThatThrownBy(() -> service.updateNickname(7L, "renamed"))
            .extracting("exception")
            .isEqualTo(AdministratorManagementException.MEMBER_PROFILE_REQUIRED);
}
```

- [ ] **Step 2: Implement**

`UpdateAdministratorRequest.java`:

```java
@Getter @NoArgsConstructor @AllArgsConstructor
public class UpdateAdministratorRequest {
    @NotBlank @Size(max = 64)
    private String nickname;
}
```

Service method:

```java
@Transactional
public void updateNickname(Long administratorId, String nickname) {
    AdministratorData admin = administratorRepository.findById(administratorId)
            .orElseThrow(() -> ExceptionCreator.create(AdministratorManagementException.NOT_FOUND));
    MemberData member = memberRepository.findByUserAccountId(admin.getUserAccountId())
            .orElseThrow(() -> ExceptionCreator.create(AdministratorManagementException.MEMBER_PROFILE_REQUIRED));
    member.getProfileData().updateNickname(nickname);  // Task 5c mutator via @OneToOne cascade
    log.info("admin_management.update_nickname administrator_id={}", administratorId);
}
```

- [ ] **Step 3: Run, commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/UpdateAdministratorRequest.java \
        app/src/main/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementService.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementServiceTest.java
git commit -m "feat(administration): patch nickname for administrator (PR 6)"
```

---

### Task 9: PATCH + member-profile attach controller endpoints

**Files:**
- Modify: `AdministratorManagementController.java`
- Modify: `AdministratorManagementService.java` — add `attachMemberProfile`
- Create: `AttachMemberProfileRequest.java`
- Modify: `AdministratorManagementControllerTest.java`

- [ ] **Step 1: Failing controller tests — PATCH and attach-profile**

```java
@Test @WithMockUser(authorities = {"ROLE_ADMIN", "ROLE_SUPER_ADMIN"})
void patch_superAdmin_returns204() throws Exception {
    mockMvc.perform(patch("/api/v1/admin/system/administrators/7")
                    .with(csrf())
                    .contentType(APPLICATION_JSON)
                    .content("{\"nickname\":\"renamed\"}"))
            .andExpect(status().isNoContent());
    verify(administratorManagementService).updateNickname(7L, "renamed");
}

@Test @WithMockUser(authorities = {"ROLE_ADMIN", "ROLE_SUPER_ADMIN"})
void attachMemberProfile_returns200WithMemberId() throws Exception {
    given(administratorManagementService.attachMemberProfile(7L, "newbie")).willReturn(99L);
    mockMvc.perform(post("/api/v1/admin/system/administrators/7/member-profile")
                    .with(csrf())
                    .contentType(APPLICATION_JSON)
                    .content("{\"nickname\":\"newbie\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.memberId").value(99));
}
```

- [ ] **Step 2: Implement**

```java
@PreAuthorize("@adminAuth.canManageAdmins()")
@PatchMapping("/{id}")
public ResponseEntity<Void> patch(@PathVariable Long id,
                                  @Valid @RequestBody UpdateAdministratorRequest req) {
    service.updateNickname(id, req.getNickname());
    return ResponseEntity.noContent().build();
}

@PreAuthorize("@adminAuth.canManageAdmins()")
@PostMapping("/{id}/member-profile")
public ResponseEntity<ApiCommonResponse<Map<String, Long>>> attachMemberProfile(
        @PathVariable Long id,
        @Valid @RequestBody AttachMemberProfileRequest req) {
    Long memberId = service.attachMemberProfile(id, req.getNickname());
    return ResponseEntity.ok(ApiCommonResponse.success(Map.of("memberId", memberId)));
}
```

Service `attachMemberProfile`:

```java
@Transactional
public Long attachMemberProfile(Long administratorId, String nickname) {
    AdministratorData admin = administratorRepository.findById(administratorId)
            .orElseThrow(() -> ExceptionCreator.create(AdministratorManagementException.NOT_FOUND));
    UserAccountData ua = userAccountRepository.findById(new UserId(admin.getUserAccountId()))
            .orElseThrow(); // invariant: admin → ua
    MemberData member = memberSignService.getOrCreateMemberFor(ua);  // Task 5b — no recordLogin
    member.getProfileData().updateNickname(nickname);  // Task 5c mutator via @OneToOne cascade
    return member.getMemberId();
}
```

- [ ] **Step 3: Run + commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdministratorManagementController.java \
        app/src/main/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementService.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/AttachMemberProfileRequest.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdministratorManagementControllerTest.java
git commit -m "feat(administration): patch + attach member-profile endpoints (PR 6)"
```

---

### Task 10: `revoke` endpoint with last-super-admin + self-revoke guards

**Files:**
- Modify: `AdministratorManagementController.java`
- Modify: `AdministratorManagementService.java`
- Modify: tests

- [ ] **Step 1: Failing service tests**

```java
@Test
void revoke_normalAdmin_setsRevokedAt() {
    given(administratorRepository.findById(7L)).willReturn(Optional.of(adminFixture));
    given(adminFixture.getRole()).willReturn(AdminRole.ADMIN);
    service.revoke(7L, /*actorAdministratorId=*/ 1L);
    verify(adminFixture).revoke();
}

@Test
void revoke_self_throwsConflict() {
    given(administratorRepository.findById(1L)).willReturn(Optional.of(superAdminFixture));
    assertThatThrownBy(() -> service.revoke(1L, 1L))
            .extracting("exception")
            .isEqualTo(AdministratorManagementException.CANNOT_REVOKE_SELF);
}

@Test
void revoke_lastActiveSuperAdmin_throwsConflict() {
    given(administratorRepository.findById(1L)).willReturn(Optional.of(superAdminFixture));
    given(superAdminFixture.getRole()).willReturn(AdminRole.SUPER_ADMIN);
    given(administratorRepository.countByRoleAndRevokedAtIsNull(AdminRole.SUPER_ADMIN))
            .willReturn(1L);
    assertThatThrownBy(() -> service.revoke(1L, /*actor=*/ 99L))
            .extracting("exception")
            .isEqualTo(AdministratorManagementException.CANNOT_REVOKE_LAST_SUPER_ADMIN);
}
```

- [ ] **Step 2: Implement**

```java
@Transactional
public void revoke(Long administratorId, Long actorAdministratorId) {
    if (Objects.equals(administratorId, actorAdministratorId)) {
        throw ExceptionCreator.create(AdministratorManagementException.CANNOT_REVOKE_SELF);
    }
    AdministratorData target = administratorRepository.findById(administratorId)
            .orElseThrow(() -> ExceptionCreator.create(AdministratorManagementException.NOT_FOUND));
    if (target.getRole() == AdminRole.SUPER_ADMIN) {
        long activeSuperAdmins = administratorRepository
                .countByRoleAndRevokedAtIsNull(AdminRole.SUPER_ADMIN);
        if (activeSuperAdmins <= 1) {
            throw ExceptionCreator.create(AdministratorManagementException.CANNOT_REVOKE_LAST_SUPER_ADMIN);
        }
    }
    target.revoke();
    log.warn("admin_management.revoke target_id={} actor_id={}", administratorId, actorAdministratorId);
}
```

Controller:

```java
@PreAuthorize("@adminAuth.canManageAdmins()")
@PostMapping("/{id}/revoke")
public ResponseEntity<Void> revoke(@PathVariable Long id) {
    service.revoke(id, adminContext.currentAdministratorId());
    return ResponseEntity.noContent().build();
}
```

- [ ] **Step 3: Failing controller test for the new endpoint, then implement, run, commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdministratorManagementController.java \
        app/src/main/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementService.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdministratorManagementControllerTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementServiceTest.java
git commit -m "feat(administration): revoke administrator with self/last-super-admin guards (PR 6)"
```

---

## Chunk 4 — Reset password + Self-change + Login-flow signal

### Task 11: `AdminLoginService` reads `mustChangePassword`, threads through `AdminAuthResult` (Group G2)

**Files:**
- Modify: `app/.../auth/application/dto/result/AdminAuthResult.java`
- Modify: `app/.../auth/application/service/AdminLoginService.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/auth/application/service/AdminLoginServiceTest.java`

- [ ] **Step 1: Failing test — login result carries mustChangePassword=true**

```java
@Test
void login_userMustChangePassword_resultCarriesFlag() {
    given(userAccountRepository.findByEmailAndProviderType("a@x", LOCAL))
            .willReturn(Optional.of(userAccountFixture));
    given(userAccountFixture.isMustChangePassword()).willReturn(true);
    given(passwordEncoder.matches(...)).willReturn(true);
    // ... rest of fixture setup

    AdminAuthResult result = service.login(new AdminLoginCommand("a@x", "pwd", "1.2.3.4"));

    assertThat(result.mustChangePassword()).isTrue();
}
```

- [ ] **Step 2: Update record + service**

`AdminAuthResult.java`:

```java
public record AdminAuthResult(
        String adminAccessToken,
        String sharedSessionToken,
        AdminRole role,
        long adminAccessTokenTtlMs,
        long sharedSessionTokenTtlMs,
        LocalDateTime issuedAt,
        boolean mustChangePassword
) {}
```

`AdminLoginService.java` — at the end, return `... new AdminAuthResult(..., ua.isMustChangePassword())`.

- [ ] **Step 3: Run service + dependent tests; expect compile fixes needed in `AdminAuthController`**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava
```

Expected: `AdminAuthController.java` compile error referencing missing `result.mustChangePassword()` on response builder. We'll fix in Task 12 — same group. Skip commit.

---

### Task 12: `AdminLoginResponse` carries flag; `AdminAuthController` wires it (Group G2)

**Files:**
- Modify: `AdminLoginResponse.java`
- Modify: `AdminAuthController.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthControllerTest.java` — **2 existing `new AdminAuthResult(...)` constructions at lines 57 and 75 — both must be updated to pass the new 7th argument.**

- [ ] **Step 1: Update response record**

```java
@Builder
public record AdminLoginResponse(
        String tokenType,
        long expiresIn,
        LocalDateTime issuedAt,
        AdminRole role,
        boolean mustChangePassword
) {}
```

- [ ] **Step 2: Update controller**

```java
return ResponseEntity.ok(ApiCommonResponse.success(AdminLoginResponse.builder()
        .tokenType("Cookie")
        .expiresIn(result.adminAccessTokenTtlMs() / 1000)
        .issuedAt(result.issuedAt())
        .role(result.role())
        .mustChangePassword(result.mustChangePassword())
        .build()));
```

- [ ] **Step 3: Adjust controller test**

```java
@Test
void login_mustChange_setsFlagInResponse() throws Exception {
    given(adminLoginService.login(any())).willReturn(
            new AdminAuthResult("ad", "sh", AdminRole.ADMIN, 900_000L, 86_400_000L,
                    LocalDateTime.now(), true));
    mockMvc.perform(post("/api/v1/auth/admin/login")
                    .contentType(APPLICATION_JSON)
                    .content("{\"email\":\"a@x\",\"password\":\"p\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mustChangePassword").value(true));
}
```

- [ ] **Step 4: Run + G2 commit**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminLoginServiceTest*" --tests "*AdminAuthControllerTest*"
git add app/src/main/java/com/pfplaybackend/api/auth/application/dto/result/AdminAuthResult.java \
        app/src/main/java/com/pfplaybackend/api/auth/application/service/AdminLoginService.java \
        app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/payload/response/AdminLoginResponse.java \
        app/src/main/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthController.java \
        app/src/test/java/com/pfplaybackend/api/auth/application/service/AdminLoginServiceTest.java \
        app/src/test/java/com/pfplaybackend/api/auth/adapter/in/web/AdminAuthControllerTest.java
git commit -m "feat(auth): surface mustChangePassword in admin login response (PR 6)"
```

---

### Task 13: Reset-password endpoint (super-admin → other admin)

**Files:**
- Create: `app/.../payload/response/ResetPasswordResponse.java`
- Modify: `AdministratorManagementController.java` + `Service.java` + tests

- [ ] **Step 1: Failing service test**

```java
@Test
void resetPassword_setsTempPasswordHash_setsMustChangeFlag_returnsTempPlaintext() {
    given(administratorRepository.findById(7L)).willReturn(Optional.of(adminFixture));
    given(userAccountRepository.findById(new UserId(adminFixture.getUserAccountId())))
            .willReturn(Optional.of(uaFixture));
    given(tempPasswordGenerator.generate()).willReturn("Yp4@xQ7zVwLm");
    given(passwordEncoder.encode("Yp4@xQ7zVwLm")).willReturn("BCRYPT$$");

    ResetPasswordResponse resp = service.resetPassword(7L, /*actor=*/ 1L);

    assertThat(resp.tempPassword()).isEqualTo("Yp4@xQ7zVwLm");
    verify(uaFixture).requirePasswordChange("BCRYPT$$");
}
```

- [ ] **Step 2: Implement**

`ResetPasswordResponse.java`:

```java
@Builder
public record ResetPasswordResponse(String tempPassword, String message) {}
```

Service:

```java
@Transactional
public ResetPasswordResponse resetPassword(Long administratorId, Long actorAdministratorId) {
    AdministratorData target = administratorRepository.findById(administratorId)
            .orElseThrow(() -> ExceptionCreator.create(AdministratorManagementException.NOT_FOUND));
    UserAccountData ua = userAccountRepository.findById(new UserId(target.getUserAccountId()))
            .orElseThrow();
    String tempPwd = tempPasswordGenerator.generate();
    ua.requirePasswordChange(passwordEncoder.encode(tempPwd));
    log.warn("admin_management.reset_password target_id={} actor_id={}",
            administratorId, actorAdministratorId);
    return ResetPasswordResponse.builder()
            .tempPassword(tempPwd)
            .message("임시 비번을 안전한 채널로 전달하세요. 첫 로그인 시 변경됩니다.")
            .build();
}
```

Controller:

```java
@PreAuthorize("@adminAuth.canManageAdmins()")
@PostMapping("/{id}/reset-password")
public ResponseEntity<ApiCommonResponse<ResetPasswordResponse>> resetPassword(@PathVariable Long id) {
    return ResponseEntity.ok(ApiCommonResponse.success(
            service.resetPassword(id, adminContext.currentAdministratorId())));
}
```

- [ ] **Step 3: Failing controller test, run, commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/ResetPasswordResponse.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdministratorManagementController.java \
        app/src/main/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementService.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdministratorManagementControllerTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/service/AdministratorManagementServiceTest.java
git commit -m "feat(administration): reset-password endpoint (PR 6)"
```

---

### Task 14: `AdminPasswordController` + `AdminPasswordService` (self-change)

**Files:**
- Create: `app/.../administration/adapter/in/web/AdminPasswordController.java`
- Create: `app/.../administration/application/service/AdminPasswordService.java`
- Create: `app/.../payload/request/ChangeAdminPasswordRequest.java`
- Modify: `AbstractAdminWebMvcTest.java` — add controller + mock service
- Test: `app/src/test/java/.../AdminPasswordControllerTest.java`
- Test: `app/src/test/java/.../AdminPasswordServiceTest.java`

- [ ] **Step 1: Failing service test**

```java
@Test
void changePassword_validCurrent_clearsMustChangeFlag() {
    given(userAccountRepository.findById(userId)).willReturn(Optional.of(uaFixture));
    given(passwordEncoder.matches("oldPwd", uaFixture.getPasswordHash())).willReturn(true);
    given(passwordEncoder.encode("NewP@ssw0rd1")).willReturn("BCRYPT-NEW");

    service.changePassword(userId, "oldPwd", "NewP@ssw0rd1");

    verify(uaFixture).completePasswordChange("BCRYPT-NEW");
}

@Test
void changePassword_wrongCurrent_throwsUnauthorized() { ... }

@Test
void changePassword_weakNew_throwsBadRequest() {
    // policy.requireValid throws → service maps to AdministratorManagementException.INVALID_NEW_PASSWORD
}
```

- [ ] **Step 2: Implement**

`ChangeAdminPasswordRequest.java`:

```java
@Getter @NoArgsConstructor @AllArgsConstructor
public class ChangeAdminPasswordRequest {
    @NotBlank private String currentPassword;
    @NotBlank private String newPassword;
}
```

`AdminPasswordService.java`:

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminPasswordService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminPasswordPolicy policy;

    @Transactional
    public void changePassword(UserId userId, String currentPassword, String newPassword) {
        UserAccountData ua = userAccountRepository.findById(userId)
                .orElseThrow(() -> ExceptionCreator.create(AdministratorManagementException.NOT_FOUND));
        if (!passwordEncoder.matches(currentPassword, ua.getPasswordHash())) {
            throw ExceptionCreator.create(AdministratorManagementException.INVALID_CURRENT_PASSWORD);
        }
        try {
            policy.requireValid(newPassword);
        } catch (IllegalArgumentException e) {
            throw ExceptionCreator.create(AdministratorManagementException.INVALID_NEW_PASSWORD);
        }
        ua.completePasswordChange(passwordEncoder.encode(newPassword));
        log.info("admin_password.self_change user_id={}", userId.getUid());
    }
}
```

`AdminPasswordController.java`:

```java
@RestController
@RequestMapping("/api/v1/admin/password")
@RequiredArgsConstructor
@Tag(name = "Admin Password API", description = "어드민 셀프 비밀번호 변경 (§5.6)")
public class AdminPasswordController {

    private final AdminPasswordService service;
    private final AdminContext adminContext;

    @Operation(summary = "내 비밀번호 변경")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PostMapping("/change")
    public ResponseEntity<Void> change(@Valid @RequestBody ChangeAdminPasswordRequest req) {
        service.changePassword(adminContext.currentUserId(),
                req.getCurrentPassword(), req.getNewPassword());
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 3: Extend `AbstractAdminWebMvcTest` to register the controller + mock the service**

- [ ] **Step 4: Failing controller test**

Test that 401-on-anonymous, 403-on-MEMBER, 204-on-ADMIN, CSRF-required.

- [ ] **Step 5: Run + commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPasswordController.java \
        app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminPasswordService.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/ChangeAdminPasswordRequest.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPasswordControllerTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminPasswordServiceTest.java \
        app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java
git commit -m "feat(administration): admin self password change endpoint (PR 6)"
```

---

## Chunk 5 — Cross-cutting integration tests

### Task 15: ~~Extend §5.7.1 permission regression matrix~~ — DROPPED

The §5.7.1 parameterized matrix lives in `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminAuthorizationMatrixTest.java:42-112`. Probes deliberately use NON-EXISTENT paths (`/api/v1/admin/__test_probe__`, `/api/v1/admin/system/__test_probe__`, `/api/v1/admin/avatar/__test_probe__`) so the matrix isolates URL-level gating from controller behavior. PR 6 introduces NO new prefix branch — `/api/v1/admin/system/administrators/**` and `/api/v1/admin/password/**` both fall under the existing probe-covered prefixes. Adding real-endpoint rows to this test would conflict with its design intent.

Per-endpoint security coverage for the new endpoints lives in:
- `AdministratorManagementControllerTest` (Tasks 7, 9, 10, 13) — tests anonymous/MEMBER/ADMIN/SUPER_ADMIN matrix per endpoint.
- `AdminPasswordControllerTest` (Task 14) — same per the self-change endpoint.

**Action:** none. Skip Task 15.

---

### Task 16: End-to-end integration test — must_change_password flow

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminPasswordChangeIntegrationTest.java` — colocated with `AdminCookieIsolationIntegrationTest` (matches existing convention)

- [ ] **Step 1: Write the test**

```java
@AutoConfigureMockMvc
class AdminPasswordChangeIntegrationTest extends AbstractIntegrationTest {

    // Testcontainers MySQL is provided by AbstractIntegrationTest.

    @Autowired MockMvc mockMvc;
    @Autowired AdministratorRepository administratorRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired PasswordEncoder passwordEncoder;
    // + JWT helpers / cookie helpers from existing integration tests

    @Test
    void newAdmin_loginShowsMustChange_thenSelfChange_thenLoginCleared() throws Exception {
        // 1) Super admin (V5 seed) logs in via /api/v1/auth/admin/login → AdminAccessToken cookie.
        //    (Skip if test fixture pre-creates cookies.)

        // 2) POST /api/v1/admin/system/administrators with super-admin cookie + CSRF.
        //    Capture tempPassword from response body.

        // 3) New admin logs in with email + tempPassword. Assert response carries
        //    mustChangePassword=true. Capture cookies.

        // 4) POST /api/v1/admin/password/change with new admin's cookie + CSRF
        //    + body { currentPassword=tempPassword, newPassword="NewP@ssw0rd1" }.
        //    Expect 204.

        // 5) New admin logs in again with new password. Assert
        //    mustChangePassword=false in response.
    }
}
```

- [ ] **Step 2: Run**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*AdminPasswordChangeIntegrationTest*"
```

(Substitute the actual integration-test task name — check `build.gradle` for `task integrationTest` or whichever test source-set runs Testcontainers tests.)

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/pfplaybackend/api/common/config/security/AdminPasswordChangeIntegrationTest.java
git commit -m "test(administration): end-to-end must_change_password flow (PR 6)"
```

---

## Final verification (after Task 16)

- [ ] **Step 1: Full module build**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew clean build
```

Expected: BUILD SUCCESSFUL across all modules.

- [ ] **Step 2: Manual smoke test (optional but recommended)**

Spin up local MySQL via docker-compose; boot the app with `ADMIN_SEED_EMAIL` + `ADMIN_SEED_PASSWORD`. Use `curl` (or Postman) against the running server:

```bash
# Super admin login
curl -X POST http://localhost:8080/api/v1/auth/admin/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"ops@pfplay.xyz","password":"$ADMIN_SEED_PASSWORD"}' \
     -c cookies.txt

# Get CSRF (any GET to /api/v1/admin/** issues XSRF-TOKEN)
curl http://localhost:8080/api/v1/admin/system/administrators -b cookies.txt -c cookies.txt

# Create new admin (extract XSRF-TOKEN from cookies.txt and pass as X-XSRF-TOKEN)
curl -X POST http://localhost:8080/api/v1/admin/system/administrators \
     -H "X-XSRF-TOKEN: $(grep XSRF-TOKEN cookies.txt | awk '{print $7}')" \
     -H 'Content-Type: application/json' \
     -d '{"email":"new@pfplay.xyz","nickname":"newbie","includeMemberProfile":true}' \
     -b cookies.txt
```

Expected: 200 with `tempPassword` field. Save tempPassword. Re-login as `new@pfplay.xyz` → response has `mustChangePassword: true`. Self-change → 204. Re-login → flag cleared.

- [ ] **Step 3: Verify §5.7 conformance summary**

Use a sub-agent with `tech-lead:review` to scan PR 6 commits and confirm: (a) new endpoints all have `@PreAuthorize` + URL rule fall-through; (b) all state-changing endpoints exercise CSRF; (c) `must_change_password` mutations are confined to the three intent-named methods; (d) no new SecurityConfig URL rules added; (e) no PR 4-era files modified outside the `auth/` package.

---

## Plan summary

- **Endpoints delivered:** 7 (5 F-1 + member-profile attach + self-change).
- **Migrations:** 1 (V13).
- **Tasks:** 19 (1, 2, 3, 4, 4b, 5, 5b, 5c, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15-DROPPED, 16) + final verification.
- **New files:** ~17 main + ~9 test.
- **Modified files:** ~13 main + ~3 test. Surfaces touched outside PR 6 BC: `UserAccountData`, `Bio`, `ProfileData`, `MemberSignService` (all `user` module); `AdminLoginService`, `AdminAuthController`, `AdminAuthResult`, `AdminLoginResponse`, `AbstractAdminWebMvcTest`, `AdminAuthControllerTest` (all `app` module).
- **Atomic commit groups:** 2 (G1 = Tasks 1+2; G2 = Tasks 11+12).
- **Lessons applied from PR 5:** method-security activation means new `@PreAuthorize` annotations enforce immediately; CSRF Option A covers new POSTs without extra wiring; `AbstractAdminWebMvcTest` extension pattern (incremental — only add controllers + mocks when their classes exist).
- **Cross-cutting refactors triggered by PR 6:** `MemberSignService.getOrCreateMemberFor(UserAccountData)` extraction (Task 5b) — gives invite path a no-recordLogin code path; `Bio.updateNickname` + `ProfileData.updateNickname` mutators (Task 5c) — preserves introduction during PATCH-nickname; `AdminContext` (Task 4b) — codifies `LogoutService.java:20-21` pattern as a reusable bean.
- **Spec divergences documented in this plan:** Decision 4 (self-change path moves from spec literal `/api/v1/auth/admin/password/change` to `/api/v1/admin/password/change`); Decision 5 (temp password length 12 chars vs spec example "8자"). Both should be reflected in spec follow-up edits to keep design and implementation consistent.
- **Open follow-ups (out of scope, tracked):** lockout endpoint (§5.5.5), DB audit (PR 12), restore-administrator, JWT-claim signaling for must-change, race-safe `revoke` lock (Decision 11).

---
