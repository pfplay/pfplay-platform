# Presence Grace Window — Design

**Status**: draft
**Author**: backend
**Date**: 2026-05-09
**Related**: Issue #193 (re-entry exited_at), Issue #195 (Redis expired-event loss)

## Problem

When a user in a partyroom briefly disconnects (mobile background, screen lock, transient
network blip), the current system treats the disconnect as a hard exit: crew row goes
inactive, `crew_exited` broadcasts, and if they were the current DJ, `skipPlayback` cuts
their track and rotates the queue. When the same user comes back ~5–10s later, they
re-enter as a fresh crew row, triggering `crew_entered`. To other crew members the user's
avatar disappears and reappears; to the user, their listening session is interrupted; to
the room, the music may have been needlessly skipped.

Goal: absorb sub-grace-window disconnects silently while keeping the visible/audible
state stable. Make the perceived experience match what would happen if the network blip
had not occurred.

## Non-goals

- Detecting *why* the disconnect happened (heartbeat vs pagehide vs crash) — handled the same.
- Multi-tab presence dedup — out of scope. One tab per user is the assumed model.
- Cross-room state — multi-room invariant ("one user, one active room") still enforced as today.
- Redis pub/sub durability fix — tracked separately as Issue #195. This spec uses DB as
  source of truth so it does *not* depend on that fix landing first.

## Design overview

Three-state presence machine per crew row:

```
ONLINE ──[disconnect signal]──► PENDING_EXIT ──[grace TTL elapses]──► OFFLINE
   ▲                                  │
   └──────[reconnect within grace]────┘
```

- **ONLINE**: normal. `crew.is_active = 1`, no `pending_exit_at`.
- **PENDING_EXIT**: client gone but unconfirmed. `crew.is_active = 1`, `pending_exit_at = T`.
  No event broadcast to the room. Grace clock ticking.
- **OFFLINE**: confirmed gone. Existing `exit()` flow runs (deactivate crew + skipPlayback if
  current DJ + `crew_exited` broadcast).

Only the transition `PENDING_EXIT → OFFLINE` produces user-visible side effects in the room.
Both `ONLINE → PENDING_EXIT` and `PENDING_EXIT → ONLINE` are silent.

## State storage

### DB (truth)

New nullable column on `crew`:

```sql
ALTER TABLE crew ADD COLUMN pending_exit_at DATETIME(6) NULL;
ALTER TABLE crew ADD INDEX idx_crew_pending_exit (pending_exit_at);
```

- `pending_exit_at IS NULL`  AND `is_active = 1` → ONLINE
- `pending_exit_at IS NOT NULL` AND `is_active = 1` → PENDING_EXIT
- `is_active = 0` → OFFLINE (and historically possibly never PENDING_EXIT — old rows OK)

### Redis (timing trigger, not truth)

Per-crew expirable key whose only job is to fire a `__keyevent@*__:expired` notification
when grace elapses:

```
PRESENCE:PENDING:<partyroomId>:<crewId>   TTL=grace_seconds
```

- Value: irrelevant (DB has the truth). Empty string is fine.
- The expired event triggers the OFFLINE handler. Handler re-reads DB, confirms still
  PENDING_EXIT, then runs `exit()`-equivalent.

### Why both?

DB-only with a periodic cron is robust but introduces 5–10s jitter on grace expiration.
Redis TTL gives second-precision in normal operation. DB persistence + a low-frequency
recovery cron handles the Issue #195 failure mode (event lost during app restart) without
making this design block on #195 being fixed.

## Trigger paths

### Disconnect → PENDING_EXIT

Three signals can fire this transition. All three converge on a single domain method
`PartyroomPresenceService.markPending(partyroomId, userId)`:

1. **STOMP DISCONNECT frame** received by server. Existing session listener; needs to
   look up which partyroom the user was subscribed to.
2. **Heartbeat timeout** — server-side timer per session (existing STOMP heartbeat
   contract). When N seconds pass without heartbeat, treat as DISCONNECT.
3. **Explicit pagehide ping** from client (existing exit endpoint, but with a new
   `mode=pending` query param). Optimization: client knows it's about to background and
   tells the server proactively, reducing perceived disconnect latency.

`markPending` is idempotent: setting `pending_exit_at` when already set is a no-op (keep
the original timestamp so grace doesn't extend). Re-firing the Redis key is fine.

### Reconnect → ONLINE

Two signals:

1. **STOMP CONNECT** with same userId while a `PRESENCE:PENDING:<crewId>` key exists →
   `clearPending(partyroomId, userId)`.
2. (No second signal needed for now. STOMP re-subscribe is the canonical "I'm back".)

`clearPending`:
- DB: `UPDATE crew SET pending_exit_at = NULL WHERE crew_id = ?`
- Redis: `DEL PRESENCE:PENDING:<partyroomId>:<crewId>`
- Broadcasts: none. The room never knew this crew left.

### Grace expires → OFFLINE

`PresenceExpirationListener` (new) subscribes to `__keyevent@*__:expired` filtered for
`PRESENCE:PENDING:*`. On fire:

```java
1. Parse partyroomId + crewId from the key.
2. Re-read crew row. If pending_exit_at IS NULL → user already reconnected → return.
3. If is_active = 0 → already offline (race with explicit exit) → return.
4. Run exit-equivalent: deactivateCrew + handleDjQueueOnLeave + crew_exited broadcast.
5. Clear pending_exit_at as part of the same TX.
```

Step 4 reuses existing logic by delegating to `PartyroomAccessCommandService.exitInternal`
(extract the body of `exit()` so both the user-initiated DELETE endpoint and the presence
listener can call it without ThreadLocal AuthContext setup).

### Explicit user exit during PENDING_EXIT

User clicks "leave room" while their previous session was already PENDING_EXIT (e.g., one
tab silently disconnected, another tab clicks Exit):

- DELETE endpoint runs `exitInternal` → goes straight to OFFLINE
- `clearPending` runs as part of `exitInternal` to clean Redis key
- No double broadcast (atomic toggle on `is_active` already enforces single EXIT — see
  existing `deactivateCrew` returning row-count)

## Grace duration policy

Differentiated by role at the moment of disconnect:

| Role at disconnect | Grace | Source |
|---|---|---|
| Currently the playing DJ (`partyroom_playback.current_dj_crew_id == crewId`) | 30s | `system_config: presence.dj_grace_seconds` |
| Anyone else (including queued-but-not-current DJs) | 10s | `system_config: presence.listener_grace_seconds` |

Defaults seeded in `V16__add_presence_config.sql`. `SystemConfigCache` (already present)
caches values with 30s TTL. New `readInt` helper added; admin-driven changes can
`invalidate()` for instant propagation if/when an admin endpoint is added.

Grace is set **at the moment of disconnect** based on role at that instant. If the user
becomes/stops being current DJ during PENDING_EXIT (only possible via track-end rotation
during their grace), the grace is not retroactively adjusted. Simpler and adequate.

## Interaction with existing flows

### Track end during grace

If the current DJ is PENDING_EXIT and the track's `TASK:WAIT` expires naturally:
- `complete()` → `tryProceed()` → queue rotates → next DJ's track starts.
- The PENDING_EXIT user is still in queue (not removed yet). They'd be eligible for the
  next rotation, but they're now no longer "current DJ" — just queued.
- If their grace then expires, `exitInternal` runs and removes them from the queue. No
  visible churn because they were no longer the current DJ at that moment.
- DJ point award (`updateDjPointScore`) still fires for the just-completed track. Correct:
  the user did finish that track, even if disconnected.

### Multi-room invariant

Existing `tryEnter` calls `exit()` on the previous room before entering the new one. With
the presence model, this stays the same: explicit room-switch is an explicit signal, no
grace applied. The previous-room crew row goes straight to OFFLINE.

This means if a user is PENDING_EXIT in room A and a *different* device of theirs enters
room B, they'll be hard-exited from A (existing `exit()` runs). Acceptable.

### Re-entry after OFFLINE

After OFFLINE the crew row stays `is_active=0` with `exited_at` set. If the same user
later enters again (within or beyond presence grace expiration), the existing `tryEnter`
path runs. Note: Issue #193 (re-entry leaves stale `exited_at`) needs to be fixed in the
same PR that lands this presence model — otherwise the new `pending_exit_at` field would
similarly need explicit reset on re-entry. Reusing the fix is cheap.

## Recovery (Issue #195 hedge)

App restart between Redis key SET and TTL expiration loses the expired event. To prevent
permanent PENDING_EXIT zombies:

```java
@Scheduled(fixedDelay = 60_000) // every 60s
public void reconcilePendingExits() {
    LocalDateTime threshold = LocalDateTime.now(clock).minusSeconds(MAX_GRACE_SECONDS);
    crewRepository.findStalePending(threshold).forEach(crew ->
        presenceService.forceOffline(crew.getPartyroomId(), crew.getUserId()));
}
```

`MAX_GRACE_SECONDS` = max(djGrace, listenerGrace) + 10s buffer. Idempotent with the Redis
listener (both call `exitInternal`, which is itself idempotent via atomic toggle).

Same cron also acts as a startup recovery — first tick after boot catches anything
stranded during downtime.

## Race conditions handled

| Race | Resolution |
|---|---|
| Reconnect arrives while OFFLINE handler is mid-tx | Handler re-reads `pending_exit_at`; if NULL, returns. No double-broadcast. |
| Two disconnect signals (STOMP + heartbeat timeout) for same user | `markPending` is idempotent; `pending_exit_at` set once, Redis SET is overwrite-safe. Grace not extended. |
| Explicit Exit during PENDING_EXIT | `exitInternal` clears `pending_exit_at` and Redis key; OFFLINE handler later finds NULL and bails. |
| Recovery cron + Redis listener fire on same crew | Both call `exitInternal` → atomic toggle returns 0 on the second one → idempotent. |
| User PENDING_EXIT in room A, joins room B from different device | `tryEnter` for B calls `exitInternal` for A first → goes straight to OFFLINE, clears pending state. |

## Config

```sql
-- V16__add_presence_config.sql
INSERT INTO system_config (config_key, config_value, description) VALUES
  ('presence.dj_grace_seconds',       '30', '현재 DJ가 끊겼을 때 OFFLINE 판정까지의 유예(초)'),
  ('presence.listener_grace_seconds', '10', '일반 listener가 끊겼을 때 OFFLINE 판정까지의 유예(초)');
```

`ConfigKey` enum: `PRESENCE_DJ_GRACE_SECONDS`, `PRESENCE_LISTENER_GRACE_SECONDS`.
`SystemConfigCache.readInt(ConfigKey, int fallback)` helper added.

## Schema migration

```sql
-- V16__add_presence.sql (combine config + crew column)
ALTER TABLE crew
  ADD COLUMN pending_exit_at DATETIME(6) NULL,
  ADD INDEX idx_crew_pending_exit (pending_exit_at);

INSERT INTO system_config (config_key, config_value, description) VALUES
  ('presence.dj_grace_seconds',       '30', '현재 DJ가 끊겼을 때 OFFLINE 판정까지의 유예(초)'),
  ('presence.listener_grace_seconds', '10', '일반 listener가 끊겼을 때 OFFLINE 판정까지의 유예(초)');
```

No data backfill needed — column is nullable, all existing rows default to NULL = ONLINE
or OFFLINE depending on `is_active`.

## Implementation plan (single PR)

Coupled enough that splitting hurts review:

1. **V16 migration** — column + index + config seeds.
2. **`SystemConfigCache.readInt`** + presence ConfigKeys + Snapshot extension.
3. **`CrewData.pendingExitAt`** field + JPA mapping. Domain methods `markPending()`,
   `clearPending()`, `isPendingExit()`.
4. **`CrewRepository`**: `findStalePending(threshold)` query.
5. **Refactor `PartyroomAccessCommandService.exit()`**: extract body to
   `exitInternal(partyroomId, userId)` taking explicit args (no ThreadLocal). Public
   `exit()` becomes a thin wrapper. Also extract `expel()` body the same way for
   consistency.
6. **`PartyroomPresenceService`** (new): `markPending`, `clearPending`, `forceOffline`.
   Encapsulates DB write + Redis key SET/DEL.
7. **`PresenceExpirationListener`** (new): subscribes to `__keyevent@*__:expired`
   filtered for `PRESENCE:PENDING:*`. Delegates to `presenceService.forceOffline`.
   Pattern matches existing `PlaybackDurationWaitTopicListener`.
8. **STOMP DISCONNECT hook**: existing STOMP session listener (`SubscriptionEventListener`
   was seen in logs) gains a disconnect handler → `markPending` for each subscribed room.
9. **Heartbeat timeout**: STOMP heartbeat configured in `WebSocketConfig` —
   `setHeartbeatValue([10_000, 5_000])` + dedicated `ThreadPoolTaskScheduler`.
   Server-side timeout fires `SessionDisconnectEvent` automatically, which
   the existing `DisconnectionEventListener` already routes to
   `presencePort.onSessionDisconnected`.
10. **Reconcile cron** (`@Scheduled(fixedDelay = 60_000)`): scan stale PENDING_EXIT, call
    `forceOffline`. Doubles as startup recovery.
11. **Issue #193 fix in same PR**: `activatePresence` and `activateCrew` JPQL clear
    `exitedAt` AND `pendingExitAt` on re-entry.
12. **Tests**: see below.

## Test plan

- Unit: presence service state transitions (mark, clear, force-offline idempotency).
- Unit: `SystemConfigCache.readInt` (parse, fallback on garbage).
- Integration: full disconnect → reconnect within grace → no broadcast.
- Integration: full disconnect → grace expires → `crew_exited` fires once.
- Integration: current DJ disconnect → grace expires → `skipPlayback` fires once
  (verify track actually advances).
- Integration: track end during grace of current DJ → rotation proceeds, point awarded.
- Integration: explicit Exit during PENDING_EXIT → no double broadcast.
- Integration: stale PENDING_EXIT (older than max grace) → reconcile cron processes it.
- Integration: re-entry after OFFLINE → both `exited_at` and `pending_exit_at` cleared
  (Issue #193 regression).

## STOMP heartbeat (resolved)

Configured in `WebSocketConfig`:

```
server → client:  10000 ms  (server emits heartbeat every 10s)
client → server:   5000 ms  (server expects client heartbeat every 5s)
```

With STOMP's 1.5x grace, a missing client heartbeat triggers DISCONNECT within
~7.5s of the actual disconnect. That leaves ~2.5s of headroom inside the default
10s `listener_grace_seconds` before `forceOffline` fires — so a brief blip
(<10s total: detection + grace) does not disturb the room. A dedicated
single-thread `ThreadPoolTaskScheduler` bean drives the heartbeats.

Note: heartbeat is negotiated at CONNECT. The pfplay-web stomp client must
opt in (e.g., `client.heartbeatIncoming = 10000; client.heartbeatOutgoing = 5000`)
otherwise the negotiated value falls back to `0,0` and only TCP keepalive
detects disconnects (much slower). This is captured in the pfplay-web E2E
contract issue.

## Open questions for review

1. **Pagehide proactive ping**: do we want to add a small endpoint that the
   client can hit during the browser `pagehide` event (e.g., via
   `navigator.sendBeacon`) to enter PENDING_EXIT immediately rather than
   waiting for STOMP heartbeat timeout? The model works without it — STOMP
   DISCONNECT alone is enough trigger. Adding it shaves the ~7.5s detection
   gap off the disconnecting user's perceived recovery window. Defer until
   we measure real-world cases of "user reconnected within 10s but was
   already broadcast as exited".
2. **Multi-instance considerations**: deployment is currently single-instance
   per env. When/if we go multi-instance, `PRESENCE:PENDING:*` Redis keys are
   shared so the listener on any instance handles the expired event. The
   reconcile cron would need leader election to avoid duplicate processing.
   Out of scope for this PR; flag for future.

## Out of scope (deferred)

- Visible "(reconnecting…)" UI indicator. Decided against (silence over noise).
- Per-user variable grace (e.g., longer for paid users). YAGNI.
- Connection quality awareness (slow vs flaky). Beyond v1.
- Cross-room presence (showing "user X is in room Y" to friends). Different feature.
