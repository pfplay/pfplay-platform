# 가상 DJ 봇 모델 개편 + 리소스 라이프사이클 + 좋아요/점검 게이트 — 설계

> 상태: brainstorming 완료, spec 리뷰 대기. 코드 미착수(사용자 게이트).
> 배포 게이트: **구현 + 로컬 풀스택 e2e 까지, dev 머지는 보류.**

## 1. 목적 / 배경

가상 DJ 봇 시스템을 다음으로 개편한다:

1. **명시적 2역할 모델** — 봇을 **크루 봇(= DJ 봇)** 과 **리스너 봇**으로 나누고, 어드민이 총원과 DJ 수를 명시 지정.
2. **고정 개수** — 봇 수를 사람 수에 적응시키지 않고(현 `DesiredBotCalculator` 제거) 어드민 지정값으로 고정.
3. **트랙 분배** — 송 팩 트랙을 DJ 봇 플레이리스트에 균등 분배(최소 1개, `DJ 수 ≤ 트랙 수`).
4. **호스트 존중** — 호스트가 DJ 대기열에서 봇을 제거하면 런타임 중 재등록하지 않음(지속 수렴 제거).
5. **AI 리소스 라이프사이클** — 점검 시작 시 드레인, 부팅 시 부활, 어드민 수동 드레인/부활(파티룸별).
6. **봇 좋아요 반응**(신규) + **점검 게이트**(스케줄러 구동 봇 자율행동을 점검 중 억제).

기존 시스템은 단일 `target_count` + `companion_floor`로 사람 수에 적응하는 수렴 모델이고, 투입된 봇이 전부 DJ이며, 송 팩 전체를 각 봇에 복사한다. 이 문서가 그 전제를 대체한다.

관련 파킹 문서: `docs/superpowers/specs/2026-07-02-virtual-dj-bot-reaction-liveliness-design.md`(좋아요 반응 원안 — 본 문서에 흡수).

## 2. 용어 (역할)

| 역할 | 진입 | DJ 회전 | 플레이리스트 | 카운트 소스 | 반응/채팅 |
|---|---|---|---|---|---|
| **크루 봇(=DJ 봇)** | `tryEnter` + `enqueueDj` | 참여 | 트랙 subset(분배) | 봇 DJ 수(`dj` 조인) | O |
| **리스너 봇** | `tryEnter` 만(grade LISTENER) | 미참여 | 없음 | 활성 봇 crew − 봇 DJ | O |

- 둘 다 활성 crew(`crew.is_active=true`)라 `findActivePersonaBotsInRoom`(crew 멤버십 + 페르소나 INNER JOIN)에 잡힌다 → 좋아요·채팅 자동 포함.
- **⚠️ 페르소나 필수**: `findActivePersonaBotsInRoom`은 `bot_persona_assignment` INNER JOIN이라, 페르소나 미배정 리스너 봇은 반응·채팅에서 **조용히 누락**된다. 풀 봇에 페르소나 배정을 보장한다(§13).

## 3. 설정 스키마

`partyroom_virtual_dj_config`:
- `target_count`(총 봇 수) **유지**.
- `dj_count`(크루 봇 수) **신규**(INT UNSIGNED NOT NULL).
- `companion_floor` **제거**.
- 리스너 봇 수 = `target_count − dj_count`(파생, 미저장).

봇별 slot(§6)은 별도 저장소가 필요하다:
- 봇의 DJ slot 인덱스를 **영속화**한다. 저장 위치는 봇 배치 소유 테이블(예: 봇 crew 배치 레코드 또는 신규 `virtual_dj_bot_slot(partyroom_id, bot_user_id, slot_index)`). 구현 시 기존 배치 추적 구조에 맞춰 결정하되, **(partyroomId, slotIndex) 유니크**를 보장한다.

### 3.1 Flyway 마이그레이션
- `dj_count` 컬럼 추가, 기존 행 `dj_count = target_count`(구 "전원 DJ" 동작 보존), `companion_floor` drop.
- slot 저장소 생성(위).
- **⚠️ 버전 슬롯 충돌 위험**: 명목상 다음 버전이나, 병렬 PR(#302/#313)이 V30 선점·V31~33 예약 이력이 있다. **머지 직전 `db/migration` 전체 uniq 스캔으로 빈 버전 확정**(reference: Flyway 슬롯 충돌 교훈).

## 4. 어드민 applyConfig

`PUT /api/v1/admin/partyrooms/{partyroomId}/virtual-dj` — 요청 payload: `status`(`MANAGED`/`OFF`, FROZEN 제거 §14) + `targetCount` + `djCount`(`companionFloor` 제거) + `songPackId`. `ApplyVirtualDjConfigRequest`·`BulkApplyVirtualDjConfigRequest`·`VirtualDjLiveStatusResponse`에서 companionFloor→djCount 교체.

**검증(위반 시 HTTP 400 `INVALID_CONFIG`)**:
- `djCount ≤ targetCount`
- `djCount ≤ (송 팩 트랙 중 룸 playbackTimeLimit 통과 개수)` — 필터 먼저 계산 후 비교.

검증 통과 시 config 저장(saveAndFlush) 후 `placeToTarget(room)` 1회 트리거(MANAGED), OFF면 기존 `drainRoom`(config OFF).

**400은 어드민 동기 HTTP 호출 전용.** 부팅/부활/점검 경로에는 HTTP가 없어 400이 존재하지 않으며, 그 경로의 트랙 부족은 §6 clamp로 best-effort 처리(예외·재시도 없음).

## 5. per-room 프리미티브 (단일 소스)

라이프사이클 트리거(§7)·수동 엔드포인트(§8)가 모두 아래 두 프리미티브를 파티룸별로 호출한다.

### 5.1 `placeToTarget(partyroomId)`
파티룸 분산락 안에서 멱등·1회성으로 목표 상태에 맞춘다:
- **크루 봇(DJ)**: 현재 봇 DJ 수 → `effectiveDjTarget` 수렴.
  - `effectiveDjTarget = min(djCount, filteredTrackCount)`.
  - 부족: idle 풀에서 claim → **빈 slot 결정적 배정(§6)** → 해당 slot의 트랙 조각을 플레이리스트에 적용 → `tryEnter` + `enqueueDj`.
  - 초과(어드민이 djCount 축소): 초과분 exit(가입 역순).
  - `effectiveDjTarget < djCount`면 `WARN` 로그(`DJ_COUNT_CLAMPED_BY_TRACKS: room, djCount, filtered`) — silent 아님.
- **리스너 봇**: 현재 활성 봇 crew 중 비-DJ 수 → `listenerCount = targetCount − djCount` 수렴.
  - 부족: idle 풀에서 claim → `tryEnter` 만(DJ·플레이리스트 없음).
  - 초과: 초과분 exit.
- idle 풀 부족 시 가능한 만큼만(기존 `INSUFFICIENT_IDLE_BOTS` 패턴 + WARN). **런타임 자동 재시도 없음**(§11) — 다음 부팅/수동 부활에서 채움.

### 5.2 `drainResources(partyroomId)`
파티룸의 **크루 봇 + 리스너 봇 전원 exit**, **config는 MANAGED 유지**(부활 가능).
- ⚠️ 기존 `drainRoom`/`doDrain`은 roster를 **DJ 스냅샷**에서 얻어 리스너 봇을 못 지운다. `drainResources`는 roster를 **활성 봇 crew 전체**(페르소나 무관 — 페르소나 없는 봇도 지워야 함, §13의 카운트 쿼리와 동일 소스)에서 얻어 봇마다 `botIdentity.runAs(bot, () -> accessCommandService.exit(room))`.
- slot 배정(§6)도 정리한다(해당 방 slot 레코드 제거).
- (FlapGuard는 §14로 전면 제거되므로 removal-intent 정리 단계 없음.)

## 6. 봇 slot 결정성 (트랙 분배)

DJ 봇마다 **slot 인덱스(0..djCount−1)를 영속화**한다.

- **트랙 조각화**: 필터 통과 트랙(룸 시간제한 이하)을 `djCount` 개의 연속 조각으로 분할(균등, 나머지는 앞 조각부터 +1, 각 조각 ≥1). slot k → chunk k.
- **배정**: `placeToTarget`가 DJ 봇을 추가할 때, **현재 미사용 slot 중 최소 인덱스**를 집어 그 봇에 영속 배정하고 chunk k를 플레이리스트로 적용.
- **slot 재확보(필수)**: slot 저장소는 **live 상태의 투영(projection)**이다. 호스트 kick·비-점검 재기동(크래시/배포) 등 §9로 런타임 정리가 없는 경로에서는 떠난 봇의 slot 행이 남는다. 따라서 `placeToTarget`는 **시작 시 slot 행을 live 기준으로 정리**한다: 현재 방의 **활성 DJ 봇이 아닌** 모든 slot 행 삭제(떠났거나 DJ에서 강등된 봇 포함). 그 후 `free slots = {0..djCount−1} − {live DJ 봇에 배정된 slot}`. 이렇게 하면 `(partyroomId, slotIndex)` UNIQUE 위반 없이 빈 slot 재사용이 결정적이다.
- **재-add 안전성**: 중간 봇(예: slot 1)이 제거되면 위 정리로 slot 1 행이 삭제되어 free가 되고, 다음 부팅/부활의 `placeToTarget`가 최소 free인 slot 1을 새 봇에 배정 → **중복·고아 없음**. 이미 재생 중인 봇의 플레이리스트는 재작성하지 않는다(그 봇의 slot은 live라 정리 대상 아님).
- **clamp와의 상호작용**: `effectiveDjTarget < djCount`면 상위 slot(≥ filteredTrackCount)은 채우지 않는다 → **빈 조각으로 enqueue 하는 일이 없다**(빈 플레이리스트 `enqueueDj` 예외 회피, `DjCommandService`의 빈 플레이리스트 검증).
- 트랙 분배 로직은 순수 함수(입력: 필터 트랙 리스트, djCount, slot)로 분리해 단위 테스트한다.

## 7. 라이프사이클 트리거

모두 대상 방 집합을 순회하며 §5 프리미티브를 호출한다.

| 트리거 | 대상 | 동작 |
|---|---|---|
| `applyConfig`(어드민) | 해당 방 | `placeToTarget` 1회 |
| **점검 시작** `MaintenanceStartedEvent` 리스너(AFTER_COMMIT) | 전 MANAGED 방 | `drainResources` |
| **점검 종료(완료)** `MaintenanceEndedEvent` 리스너(AFTER_COMMIT) | 전 MANAGED 방 | `placeToTarget`(`!isUnderMaintenance` 가드) — §7.2 |
| **점검 취소(ACTIVE였음)** `AnnouncementCancelledEvent` 리스너(AFTER_COMMIT) | 전 MANAGED 방 | `placeToTarget`(취소된 공지가 점검이고 `maintenanceStartedAt != null`일 때만) — §7.2 |
| **부팅** `ApplicationReadyEvent` | 전 MANAGED 방 | `placeToTarget`(점검 중이면 skip) — §7.1 중복 방지 |
| `PartyroomTerminatedEvent`(기존) | 해당 방 | config OFF(기존 `onTerminated` 유지) |

**근거(빈 방 전제)**: graceful 점검 재기동은 WS 단절 → 모든 human 크루가 서버사이드 exit + 봇은 점검 드레인으로 exit → 재기동 시점 파티룸은 사실상 빈 방. 따라서 부팅 부활이 호스트/유저와 싸울 상대가 없고 config대로 복원이 안전하다. **단 이 전제는 절대적이지 않다**: 크래시/배포(점검 없는 재기동)에서는 stale crew가 자동 정리되지 않아(reference: stale crew cleanup) 부팅 `placeToTarget`의 count 멱등성이 잔존 봇을 "존재"로 보고 no-op할 수 있다 → 그 경로는 §9 수용된 유실 + 수동 `/revive`에 의존한다.

### 7.1 중복 부활 방지 (필수)
부팅 부활이 **다중 인스턴스 또는 재트리거로 중복 투입되지 않도록** 보장한다:
- 방별 **분산락**(기존 orchestrator `DistributedLockExecutor` 재사용) 안에서 `placeToTarget` 실행 → 동시 실행 레이스로 인한 2×djCount 방지.
- `placeToTarget`의 **count 기반 멱등성**(현재 봇 수 == 목표면 no-op) → 잔존 봇 위 재투입 방지.
- 부팅 sweep 자체는 **클러스터 1회 가드**(ShedLock / Redis NX boot-epoch 키)로 한 인스턴스만 수행. (참고: 파킹된 vdj-reconcile 다중 인스턴스 설계와 동형.)

### 7.2 점검 종료 (설계 수정 — spec 리뷰 반영)
당초 "점검 종료 = 재부팅 이후"를 전제해 부팅 부활만으로 커버하려 했으나, 코드상 **재부팅 없는 점검 해제 경로가 두 가지** 존재한다:
- **완료**: `SystemAnnouncementCommandService.complete()` 및 `MaintenanceSchedulerService.completeExpiredMaintenance()` → **`MaintenanceEndedEvent`** 발행.
- **취소**: `SystemAnnouncementCommandService.cancel()` → **`AnnouncementCancelledEvent`** 발행(별개 이벤트!). `SystemAnnouncementData.isMaintenancePhaseActive()`는 `cancelledAt` 또는 `completedAt` 중 하나만 세팅돼도 종료로 간주하므로, ACTIVE 점검의 취소도 실제 "점검 종료" 경로다.

이 비대칭(시작 드레인 hook은 있고 종료 부활 hook은 없음)은 footgun(전 방 빈방 잔존)이므로 **두 이벤트 모두 hook**한다:
- `MaintenanceEndedEvent` → 전 MANAGED 방 `placeToTarget`(`!isUnderMaintenance` 가드).
- `AnnouncementCancelledEvent` → 취소된 공지가 점검(`maintenanceStartedAt != null`, 즉 ACTIVE였음)일 때만 → 전 MANAGED 방 `placeToTarget`(`!isUnderMaintenance` 가드). 취소 공지가 점검이 아니면 무시.
- **중복 방지**: 부팅 부활과 겹치거나 두 이벤트가 근접 발생해도, **방별 분산락 + `placeToTarget`의 count 멱등성**이 이중 투입을 막는다(이벤트 구동 부활은 단일 인스턴스 in-process라 boot-epoch 클러스터 가드와 무관 — §7.1의 per-room lock + count 멱등이 실질 직렬화). 
- 이로써 시작=드레인 / 종료(완료·취소)=부활 **대칭**이 되고, 재부팅 유무와 무관하게 빈방 잔존이 없다.

## 8. 수동 어드민 엔드포인트 (신규, 파티룸별)

- `POST /api/v1/admin/partyrooms/{partyroomId}/virtual-dj/drain-resources` → `drainResources`(MANAGED 유지, 부활 가능).
- `POST /api/v1/admin/partyrooms/{partyroomId}/virtual-dj/revive` → `placeToTarget`.
- 기존 `POST .../virtual-dj/drain`(config OFF, 영구 중단)·`applyConfig`는 **그대로**. 세 동작 의미 구분:

| 동작 | config | 봇 | 부팅 복원 |
|---|---|---|---|
| `/drain`(기존) | OFF 전환 | 전원 제거 | ❌ |
| `/drain-resources`(신규) | MANAGED 유지 | 전원 제거 | ✅ |
| `/revive`(신규) | MANAGED 유지 | 목표 재배치 | — |

`applyConfig`·`/revive`는 **점검 게이트 예외(어드민 오버라이드)** — 점검 중에도 어드민이 명시 호출하면 수행(문서화). 자동 스윕(§10)만 점검 중 skip.

## 9. 런타임 재등록 전면 제거 (호스트 존중)

- `VirtualDjReconcileScheduler` 60초 스윕에서 **`orchestrator.reconcileRoom` 호출 제거**(스윕은 §10 반응·self-update 만 유지).
- `VirtualDjEventListener.onCrewAccessed`·`onDjQueueChanged` **제거**(런타임 재등록/human-adaptive 소멸). `onTerminated`(config OFF) 유지.
  - 회귀: `VirtualDjEventListenerIT.crewAccessed_triggers_reconcile...` 삭제/개편. `DjQueueChanged` 전용 IT는 없음.
- `DesiredBotCalculator`(human-adaptive) **삭제** — 고정 djCount/listenerCount로 대체. `DesiredBotCalculatorTest` 삭제, `VirtualDjOrchestratorImpl` desired 계산 재작성.
- **수용된 결과**: 런타임 유실(idle풀 부족·transient 오류·호스트 kick)은 다음 부팅/부활(점검종료·수동) 전까지 복구되지 않음(자가수렴 제거의 논리적 귀결). 리스너 봇도 동일 정책.

### 9.1 "kick" 의미 정의
호스트가 봇을 **DJ 회전에서 제거**할 때 두 경우가 있다:
1. **방에서 완전 exit**(크루 나감) → 활성 crew 아님 → DJ·리스너 어느 카운트에도 안 잡힘. 다음 `placeToTarget`가 부족분을 채움(빈방/부팅 전제 하).
2. **DJ에서만 강등, 방에는 리스너로 잔류** → 활성 crew라 이제 **리스너로 카운트**됨(`findActivePersonaBotsInRoom` − DJ). 이 봇의 DJ slot은 §6 정리로 free 처리.
- 런타임에는 어느 경우도 재등록 트리거가 없다(§9). 다음 `placeToTarget`(부팅/점검종료/수동) 시 역할별 카운트를 **live 기준으로 재계산**해 수렴: 경우 2에서 DJ 부족→새 봇 claim(free slot), 리스너 초과→가입 역순 exit(봇은 상호 교체 가능한 신원이라 강등된 ex-DJ 봇이든 다른 리스너 봇이든 무방). orphan slot·중복 없음.

## 10. 60초 스윕 (유지 + 점검 게이트)

`VirtualDjReconcileScheduler.reconcileManagedRooms`(fixedDelay 60s):
- 최상단 **점검 게이트**: `MaintenanceGate.isUnderMaintenance()`면 전체 early return.
- self-update 루프 유지.
- **좋아요 반응 루프 추가**(§11).
- count 수렴 없음(§9).

## 11. 봇 좋아요 반응 (신규)

파킹된 원안(2026-07-02)을 본 모델에 얹는다.

- `BotReactionService.tryReact(partyroomId)`: 재생 중이면 확률 롤 → 히트 시 후보 중 1명 LIKE.
- **후보 = `findActivePersonaBotsInRoom(room)` − 현재 DJ crewId** = 리스너 봇 + 비-현재 DJ 크루 봇. (현재 재생 중인 봇만 제외, 대기 크루/리스너는 자기 자신 아닌 곡에 반응.)
- 적용: `PlaybackReactionSimulationService.apply(userId, crewId, playbackId, partyroomId, LIKE, 0)` — admin `ReactionSimulationService`에서 **party 모듈로 추출**(WS·ThreadLocal 무관, 명시적 신원). admin `simulateReactions`는 이 서비스에 위임(LIKE/GRAB 2그룹 async 보존).
- 현재 재생 상태 read: virtualdj는 party `~AggregatePort` 직접 의존 금지(`VirtualDjArchitectureTest`) → party 애플리케이션 서비스 `getCurrentPlaybackState(partyroomId): Optional<CurrentPlaybackView(playbackId, currentDjCrewId)>` 신설 경유.
- 설정: `ConfigKey`(record) 정적 필드 `vdj.reaction.enabled`(default false, fail-closed) / `vdj.reaction.probability_percent`. `SystemConfigCache.readBoolean/readInt(ConfigKey, default)`. `Randomizer`(virtualdj.application.port) 사용.
- LIKE 멱등 + `(user, playback)` 유니크 제약 → 스팸 불가. 틱당 방별 1롤(별도 쿨다운 불요).
- ⚠️ `SystemConfigCache.readInt`는 ≤0이면 fallback → 확률 0 불가, 완전 정지는 `enabled=false`로.

## 12. 채팅

작업 없음. 리스너 봇이 활성 crew라 기존 `BotChatTrigger`(`findActivePersonaBotsInRoom`)에 자동 포함. 단 §2의 페르소나 필수 조건 준수.

## 13. 아키텍처 / ArchUnit

- **admin 의존 금지 확대**: 현 규칙(`VirtualDjArchitectureTest`)은 `*Orchestrator*` 이름 클래스만 `..admin..` import를 금지 → 반응 서비스가 admin import해도 초록(허위 안전). 규칙을 **`..virtualdj..` 전체**로 확대하고, `PlaybackReactionSimulationService`는 **party**에 배치(admin `AdminPartyroomPort`/`SimulateReactionsResult` 의존 제거, party `PlaybackReactionHistoryRepository` 직접 사용).
- **점검 이벤트 리스너**: `MaintenanceStartedEvent`(administration 모듈) 구독은 ArchUnit 허용됨(orchestrator 이름 아니면 무제한). 단 (a) 리스너 클래스명에 `Orchestrator` 금지, (b) 리스너가 이벤트를 순수 `PartyroomId`로 변환해 orchestrator/프리미티브 호출(orchestrator 자체는 administration import 금지 유지). 선례: `VirtualDjEventListener`가 party 도메인 이벤트 구독.
- **리스너 봇 카운트는 페르소나 비게이트 쿼리로**: `findActivePersonaBotsInRoom.size()`는 페르소나 INNER JOIN이라 페르소나 없는 봇을 누락 → 인식 부족분 → 과투입(중복 add) 위험. 따라서 카운트 전용 **신규 쿼리**(`countActiveBotCrewsInRoom` — 활성 봇 crew 수, 페르소나 무관)를 두고, 리스너 수 = `countActiveBotCrews − 봇 DJ 수`(둘 다 페르소나 비게이트). 반응·채팅 후보 선택만 페르소나 게이트(`findActivePersonaBotsInRoom`) 유지.
- **페르소나 배정 보장 지점**: 리스너 봇이 반응·채팅에 참여하려면 페르소나가 있어야 한다. **풀 프로비저닝/봇 claim 시점에 페르소나 배정을 보장**한다(미배정 봇은 배치 대상에서 제외하거나 배치 시 자동 배정). 카운트는 페르소나와 무관(위)하므로 페르소나 누락이 카운트 왜곡→중복투입을 일으키지 않는다. 구현 시 기존 `BotPersonaAssignmentService`/provision 경로에 보장 로직 위치.

## 14. 제거/재정의 요약

- 제거: `companion_floor`(스키마), `DesiredBotCalculator`, `VirtualDjEventListener.onCrewAccessed/onDjQueueChanged`, 스윕의 `reconcileRoom` 호출.
- **`FlapGuard` 전면 제거(결정)**: dwell/debounce는 지속 수렴 전제의 anti-flap이라, 수렴 제거로 완전 미사용이 된다. `markAdded`/`shouldRemove`/`canRemoveBot`/`clearRemovalIntent` 및 호출부(`addBots`/`removeBots`/`onTerminated`/`drainRoom`) 전부 제거. `drainResources`·`onTerminated`는 FlapGuard 호출 없이 재작성.
- **FROZEN 제거(결정)**: 고정·무수렴 모델에서 "수렴 동결"은 의미 없고, FROZEN 방이 점검 드레인(MANAGED 순회)을 회피하는 역설이 있다. `VirtualDjStatus`에서 **FROZEN 제거**, config status는 `MANAGED`/`OFF` 2종. 라이프사이클 순회 대상은 MANAGED 단일. 제거 대상 전 지점:
  - `freeze` 엔드포인트(`POST .../virtual-dj/freeze`) + 컨트롤러 매핑, `VirtualDjAdminService.freeze`/`applyStatus`의 FROZEN 분기, `PartyroomVirtualDjConfigData.freeze()`
  - `VirtualDjOrchestratorImpl:97` 스킵 분기 주석/로직(`OFF / FROZEN / 미설정`) → `MANAGED 아니면 skip`으로 단순화
  - `PartyroomVirtualDjConfigData:23` `@Comment("OFF/MANAGED/FROZEN")` → `OFF/MANAGED`
  - `ApplyVirtualDjConfigRequest` javadoc의 FROZEN 언급
  - ⚠️ 마이그레이션: 기존 FROZEN 행이 있으면 MANAGED로 승격(또는 OFF) — 배포 시 데이터 확인. (prod 봇 dormant라 FROZEN 행 없을 가능성 높음, 확인.)
- `companionFloor` 참조 전 지점(엔티티·`VirtualDjAdminService`·payload 3종·컨트롤러·예외 텍스트·orchestrator·LiveStatus·테스트 8종) 동반 수정.

## 15. 수용된 결과 / 비목표

- 런타임 유실 영구화(§9) — 수용.
- 마이그레이션 무음 축소: 기존 행 `dj_count=target_count`가 `target_count > 필터트랙수`인 방에서 부팅 시 `effectiveDjTarget`로 조용히 축소 + 각 DJ 봇 플레이리스트가 전체 팩→조각으로 축소. prod 봇 dormant(OFF)라 저위험이나 명시. stg에 기존 MANAGED 방 있으면 재적용 권장.
- 비목표: 호스트 kick의 재부팅 지속 기억(런타임 한정 존중으로 충분, §7 근거). (점검 종료 부활은 §7.2로 채택됨 — 비목표 아님.)

## 16. 테스트 전략

- 단위: 트랙 분배 순수 함수(균등·최소1·나머지·clamp), `placeToTarget` 수렴(크루/리스너 각각·slot 배정·재-add 빈 slot), `drainResources`(리스너 포함 roster), 반응 서비스(추출 동작 보존)·`BotReactionService`(후보=−현재DJ), config 검증(400 케이스), 점검 게이트.
- 통합/IT: 부팅 부활 멱등·중복 방지, 점검 시작 드레인, 이벤트 리스너 제거 후 회귀, admin `simulateReactions` 위임 보존.
- **로컬 풀스택 e2e(dev 머지 전 필수)**: 점검 게이트 있는/없는 부팅 시나리오; stg에서 봇 활성화 후 크루/리스너 봇 존재 + 좋아요/채팅 + 호스트 kick 후 미재등록 확인. `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix.

## 17. 미해결/주의

- Flyway 빈 버전은 머지 직전 확정(§3.1).
- slot 저장소의 정확한 물리 위치(기존 배치 레코드 확장 vs 신규 테이블)는 구현 탐색 후 확정 — 인터페이스(§6 결정성)는 불변.
