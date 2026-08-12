# PFPlay Backend

> **PFP Playground for Music** — Real-time music party room platform

A Spring Boot backend powering PFPlay: users create party rooms, take turns as DJ, and listen to the
same track in sync while chatting and reacting in real time.

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-8.0.30-blue.svg)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-7-red.svg)](https://redis.io/)

> **Doc index**: [`docs/DOCS_ENTRY.md`](docs/DOCS_ENTRY.md) lists every document in this repo and what
> it is good for. Docs under `docs/` are written in Korean; this README is the English entry point.

## Table of Contents

- [Features](#features)
- [Technology Stack](#technology-stack)
- [Architecture](#architecture)
- [Getting Started](#getting-started)
- [Testing](#testing)
- [API Documentation](#api-documentation)
- [WebSocket Contract](#websocket-contract)
- [Reliability: self-healing schedulers](#reliability-self-healing-schedulers)
- [Project Structure](#project-structure)
- [Configuration](#configuration)
- [Resources](#resources)

## Features

### Party Room System
- **Real-time party rooms** — synchronized playback across every client in the room
- **DJ queue** — rotation over queued DJs, each playing a track from their playlist
- **Quick-DJ** — one-shot queue entry from a single picked track (no playlist setup needed)
- **Playback cursor** — tracks expose NOW / NEXT position for the current rotation
- **Room management** — stage type, playback time limit, notice, link domain, display flags

### Presence & Crew
- **Presence state machine** — ONLINE → PENDING_EXIT (grace window) → OFFLINE, driven by WS liveness
  rather than client-declared intent. The state is *derived* from `crew.is_active` +
  `crew.pending_exit_at`; there is no stored presence enum
- **One active party room per user** — enforced as a DB invariant (V38 unique index), not just in code
- **Hierarchical grades** — HOST, COMMUNITY_MANAGER, MODERATOR, CLUBBER, LISTENER
- **Moderation** — grade changes, chat penalties, kick/ban, blocking, full audit history

### Virtual Crew (bots)
- **Persona bots** — operator-managed bots that occupy rooms, DJ from song packs, and chat via an LLM
- **Reconcile scheduler** — keeps MANAGED rooms at their configured bot population
- **Admin-driven** — bot pool, song packs, room assignment and chat config are all console-operated

### Real-time Communication
- **STOMP over WebSocket** — one subscription per party room, message kind discriminated by topic
- **Redis pub/sub relay** — domain events fan out across instances after commit
- **System announcements / maintenance** — broadcast channel plus Vercel Edge Config gate

### Playlist & Music
- **User playlists** with track ordering and a default playlist seeded at signup
- **Music search** via the `pfplay-streaming` sidecar (see [Configuration](#configuration))
- **Grab** — save the currently playing track into a personal playlist

### Notification
- **Web Push (VAPID)** — subscription management plus fan-out on system announcements.
  Disabled unless `WEB_PUSH_ENABLED=true` and VAPID keys are supplied.

### Administration
- **Admin console API** — members, guests, party rooms, reports, bug reports, avatars, announcements,
  administrators, virtual crew
- **Analytics** — daily enter/exit aggregates, per-room behavior analytics, DJ history

### Authentication & Security
- **OAuth2 social login** — Google, Twitter
- **Guest sign-in** — anonymous, room-capable identity
- **JWT in cookies** — shared session token for users, separate short-lived admin token
- **Method-level authorization** — `@PreAuthorize` + authority tiers (FM / AM / GT)

## Technology Stack

### Core
- **Java 21**, **Spring Boot 3.2.3**, **Gradle** multi-module (6 modules)

### Spring
Web · Security · Data JPA · Data Redis · WebSocket (STOMP) · WebFlux (outbound HTTP) ·
OAuth2 Client & Resource Server · Cache · Validation

### Data
- **MySQL 8.0.30** — primary store; schema owned by **Flyway** (`V1` … `V39`), `ddl-auto: validate`
  on every profile
- **Redis 7** — cache, pub/sub, distributed locks, keyspace-expiry timers
  (`--notify-keyspace-events Ex`)
- **JPA/Hibernate**, **QueryDSL 5.0.0**, **P6Spy** (SQL logging)

### Notable libraries
| Library | Use |
|---|---|
| `io.jsonwebtoken:jjwt 0.12.3` / `com.auth0:java-jwt 4.4.0` | JWT issue & verify |
| `io.hypersistence:hypersistence-tsid 2.1.3` | TSID identifiers |
| `nl.martijndwars:web-push 5.1.1` + BouncyCastle | Web Push (VAPID) |
| `com.bucket4j:bucket4j-core 8.10.1` + Caffeine | Rate limiting |
| `net.logstash.logback:logstash-logback-encoder 7.4` | Structured JSON logs + MDC requestId |
| `springdoc-openapi 2.4.0` | Swagger UI at `/spec/api` |
| `archunit-junit5 1.2.1` | Architecture rules enforced in `:app:test` |
| `testcontainers 1.19.6` | MySQL + Redis for integration tests |

> There is **no Spring Boot Actuator dependency** — do not expect `/actuator/**` to answer.
> See [Getting Started](#getting-started) for how readiness is actually checked.

## Architecture

### Hexagonal (Ports & Adapters) + DDD

```
┌─────────────────────────────────────────────────────┐
│  Inbound Adapters                                   │
│  adapter/in/web/       REST controllers             │
│  adapter/in/stomp/     STOMP controllers            │
│  adapter/in/listener/  Redis topic listeners        │
│  adapter/in/event/     Spring event listeners       │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌──────────────────────┴──────────────────────────────┐
│  Application Layer                                  │
│  application/service/   use-case orchestration      │
│  application/port/out/  outbound port interfaces    │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌──────────────────────┴──────────────────────────────┐
│  Domain Layer                                       │
│  domain/entity/data/  JPA entities + business logic │
│  domain/service/      domain services               │
│  domain/value/        value objects                 │
│  domain/event/        domain events                 │
└──────────────────────┬──────────────────────────────┘
                       ↑
┌──────────────────────┴──────────────────────────────┐
│  Outbound Adapters                                  │
│  adapter/out/persistence/  JPA + QueryDSL           │
│  adapter/out/external/     cross-BC port adapters   │
│  adapter/out/event/        Redis relay              │
└─────────────────────────────────────────────────────┘
```

ArchUnit tests enforce the boundaries (`domain → adapter` = 0, `domain → application` = 0,
cross-module access only through ports). They run as part of `:app:test`, so a boundary violation
fails the normal test task — not a separate opt-in job.

### Bounded contexts

| Context | Location | Role |
|---|---|---|
| **Party** (core) | `app` → `api.party.*`, `api.partyview.*` | rooms, crew, presence, DJ queue, playback, chat |
| **Virtual Crew** | `app` → `api.virtualcrew.*` | persona bots, song packs, room population |
| **Administration** | `app` → `api.administration.*` | admin surfaces, reports, announcements, analytics |
| **Notification** | `app` → `api.notification.*` | Web Push subscriptions and fan-out |
| **Operations** | `app` → `api.operations.*` | runtime system config |
| **IAM / Auth** | `app` → `api.auth.*` + `common` security config | OAuth2, tokens, sessions |
| **User Profile** | `user` module | member/guest identity, profile, activity score |
| **Playlist** | `playlist` module | playlists, tracks, music search |
| **Avatar** | `avatar` module | avatar catalog (pure producer BC) |
| **Realtime** | `realtime` module | WebSocket/STOMP infrastructure, zero domain imports |
| Shared Kernel | `common` module | VOs, base entity, exceptions, security/infra config |

Full map, integration rules and the port inventory: [`docs/CONTEXT_MAP.md`](docs/CONTEXT_MAP.md).

### Module dependency direction

```
app → user → avatar → common
app → playlist → common
app → realtime → common
```

`app` is the composition root and may depend on every module; the reverse never holds. Gradle
enforces this at compile time.

### Event flow

Domain events are registered on entities (`BaseEntity.registerEvent`) and published after commit.
`DomainEventRedisRelay` listens with `@TransactionalEventListener(AFTER_COMMIT)` and republishes to a
Redis topic; each instance's topic listener then pushes the message to STOMP subscribers. This makes
broadcasts safe across horizontally scaled instances.

> **Gotcha**: an `AFTER_COMMIT` listener that writes to the DB needs `REQUIRES_NEW` — the original
> transaction is already committed and will not flush anything new.

## Getting Started

### Prerequisites

- **Java 21** (`JAVA_HOME` must point at a 21 JDK)
- **Docker & Docker Compose**
- A `.env.local` file (see [Configuration](#configuration))

### Run the full stack locally

The local stack is a compose project containing MySQL, Redis, the `pfplay-streaming` sidecar and the
app itself:

```bash
# builds app/Dockerfile from the current working tree
docker compose -f docker-compose.local.yml -p pfplay-local --env-file .env.local up -d --build
```

The app listens on `http://localhost:8080`.

> **The image packages an already-built jar.** Run `./gradlew :app:bootJar` before `up --build` after
> changing Java code — otherwise the container silently boots the previous build.

> `up -d app` alone does **not** start the pytube sidecar. Music search will fail until the `pytube`
> service is up.

### Run the app from Gradle instead

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

Infrastructure (MySQL, Redis, pytube) still has to be running — start it with the compose file above.

### Verify

There is no actuator endpoint. Any HTTP response from the app means Spring is up and routing:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/     # any 2xx/3xx/401/403 → booted
curl -s http://localhost:8080/spec/api                              # Swagger UI (non-prod profiles)
```

`scripts/deploy.sh {dev|stg|prod}` uses the same permissive readiness check. Note its known
limitation — a readiness timeout still exits 0, so a genuinely failed boot can be reported as a
successful deploy (tracked in [#342](https://github.com/pfplay/pfplay-platform/issues/342)).

### First boot and schema

Flyway owns the schema and runs on startup for every profile; JPA is `validate` only. A fresh local
DB is migrated `V1 → V39` automatically. Because `ddl-auto` never creates tables, **a missing
migration surfaces as a boot failure rather than as silently drifting tables** — that is intentional.

## Testing

```bash
./gradlew test                 # unit + slice + ArchUnit. Excludes @Tag("integration")
./gradlew :app:integrationTest # Testcontainers (MySQL + Redis) integration tests only
```

- The default `test` task **excludes** the `integration` tag; `integrationTest` includes only it.
- Integration tests boot the real Flyway schema and validate it, then reset state between tests with
  `DatabaseCleaner` (truncate). This is why `integrationTest` runs with `maxParallelForks = 1` —
  parallel forks would truncate each other's shared schema.
- CI (`.github/workflows/ci-test.yml`) runs both, once per commit: PR branches trigger on
  `pull_request` only, and long-lived branches on `push` — the duplicate-trigger problem was removed
  in #364.
- Run the **whole** `:app:test` before merging, not just the area you touched: ArchUnit rules live
  there and a cross-boundary regression will not show up in a narrower task.

## API Documentation

Swagger UI: `http://localhost:8080/spec/api` (disabled on the prod profile).

### Endpoint families

| Prefix | Contents |
|---|---|
| `/api/v1/auth/**` | OAuth URL, OAuth callback, logout, admin login |
| `/api/v1/users/**` | member/guest sign-in, profile, avatar |
| `/api/v1/partyrooms/**` | room CRUD, access (enter/exit), notice, playback, reactions, DJ queue, crew moderation |
| `/api/v1/crews/**` | crew profile lookup |
| `/api/v1/playlists/**` | playlists and tracks |
| `/api/v1/music-search` | music search through the streaming sidecar |
| `/api/v1/voc/bug-reports` | user bug reports (guests allowed by design) |
| `/api/v1/push/subscriptions` | Web Push subscription register / revoke |
| `/api/v1/system/**` | system status, active announcement |
| `/api/v1/admin/**` | admin console API (separate cookie + authority tier) |

The generated OpenAPI document is the source of truth for exact paths and payloads — the table above
is a map, not a contract.

### Authentication

| Token | Lifetime | Notes |
|---|---|---|
| shared session token (cookie) | **24h** (`shared-session-token-expiration-ms`) | issued for members *and* guests |
| admin access token (cookie) | **15m** (`admin-access-token-expiration-ms`) | admin console only, separate cookie domain |

> There is **no user-facing refresh token and no sliding renewal**. When the 24h session expires the
> HTTP side starts returning 401 while an already-established WebSocket keeps running under the
> identity frozen at handshake time. That asymmetry is a known reliability gap, tracked in
> [#306](https://github.com/pfplay/pfplay-platform/issues/306) — do not document or rely on a refresh
> flow until that issue ships.

## WebSocket Contract

**Endpoint**: `ws://localhost:8080/ws` (STOMP). Authentication happens **once**, in the handshake, by
reading the session cookie; the resolved `uid` becomes the STOMP principal.

| Prefix | Meaning |
|---|---|
| `/pub` | application destination prefix (client → server) |
| `/sub` | simple broker prefix (server → client) |
| `/user` | user-destination prefix |

### Publish (client → server)

- `/pub/groups/{chatroomId}/send` — group chat message (`chatroomId` == `partyroomId`)
- `/pub/heartbeat` — liveness ping
- `/pub/private/{chatroomId}/send` — **mapped but not implemented** (handler is a TODO stub that
  returns OK without sending anything). Do not build against it.

### Subscribe (server → client)

- `/sub/partyrooms/{partyroomId}` — **all** room events on a single destination
- `/sub/system/announcements` — announcement / maintenance lifecycle
- `/user/sub/heartbeat` — per-user heartbeat reply

Clients discriminate room events by the message's event-type field rather than by destination. The
kinds come from `MessageTopic`:

```
PLAYBACK_STARTED · PLAYBACK_DEACTIVATED · DJ_QUEUE_CHANGED
CREW_ENTERED · CREW_EXITED · CREW_GRADE_CHANGED · CREW_PENALIZED · CREW_PROFILE_CHANGED
REACTION_PERFORMED · REACTION_AGGREGATION_UPDATED
CHAT_MESSAGE_SENT · PARTYROOM_NOTICE_UPDATED · PARTYROOM_CLOSED
ROOM_TERMINATED · ROOM_SUSPENDED · ROOM_RESTORED
```

`MessageTopic` also contains `CREW_PROFILE_PRE_CHECK`, which is an **internal Redis topic** used to
serialize profile-change processing across instances — it is never delivered to clients.

The machine-readable contract lives in [`docs/asyncapi/asyncapi.yml`](docs/asyncapi/asyncapi.yml).

> **One room subscription at a time.** A client must not hold subscriptions to two party rooms
> simultaneously; both the web client and the server treat that as an invariant violation.

```javascript
stompClient.subscribe('/sub/partyrooms/' + partyroomId, (message) => {
  const event = JSON.parse(message.body);
  switch (event.topic) {
    case 'PLAYBACK_STARTED': /* ... */ break;
    case 'DJ_QUEUE_CHANGED': /* ... */ break;
  }
});
```

## Reliability: self-healing schedulers

Time-based behavior leans on Redis keyspace expiry, which is fire-and-forget: if no subscriber is
connected at the moment a key expires, that notification is gone forever. Every such mechanism
therefore has a periodic reconciler behind it. These are load-bearing, not cosmetic.

| Job | Cadence | What it repairs |
|---|---|---|
| `PartyroomPlaybackReconcileService.reconcile` | 60s | orphan DJs whose crew went inactive; playback stuck with `is_activated=1` and an `end_time` older than 90s (lost expiry event) |
| `PartyroomPresenceService.reconcileStalePending` | 60s | crews left in PENDING_EXIT past their grace window — also doubles as startup recovery |
| `PartyroomPresenceService` liveness sweep | 5m (delayed after boot) | "ghost online" crews: active in DB but with no live WebSocket session, cross-checked against `SimpUserRegistry` |
| `VirtualCrewReconcileScheduler.reconcileManagedRooms` | 60s | bot population drift in MANAGED rooms; bot chat/reaction ticks |
| `MaintenanceSchedulerService` | every minute (×2) | maintenance window start/end transitions |
| `PartyroomCommandService` daily sweep | 03:00 | scheduled room cleanup |

Design notes and the reasoning behind each: [`docs/OPERATIONS.md`](docs/OPERATIONS.md),
[`docs/adr/008-self-healing-reconcile-cron.md`](docs/adr/008-self-healing-reconcile-cron.md),
[`docs/adr/009-presence-liveness-sweep.md`](docs/adr/009-presence-liveness-sweep.md).

## Project Structure

```
pfplay-platform/
├── common/        # Shared Kernel + infra config (JPA, Redis, JWT, Security, Swagger)
├── realtime/      # WebSocket/STOMP infrastructure — ports only, zero domain imports
├── playlist/      # Playlist domain + music search
├── avatar/        # Avatar catalog (pure producer BC)
├── user/          # Member/guest identity, profile, activity
├── app/           # Party, Virtual Crew, Administration, Notification, Operations, Auth, Bootstrap
│   └── src/main/resources/db/migration/   # Flyway V1..V39 — the schema source of truth
├── scripts/deploy.sh
├── docker-compose.{local,dev,stg,prod}.yml
└── docs/          # Korean design docs, ADRs, operations guide (see docs/DOCS_ENTRY.md)
```

Each domain package follows the same internal shape:

```
{domain}/
├── adapter/in/{web,stomp,listener,event}/
├── adapter/out/{persistence,external,event}/
├── application/{service,port/out,dto}/
└── domain/{entity/data,service,value,enums,event,port}/
```

## Configuration

### Profiles

`local` · `dev` · `staging` · `prod`, plus a shared `common` document that the others inherit. All of
them use `ddl-auto: validate` and enable Flyway.

Branch ↔ environment mapping: `develop` → dev, `release` → staging, `main` → prod.

### Environment variables

Supplied through `.env.{profile}` (compose `env_file`) — the names below are the actual placeholders
in `application.yml`.

**Database / cache**
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `REDIS_HOST`, `REDIS_PORT`

**Auth / cookies**
- `JWT_SECRET`
- `COOKIE_DOMAIN`, `COOKIE_SECURE`, `COOKIE_SAME_SITE`, `ADMIN_COOKIE_DOMAIN`
- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`
- `TWITTER_CLIENT_ID`, `TWITTER_CLIENT_SECRET`, `TWITTER_REDIRECT_URI`

**CORS**
- `CORS_ALLOWED_ORIGINS`, `CORS_ALLOWED_METHODS`, `CORS_ALLOWED_HEADERS`, `CORS_ALLOW_CREDENTIALS`,
  `CORS_MAX_AGE`

**Music search sidecar** (`pfplay-streaming`)
- `PYTUBE_URI`, `PYTUBE_API_KEY`, `PYTUBE_API_SECRET`

> Music search does **not** call the YouTube Data API directly — it proxies to the streaming sidecar,
> which is why there is no YouTube API key here.

**Virtual crew LLM** (optional — bots skip LLM responses when unset)
- `LLM_PROVIDER` (`openai` by default), `OPENAI_API_KEY`, `OPENAI_BASE_URI`, `OPENAI_MODEL`,
  `OPENAI_TIMEOUT_MS`
- `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URI`, `ANTHROPIC_MODEL`, `ANTHROPIC_TIMEOUT_MS`

**Web Push** (fails closed when disabled)
- `WEB_PUSH_ENABLED` (default `false`), `WEB_PUSH_VAPID_PUBLIC_KEY`, `WEB_PUSH_VAPID_PRIVATE_KEY`,
  `WEB_PUSH_SUBJECT`

**Avatar storage (GCS)**
- `PFPLAY_AVATAR_BUCKET`, `PFPLAY_AVATAR_GCS_KEY_PATH`

**Maintenance gate (Vercel Edge Config)**
- `VERCEL_API_TOKEN`, `VERCEL_TEAM_ID`, `VERCEL_EDGE_CONFIG_ID`, `VERCEL_EDGE_CONFIG_KEY`

## Resources

### Docs in this repo
- [Doc index](docs/DOCS_ENTRY.md) — what exists and when to read it
- [Context Map](docs/CONTEXT_MAP.md) — bounded contexts, ports, events
- [Operations](docs/OPERATIONS.md) — schedulers, maintenance mode, deploy, environments
- [Known issues](docs/KNOWN_ISSUES.md) — traps worth knowing before you debug
- [ADRs](docs/adr/) — architecture decision records
- [Naming convention](docs/NAMING_CONVENTION.md)
- [Refactoring roadmap](docs/REFACTORING_ROADMAP.md)

### External
- [Spring Boot](https://docs.spring.io/spring-boot/docs/current/reference/html/) ·
  [Spring Security](https://docs.spring.io/spring-security/reference/) ·
  [Spring WebSocket](https://docs.spring.io/spring-framework/reference/web/websocket.html) ·
  [Spring Data JPA](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/) ·
  [QueryDSL](http://querydsl.com/static/querydsl/latest/reference/html/) ·
  [Flyway](https://documentation.red-gate.com/fd)

## License

Copyright (c) PFPlay. All rights reserved.
