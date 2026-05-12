# PFPlay Context Map

> **Source of truth**: `docs/superpowers/specs/2026-04-19-admin-platform-design.md §3.1 (BC taxonomy), §3.2 (통합 규칙), §3.4 (Context Map)`.
> 본 문서는 현재 레포 실제 상태를 반영한 **경량 참조용**이다. Avatar BC 신설(V12) + Administration 재편 + V13 신고 + V14 시스템 공지 + V15 UNIQUE 제약 + V16 presence grace window 까지 반영되어 있다.
> 세부 aggregate/스키마/이벤트 시그니처는 상기 사양 문서와 `docs/superpowers/specs/`의 후속 spec(예: `2026-05-03-system-announcement-design.md`, `2026-05-09-presence-grace-window-design.md`)을 우선 참조한다.
> 운영 정책·함정은 [`docs/OPERATIONS.md`](OPERATIONS.md) 참조.

## Bounded Contexts (V16 반영, admin 콘솔 prod 진입 후 최종 상태 기준)

| # | Context | Type | 현 Gradle 모듈 / 패키지 | 주요 Aggregate |
|---|---|---|---|---|
| 1 | **IAM** | Generic | 현재 분산: `common/.../config/security/*` + `app/.../auth/*` + `user/.../api/user/identity/*` | `UserAccount` |
| 2 | **User Profile** | Supporting | `user` 모듈, `api.user.profile.*` 패키지 | `Member`, `Guest` |
| 3 | **Party** | **Core** | `app` 모듈, `api.party.*` + `api.partyview.*` | `Partyroom`, `Crew` (V16: `pending_exit_at` presence 컬럼), `Playback`, `DjQueue`, `CrewPenaltyHistory` |
| 4 | **Playlist** | Supporting | `playlist` 모듈 | `Playlist`, `Track` |
| 5 | **Avatar** | Supporting (전략적) | **`avatar` 모듈** (PR 10) | `AvatarBodyResource`, `AvatarFaceResource` |
| 6 | **Realtime** | — (BC 아님; 런타임 분리 모듈, 향후 WebFlux) | `realtime` 모듈 | (도메인 aggregate 없음). 포트: `WebSocketAuthPort`, `SessionCachePort`, `PresencePort` 🆕 |
| 7 | **Administration** | Supporting | `app` 모듈, `api.administration.*` | `Administrator`, `AdminAction`, `PartyroomReport` (V13), `SystemAnnouncement` 🆕 (V14), `UserActivityLog` |
| 8 | **Operations** | Supporting | `app` 모듈, `api.operations.*` | `SystemConfig` + `MaintenanceModeFilter` (V14 점검 모드 핸들오프), `SystemConfigCache` |
| — | Shared Kernel | — | `common` 모듈 | (값 객체 · 예외 · 보안 인프라) |
| — | Bootstrap | — | `app.bootstrap.*` | (컴포지션 루트, BC 아님) |

## 컨텍스트 간 통합 규칙 요약

- **Cross-context FK 금지** — 무결성은 애플리케이션 레이어 + 도메인 이벤트로 보장 (`docs/superpowers/specs/2026-04-19-admin-platform-integrity.md` §8)
  - V15에서 `user_profile.nickname`, `partyroom.link_domain`에 한해 BC 내부 UNIQUE 제약을 추가했음 (silent collision 차단). cross-BC FK 정책은 그대로 유지.
- **식별자는 값으로만 공유** (`UserAccountId`, `PartyroomId`, avatar URI 등)
- **Avatar → 순수 생산자 BC**: 다른 BC를 import하지 않는다. 역방향(User Profile/Administration/app → Avatar)은 허용. Gradle 자체가 이 방향성을 강제.
- **Administration → Party 내부 엔티티 import 금지** — 값 객체만 허용 (ArchUnit 검증)
- **Realtime 모듈은 도메인 import 0개** — 모든 호출은 포트 인터페이스(`PresencePort`, `WebSocketAuthPort`, `SessionCachePort`) 통과

## 주요 Cross-Context 포트 (V16 이후)

| 포트 | Direction | 용도 |
|---|---|---|
| `AvatarCatalogQueryUseCase` | user / app → avatar | 아바타 카탈로그 조회 (피커, 어드민) |
| `AvatarCatalogCommandUseCase` | app → avatar | 어드민 CRUD (SUPER_ADMIN 전용) |
| `AvatarStoragePort` | avatar 내부 application → out-adapter | GCS 업로드 추상화 |
| `PlaylistCommandPort` | Party → Playlist | 트랙 그랩, 첫 트랙 조회 |
| `PlaylistQueryPort` | Party → Playlist | 플레이리스트 비었는지 확인 |
| `UserProfileQueryPort` | Party → User Profile | 닉/아바타 등 프로필 조회 |
| `UserActivityPort` | Party → User Profile | DJ 활동 점수 업데이트 |
| `OAuth2RedirectPort` | User → Auth (IAM) | OAuth2 프로바이더 URL 생성 |
| `PlaylistSetupPort` | User → Playlist | 신규 회원 기본 플레이리스트 생성 |
| `WebSocketAuthPort` | Realtime ← Common | JWT 기반 WebSocket 인증 |
| `SessionCachePort` | Realtime ← Party | 세션 라이프사이클 관리 |
| `PresencePort` 🆕 | Party → Realtime | Presence grace window (PENDING_EXIT/OFFLINE 판정, Redis TTL 키 관리). V16. 어댑터: `PresencePortAdapter` |
| `EdgeConfigPort` 🆕 | Administration → external (Vercel) | V14 점검 모드 / 시스템 공지 토글을 Vercel Edge Config로 전파. 어댑터: `VercelEdgeConfigAdapter` |
| `AdminAvatarResourcePort` | app.administration → avatar | 가상 유저 아바타 조립 |
| `PartyCleanupPort` | Auth → Party | 로그아웃 시 파티 정리 |

## Module Dependency Direction (V12 이후)

```
app  --> user --> avatar --> common
app  --> playlist --> common
app  --> avatar --> common
app  --> realtime --> common (런타임 분리)
user --> avatar --> common
user --> common
playlist --> common
realtime --> common
```

- `app`은 모든 모듈에 의존
- `user`는 `avatar`에 의존 (유저 피커가 avatar 카탈로그 조회)
- `avatar`는 `common`에만 의존 (순수 생산자)
- 모듈 간 상호 호출은 Port/Adapter 통해서만
- Gradle이 이 방향성을 컴파일 레벨에서 강제

## 도메인 이벤트 (핵심만)

상세 목록은 `docs/superpowers/specs/2026-04-19-admin-platform-integrity.md §8.2.1` 참조.

- `UserAccountWithdrawn` (IAM 발행) → User Profile · Administration 리스너
- `PartyroomSuspendedByAdmin` / `Terminated` / `MetaUpdatedByAdmin` (Party 발행) → Administration
- `MemberTierChanged` (User Profile 발행) → Administration
- `AdminPenalizedCrew` (Administration 또는 Party 발행) → 양쪽 기록
- `CrewAccessedEvent (ENTER/EXIT)` (Party) → Administration · Party
- `AvatarResourcePublished` (Avatar 발행) → Administration · User Profile
- `AvatarResourceRetired` (Avatar 발행) → Administration
- `AnnouncementPublishedEvent` 🆕 (Administration 발행, V14) → Operations · Realtime broadcast
- `AnnouncementCancelledEvent` 🆕 (Administration 발행, V14) → Operations · Realtime broadcast
- `MaintenanceStartedEvent` 🆕 (Administration 발행, V14) → Operations (점검 모드 진입 신호)

## Shared Kernel (common 모듈)

| 요소 | 설명 |
|---|---|
| `UserId`, `PartyroomId`, `PlaylistId`, `CrewId`, …Id VO들 | 전 BC 공유 식별자 |
| `Duration`, `DomainEvent`, `BaseEntity` | 도메인 기반 구조 |
| `AuthContext` / `ThreadLocalContext` | 인증 컨텍스트 전파 |
| `SecurityConfig`, `JwtService`, `CorsProperties`, ... | 보안 인프라 (현재 IAM BC의 일부가 여기 거주) |
| 글로벌 예외 (`GlobalExceptionHandler`) + 에러 코드 enum | 공통 에러 처리 |

## 데이터 흐름 (일반)

```
[Client] --HTTP/WS--> [Controller]
                          |
                    [Application Service]
                          |
               [Port (Aggregate / External)]
                          |
                    [Adapter / Repository]
                          |
                [Database (MySQL) / Redis / GCS]
```

**Cross-module**:
```
[Party Service]  --PlaylistCommandPort-->  [PlaylistCommandAdapter]  -->  [Playlist Service]
[User Service]   --AvatarCatalogQueryUseCase-->  [AvatarCatalogQueryService]  (avatar 모듈, PR 10 이후)
```

---

**관련 문서**:
- 설계 원본: `docs/superpowers/specs/2026-04-19-admin-platform-design.md`
- 스키마 / V12: `docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.11`
- V14 시스템 공지 디자인: `docs/superpowers/specs/2026-05-03-system-announcement-design.md`
- V16 presence grace window: `docs/superpowers/specs/2026-05-09-presence-grace-window-design.md`
- 구현 계획: `docs/superpowers/plans/2026-04-20-admin-platform-pr0-pr10-pr11.md`
- 운영 정책·함정: [`docs/OPERATIONS.md`](OPERATIONS.md)
- ADR: `docs/adr/` 001~011 (001 unified entity model, 002 aggregate-repo facade, 003 id reference migration, 004 hybrid domain event, 005 cross-domain port adapter, 006 admin CSRF token, 007 V14 system announcement, 008 super-admin seed lifecycle, 009 JVM TZ KST, 010 V16 presence grace window, 011 admin origin guard)
