# App Module — Party / Virtual Crew / Administration / Notification / Auth / Bootstrap

> 실사 기준: 2026-07-31 (`origin/develop`).

`app` 은 컴포지션 루트이자 여러 BC 가 함께 사는 최상위 모듈이다. 모든 모듈에 의존할 수 있고,
역방향 의존은 없다.

| 패키지 | BC | 요약 |
|---|---|---|
| `api.party.*` · `api.partyview.*` | **Party (Core)** | 파티룸, 크루, presence, DJ 큐, 재생, 채팅 |
| `api.virtualcrew.*` | Virtual Crew | 페르소나 봇, 송팩, MANAGED 룸 인원 유지 |
| `api.administration.*` | Administration | 어드민 API, 신고·버그리포트, 공지·점검, 분석 |
| `api.notification.*` | Notification | Web Push 구독 관리 및 fan-out |
| `api.operations.*` | Operations | 런타임 시스템 설정(`SystemConfig`) |
| `api.auth.*` | IAM / Auth | OAuth2, 토큰, 로그아웃 |
| `api.admin.*` | (구 표면) | 데모·시뮬레이션 컨트롤러. **신규 기능은 `administration` 으로** |
| `api.bootstrap.*` | — | 컴포지션 루트 — 모듈 경계를 넘는 어댑터 배치 |

---

## Party Context

### 책임

- 파티룸 생성/관리/종료, 링크 도메인, 공지, 표시 플래그
- 크루 입장/퇴장, **presence 상태 기계**, 등급, 제재
- DJ 큐 등록/해제, 회전, Quick-DJ 단발 등록
- 재생 제어(시작·스킵·시간 제한)와 반응 집계
- 실시간 채팅 (Redis pub/sub + STOMP)

### Partyroom Aggregate

- **Root**: `PartyroomData`
- **내부 엔티티**: `CrewData`, `DjData`, `DjQueueData`, `PartyroomPlaybackData`
- **Aggregate Port**: `PartyroomAggregatePort` (`domain/port/`)

### 핵심 엔티티

| 엔티티 | 대표 비즈니스 로직 |
|---|---|
| `PartyroomData` | `create()`, `terminate()`, `validateHost()`, `updateBaseInfo()` |
| `CrewData` | `deactivatePresence()`, `markPending()`/`clearPending()`, `enforceBan()`, `isBelowGrade()` |
| `DjData` / `DjQueueData` | DJ 참여, 큐 상태(열림/닫힘) |
| `PartyroomPlaybackData` | 현재 재생 상태, `deactivate()`, `isCurrentDj()` |
| `PlaybackData` | 재생 이력(트랙, `endTime`) |
| `PlaybackAggregationData` | 반응 집계 |

> **presence 는 저장된 enum 이 아니다.** `crew.is_active` + `crew.pending_exit_at` 조합에서
> 파생된다 — ONLINE / PENDING_EXIT(grace) / OFFLINE. 상태를 새로 추가하기 전에 이 파생 규칙을 먼저 본다.

> **유저당 활성 크루 1개는 DB 불변식이다** (`uk_crew_active_user`, V38). 애플리케이션에서만
> 막지 않는다.

### Application Service (명령/조회 분리)

| Service | 역할 |
|---|---|
| `PartyroomCommandService` / `PartyroomQueryService` | 파티룸 CRUD / 조회 (+ 03:00 정리 크론) |
| `PartyroomAccessCommandService` / `PartyroomAccessQueryService` | 입장·퇴장 / 접근 조회 |
| `PartyroomPresenceService` | presence 전이, grace, **reconcile·liveness 스윕** |
| `PartyroomPlaybackReconcileService` | 고아 DJ·고착 재생 자가치유 크론 |
| `DjCommandService` · `QuickDjService` | DJ 큐 관리, 단발 등록 |
| `PlaybackCommandService` / `PlaybackQueryService` | 재생 제어 / 조회 |
| `PlaybackReactionCommandService` · `…PostProcessCommandService` · `…QueryService` | 반응 처리·후처리·조회 |
| `CrewGradeCommandService` · `CrewPenaltyCommandService`/`QueryService` · `CrewBlockCommandService`/`QueryService` | 등급·제재·차단 |
| `PartyroomNoticeCommandService` / `PartyroomNoticeQueryService` | 공지 수정 / 조회 |
| `UserSessionRegistry` | 사용자 세션 레지스트리 |
| `chat/` · `lock/` | 채팅 서비스, 분산락 실행기 |

### 도메인 이벤트

`CrewAccessedEvent`(ENTER/EXIT) · `CrewGradeChangedEvent` · `CrewPenalizedEvent` ·
`AdminCrewPenalizedEvent` · `AdminCrewPenaltyReleasedEvent` · `DjQueueChangedEvent` ·
`PlaybackStartedEvent` · `PlaybackDeactivatedEvent` · `ReactionMotionChangedEvent` ·
`ReactionAggregationChangedEvent` · `PartyroomCreatedEvent` · `PartyroomClosedEvent` ·
`PartyroomNoticeUpdatedEvent` ·
`PartyroomTerminatedEvent` · `PartyroomSuspendedEvent` · `PartyroomRestoredEvent` ·
`PartyroomMetaUpdatedEvent` · `PartyroomDisplayFlagChangedEvent`

`DomainEventRedisRelay` 가 `AFTER_COMMIT` 에 받아 Redis 토픽으로 재발행하고, 각 인스턴스의
`GroupBroadcastTopicListener` 가 `/sub/partyrooms/{id}` 로 밀어낸다.

### 소비하는 Cross-BC Port

`PlaylistCommandPort` · `PlaylistQueryPort` · `UserProfileQueryPort` · `UserActivityPort` ·
`GuestAuthPort` · `LivenessSweepQueryPort` · `ExpirationTaskPort` · `ChatPenaltyCachePort`

---

## Virtual Crew Context

운영자가 관리하는 페르소나 봇이 방을 채우고, 송팩에서 DJ 를 하고, LLM 으로 대화한다.

- `VirtualCrewReconcileScheduler` (60초) — MANAGED 룸의 봇 인원 정합 + 채팅/반응 틱
- `VirtualCrewBootReviver` · `VirtualCrewManagedRoomSweeper` — 부팅 복구, 룸 정리
- 어드민 API: `AdminVirtualCrewController` (봇 풀·송팩·룸 배치·채팅 설정)
- LLM 은 `LLM_PROVIDER` + API 키가 있을 때만 동작한다(미설정 시 응답 skip)

> 봇 '좋아요' 반응 로직은 구현돼 있으나 `vcrew.reaction.enabled` 기본값이 `false` 이고
> **어드민 토글이 없다** ([#343](https://github.com/pfplay/pfplay-platform/issues/343)).

---

## Administration Context

어드민 콘솔의 백엔드. 회원·게스트·파티룸·신고·버그리포트·아바타·공지·관리자 관리와 분석 API.

- 점검 라이프사이클: `MaintenanceSchedulerService`(분 단위) + `ActiveMaintenanceGate`(30초 캐시)
  + `AnnouncementBroadcaster`(WS + Vercel Edge Config)
- 점검 상태의 SoT 는 `system_announcement` 의 ACTIVE 행이다
- **Party 내부 엔티티 import 금지** — 값 객체·포트만 (ArchUnit 검증)

---

## Notification Context

Web Push(VAPID) 구독 저장과 공지 fan-out.

- `PushSubscriptionController` (`/api/v1/push/subscriptions`)
- `PushFanoutService` — keyset 페이지네이션 + **HTTP 발송은 트랜잭션 밖**, GONE(404/410) 구독은
  단일 bulk revoke 트랜잭션으로 정리
- `WEB_PUSH_ENABLED=false` 가 기본이라 키가 없으면 fail-closed

---

## Auth Context

- OAuth2 인증 흐름 (Google, Twitter), 게스트 사인인, 로그아웃
- 세션 토큰 24h, 어드민 액세스 토큰 15분. **사용자용 refresh 토큰은 없다**
  ([#306](https://github.com/pfplay/pfplay-platform/issues/306))
- Port: `StateStorePort`(OAuth state) · `PartyCleanupPort`(로그아웃 시 파티 정리)

---

## Bootstrap (Composition Root)

모듈 경계를 넘지만 어느 도메인에도 속하지 않는 어댑터를 배치한다.

| 어댑터 | 구현하는 Port |
|---|---|
| `PlaylistSetupAdapter` | user → `PlaylistSetupPort` |
| `OAuth2RedirectAdapter` | user → `OAuth2RedirectPort` |
| `JwtWebSocketAuthAdapter` | realtime → `WebSocketAuthPort` |

---

## 의존 방향

```
app → common, user, avatar, playlist, realtime
```
