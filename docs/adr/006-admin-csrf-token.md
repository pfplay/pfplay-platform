# ADR-006: Admin CSRF Token Defense (Option A)

**Date:** 2026-04-27
**Status:** Accepted
**Related spec:** docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.4.3
**Related plan:** docs/superpowers/plans/2026-04-27-admin-platform-pr5.md

## Context

PR 4 shipped Option B (Origin/Referer header allowlist) for admin CSRF defense. Option A (token-based double-submit) was deferred to PR 5 because it required a `SecurityConfig` overhaul (CSRF cannot be selectively re-enabled while staying compatible with stateless JWT for non-admin paths).

Spec §5.4.3 recommends A + B as defense in depth. Option A defends against attackers who can spoof the `Origin` header (rare; requires browser bug or non-browser client hitting an XSS in `admin.pfplay.xyz`).

## Decision

Use Spring Security's stock `CookieCsrfTokenRepository.withHttpOnlyFalse()` configured via `setCookieCustomizer` to issue an `XSRF-TOKEN` cookie scoped to `admin.pfplay.xyz`. The admin frontend (pfplay-admin) reads the cookie value and echoes it as the `X-XSRF-TOKEN` header on every state-changing request.

### Scope

CSRF validation is enforced when ALL of:
1. HTTP method is one of {POST, PUT, PATCH, DELETE} (state-changing)
2. Path matches `/api/v1/admin/**` OR is exactly `/api/v1/auth/admin/logout`

CSRF validation is BYPASSED for `/api/v1/auth/admin/login` (no session yet) and for all non-admin paths (today's behavior — OAuth flows, member sign-in, etc.).

### Cookie shape

| Attribute | Value | Rationale |
|---|---|---|
| Name | `XSRF-TOKEN` | Spring default; pfplay-admin axios stock config picks it up. |
| Domain | `admin.pfplay.xyz` (no leading dot) | Matches `AdminAccessToken` — same isolation. |
| Path | `/` | Admin frontend hits paths under `/`. |
| Secure | true (default), false (local/test) | TLS-only in deployed envs. |
| HttpOnly | **false** | Frontend JS must read the value. |
| SameSite | Strict | Aligns with `AdminAccessToken`. |

### Frontend contract (for pfplay-admin team)

1. After admin login (`POST /api/v1/auth/admin/login`), the first GET against any `/api/v1/admin/**` endpoint will set the `XSRF-TOKEN` cookie.
2. Frontend reads `XSRF-TOKEN` from `document.cookie` (it's not HttpOnly).
3. On every state-changing request (POST/PUT/PATCH/DELETE) to `/api/v1/admin/**` or `/api/v1/auth/admin/logout`, frontend includes header `X-XSRF-TOKEN: <cookie value>`.
4. Server-side `CsrfFilter` compares header to cookie. Mismatch or missing → 403.

Axios users: `axios.defaults.xsrfCookieName = 'XSRF-TOKEN'; axios.defaults.xsrfHeaderName = 'X-XSRF-TOKEN'` does this automatically.

### What we did NOT pick

- HMAC-derived per-session tokens — overkill for MVP; cookie pair already binds.
- Custom `GET /admin/csrf-token` endpoint (per spec §5.4.3) — Spring's stock cookie issuance on every safe response is equivalent and simpler.
- Separate `SecurityFilterChain` for admin vs general — see plan Decision 9; deferred.

## Consequences

- Admin frontend must wire an axios interceptor (one-liner — Axios reads `XSRF-TOKEN` and sets `X-XSRF-TOKEN` automatically when configured with `xsrfCookieName` + `xsrfHeaderName`).
- Local dev: `secure: false` in `local` profile and test profile — see `application.yml` and `application-test.yml`.
- Origin/Referer guard (PR 4) stays — defense in depth.

## Verification

- `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminCsrfIntegrationTest.java` proves: token missing → 403, token mismatched → 403, token correct → 200/expected.
- Frontend integration is verified by pfplay-admin's CI; not in scope for this repo.
