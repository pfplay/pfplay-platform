# PFPlay Context Map

> **성격**: 현 코드 기준의 **경량 참조 문서**. 모듈·패키지·포트 목록은 `origin/develop` 실사 결과다.
> 설계 의도와 상세 시그니처는 `docs/superpowers/specs/` 의 해당 설계 문서를 우선 참조한다.
> 최종 실사: 2026-07-31.

## 1. Bounded Contexts

| # | Context | Type | 모듈 / 패키지 | 주요 Aggregate·개념 |
|---|---|---|---|---|
| 1 | **Party** | **Core** | `app` — `api.party.*`, `api.partyview.*` | `Partyroom`, `Crew`, `Dj`/`DjQueue`, `PartyroomPlayback`, `Playback`, `PlaybackAggregation`, `CrewPenaltyHistory` |
| 2 | **Virtual Crew** | Supporting (전략적) | `app` — `api.virtualcrew.*` | `VirtualPersona`, 봇 풀, `VirtualSongPack`/`VirtualSongPackTrack`, 룸 배치·채팅 설정 |
| 3 | **Administration** | Supporting | `app` — `api.administration.*` | `Administrator`, `AdminAction`, `PartyroomReport`, `BugReport`, `SystemAnnouncement`, `UserActivityLog` |
| 4 | **Notification** | Supporting | `app` — `api.notification.*` | `PushSubscription` (Web Push VAPID) |
| 5 | **Operations** | Supporting | `app` — `api.operations.*` | `SystemConfig` |
| 6 | **IAM / Auth** | Generic | `app` — `api.auth.*` + `common/.../config/security/*` + `user/.../identity/*` | `UserAccount`, 세션 토큰 |
| 7 | **User Profile** | Supporting | `user` 모듈 | `Member`, `Guest`, 프로필, 활동 점수 |
| 8 | **Playlist** | Supporting | `playlist` 모듈 | `Playlist`, `Track`, 음악 검색 |
| 9 | **Avatar** | Supporting (전략적) | `avatar` 모듈 | `AvatarBodyResource`, `AvatarFaceResource` |
| 10 | **Realtime** | — (BC 아님, 기술 모듈) | `realtime` 모듈 | 도메인 aggregate 없음 — STOMP 인프라 + 포트 |
| — | Shared Kernel | — | `common` 모듈 | VO · 예외 · 보안/인프라 config |
| — | Bootstrap | — | `app.bootstrap.*` | 컴포지션 루트 (BC 아님) |
| — | Admin (구 표면) | — | `app` — `api.admin.*` | 데모/시뮬레이션 잔존 컨트롤러 |

> `api.admin.*` 와 `api.administration.*` 가 공존한다. **신규 어드민 기능은 `administration`** 에
> 붙인다. `admin` 은 데모·시뮬레이션이 남은 구 표면이며 cross-BC 접근은 `AdminXxxPort` 로만 한다.

## 2. 통합 규칙

- **Cross-context FK 금지.** 무결성은 애플리케이션 레이어 + 도메인 이벤트로 보장한다.
- **식별자는 값으로만 공유** (`UserId`, `PartyroomId`, `CrewId`, `PlaylistId`, avatar URI …).
- **Avatar = 순수 생산자 BC.** 다른 BC를 import 하지 않는다. 역방향만 허용하며 Gradle 의존 방향이
  이를 컴파일 레벨에서 강제한다.
- **Administration → Party 내부 엔티티 import 금지.** 값 객체·포트만 허용(ArchUnit 검증).
- **cross-BC 조인 어댑터는 소비 측 `adapter/out/external` 에 둔다.** 예: presence liveness 스윕이
  crew × user 를 조인해야 하므로 구현체가 `party.adapter.out.external.LivenessSweepQueryAdapter`.

## 3. 포트 인벤토리 (실사)

### 3.1 Party 가 소비하는 포트

| 포트 | 대상 | 용도 |
|---|---|---|
| `PlaylistCommandPort` | Playlist | 트랙 소비(그랩·큐 진행) |
| `PlaylistQueryPort` | Playlist | DJ 트랙/플리 상태 조회 |
| `UserProfileQueryPort` | User Profile | 닉네임·아바타 등 프로필 조회 |
| `UserActivityPort` | User Profile | DJ 활동 점수 갱신 |
| `GuestAuthPort` | Auth | 게스트 신원 확보 |
| `PartyroomQueryPort` | (self) | 파티룸 DTO 조회 |
| `PlaybackControlPort` | (self) | reconcile → `skipPlayback` 재진입 |
| `ExpirationTaskPort` | Redis | 트랙 종료 타이머(키 만료) |
| `ChatPenaltyCachePort` | Redis | 채팅 제재 캐시 |
| `LivenessSweepQueryPort` | (external) | 유령 online 후보 조회 |
| `PartyroomAggregatePort` | (self, domain) | Partyroom aggregate 영속화 파사드 |

### 3.2 그 외 방향

| 포트 | Direction | 용도 |
|---|---|---|
| `AvatarCatalogQueryUseCase` | user / app → avatar | 아바타 카탈로그 조회 |
| `AvatarAdminCatalogQueryUseCase` | app → avatar | 어드민 카탈로그 조회 |
| `AvatarCatalogCommandUseCase` | app → avatar | 어드민 CRUD |
| `AvatarStoragePort` | avatar 내부 → out-adapter | GCS 업로드 추상화 |
| `OAuth2RedirectPort` | user → auth | OAuth2 URL 생성 |
| `PlaylistSetupPort` | user → playlist | 신규 회원 기본 플레이리스트 생성 |
| `PartyCleanupPort` | auth → party | 로그아웃 시 파티 정리 |
| `StateStorePort` | auth → Redis | OAuth state 저장/검증 |
| `EdgeConfigPort` | administration → Vercel Edge Config | 점검 게이트 키 제어 |
| `AdminMemberPort` · `AdminPartyroomPort` · `AdminPlaylistPort` · `AdminAvatarResourcePort` | app.admin → 각 BC | 구 어드민 표면의 cross-BC 접근 |
| `PersonaQueryPort` · `BotAvatarApplyPort` · `VirtualMemberProvisionPort` | virtualcrew → user/avatar | 봇 신원·아바타 프로비저닝 |
| `WebSocketAuthPort` | realtime ← app(bootstrap) | 핸드셰이크 JWT 인증 (`JwtWebSocketAuthAdapter`) |
| `SessionCachePort` · `SessionRegistryPort` · `PresencePort` | realtime ← party | 세션 캐시·레지스트리·presence 연동 |

## 4. 모듈 의존 방향

```
app  --> user --> avatar --> common
app  --> playlist --> common
app  --> avatar --> common
app  --> realtime --> common
user --> avatar --> common
```

- `app` 은 모든 모듈에 의존하는 컴포지션 루트다. 역방향은 없다.
- 모듈 간 호출은 Port/Adapter 로만. Gradle 의존 그래프가 위반을 컴파일 시점에 막는다.
- `realtime` 은 도메인을 import 하지 않는다(포트 인터페이스만 정의).

## 5. 도메인 이벤트

발행 경로는 `BaseEntity.registerEvent` → 커밋 → `@TransactionalEventListener(AFTER_COMMIT)` 다.
`DomainEventRedisRelay` 가 이를 받아 Redis 토픽으로 재발행하고, 각 인스턴스의 토픽 리스너가
STOMP 로 밀어낸다(수평 확장 안전).

| BC | 이벤트 |
|---|---|
| Party | `CrewAccessedEvent`(ENTER/EXIT) · `CrewGradeChangedEvent` · `CrewPenalizedEvent` · `AdminCrewPenalizedEvent` · `AdminCrewPenaltyReleasedEvent` · `DjQueueChangedEvent` · `PlaybackStartedEvent` · `PlaybackDeactivatedEvent` · `ReactionAggregationChangedEvent` · `ReactionMotionChangedEvent` · `PartyroomCreatedEvent` · `PartyroomClosedEvent` · `PartyroomTerminatedEvent` · `PartyroomSuspendedEvent` · `PartyroomRestoredEvent` · `PartyroomMetaUpdatedEvent` · `PartyroomDisplayFlagChangedEvent` |
| User Profile | `MemberRegisteredEvent` · `MemberTierChangedEvent` · `UserProfileChangedEvent` · `ProfileChangedEvent` · `UserAccountWithdrawnEvent` |
| Playlist | `TrackAddedEvent` · `TrackRemovedEvent` |
| Administration | `AnnouncementPublishedEvent` · `AnnouncementCancelledEvent` · `MaintenanceStartedEvent` · `MaintenanceEndedEvent` |
| Auth | `UserAccountSignedInEvent` |
| Virtual Crew | `VirtualCrewChatConfigChangedEvent` |

### 클라이언트로 나가는 WS 계약

브로드캐스트 계약은 `MessageTopic` enum(17종)이다. 목적지는 **파티룸당 하나**
(`/sub/partyrooms/{id}`)이고 메시지 종류는 payload 의 topic 으로 구분한다. 전체 목록·발행 규칙은
README 「WebSocket Contract」 참조.

> ⚠️ **`AFTER_COMMIT` 리스너에서 DB를 쓰려면 `REQUIRES_NEW` 가 필요하다.** 원 트랜잭션은 이미
> 커밋됐으므로 그 컨텍스트로는 아무것도 flush 되지 않는다.

## 6. Shared Kernel (`common`)

| 요소 | 설명 |
|---|---|
| `UserId`, `PartyroomId`, `CrewId`, `PlaylistId`, … | 전 BC 공유 식별자 VO |
| `Duration`, `DomainEvent`, `BaseEntity` | 도메인 기반 구조(이벤트 수집 포함) |
| `AuthContext` / `ThreadLocalContext` | 인증 컨텍스트 전파 |
| `MessageTopic` | WS 브로드캐스트 토픽 enum |
| `SecurityConfig`, `JwtService`, `RedisLockService`, `CorsProperties` | 보안·인프라 (IAM BC 일부가 여기 거주) |
| `GlobalExceptionHandler` + 에러 코드 enum | 공통 에러 처리 |

## 7. 데이터 흐름

```
[Client] --HTTP/WS--> [Controller]
                          |
                    [Application Service]
                          |
               [Port (Aggregate / External)]
                          |
                    [Adapter / Repository]
                          |
                [MySQL / Redis / GCS / pytube sidecar]
```

Cross-module:

```
[Party Service]  --PlaylistCommandPort------->  [PlaylistCommandAdapter]    --> [Playlist]
[User Service]   --AvatarCatalogQueryUseCase->  [AvatarCatalogQueryService] --> [Avatar]
[VirtualCrew]    --VirtualMemberProvisionPort-> [provision adapter]         --> [User/Avatar]
```

---

**관련 문서**

- 운영·스케줄러: [`OPERATIONS.md`](OPERATIONS.md)
- 알려진 함정: [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md)
- ADR: [`adr/`](adr/)
- 문서 색인: [`DOCS_ENTRY.md`](DOCS_ENTRY.md)
