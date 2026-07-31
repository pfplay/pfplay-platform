# 리팩토링 로드맵

> **이 문서의 구조 (2026-07-31 개편)**
> - **[1부 — 현행 로드맵](#1부--현행-로드맵-2026-07-31-기준)**: 지금 열려 있는 작업.
> - **[2부 — 과거 기록](#2부--과거-기록-ddd-성숙도-phase-af-2026-02완료)**: 2026-02 DDD 성숙도
>   Phase A~F 완주 기록. **현재 진행 중인 작업이 아니다.** 보존 목적으로만 남긴다.

---

# 1부 — 현행 로드맵 (2026-07-31 기준)

## 1.1 이벤트 기반 신뢰성 로드맵 (주축)

원문: [`docs/superpowers/specs/2026-07-22-event-driven-reliability-roadmap.md`](superpowers/specs/2026-07-22-event-driven-reliability-roadmap.md)

핵심 명제는 하나다.

> **이벤트는 최적화이고, 정확성은 reconcile 이 담당한다.**
> edge-triggered 로 상태를 유지하면 이벤트가 유실되는 순간 상태가 영구히 어긋나며, 이벤트는
> 반드시 유실된다.

2026-07 의 사건 4건(crew 다중활성 wedge #349, 신호 유실 유령 crew #356, `crew_count` 드리프트
#358/#360, RaceIT CI 플레이크)이 전부 이 한 구조에서 나왔다는 관찰이 출발점이다.

### 이미 반영된 것

| 구현 | 원칙 |
|---|---|
| playback/dj reconcile 크론 (#308, PR #309) | level-triggered reconcile — [ADR 008](adr/008-self-healing-reconcile-cron.md) |
| presence liveness 스윕 (#356, PR #357) | 신호 유실 유령의 level-triggered 치유 — [ADR 009](adr/009-presence-liveness-sweep.md) |
| 캐시 카운터 제거 → 라이브 COUNT (#358/#360, V39) | 파생 상태는 매번 진실에서 재계산 |
| `uk_crew_active_user` DB 유니크 (V38) | 애플리케이션 밖(스토리지)의 최종 방어선 |
| CI 이중 트리거 제거 (#364, PR #365) | 플레이크 노출면 절반 |

### Phase 1 잔여 (테스트 신뢰성)

- [ ] **드리프트-0 지표** — 파생 상태 불일치를 수치로 보이게 한다.
- [ ] **플레이크 대장 + 기한부 격리** — 재시도로 통과한 테스트를 기록하고, 반복 플레이크는
      기한 있는 `@Tag("quarantine")` 로 분리(기한 없는 격리는 은폐다).
- [ ] **비결정 단언 감사** — IT/e2e 의 타이밍·파생 상태 단언을 라이브 진실 단언으로 전환.
- [ ] **Carry 자정 플레이크 근본 수정** — `Clock` 주입으로 가상 시간화.

### Phase 2 이후

이벤트 인벤토리·2분류, 아웃박스 도입까지가 원문의 범위다. 상세는 원문 §4 이후 참조.

## 1.2 파킹된 설계 (Approved · 코드 미착수)

착수 시 재설계 없이 바로 구현 가능한 상태로 승인된 설계들이다.

> ⚠️ **아래 설계 문서들은 아직 이 레포에 커밋돼 있지 않다**(작업 세션 산출물로만 존재).
> 착수하는 PR 이 해당 스펙을 `docs/superpowers/specs/` 로 함께 들여오는 것을 원칙으로 한다.

| 주제 | 요지 |
|---|---|
| 도메인 이벤트 아웃박스 | AFTER_COMMIT + sweeper, at-least-once. 신뢰성 로드맵 Phase 3 과 같은 목표 |
| DJ 회전 슬롯 FSM | 파생 `SlotState` 도입, 마이그레이션 0 |
| 재생 세션 명시 FSM | `isActivated` 불리언 → state enum |
| 재생 동기화 정합성 프로토콜 | `elapsedMs` + `serverTime` 기반 |
| vdj-reconcile 다중 인스턴스 | FlapGuardStore / ShedLock 두 안 |
| 소셜 파운데이션(팔로우·public_id·알림센터) | 착수 시 `public_id` 마이그레이션이 분리된 첫 PR |

## 1.3 코드 정리 백로그 (이슈 있음)

| 항목 | 이슈 | 요약 |
|---|---|---|
| enum 영속화 4원화 해소 | [#333](https://github.com/pfplay/pfplay-platform/issues/333) | ORDINAL 암묵 사용 제거 → `VARCHAR` + `@JdbcTypeCode` 통일. 각 단계 독립 PR |
| Presence ↔ AccessCommand 순환 | [#228](https://github.com/pfplay/pfplay-platform/issues/228) | 이벤트 기반 `forceOffline` 으로 생성자 순환 해소 |
| 탈퇴 닉네임 익명화 | [#339](https://github.com/pfplay/pfplay-platform/issues/339) | UNIQUE 제약상 익명화 외 해법 없음 |
| 토큰 만료 graceful 재인증 | [#306](https://github.com/pfplay/pfplay-platform/issues/306) | refresh/슬라이딩 갱신 + WS inbound 만료 검증 |
| 배포 readiness 판정 | [#342](https://github.com/pfplay/pfplay-platform/issues/342) | 타임아웃 = 실패로 처리, 헬스 경로 교체 |

### 구조 정리 (이슈 없음, 관측 사항)

- `api.admin.*` 와 `api.administration.*` 의 공존. 신규는 `administration` 으로 가고 있으나
  구 표면(데모·시뮬레이션)이 남아 있다. 이관 시점 미정.

---

# 2부 — 과거 기록: DDD 성숙도 Phase A~F (2026-02 완료)

> **아래는 완료 기록이다.** 2026-02-22 시점에 40.0/40 을 달성하며 종료됐고, 그 이후의 작업은
> 1부를 본다. 점수·표는 당시 스냅샷이며 현재 코드 상태의 평가가 아니다.
> 이전 리팩토링(Phase 0~8, 모듈 재구조화, ERD 정규화)은 `docs/archive/` 에 있다.

**현재 종합 점수**: 40.0/40 (100%)
**목표 종합 점수**: 40.0/40 (100%) ✅ 달성
**평가 기준 문서**: `docs/MATURITY_ASSESSMENT.md`

---

## 로드맵 개요

```
Phase A — 즉시 해소 (Quick Wins)                     ✅ 완료
Phase B — application→adapter.in 경계 정리            ✅ 완료
Phase C — 도메인 레이어 순수화 (user 모듈)             ✅ 완료
Phase D — VO 일관성 및 Aggregate 강화                  ✅ 완료
Phase E — 테스트 전략 강화                             ✅ 완료
Phase F — 모듈 경계 및 admin 도메인 정리               ✅ 완료
```

### 성숙도 변화 실적

| Phase | 완료 전 | 완료 후 | 주요 효과 |
|-------|---------|---------|----------|
| A | 31.0 | 31.5 | ArchUnit 정확성 회복, cross-module 위반 제거 |
| B | 31.5 | 33.5 | 헥사고널 준수 3.5→4.0, ArchUnit TODO 22건→0건 |
| C | 33.5 | 35.0 | 도메인 순수성 4.0→5.0, 모델 풍부성 4.0→4.5 |
| D | 35.0 | 35.5 | 이벤트 VO化 완료, WalletDomainService 제거 |
| D (D-1/D-2) | 35.5 | 36.0 | FK VO化 57파일, 포트 파라미터 VO化 |
| E | 36.0 | 37.0 | Testcontainers 3클래스, @WebMvcTest 4클래스, 334 테스트 |
| F | 37.0 | 37.5 | admin 포트 3쌍, JwtWebSocketAuthAdapter 이동, partyview ArchUnit |
| #4+#6 | 37.5 | 38.0 | user/playlist 이벤트 4건, ReactionPostProcessResult 불변화 |
| #11 | 38.0 | 38.5 | AdminDemoService→AdminPartyroomPort 전환, 헥사고널 준수 4.5→5.0 |
| @AggregateRoot | 38.5 | 39.0 | `@AggregateRoot` 마커 7개 엔티티 적용, ArchUnit 검증. Aggregate/VO 4.5→5.0 |
| 이벤트 payload 보강 | 39.0 | 39.5 | 7개 이벤트 payload 보강, DjChangeType/ProfileChangeType 도입. 도메인 이벤트 4.5→5.0 |
| #10 admin 테스트 | 39.5 | **40.0** | admin 서비스 단위 테스트 3클래스 17건, 테스트 전략 4.5→5.0 |

> 최종 점수 40.0/40 (100%) 달성. 2026-02-22 admin 서비스 테스트로 차원 6 4.5→5.0 완료.

---

## ✅ Phase A — 즉시 해소 (Quick Wins) — 완료

| 항목 | 상태 | 커밋 |
|------|------|------|
| A-1. UserArchitectureTest domain→adapter 예외 제거 | ✅ | `0f98b85` |
| A-2. PlaybackData.create() → PlaybackTrackDto 의존 제거 | ✅ | `0f98b85` |
| A-3. ReactionPostProcessResult → domain/model/ 이동 | ✅ | `0f98b85` |

---

## ✅ Phase B — application→adapter.in 경계 정리 — 완료

| 항목 | 상태 | 커밋 |
|------|------|------|
| B-1. party 도메인 Command/Result DTO 도입 (8 서비스) | ✅ | `2566af7` |
| B-2. auth 도메인 Command/Result DTO 도입 (2 서비스) | ✅ | `2566af7` |
| B-3. admin 도메인 Command/Result DTO 도입 (3 서비스) | ✅ | `2566af7` |
| B-4. user 모듈 Command/Result DTO 도입 (4 서비스) | ✅ | `2566af7` |
| B-5. playlist 모듈 Command DTO 도입 (2 서비스) | ✅ | `2566af7` |
| B-6. ArchUnit 예외 전부 제거 (22건→0건) | ✅ | `2566af7` |

**결과**: 77파일 변경, application→adapter.in 의존 **0건**

---

## ✅ Phase C — 도메인 레이어 순수화 (user 모듈) — 완료

| 항목 | 상태 | 커밋 |
|------|------|------|
| C-1. UserAvatarDomainService → AvatarResourceService 의존 제거 | ✅ | `881e84b` |
| C-2. MemberData → application DTO 의존 제거 (4건) | ✅ | `881e84b` |
| C-3. UserAccountData → ProfileSummaryDto → domain VO 전환 | ✅ | `881e84b` |
| C-4. UserArchitectureTest domain→application 예외 제거 | ✅ | `881e84b` |

**결과**: domain→application **0건** (전 모듈). `ProfileSummary`, `ActivitySummary` domain VO 신규.

---

## ✅ Phase D — VO 일관성 및 Aggregate 강화 — 완료

| 항목 | 상태 | 커밋 | 비고 |
|------|------|------|------|
| D-1. FK 컬럼 VO化 (4 엔티티 @EmbeddedId/@Embedded) | ✅ | `e9e57f9` | 57파일, DjQueue/PlaybackData→PartyroomId, PlaybackAggregation→PlaybackId, TrackData→PlaylistId |
| D-2. PartyroomAggregatePort/PlaylistAggregatePort 파라미터 VO化 | ✅ | `e9e57f9` | D-1과 함께 완료, PlaylistId→common 이동 포함 |
| D-3. 도메인 이벤트 ID VO化 (3건) | ✅ | `59b86f7` | |
| D-4. WalletDomainService 정리 | ✅ | `59b86f7` | 데드 코드 제거 |

---

## ✅ Phase E — 테스트 전략 강화 — 완료

| 항목 | 상태 | 커밋 | 비고 |
|------|------|------|------|
| E-1. PartyroomInfoController Repository 직접 접근 제거 | ✅ | `f9a3bf1` | |
| E-2. 미테스트 application service 단위 테스트 (4건) | ✅ | `f9a3bf1` | |
| E-3. Testcontainers 통합 테스트 인프라 | ✅ | — | MySQL+Redis TestContainerConfig 3클래스, 통합 테스트 6건 |
| E-4. 컨트롤러 슬라이스 테스트 | ✅ | `7f50244` | @WebMvcTest 4클래스 (playlist 2, user 2), 12 테스트 케이스 |

---

## ✅ Phase F — 모듈 경계 정리 — 완료

| 항목 | 상태 | 커밋 | 비고 |
|------|------|------|------|
| F-1. admin 도메인 포트 도입 | ✅ | — | AdminMemberPort, AdminAvatarResourcePort, AdminPlaylistPort + 3 Adapter |
| F-2. LogoutService → party 접근 포트화 | ✅ | `fd0570c` | PartyCleanupPort + Adapter |
| F-3. JwtWebSocketAuthAdapter 이동 | ✅ | — | common→app(bootstrap) 이동, common→realtime 의존 제거 |
| F-4. PartyroomAccessController → GuestSignService 포트화 | ✅ | `fd0570c` | GuestAuthPort + Adapter |
| F-5. partyview ArchUnit 검증 범위 포함 | ✅ | `7f50244` | HexagonalArchitectureTest에 partyviewClasses 규칙 추가 |

---

## 잔존 과제 요약 (40.0 도달을 위한 작업)

### 높은 우선순위 (성숙도 영향 +1.0)

| # | 항목 | 차원 | 예상 파일 수 | 비고 |
|---|------|------|-------------|------|
| ~~1~~ | ~~admin 도메인 포트 도입 (F-1)~~ | ~~2, 8~~ | ~~~15~~ | ✅ 완료 — 3 Port + 3 Adapter, cross-module 직접 참조 9건→0건 |
| ~~2~~ | ~~Testcontainers 통합 테스트 (E-3)~~ | ~~6~~ | ~~10 (신규)~~ | ✅ 완료 — 통합 테스트 0건 → 6건 (MySQL+Redis Testcontainers) |
| ~~3~~ | ~~FK 컬럼 VO化 (D-1, D-2)~~ | ~~4~~ | ~~57~~ | ✅ 완료 — 4 엔티티 @EmbeddedId/@Embedded, PlaylistId→common 이동 |

### 중간 우선순위 (성숙도 영향 +0.5)

| # | 항목 | 차원 | 예상 파일 수 | 비고 |
|---|------|------|-------------|------|
| ~~4~~ | ~~user/playlist 도메인 이벤트 도입~~ | ~~5~~ | ~~~10~~ | ✅ 완료 — UserProfileChangedEvent, TrackAddedEvent, TrackRemovedEvent 등 |
| ~~5~~ | ~~JwtWebSocketAuthAdapter 이동 (F-3)~~ | ~~8~~ | ~~~3~~ | ✅ 완료 — common→app(bootstrap) 이동, common→realtime 의존 제거 |
| ~~6~~ | ~~ReactionPostProcessResult 불변화~~ | ~~3, 4~~ | ~~~5~~ | ✅ 완료 — record 변환, @Setter 제거 |
| ~~7~~ | ~~컨트롤러 슬라이스 테스트 (E-4)~~ | ~~6~~ | ~~8 (신규)~~ | ✅ 완료 — @WebMvcTest 4건, 12 테스트 케이스 |

### 낮은 우선순위 (정리 수준)

| # | 항목 | 차원 | 비고 |
|---|------|------|------|
| ~~8~~ | ~~PartyroomSetupQueryService Repo 직접 접근 제거~~ | ~~2~~ | ✅ 이미 해소 — Repository 직접 import 0건 확인 |
| ~~9~~ | ~~partyview ArchUnit 검증 범위 추가 (F-5)~~ | ~~2, 6~~ | ✅ 완료 — HexagonalArchitectureTest에 partyviewClasses 포함 |
| ~~11~~ | ~~AdminDemoService 인트라모듈 Repository 접근 제거~~ | ~~2~~ | ✅ 완료 — AdminPartyroomPort + Adapter, party repo 5개→0개 |
| ~~10~~ | ~~admin 서비스 단위 테스트~~ | ~~6~~ | ✅ 완료 — AdminProfileServiceTest, AdminUserServiceTest, AdminPartyroomServiceTest (17 테스트) |

---

## 참고 문서

| 문서 | 위치 | 내용 |
|------|------|------|
| 성숙도 평가 기준 | `docs/MATURITY_ASSESSMENT.md` | 8개 차원 채점 기준, 현재/목표 점수 |
| Context Map | `docs/CONTEXT_MAP.md` | Bounded Context 관계 및 포트 매핑 |
| ADR | `docs/adr/` | 아키텍처 의사결정 기록 5건 |
| DTO Record 정책 | `docs/archive/DTO_RECORD_POLICY.md` | record vs class 사용 지침 |
| 이전 리팩토링 계획 | `docs/archive/REFACTORING_PLAN.md` | Phase 0~8 완료 이력 |
| 이전 모듈 재구조화 | `docs/archive/MODULE_RESTRUCTURING_PLAN.md` | Step A~E 완료 이력 |
| 이전 ERD 변경 | `docs/archive/ERD_DATA_LAYER_CHANGELOG.md` | ERD-1~6 완료 이력 |

---

*본 문서는 각 Phase 완료 시마다 업데이트하고, 성숙도 재평가 결과를 `MATURITY_ASSESSMENT.md`에 반영한다.*
