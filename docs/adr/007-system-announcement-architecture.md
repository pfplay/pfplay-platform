# ADR-007: System Announcement Architecture (V14)

**Date:** 2026-05-03
**Status:** Accepted
**Related spec:** docs/superpowers/specs/2026-05-03-system-announcement-design.md
**Related plan:** docs/superpowers/plans/2026-05-03-system-announcement.md
**Migration:** `V14__create_system_announcement.sql`

## Context

PFPlay needs a way for super-admins to broadcast system-wide notices (feature announcements, scheduled maintenance, urgent alerts) that reach every connected client without per-partyroom fan-out. The first prod use case is scheduled maintenance: clients must enter a read-only/blocked mode when a maintenance window starts, and the backend must self-trigger that transition on time without depending on an admin being online to press a button.

The frontend (pfplay-web) is on Vercel; an Edge Config toggle is the natural place to flip user-facing β features and maintenance gates synchronously inside the edge middleware.

## Decision

Treat system announcements as an aggregate inside the **Administration BC** (`api.administration.*`), persisted in MySQL, broadcast over STOMP to a system-wide topic, and reflected to Vercel Edge Config for edge-rendered consumers.

### Aggregate & schema (V14)
- Aggregate: `SystemAnnouncement` (`SystemAnnouncementData`)
- Required: `type` (`SYSTEM_NOTIFICATION` | `MAINTENANCE_NOTICE`), `severity`, ko/en title + message, `sent_at`, `sent_by_administrator_id`
- Optional: `scheduled_start_at` / `scheduled_end_at` (mandatory iff `MAINTENANCE_NOTICE`), `expires_at`, `cancelled_at` + `cancelled_by_administrator_id`, `maintenance_started_at`

### Services
- `SystemAnnouncementCommandService` — publish / cancel (admin-only)
- `SystemAnnouncementQueryService` — active announcement lookups
- `MaintenanceSchedulerService` — **1-minute cron** that fires `MaintenanceStartedEvent` when `scheduled_start_at` is crossed; no manual action required to enter maintenance mode

### Broadcast
- `AnnouncementBroadcaster` (`adapter/out/event/`) sends three event types to **`/sub/system/announcements`** (system-wide, not partyroom-scoped):
  - `ANNOUNCEMENT_PUBLISHED`
  - `ANNOUNCEMENT_CANCELLED`
  - `MAINTENANCE_STARTED`
- AsyncAPI: see `docs/asyncapi/asyncapi.yml#channels.systemAnnouncementBroadcast`

### Edge Config sync
- `EdgeConfigPort` (administration domain) → `VercelEdgeConfigAdapter` (out adapter)
- The edge middleware in pfplay-web reads `system-status.phase === 'ACTIVE'` to rewrite all routes to `/maintenance` without a round-trip to the backend
- DB stays source of truth; Edge Config is a derived view

### Operations BC handoff
- `MaintenanceModeFilter` (operations module) inspects `SystemConfigCache` and short-circuits user-facing requests during an active maintenance window
- Administration owns the schedule; Operations enforces request-time behavior

## Consequences

- **DB is source of truth**, Redis is uninvolved — restart-durable without rehydration logic
- **Cron-driven activation** means a maintenance announcement can be staged hours in advance and will fire on its own; no human in the loop at activation time
- **Token injection**: the Vercel Edge Config endpoint requires a write token. We provision this via **DOT_ENV append on the GCE VM** (not Cloud Run) — see `docs/OPERATIONS.md` §1, §9
- The Edge Config dependency means the frontend has a non-Vercel-Edge fallback path (DB poll via `/api/v1/system/announcements/active`) for non-edge clients
- AsyncAPI must list `systemAnnouncementBroadcast` separately because it lacks the `partyroomId` metadata field that every other broadcast carries

## What we did NOT pick

- **Redis pub/sub instead of cron** for scheduled activation — required an always-on listener that holds the schedule; the cron is simpler and matches a "1-minute reaction time is fine for maintenance" SLA
- **Per-partyroom fan-out** — would have multiplied STOMP message volume by partyroom count without semantic gain
- **WebSocket-only delivery** — would have stranded users in non-WS pages (login, settings) during maintenance announcements; Edge Config handles the static-render case

## Verification

- `SystemAnnouncementCommandServiceTest`, `MaintenanceSchedulerServiceTest`, `SystemAnnouncementRepositoryIntegrationTest`, `AnnouncementBroadcasterIT`, `VercelEdgeConfigAdapterTest`, `AdminAnnouncementControllerTest`, `SystemStatusControllerTest`
- Frontend (pfplay-web) verifies edge middleware behavior in its own e2e
