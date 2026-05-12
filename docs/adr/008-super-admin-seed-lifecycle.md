# ADR-008: Super-Admin Seed Lifecycle

**Date:** 2026-04-29
**Status:** Accepted
**Related migration:** `V5__create_administrator.sql` (placeholder row)
**Related code:** `app/bootstrap/ApplicationReadyEventListener`, `app/administration/application/service/SuperAdminSeedService`

## Context

The admin console (pfplay-admin) requires a super-admin account to bootstrap any further admin user provisioning. The first deployment in any environment has zero administrators, and the second admin can only be created by an existing super-admin. Two anti-patterns were considered and rejected:

1. **Bake the super-admin into a Flyway migration** — embeds a secret (or a known weak password) in source control / migration logs. Rejected on hygiene grounds.
2. **Require an out-of-band insert via DBA** — couples our deploy to a manual SQL step and is impossible to do safely on managed databases.

We need a path where the super-admin appears on first boot via injected secrets, and where the bootstrap is **safe to re-run** on every subsequent boot (no duplicate inserts, no overwriting a rotated password).

## Decision

Adopt a placeholder + idempotent runtime seed pattern.

### Placeholder (Flyway)
- `V5__create_administrator.sql` creates the `administrator` table but **does not insert any rows**. There is no secret in the migration.

### Runtime seed (Spring application lifecycle)
- `ApplicationReadyEventListener` (in `app.bootstrap`) listens to Spring's `ApplicationReadyEvent`
- It calls `SuperAdminSeedService.seedIfMissing(seedEmail, seedPassword)` once
- `SuperAdminSeedService`:
  - Queries for any existing super-admin by role
  - **If one exists, returns immediately** (no comparison, no overwrite)
  - **If none exists**, inserts using the injected `ADMIN_SEED_EMAIL` / `ADMIN_SEED_PASSWORD` with `mustChange = true`

### `matchIfMissing` guard
- The listener is wired via `@ConditionalOnProperty(... matchIfMissing = true)` so that the service runs by default but can be disabled per-profile (e.g. test) without code changes.

### Secret hygiene
- `ADMIN_SEED_EMAIL` / `ADMIN_SEED_PASSWORD` env vars are required only on the **first boot** of any environment.
- Once a super-admin exists, the env vars are inert. Operations may (and **should**) remove them from the deploy environment to minimize secret surface.
- For local development, `.env.local` carries a well-known throwaway pair (`admin@pfplay.local` / `local-test-only-rotate-in-prod`) — see `docs/OPERATIONS.md` §3.

## Consequences

- **No secret in source control / Flyway** — migration is replayable on any clean DB without leaking credentials.
- **Idempotent** — re-running on a populated DB is a no-op even if env vars remain. Restart-safe.
- **First-login password change is enforced** by the existing `mustChange` flow (the admin console redirects to `/password/change` after the first sign-in).
- **Operational debt**: prod still carries `ADMIN_SEED_*` env vars from the first deploy. Removing them is tracked as an outstanding action in `docs/OPERATIONS.md` §3.

## What we did NOT pick

- **Per-environment seed values bundled in the JAR** — couples build to env, defeats the hygiene goal
- **CLI / admin-CLI for first-boot seed** — increases the surface where the secret could appear (shell history, terminal multiplexers); the env approach keeps it contained to the deploy platform's secret store
- **Always-overwrite on boot** — would clobber a rotated password the moment ops restarted the pod with a stale env value

## Verification

- `SuperAdminSeedServiceTest` covers: empty DB → inserts; populated DB → no-op; missing env → no-op (no exception)
- `IamRepositoryIntegrationTest` validates the resulting administrator row has the correct `role` + `mustChange` flag
