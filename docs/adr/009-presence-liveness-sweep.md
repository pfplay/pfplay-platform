# ADR-009: Ghost presence is resolved against live WebSocket sessions, not against client intent

**Date:** 2026-07-22
**Status:** Accepted
**Related issues:** #241 (suspected ghost online — confirmed in prod), #356
**Related work:** PR #357
**Related ADR:** [008](008-self-healing-reconcile-cron.md)

## Context

Crew presence is *derived*, not stored: a row is ONLINE when `is_active = 1 AND pending_exit_at IS
NULL`, PENDING_EXIT once `pending_exit_at` is set, and OFFLINE after the grace window expires.

Every existing recovery path keyed off `pending_exit_at`:

- the Redis grace timer fires on `PRESENCE:PENDING:<crewId>` expiry;
- `reconcileStalePending` sweeps rows whose `pending_exit_at` is older than the grace window.

Which means a row that never got `pending_exit_at` set was **invisible to all of them**. That happens
whenever the disconnect signal is lost: silent WebSocket death, a Redis failure rolling back
`markPending`, or an instance dying without running its disconnect handlers.

#241 filed this as a suspicion. Production settled it: two real users sat as "online" ghosts for
days, and their rooms carried a phantom occupant that nothing could clear until they re-entered.

The deeper point: presence had no source of truth for *"is this person actually connected right
now?"* — only a record of transitions it had been told about.

## Decision

Add a low-frequency sweep that answers that question directly, by comparing DB state against the
**live STOMP session registry**.

`PartyroomPresenceService.sweepOrphanActiveCrews()`:

1. **Candidates** — `is_active = 1 AND pending_exit_at IS NULL`, excluding bots (`is_dummy`) and
   anyone who entered within the last 10 minutes, via `LivenessSweepQueryPort`.
2. **Verdict** — `SimpUserRegistry.getUser(uid)`. A live in-process STOMP session means a genuine
   participant; absence means the leave signal was lost.
3. **Action** — `markPending`, *not* an immediate exit. The row simply joins the existing state
   machine.

Cadence is 5 minutes with a 5-minute boot delay.

### Why these choices

- **Judged by the session registry, not by heartbeats or client messages.** A client can lie or go
  quiet; an open server-side session cannot be faked. This is the only place in the system that knows
  the truth about connectivity.
- **`markPending` instead of a direct exit.** It reuses the proven path — a false positive is undone
  by `clearPending` when the user reconnects inside the grace window, and a real ghost exits normally
  (DJ queue cleanup + EXIT event) when it expires. The sweep therefore cannot cause an abrupt kick.
- **Boot delay.** The registry is in-process and empty right after startup; sweeping immediately
  would classify every reconnecting user as a ghost.
- **Recent-enter exclusion.** A user who just entered over HTTP may not have completed the WebSocket
  handshake yet.
- **5-minute interval.** Ghosts persisted for days, so nothing is gained by sweeping aggressively and
  DB load stays negligible.

## Consequences

**Positive**

- Closes the last presence gap that no timer covered, and does so without adding a new state.
- Downstream cascades (`forceOffline → exitInternal → handleDjQueueOnLeave → skipPlayback`) come for
  free, because the sweep feeds the existing machine.

**Negative / accepted cost**

- **`SimpUserRegistry` is in-process — this design assumes a single app instance.** With horizontal
  scaling each instance sees only its own sessions and would classify other instances' users as
  ghosts. Before scaling out, this must be promoted to a per-instance sweep or a Redis-backed session
  union. Treat that as a hard prerequisite, not a nice-to-have.
- Detection is delayed by up to one interval plus the grace window.
- `@Transactional` sits on the sweep itself because `markPending` is a self-invocation and would
  otherwise bypass the proxy — a subtlety that must survive future refactors of this class.
