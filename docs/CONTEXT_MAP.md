# PFPlay Context Map

> **Source of truth**: `docs/superpowers/specs/2026-04-19-admin-platform-design.md §3.1 (BC taxonomy), §3.2 (통합 규칙), §3.4 (Context Map)`.
> 본 문서는 현재 레포 실제 상태를 반영한 **경량 참조용**이다. Avatar BC 신설 (V14 적용 + PR 10 이후) 및 Administration / Operations BC 확장 내용이 반영되어 있다.
> 세부 aggregate/스키마/이벤트 시그니처는 상기 사양 문서를 우선 참조한다.

## Bounded Contexts (V14 반영, PR 10 머지 후 최종 상태 기준)

| # | Context | Type | 현 Gradle 모듈 / 패키지 | 주요 Aggregate |
|---|---|---|---|---|
| 1 | **IAM** | Generic | 현재 분산: `common/.../config/security/*` + `app/.../auth/*` + `user/.../api/user/identity/*` | `UserAccount` |
| 2 | **User Profile** | Supporting | `user` 모듈, `api.user.profile.*` 패키지 | `Member`, `Guest` |
| 3 | **Party** | **Core** | `app` 모듈, `api.party.*` + `api.partyview.*` | `Partyroom`, `Crew`, `Playback`, `DjQueue`, `CrewPenaltyHistory` |
| 4 | **Playlist** | Supporting | `playlist` 모듈 | `Playlist`, `Track` |
| 5 | **Avatar** 🆕 | Supporting (전략적) | **`avatar` 모듈** (PR 10) | `AvatarBodyResource`, `AvatarFaceResource` |
| 6 | **Realtime** | — (BC 아님; 런타임 분리 모듈, 향후 WebFlux) | `realtime` 모듈 | (도메인 aggregate 없음) |
| 7 | **Administration** | Supporting | `app` 모듈, `api.administration.*` (기존 `api.admin.*` 재편, PR 2 이후) | `Administrator`, `AdminAction`, `PartyroomReport`, `UserActivityLog` |
| 8 | **Operations** | Supporting | `app` 모듈, `api.operations.*` (PR 3 이후) | `SystemConfig` |
| — | Shared Kernel | — | `common` 모듈 | (값 객체 · 예외 · 보안 인프라) |
| — | Bootstrap | — | `app.bootstrap.*` | (컴포지션 루트, BC 아님) |

## 컨텍스트 간 통합 규칙 요약

- **Cross-context FK 금지** — 무결성은 애플리케이션 레이어 + 도메인 이벤트로 보장 (`docs/superpowers/specs/2026-04-19-admin-platform-integrity.md` §8)
- **식별자는 값으로만 공유** (`UserAccountId`, `PartyroomId`, avatar URI 등)
- **Avatar → 순수 생산자 BC**: 다른 BC를 import하지 않는다. 역방향(User Profile/Administration/app → Avatar)은 허용. Gradle 자체가 이 방향성을 강제.
- **Administration → Party 내부 엔티티 import 금지** — 값 객체만 허용 (ArchUnit 검증)

## 주요 Cross-Context 포트 (V14 이후)

| 포트 | Direction | 용도 |
|---|---|---|
| `AvatarCatalogQueryUseCase` 🆕 | user / app → avatar | 아바타 카탈로그 조회 (피커, 어드민) |
| `AvatarCatalogCommandUseCase` 🆕 | app → avatar | 어드민 CRUD (SUPER_ADMIN 전용, PR 11) |
| `AvatarStoragePort` 🆕 | avatar 내부 application → out-adapter | GCS 업로드 추상화 (PR 11) |
| `PlaylistCommandPort` | Party → Playlist | 트랙 그랩, 첫 트랙 조회 |
| `PlaylistQueryPort` | Party → Playlist | 플레이리스트 비었는지 확인 |
| `UserProfileQueryPort` | Party → User Profile | 닉/아바타 등 프로필 조회 |
| `UserActivityPort` | Party → User Profile | DJ 활동 점수 업데이트 |
| `OAuth2RedirectPort` | User → Auth (IAM) | OAuth2 프로바이더 URL 생성 |
| `PlaylistSetupPort` | User → Playlist | 신규 회원 기본 플레이리스트 생성 |
| `WebSocketAuthPort` | Realtime ← Common | JWT 기반 WebSocket 인증 |
| `SessionCachePort` | Realtime ← Party | 세션 라이프사이클 관리 |
| `AdminAvatarResourcePort` | app.admin → user/avatar (재편 대상) | 가상 유저 아바타 조립 (PR 10에서 avatar 모듈 포트 경유로 재배선) |
| `PartyCleanupPort` | Auth → Party | 로그아웃 시 파티 정리 |

**주의**: `AdminMember/AdminPartyroom/AdminPlaylistPort` 등 기존 admin 포트는 PR 2 이후 `api.administration.*` 패키지로 재편 예정.

## Module Dependency Direction (V14 이후)

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
- `AvatarResourcePublished` 🆕 (Avatar 발행) → Administration · User Profile
- `AvatarResourceRetired` 🆕 (Avatar 발행) → Administration

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
- 스키마 / V14: `docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.11`
- 구현 계획: `docs/superpowers/plans/2026-04-20-admin-platform-pr0-pr10-pr11.md`
- ADR: `docs/adr/` (001-005, 기존 결정들 여전히 유효)
