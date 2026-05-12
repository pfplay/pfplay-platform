# ADR-011: Admin Origin Guard

**Date:** 2026-04-20
**Status:** Accepted (current implementation) — **Hardening Pending**
**Related code:** `common/.../config/security/web/properties/AdminOriginProperties`, `common/.../config/security/SecurityConfig`
**Companion ADR:** [ADR-006](006-admin-csrf-token.md) (Option A — token), this is Option B (origin allowlist)

## Context

The admin API (`/api/v1/admin/**`, `/api/v1/auth/admin/**`) carries higher privilege than the customer-facing API and runs on a dedicated subdomain (`admin.pfplay.xyz`). A CSRF defense based purely on tokens (ADR-006) handles same-origin spoofing well but cannot stop an attacker who has chosen a cross-origin attack vector via a misconfigured CORS layer or a leaked browser bug.

A second, cheap layer of defense is an `Origin` / `Referer` header allowlist: refuse any request to an admin path whose `Origin` is not one of the known admin frontends. Spec §5.4.3 prescribes A + B as defense in depth.

## Decision

Maintain an allowlist of admin origins in `application.yml` under `pfplay.security.admin-origin-guard.allowed`, bind it to `AdminOriginProperties`, and enforce it in `SecurityConfig` for state-changing requests on admin paths.

### Enforcement scope
- HTTP method ∈ {POST, PUT, PATCH, DELETE}
- Path matches `/api/v1/admin/**` or `/api/v1/auth/admin/**`
- `Origin` header (fallback: `Referer`) must match one of the configured origins; mismatch → 403

### Allowlist
The current `application.yml` snippet (single shared list across profiles):
```yaml
pfplay:
  security:
    admin-origin-guard:
      allowed:
        - https://admin.pfplay.xyz
        # ... additional admin frontends
```

Local development uses a relaxed value in `application-local.yml` / `application-test.yml`.

## Consequences

- Defense-in-depth alongside ADR-006: a leaked CSRF token is still useless without a matching admin origin
- New admin frontends (preview deployments, alternate hostnames) require **a backend redeploy** to be added to the allowlist — a deliberate trade-off

### Hardening pending

The current implementation has two known limitations tracked as outstanding:

1. **Single-list across environments** — `application.yml` holds one allowlist that all non-local profiles share. dev / stg / prod admin frontends are conceptually different origins but currently must coexist in one list. This violates least-privilege: a dev admin origin should not authorize a prod admin request.
2. **Hardcoded in source** — adding a new admin origin requires a code commit + deploy. For preview environments (Cloudflare Pages branch URLs), this is a friction point.

The hardening path is to **split the list per profile** (`application-dev.yml`, `application-stg.yml`, `application-prod.yml`) and resolve via the active Spring profile. See `docs/OPERATIONS.md` §4 for status.

## What we did NOT pick

- **Wildcard origin** — defeats the purpose
- **Database-stored allowlist** — adds a runtime SQL dependency to a request that fires on every admin call; the trade-off didn't pencil out for the team-of-one operation tempo
- **CORS-only defense** — CORS protects browsers from cross-origin reads, not from cross-origin writes; it cannot replace this guard

## Verification

- `AdminAuthControllerTest` covers: allowed origin → 200; disallowed origin → 403; missing `Origin` + valid `Referer` → 200
- The guard is exercised in production every time the admin console makes an `/api/v1/admin/**` call
