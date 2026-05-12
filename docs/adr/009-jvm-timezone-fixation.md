# ADR-009: JVM Timezone Fixation to Asia/Seoul

**Date:** 2026-04-30
**Status:** Accepted — Shipped PR #202 (develop) / #203 (release) / #205 (main)

## Context

PFPlay operates in a single market (Korea) with all users, admins, and operational stakeholders in `Asia/Seoul`. Before this change, the JVM picked up the host container's timezone, which differed between local dev (host OS), the GCE VM (UTC by default), and any future deployment target. This caused:

- `LocalDateTime` columns drifting between writers and readers depending on where the code ran
- Log timestamps in UTC while everyone reading the logs reasoned in KST
- Cron schedules (notably the V14 maintenance scheduler — see ADR-007) firing on UTC boundaries instead of intuitive KST boundaries
- The admin console nearing prod entry, where admins schedule maintenance windows in KST and expect the backend to interpret those times identically

Multi-timezone support is YAGNI for this product. The cost of adding it later (introduce a per-user TZ + render-time conversion) is bounded; the cost of letting drift accumulate is not.

## Decision

Fix the JVM timezone to `Asia/Seoul` at two layers — container env + application code — so that no host or profile can override it silently.

### Container layer
- `app/Dockerfile`: `ENV TZ=Asia/Seoul`
- MySQL container in `docker-compose.*.yml`: `--default-time-zone=+09:00`

### Application layer
- `ClockConfig` exposes a `Clock` bean with `ZoneId.of("Asia/Seoul")`
- All time-reading code (services, schedulers, value objects) takes `Clock` via DI rather than calling `LocalDateTime.now()` directly. Direct calls are an ArchUnit smell.

## Consequences

- **Single source of truth** for "now" across all environments and tests
- **Admin / user consoles display KST** regardless of the viewer's browser timezone — frontends rely on this assumption (see pfplay-admin `shared/lib/format-kst.ts`, pfplay-web's KST presentation defaults)
- **Cron schedules align with KST** — V14 maintenance windows scheduled for "tomorrow 03:00" fire at KST 03:00 without TZ math
- **Existing data** persisted before this change may have been written under a different TZ assumption. Mitigation: dev/stg DBs were reset; prod retained the option of a one-shot UPDATE migration if drift was observed (see `docs/OPERATIONS.md` §1, §11)
- **Future multi-TZ requirement** would require swapping `ClockConfig` for a request-scoped clock + per-user TZ; design space is clean but not pre-built

## What we did NOT pick

- **Per-request `Clock` injection** — overkill for a single-region product, and `LocalDateTime` semantics already imply a wall-clock reading rather than an instant
- **`ZonedDateTime` everywhere** — moves the problem to every API boundary without solving it; we'd still need a default TZ to fall back to
- **`Instant` + UTC** — correct for cross-region products, but introduces conversion overhead at every UI/log boundary in a single-region product

## Verification

- ArchUnit / convention: direct `LocalDateTime.now()` / `LocalDate.now()` calls without an injected `Clock` are flagged in review
- The admin console's display of "now" in KST is verified via pfplay-admin's `format-kst.ts` tests (different repo)
- Operational verification: after PR #205 shipped, log timestamps and DB `created_at` values for new rows are in KST
