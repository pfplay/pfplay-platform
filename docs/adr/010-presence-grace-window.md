# ADR-010: Presence Grace Window (V16)

**Date:** 2026-05-09
**Status:** Accepted
**Related spec:** docs/superpowers/specs/2026-05-09-presence-grace-window-design.md
**Migration:** `V16__add_presence.sql`

## Context

The original presence model treated WebSocket disconnect as immediate `OFFLINE`. This produced three failures in practice:

1. **DJ flicker** — a DJ whose Wi-Fi blinked for 2 seconds was kicked from the queue, the next DJ was promoted, and the original DJ reconnecting found themselves a listener. The "blink → kick" path was indistinguishable from an intentional exit.
2. **Listener churn** — short tab-suspend or network roams generated `crew_exited` / `crew_entered` pairs that spam-updated every other client's avatar grid.
3. **Restart fragility** — Redis-only presence keys (TTL-driven) didn't survive an app restart; on rolling deploy, every connected user appeared to vanish.

We need a presence model that (a) distinguishes a transient drop from a real exit, (b) gives a DJ more slack than a listener (broadcasting responsibility), and (c) survives application restarts.

## Decision

Introduce a two-state extension to crew presence with a grace timer driven by Redis TTL and authoritatively recorded in the database.

### Schema (V16)
```sql
ALTER TABLE crew
    ADD COLUMN pending_exit_at DATETIME(6) NULL,
    ADD INDEX idx_crew_pending_exit (pending_exit_at);
```

The new state lattice:
- `ONLINE` — connected (no `pending_exit_at`)
- `PENDING_EXIT` — recently disconnected, grace window running (`pending_exit_at` set; Redis TTL key live)
- `OFFLINE` — grace expired (handled like the old exit; `pending_exit_at` cleared, `crew_exited` broadcast fires)

### Source of truth
- **DB row** is authoritative. `crew.pending_exit_at` survives Redis flush, app restart, and rolling deploy.
- **Redis TTL key** drives the grace timer in normal operation — `PresenceExpirationListener` listens for Redis keyspace `expired` events and finalizes `OFFLINE`.
- **Cron safety net** scans `pending_exit_at` periodically so that a Redis outage cannot leave a crew stuck in `PENDING_EXIT` forever.

### Grace seconds (system_config)
```sql
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('presence.dj_grace_seconds',       '30', '현재 DJ가 끊겼을 때 OFFLINE 판정까지의 유예(초)'),
    ('presence.listener_grace_seconds', '10', '일반 listener가 끊겼을 때 OFFLINE 판정까지의 유예(초)');
```

DJ gets 30s, listener gets 10s. Values live in `system_config` (Operations BC) so they're tunable without a deploy.

### Components
- `realtime.port.PresencePort` — port interface (no domain imports)
- `app.party.adapter.out.realtime.PresencePortAdapter` — Party-side adapter
- `app.party.application.service.PartyroomPresenceService` — orchestrates state transitions
- `app.party.adapter.in.listener.PresenceExpirationListener` — Redis keyspace expire subscriber → finalizes OFFLINE

### Silent transitions
- `ONLINE → PENDING_EXIT` and `PENDING_EXIT → ONLINE` are **silent** (no STOMP broadcast). The frontend handles transient flicker locally and is not told.
- Only the final `PENDING_EXIT → OFFLINE` (or direct exit) emits the existing `crew_exited` event. AsyncAPI requires no new topic for V16.

## Consequences

- DJ disconnects of up to 30 seconds are recoverable without dropping the queue position
- Listener-side broadcast spam falls dramatically — short disconnects no longer trigger fan-out
- Application restart: in-flight `PENDING_EXIT` crews stay in that state until the cron safety net catches them (acceptable; bounded by cron interval)
- Grace seconds are tunable at runtime via `system_config` rows + `SystemConfigCache` invalidation
- No public surface change — clients see the same `crew_exited` event they always did, just less often

## What we did NOT pick

- **Redis-only state** — failed the restart-durability requirement
- **DB-only state with a polling timer** — adds DB load proportional to active partyrooms; Redis TTL is purpose-built for this
- **Per-crew grace overrides** — premature; two roles (DJ vs listener) covered the observed pain
- **Broadcasting `PENDING_EXIT`** — would have leaked an implementation detail to frontends, and silent recovery is the desired UX

## Verification

- `PartyroomPresenceServiceTest`, `CrewRepositoryAtomicToggleIT`, listener tests under `realtime/.../event/`
- Operational: rolling deploy in stg produced zero spurious `crew_exited` events for online crews (the prior failure mode)
