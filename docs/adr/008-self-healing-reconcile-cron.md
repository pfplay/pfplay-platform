# ADR-008: Time-based state is repaired by reconcile crons, not trusted to Redis expiry events

**Date:** 2026-07-08
**Status:** Accepted
**Related issues:** #195 (lost expiry → stuck playback), #299, #304 (orphan DJ), #308
**Related work:** PR #309 (`PartyroomPlaybackReconcileService`)

## Context

Track advancement is driven by a Redis key whose TTL equals the track duration
(`TASK:WAIT:<roomId>`), consumed through keyspace expiry notifications
(`__keyevent@*__:expired`).

Redis pub/sub is fire-and-forget and **expired events are not buffered**. If no subscriber is
connected at the instant the key expires, the notification is gone permanently — the key is deleted
and nothing records that it ever existed.

This is not theoretical. On 2026-05-09 an app restart spanned the natural end of tracks in ~14 rooms;
each stayed pinned to a finished track for ~11 hours (`is_activated = true`, `current_playback_id`
pointing at a track that ended hours earlier). Nothing recovered on its own.

A related failure class came from the same "state can silently go wrong" family: a DJ row surviving
after its crew went inactive (#304), which wedges the rotation.

## Decision

Every time-based mechanism gets a **periodic reconciler that can repair the end state from the
database alone**, without needing the lost signal.

`PartyroomPlaybackReconcileService` runs `@Scheduled(fixedDelay = 60_000)` and performs two sweeps:

| Sweep | Detection | Repair |
|---|---|---|
| orphan DJ | rooms having a DJ whose crew is inactive | remove those DJ rows; if one was the current DJ, `skipPlayback` |
| stuck playback | `is_activated = 1` AND `end_time < now - 90s` | `skipPlayback` (advances or deactivates) |

Safety properties, all of which are load-bearing:

- **Per-room distributed lock.** Normal playback commands do not take this lock; it exists to stop
  multiple cron instances from repairing the same room concurrently.
- **Re-check inside lock + transaction.** The healer re-reads state after acquiring the lock and
  no-ops if the room already healed or a new track started. Running the job twice never
  double-advances.
- **90s buffer.** Keeps the sweep away from the normal path's timing, so a track that just ended is
  handled by the ordinary listener, not by the reconciler.
- **Separate bean for the healer.** `@Transactional` is applied via AOP, which self-invocation would
  bypass — so the re-check and the mutation must live in a different bean to share one transaction.

Failures are logged and retried on the next tick; the job is idempotent, so a lost tick costs at most
one interval.

## Consequences

**Positive**

- Recovery time went from "until a human notices" to ≤ interval + buffer (~60–150s).
- The same pattern is now reused for presence (`reconcileStalePending`, liveness sweep) and virtual
  crew population, giving one recognizable shape for self-healing across the codebase.
- Debugging gets easier: `[reconcile]` log lines say what was repaired and why.

**Negative / accepted cost**

- Steady-state DB load from queries that usually find nothing.
- Repair is eventual, not instant. Sub-minute correctness still depends on the normal path.
- A wrong detection query silently repairs healthy rooms. Detection predicates must stay narrow, and
  the in-lock re-check must remain the last word.

**Rejected alternative**

A durable delayed queue (e.g. Redisson `RDelayedQueue`) would remove the root cause instead of
compensating for it, but it introduces a new infrastructure dependency and a migration for every
existing timer. Revisit if the number of time-based features keeps growing.

## Rule of thumb

**If you add a feature that depends on a Redis key expiring, add its reconciler in the same PR.**
