# 가상 DJ 봇 모델 개편 구현 계획

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 가상 DJ 봇을 크루 봇(=DJ)/리스너 봇 2역할·고정 개수 모델로 개편하고, 트랙 slot 분배·AI 리소스 라이프사이클(점검 드레인/부팅·점검종료 부활)·봇 좋아요 반응·점검 게이트를 구현한다.

**Architecture:** 배치는 어드민 `applyConfig`·부팅·점검종료가 공유하는 per-room 프리미티브 `placeToTarget`/`drainResources`로 수렴(1회성·멱등). 런타임 지속 재등록(스윕 reconcile·이벤트 리스너·`DesiredBotCalculator`)은 제거해 호스트 kick을 존중. 좋아요는 60초 스윕에 확률 LIKE로 얹고, 반응 적용은 admin `ReactionSimulationService`를 party로 추출해 재사용한다.

**Tech Stack:** Java 21, Spring Boot, JPA/QueryDSL, Flyway, JUnit5 + Mockito + AssertJ, Gradle. 빌드/테스트는 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix 필수.

**Spec:** `docs/superpowers/specs/2026-07-08-virtual-dj-bot-model-overhaul-design.md`
**재사용 플랜(좋아요):** `docs/superpowers/plans/2026-07-02-virtual-dj-bot-reaction-liveliness.md`

---

## 전역 파일 맵

신규:
- `virtualdj/domain/service/TrackDistribution.java` — 순수 분배 함수
- `virtualdj/domain/entity/data/PartyroomBotSlotData.java` — 봇 slot 엔티티
- `virtualdj/adapter/out/persistence/PartyroomBotSlotRepository.java` (+impl 필요 시)
- `virtualdj/application/service/BotPlacementService.java` — `placeToTarget`/`drainResources` (orchestrator에서 분리)
- `virtualdj/adapter/in/event/VirtualDjMaintenanceListener.java` — 점검 드레인/부활
- `virtualdj/application/service/VirtualDjBootReviver.java` — 부팅 부활(ApplicationReady)
- `party/application/service/PlaybackReactionSimulationService.java` — 반응 프리미티브(추출)
- `party/application/dto/CurrentPlaybackView.java`
- `virtualdj/application/service/VirtualDjReactionConfig.java`, `BotReactionService.java`
- Flyway `Vxx__virtual_dj_bot_model_overhaul.sql`

수정:
- `virtualdj/domain/enums/VirtualDjStatus.java`(FROZEN 제거)
- `virtualdj/domain/entity/data/PartyroomVirtualDjConfigData.java`(companionFloor→djCount, freeze 제거)
- payload 3종 + `VirtualDjAdminService`(applyConfig 검증·drainResources/revive·LiveStatus) + `AdminVirtualDjController`(신규 2엔드포인트, freeze 제거)
- `VirtualDjOrchestratorImpl`(placeToTarget/drainResources로 재편, DesiredBotCalculator 제거, FlapGuard 제거)
- `SongPackApplier`(slot chunk 적용)
- `VirtualDjReconcileScheduler`(reconcile 제거, 반응 루프+점검 게이트)
- `VirtualDjEventListener`(onCrewAccessed/onDjQueueChanged 제거)
- `operations/domain/value/ConfigKey.java`, `party/.../PartyroomQueryService.java`, `admin/.../AdminPartyroomService.java`
- `BotPoolQueryRepository`(+impl) — `countActiveBotCrewsInRoom`, `findActiveBotCrewUserIdsByJoinedDesc`
- ArchUnit `VirtualDjArchitectureTest`

삭제:
- `virtualdj/domain/service/DesiredBotCalculator.java`(+Test)
- `virtualdj/application/service/FlapGuard.java`(+사용처)
- `admin/.../ReactionSimulationService.java`(+Test) — party 이관

---

## Chunk 1: 스키마 · 상태 · 설정 API

### Task 1.1: `VirtualDjStatus`에서 FROZEN 제거

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/virtualdj/domain/enums/VirtualDjStatus.java`
- Modify: `app/.../virtualdj/application/service/VirtualDjAdminService.java`(`applyStatus` FROZEN 분기·`freeze` 메서드 제거)
- Modify: `app/.../virtualdj/adapter/in/web/AdminVirtualDjController.java`(`freeze` 핸들러 제거)
- Modify: `app/.../virtualdj/application/service/VirtualDjOrchestratorImpl.java`(doReconcile 스킵 분기 주석 `OFF/FROZEN/미설정`→`MANAGED 아니면 skip`)
- Modify: `app/.../virtualdj/domain/entity/data/PartyroomVirtualDjConfigData.java`(`@Comment("OFF/MANAGED/FROZEN")`→`"OFF/MANAGED"`, `freeze()` 제거)
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/VirtualDjPersistenceIT.java`(`freeze()` 단언 제거), `VirtualDjAdminServiceIT.java`(`freeze_setsFrozen` 삭제), `AdminVirtualDjControllerTest.java`(`freeze_admin_returns204` **freeze 케이스만** 삭제. ⚠️ `drain_missingCsrf_returns403`은 **유지**할 `/drain` 엔드포인트의 CSRF 테스트라 freeze와 무관 — 삭제 금지)

- [ ] **Step 1: 회귀 테스트 수정** — 위 3개 테스트에서 FROZEN/freeze 참조 케이스 제거(컴파일 통과 목적). `VirtualDjPersistenceIT.config_기본값과_전이`의 `cfg.freeze(); assertThat(...FROZEN)` 2줄 삭제.
- [ ] **Step 2: 실패 확인** — Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew compileJava compileTestJava` → FROZEN 참조로 FAIL(아직 enum에 있음/제거 전).
- [ ] **Step 3: 구현** — enum에서 `FROZEN` 삭제. `VirtualDjStatus { OFF, MANAGED }`. `applyStatus` switch에서 `case FROZEN` 제거(2종만). `VirtualDjAdminService.freeze()` + 컨트롤러 `freeze` 핸들러 제거. 엔티티 `freeze()` 제거, `@Comment` 수정. orchestrator 주석 정리.
- [ ] **Step 4: 통과 확인** — Run: `... ./gradlew compileJava compileTestJava` → PASS. `grep -rn "FROZEN\|\.freeze(" app/src` → 0건(마이그레이션 SQL 제외).
- [ ] **Step 5: 커밋**
```bash
git add -A && git commit -m "refactor(vdj): VirtualDjStatus FROZEN 제거(고정·무수렴 모델)"
```

### Task 1.2: `PartyroomVirtualDjConfigData` companionFloor→djCount

**Files:**
- Modify: `app/.../virtualdj/domain/entity/data/PartyroomVirtualDjConfigData.java`
- Test: `app/.../virtualdj/VirtualDjPersistenceIT.java`

- [ ] **Step 1: 실패 테스트** — `VirtualDjPersistenceIT`의 config 테스트를 djCount로 갱신:
```java
PartyroomVirtualDjConfigData cfg = PartyroomVirtualDjConfigData.create(1L);
assertThat(cfg.getStatus()).isEqualTo(VirtualDjStatus.OFF);
assertThat(cfg.getTargetCount()).isEqualTo(2);
assertThat(cfg.getDjCount()).isEqualTo(1);          // 신규 기본값
cfg.applyManaged(3, 2, 10L);                         // targetCount=3, djCount=2
assertThat(cfg.getDjCount()).isEqualTo(2);
```
- [ ] **Step 2: 실패 확인** — Run: `... ./gradlew test --tests "*VirtualDjPersistenceIT"` → FAIL(getDjCount 없음).
- [ ] **Step 3: 구현** — 필드 `companionFloor`→`djCount`(`@Column(name="dj_count", nullable=false, columnDefinition="int unsigned")`). 빌더/생성자/`create()`(기본 djCount=1)/`applyManaged(int targetCount, int djCount, Long songPackId)` 시그니처 교체. `@Getter`로 `getDjCount()`.
- [ ] **Step 4: 통과 확인** — Run: 위 → PASS.
- [ ] **Step 5: 커밋** — `refactor(vdj): config companionFloor→djCount`

### Task 1.3: Flyway 마이그레이션 (스키마 전환 + slot 테이블)

**Files:**
- Create: `app/src/main/resources/db/migration/Vxx__virtual_dj_bot_model_overhaul.sql`

> ⚠️ **버전 번호는 머지 직전 확정.** 현재 트리 최신 V29이나 병렬 PR(#302/#313 V30 선점, V31~33 예약) 이력 → `db/migration` 디렉토리 `uniq -d` 스캔 후 빈 번호 사용(reference: Flyway 슬롯 충돌). 아래는 V34 가정.

- [ ] **Step 1: 마이그레이션 작성**
```sql
-- V34: 가상 DJ 봇 모델 개편 — dj_count 추가, companion_floor 제거, FROZEN 제거, 봇 slot 테이블
ALTER TABLE partyroom_virtual_dj_config
    ADD COLUMN dj_count INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '크루(DJ) 봇 수' AFTER target_count;
-- 기존 행 back-compat: 구 동작=전원 DJ → dj_count = target_count
UPDATE partyroom_virtual_dj_config SET dj_count = target_count;
ALTER TABLE partyroom_virtual_dj_config DROP COLUMN companion_floor;
-- FROZEN 행 승격(있으면) 후 enum 축소
UPDATE partyroom_virtual_dj_config SET status = 'MANAGED' WHERE status = 'FROZEN';
ALTER TABLE partyroom_virtual_dj_config
    MODIFY COLUMN status ENUM('OFF','MANAGED') NOT NULL DEFAULT 'OFF' COMMENT 'OFF/MANAGED';

CREATE TABLE virtual_dj_bot_slot (
    partyroom_id BIGINT UNSIGNED NOT NULL,
    bot_user_id  BIGINT UNSIGNED NOT NULL,
    slot_index   INT UNSIGNED    NOT NULL,
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (partyroom_id, bot_user_id),
    UNIQUE KEY uk_room_slot (partyroom_id, slot_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```
- [ ] **Step 2: 부팅 검증** — 로컬 docker compose fresh DB 풀부팅으로 마이그레이션·Hibernate validate 통과 확인(reference: 로컬 부팅 게이트). Run: 로컬 스택 기동 후 로그에 Flyway 성공 + 스키마 validate 통과.
- [ ] **Step 3: 커밋** — `feat(vdj): dj_count·bot_slot 마이그레이션 + companion_floor/FROZEN 제거`

### Task 1.4: 설정 API — payload·검증(400)·LiveStatus

**Files:**
- Modify: `app/.../virtualdj/adapter/in/web/payload/ApplyVirtualDjConfigRequest.java`, `BulkApplyVirtualDjConfigRequest.java`, `VirtualDjLiveStatusResponse.java`
- Modify: `app/.../virtualdj/application/service/VirtualDjAdminService.java`(`applyConfig`/`applyStatus`/`applyBulk`/`LiveStatus` 시그니처 companionFloor→djCount, 검증 추가)
- Modify: `app/.../virtualdj/adapter/in/web/AdminVirtualDjController.java`(호출부 인자)
- New exception: `VirtualDjException.DJ_COUNT_EXCEEDS_TRACKS`("VDJ-014", ErrorType.BAD_REQUEST)
- **필터 트랙 카운트 헬퍼(신규 의존성)**: `applyStatus`는 현재 트랙 소스도, 룸 timeLimit 소스도 주입돼 있지 않다. 추가:
  - 룸 시간제한: `PartyroomQueryService.getPartyroomById(partyroomId).getPlaybackTimeLimit().getMinutes()`(orchestrator와 동일 경로) — `VirtualDjAdminService`에 `PartyroomQueryService` 주입.
  - 필터 카운트: `SongPackApplier`(또는 `VirtualSongPackTrackRepository`)에서 "룸 timeLimit 통과 트랙 수" 헬퍼 `countPlayableTracks(songPackId, timeLimitMinutes): int` 추출(기존 `timeLimit.exceedsDuration` 필터 재사용). 이 헬퍼 추출을 별도 sub-step으로 먼저 작성.
- Test: `AdminVirtualDjControllerTest.java`, `VirtualDjAdminServiceIT.java`

- [ ] **Step 1: 실패 테스트** — 컨트롤러 테스트 payload를 `companionFloor`→`djCount`로 교체하고 검증 케이스 추가:
```java
// applyConfig payload
.content("""{"status":"MANAGED","targetCount":3,"djCount":2,"songPackId":5}""")
verify(adminService).applyConfig(new PartyroomId(7L), VirtualDjStatus.MANAGED, 3, 2, 5L);
```
서비스 IT에 400 케이스: `djCount(3) > targetCount(2)` → INVALID_CONFIG; `djCount(5) > 필터통과트랙(2)` → DJ_COUNT_EXCEEDS_TRACKS.
- [ ] **Step 2: 실패 확인** — Run: `... ./gradlew test --tests "*AdminVirtualDjControllerTest" --tests "*VirtualDjAdminServiceIT"` → FAIL.
- [ ] **Step 3: 구현** —
  - payload 3종 `companionFloor`→`djCount`(`@Min(1)`), `LiveStatus`/`VirtualDjLiveStatusResponse` 동일.
  - `applyStatus`의 MANAGED 분기 검증: `targetCount==null||djCount==null`→INVALID_CONFIG; `djCount>targetCount`→INVALID_CONFIG; songPackId 있으면 `djCount > filteredTrackCount(songPackId, roomTimeLimit)`→DJ_COUNT_EXCEEDS_TRACKS. `cfg.applyManaged(targetCount, djCount, songPackId)`.
  - `applyBulk`/컨트롤러 인자 교체.
  - `liveStatus`: `new LiveStatus(status, targetCount, djCount, songPackId, currentBotDjCount)`.
- [ ] **Step 4: 통과 확인** — Run: 위 → PASS. `grep -rn "companionFloor\|companion_floor" app/src` → 0건.
- [ ] **Step 5: 커밋** — `feat(vdj): applyConfig djCount 수신·검증(djCount≤target, ≤필터트랙수 400)`

---

## Chunk 2: 트랙 slot 분배

### Task 2.1: `TrackDistribution` 순수 분배 함수

**Files:**
- Create: `app/.../virtualdj/domain/service/TrackDistribution.java`
- Test: `app/.../virtualdj/domain/service/TrackDistributionTest.java`

- [ ] **Step 1: 실패 테스트** — 균등·최소1·나머지 앞조각·slot 인덱싱:
```java
class TrackDistributionTest {
    @Test @DisplayName("10트랙 3조각 → [4,3,3] 연속 분할")
    void split_10_into_3() {
        List<Integer> tracks = IntStream.rangeClosed(1,10).boxed().toList();
        assertThat(TrackDistribution.chunkFor(tracks, 3, 0)).containsExactly(1,2,3,4);
        assertThat(TrackDistribution.chunkFor(tracks, 3, 1)).containsExactly(5,6,7);
        assertThat(TrackDistribution.chunkFor(tracks, 3, 2)).containsExactly(8,9,10);
    }
    @Test @DisplayName("effectiveDjTarget = min(djCount, trackCount)")
    void effective_clamped() {
        assertThat(TrackDistribution.effectiveDjTarget(5, 2)).isEqualTo(2);
        assertThat(TrackDistribution.effectiveDjTarget(3, 10)).isEqualTo(3);
    }
}
```
- [ ] **Step 2: 실패 확인** — Run: `... ./gradlew test --tests "*TrackDistributionTest"` → FAIL.
- [ ] **Step 3: 구현** — 제네릭 순수 함수:
```java
public final class TrackDistribution {
    private TrackDistribution() {}
    /** trackCount를 djCount 조각으로. 각 조각 크기 = base 또는 base+1(앞 remainder개). */
    public static int effectiveDjTarget(int djCount, int filteredTrackCount) {
        return Math.min(djCount, filteredTrackCount);
    }
    public static <T> List<T> chunkFor(List<T> tracks, int djCount, int slotIndex) {
        int n = tracks.size(), base = n / djCount, rem = n % djCount;
        int start = slotIndex * base + Math.min(slotIndex, rem);
        int size = base + (slotIndex < rem ? 1 : 0);
        return List.copyOf(tracks.subList(start, start + size));
    }
}
```
> slot은 `[0, effectiveDjTarget)` 범위에서만 사용된다(호출측 보장). 그 범위 밖 slot은 빈 조각이 되므로 배정하지 않는다.
- [ ] **Step 4: 통과 확인** — Run: 위 → PASS.
- [ ] **Step 5: 커밋** — `feat(vdj): 트랙 slot 분배 순수 함수 TrackDistribution`

### Task 2.2: 봇 slot 엔티티·리포지토리 + slot 배정

**Files:**
- Create: `app/.../virtualdj/domain/entity/data/PartyroomBotSlotData.java`
- Create: `app/.../virtualdj/adapter/out/persistence/PartyroomBotSlotRepository.java`
- Create: `app/.../virtualdj/application/service/BotSlotAssigner.java`
- Test: `app/.../virtualdj/application/service/BotSlotAssignerTest.java`(단위, repo mock)

- [ ] **Step 1: 실패 테스트** — non-live 정리 후 최소 free slot 배정:
```java
@ExtendWith(MockitoExtension.class)
class BotSlotAssignerTest {
    @Mock PartyroomBotSlotRepository slots;
    @InjectMocks BotSlotAssigner assigner;
    PartyroomId room = new PartyroomId(1L);

    @Test @DisplayName("live DJ 봇 slot 정리 후, 최소 free slot 반환")
    void assigns_lowest_free_after_pruning() {
        // 저장된 slot: bot10=slot0, bot11=slot1 (bot11은 이제 non-live)
        when(slots.findByPartyroomId(1L)).thenReturn(List.of(
            slot(10L,0), slot(11L,1)));
        List<Long> liveDjBots = List.of(10L);           // bot11 떠남
        int assigned = assigner.reclaimAndAssign(room, liveDjBots, 12L, /*djCount*/3);
        verify(slots).deleteByPartyroomIdAndBotUserId(1L, 11L);   // non-live 정리
        assertThat(assigned).isEqualTo(1);                        // slot0 점유(live), 최소 free=1
    }
}
```
- [ ] **Step 2: 실패 확인** — Run: `... ./gradlew test --tests "*BotSlotAssignerTest"` → FAIL.
- [ ] **Step 3: 구현** — 엔티티(PK `(partyroomId, botUserId)`, `slotIndex`), 리포(`findByPartyroomId`, `deleteByPartyroomIdAndBotUserId`, `save`, `deleteByPartyroomId`), `BotSlotAssigner.reclaimAndAssign(room, liveDjBotUserIds, newBotUserId, djCount)`:
  1. 방 slot 전부 조회, `botUserId ∉ liveDjBotUserIds`인 행 삭제(§6 정리).
  2. 점유 slot = live 봇의 slotIndex 집합.
  3. `[0, djCount)`에서 최소 미점유 인덱스 선택, `newBotUserId`에 upsert 저장, 반환.
- [ ] **Step 4: 통과 확인** — Run: 위 → PASS.
- [ ] **Step 5: 커밋** — `feat(vdj): 봇 slot 엔티티·리포 + 재확보 배정 BotSlotAssigner`

### Task 2.3: `SongPackApplier` slot chunk 적용

**Files:**
- Modify: `app/.../virtualdj/application/service/SongPackApplier.java`
- Test: `app/.../virtualdj/application/service/SongPackApplierTest.java`(신규 단위 or 기존 IT 확장)

- [ ] **Step 1: 실패 테스트** — 필터 통과 트랙 중 slot 조각만 봇 플레이리스트에 복사:
```java
// songPack 6트랙 전부 시간제한 통과, djCount=3, slot=1 → 트랙 [3,4]만 복사(2개)
```
- [ ] **Step 2: 실패 확인** — Run: `... ./gradlew test --tests "*SongPackApplierTest"` → FAIL.
- [ ] **Step 3: 구현** — 신규 메서드 `applyChunkToBot(UserId botUserId, Long songPackId, int roomTimeLimitMinutes, int slotIndex, int djCount)`: 기존 `applyToBot`의 (1)봇 playlist 조회 (2)기존 트랙 삭제 (3)송팩 트랙 정렬 후 **시간제한 필터** → 여기서 `TrackDistribution.chunkFor(filtered, djCount, slotIndex)`로 조각만 골라 orderNumber 재부여 저장 (4)`bindSongPackSource`. 기존 `applyToBot`은 admin/기타 호출부 없으면 제거, 있으면 유지.
- [ ] **Step 4: 통과 확인** — Run: 위 → PASS.
- [ ] **Step 5: 커밋** — `feat(vdj): SongPackApplier slot chunk 적용(필터 후 분배)`

---

## Chunk 3: 카운팅 · placeToTarget · drainResources

### Task 3.1: 활성 봇 crew 조회/카운트 쿼리

**Files:**
- Modify: `app/.../virtualdj/adapter/out/persistence/BotPoolQueryRepository.java`(+impl)
- Test: 기존 `BotPoolQueryRepositoryImplIT`(or 신규 IT)

- [ ] **Step 1: 실패 테스트(IT)** — 방에 DJ 봇 2 + 리스너 봇 1 세팅 → `countActiveBotCrewsInRoom=3`, `findActiveBotCrewUserIdsByJoinedDesc.size()=3`(페르소나 무관).
- [ ] **Step 2: 실패 확인** — Run: `... ./gradlew test --tests "*BotPool*IT"` → FAIL.
- [ ] **Step 3: 구현** — QueryDSL(페르소나 조인 없음): `crewData` where `partyroomId`, `crewData.isActive`, `userAccountData.isDummy`, `withdrawnAt null`. `countActiveBotCrewsInRoom(PartyroomId): long`, `findActiveBotCrewUserIdsByJoinedDesc(PartyroomId): List<UserId>`(enteredAt desc).
- [ ] **Step 4: 통과 확인** — Run: 위 → PASS.
- [ ] **Step 5: 커밋** — `feat(vdj): 활성 봇 crew 카운트/조회 쿼리(페르소나 비게이트)`

### Task 3.2: `BotPlacementService.placeToTarget` + `DesiredBotCalculator` 삭제

> orchestrator의 doReconcile 수렴 로직을 신규 `BotPlacementService`로 분리해 고정 2역할 모델로 재작성. 분산락은 호출측(orchestrator/트리거)이 감싼다.

**Files:**
- Create: `app/.../virtualdj/application/service/BotPlacementService.java`
- Delete: `app/.../virtualdj/domain/service/DesiredBotCalculator.java` + `DesiredBotCalculatorTest.java`
- Modify: `app/.../virtualdj/application/service/VirtualDjOrchestratorImpl.java`(doReconcile→placeToTarget 위임 or 이관; addBots/removeBots를 placement로 이동)
- Test: `app/.../virtualdj/application/service/BotPlacementServiceTest.java`

- [ ] **Step 1: 실패 테스트** — 고정 수렴 3케이스:
```java
// (a) DJ 부족: djBots=1, djCount=3, 필터트랙=10, idle 충분 → 2봇 추가(slot 배정+chunk+enqueueDj)
// (b) 트랙 clamp: djCount=5, 필터트랙=2 → effective=2, DJ 2만 유지(WARN)
// (c) 리스너 부족: listenerCount=target-dj, 봇crew-봇DJ 기준 부족분 tryEnter만
```
사람 수 무관 검증(human 5명 있어도 djCount 그대로). `DesiredBotCalculator` 미사용.
- [ ] **Step 2: 실패 확인** — Run: `... ./gradlew test --tests "*BotPlacementServiceTest"` → FAIL.
- [ ] **Step 3: 구현** — `placeToTarget(PartyroomId)`:
  1. config 로드, MANAGED 아니거나 songPackId null이면 return.
  2. 필터 트랙 수 계산 → `effectiveDjTarget = TrackDistribution.effectiveDjTarget(djCount, filtered)`; `< djCount`면 WARN(`DJ_COUNT_CLAMPED_BY_TRACKS`).
  3. 현재 봇 DJ(=ActiveDjSnapshot.botUserIdsByJoinedDesc) vs effectiveDjTarget: 부족→`addDjBots`(idle claim → `BotSlotAssigner.reclaimAndAssign` → `SongPackApplier.applyChunkToBot` → `botIdentity.runAs`{tryEnter+enqueueDj}), 초과→exit(가입 역순).
  4. 현재 리스너(=`countActiveBotCrews − botDJ`) vs `targetCount − djCount`: 부족→idle claim → `botIdentity.runAs`{tryEnter만}, 초과→exit.
  5. idle 풀 부족은 가능한 만큼(WARN `INSUFFICIENT_IDLE_BOTS`), 재시도 없음.
  `DesiredBotCalculator` 삭제, orchestrator는 placeToTarget에 위임(또는 로직 이관). **신규 `BotPlacementService`는 처음부터 FlapGuard를 참조하지 않는다**(구 `markAdded`/dwell 드롭) — 3.3의 FlapGuard 전면 제거와 충돌하는 전이 의존 없음.
- [ ] **Step 4: 통과 확인** — Run: 위 → PASS. `grep -rn "DesiredBotCalculator" app/src` → 0건.
- [ ] **Step 5: 커밋** — `feat(vdj): placeToTarget 고정 2역할 수렴 + DesiredBotCalculator 제거`

### Task 3.3: `drainResources` + FlapGuard 제거

**Files:**
- Modify: `app/.../virtualdj/application/service/BotPlacementService.java`(`drainResources`)
- Delete: `app/.../virtualdj/application/service/FlapGuard.java`(+Store/Test) 및 orchestrator/이벤트리스너 호출부 제거
- Modify: `VirtualDjOrchestratorImpl`(doDrain을 drainResources 재사용 or 병존; FlapGuard 참조 제거)
- Test: `BotPlacementServiceTest`(drainResources 케이스)

- [ ] **Step 1: 실패 테스트** — `drainResources`가 **활성 봇 crew 전원**(DJ+리스너, 페르소나 무관) exit + slot 정리, config는 MANAGED 유지:
```java
// 봇 DJ 2 + 리스너 1 → exit 3회(botIdentity.runAs+exit), slots.deleteByPartyroomId 호출, config status 불변(MANAGED)
```
- [ ] **Step 2: 실패 확인** — Run: `... ./gradlew test --tests "*BotPlacementServiceTest"` → FAIL.
- [ ] **Step 3: 구현** — `drainResources(PartyroomId)`: roster=`findActiveBotCrewUserIdsByJoinedDesc`(§3.1), 봇마다 `botIdentity.runAs(bot, () -> accessCommandService.exit(room))`, `slots.deleteByPartyroomId(roomId)`. config 미변경. FlapGuard 클래스·`markAdded`/`shouldRemove`/`clearRemovalIntent` 및 전 호출부 제거(addBots/removeBots/onTerminated/doDrain).
- [ ] **Step 4: 통과 확인** — Run: 위 → PASS. `grep -rn "FlapGuard" app/src` → 0건.
- [ ] **Step 5: 커밋** — `feat(vdj): drainResources(MANAGED 유지) + FlapGuard 제거`

---

## Chunk 4: 라이프사이클 트리거 · 런타임 재등록 제거

### Task 4.1: applyConfig→placeToTarget, 스윕 reconcile 제거

**Files:**
- Modify: `VirtualDjAdminService.applyConfig`(MANAGED→`placeToTarget`, OFF→기존 drain)
- Modify: `virtualdj/application/service/VirtualDjReconcileScheduler.java`(managed 순회에서 `orchestrator.reconcileRoom` 호출 제거; self-update 루프는 유지)
- Test: `VirtualDjAdminServiceIT`, 기존 스케줄러 테스트

- [ ] **Step 1: 실패 테스트** — applyConfig MANAGED가 placeToTarget 경유로 봇을 채우는지(IT). 스케줄러가 더 이상 reconcile로 봇을 추가/제거하지 않는지(있으면 갱신).
- [ ] **Step 2: 실패 확인** — Run 관련 테스트 → FAIL.
- [ ] **Step 3: 구현** — applyConfig의 `orchestrator.reconcileRoom`→`placeToTarget`(분산락 래핑 유지). 스케줄러 `reconcileManagedRooms`의 reconcile for-loop 제거(self-update 루프 유지, 반응 루프는 Chunk 6).
- [ ] **Step 4: 통과 확인** — Run → PASS.
- [ ] **Step 5: 커밋** — `feat(vdj): 배치=placeToTarget, 스윕 count 재등록 제거`

### Task 4.2: 이벤트 재등록 리스너 제거

**Files:**
- Modify: `virtualdj/adapter/in/event/VirtualDjEventListener.java`(`onCrewAccessed`·`onDjQueueChanged` 제거, `onTerminated` 유지)
- Test: `VirtualDjEventListenerIT.java`(crewAccessed→reconcile 케이스 삭제, terminated→OFF 유지)

- [ ] **Step 1: 회귀 테스트 수정** — `crewAccessed_triggers_reconcile...` 삭제. `terminated_turns_config_off...` 유지.
- [ ] **Step 2: 실패 확인** — Run: `... ./gradlew test --tests "*VirtualDjEventListenerIT"` → 컴파일/실패 확인.
- [ ] **Step 3: 구현** — 두 핸들러 메서드 + 미사용 import 제거.
- [ ] **Step 4: 통과 확인** — Run → PASS.
- [ ] **Step 5: 커밋** — `refactor(vdj): 런타임 재등록 이벤트 리스너 제거(호스트 kick 존중)`

### Task 4.3: 점검 드레인/부활 리스너

**Files:**
- Create: `virtualdj/adapter/in/event/VirtualDjMaintenanceListener.java`
- Inject: `MaintenanceGate`, `PartyroomVirtualDjConfigRepository`, `BotPlacementService`, `DistributedLockExecutor`
- Test: `VirtualDjMaintenanceListenerTest.java`

- [ ] **Step 1: 실패 테스트** —
```java
// MaintenanceStartedEvent(maintenance announcement) → 전 MANAGED 방 drainResources
// MaintenanceEndedEvent + AnnouncementCancelledEvent(maintenanceStartedAt!=null) → !isUnderMaintenance면 전 MANAGED 방 placeToTarget
// AnnouncementCancelledEvent(비-점검, maintenanceStartedAt==null) → 무시
```
- [ ] **Step 2: 실패 확인** — Run: `... ./gradlew test --tests "*VirtualDjMaintenanceListenerTest"` → FAIL.
- [ ] **Step 3: 구현** — `AnnouncementBroadcaster` 패턴 복제(`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`):
```java
@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
public void onMaintenanceStarted(MaintenanceStartedEvent e) {
    forEachManagedRoom(room -> lockedDrain(room));   // drainResources
}
@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
public void onMaintenanceEnded(MaintenanceEndedEvent e) { reviveIfNotUnderMaintenance(); }
@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
public void onAnnouncementCancelled(AnnouncementCancelledEvent e) {
    if (e.entity().getType() == AnnouncementType.MAINTENANCE_NOTICE
            && e.entity().getMaintenanceStartedAt() != null) reviveIfNotUnderMaintenance();
}
// reviveIfNotUnderMaintenance: if (!maintenanceGate.isUnderMaintenance()) 전 MANAGED 방 락+placeToTarget
```
클래스명에 `Orchestrator` 금지(ArchUnit). 이벤트→`PartyroomId`만 추출해 프리미티브 호출.
- [ ] **Step 4: 통과 확인** — Run → PASS.
- [ ] **Step 5: 커밋** — `feat(vdj): 점검 시작 드레인 / 종료·취소 부활 리스너`

### Task 4.4: 부팅 부활 (중복 방지)

**Files:**
- Create: `virtualdj/application/service/VirtualDjBootReviver.java`
- Test: `VirtualDjBootReviverTest.java`

- [ ] **Step 1: 실패 테스트** —
```java
// ApplicationReadyEvent 수신 → 점검 아님이면 전 MANAGED 방 placeToTarget(방별 락)
// 점검 중이면 skip
// 클러스터 1회 가드(ShedLock/Redis NX)로 단일 인스턴스만 수행 — 가드 mock으로 검증
```
- [ ] **Step 2: 실패 확인** — Run: `... ./gradlew test --tests "*VirtualDjBootReviverTest"` → FAIL.
- [ ] **Step 3: 구현** — `@EventListener(ApplicationReadyEvent.class)` 메서드: `if (maintenanceGate.isUnderMaintenance()) return;` 전 MANAGED 방 `lock.performTaskWithLock("virtualdj:"+id, placeToTarget)`.
  - **정합성은 방별 `DistributedLockExecutor`(존재함) + placeToTarget count 멱등성**으로 이미 보장(동시 인스턴스가 같은 방을 이중 투입 못 함).
  - **클러스터 1회 가드는 discover-or-defer**: 파킹된 vdj-reconcile 다중 인스턴스(ShedLock/Redis NX) 인프라는 **아직 트리 미구현**. `RedisLockService`가 있으면 `vdj:boot:<instanceEpoch>` NX로 "전 방 순회를 인스턴스 1대만" 최적화(중복 순회 방지, 정합성 아님). 없거나 불확실하면 **생략** — 방별 락+멱등으로 충분, 여러 인스턴스가 각자 순회해도 결과는 동일(중복 투입 없음).
- [ ] **Step 3b: 확인** — 여러 인스턴스가 동시에 부팅 부활을 돌려도 방별 락+count 멱등으로 봇 수가 정확히 목표에 수렴함을 테스트(2회 placeToTarget 호출 시 2번째 no-op).
- [ ] **Step 4: 통과 확인** — Run → PASS.
- [ ] **Step 5: 커밋** — `feat(vdj): 부팅 부활(ApplicationReady) + 중복 방지 가드`

---

## Chunk 5: 수동 어드민 엔드포인트

### Task 5.1: `/drain-resources` (MANAGED 유지)

**Files:**
- Modify: `VirtualDjAdminService`(`drainResources(PartyroomId)` → 락+`BotPlacementService.drainResources`)
- Modify: `AdminVirtualDjController`(`POST /partyrooms/{id}/virtual-dj/drain-resources`)
- Test: `AdminVirtualDjControllerTest`, `VirtualDjAdminServiceIT`

- [ ] **Step 1: 실패 테스트** — 컨트롤러 204 + `verify(adminService).drainResources(new PartyroomId(7L))`; IT: 봇 제거되고 config는 **MANAGED 유지**(기존 `/drain`은 OFF).
- [ ] **Step 2: 실패 확인** — Run 관련 → FAIL.
- [ ] **Step 3: 구현** — 서비스 `drainResources`(config 미변경, 프리미티브 위임) + 컨트롤러 핸들러(`@PreAuthorize("@adminAuth.canManageVirtualDj()")`).
- [ ] **Step 4: 통과 확인** — Run → PASS.
- [ ] **Step 5: 커밋** — `feat(vdj): 수동 /drain-resources(MANAGED 유지)`

### Task 5.2: `/revive`

**Files:** 위와 동형, `revive(PartyroomId)` → 락+`placeToTarget`; `POST .../virtual-dj/revive`.

- [ ] **Step 1~5** — 실패테스트(204 + verify revive; IT: MANAGED 방 목표 재배치) → 구현 → 통과 → 커밋 `feat(vdj): 수동 /revive(placeToTarget)`

---

## Chunk 6: 봇 좋아요 반응 (파킹 플랜 적응)

> 파킹 플랜 `docs/superpowers/plans/2026-07-02-virtual-dj-bot-reaction-liveliness.md`의 Task 1~6을 그대로 실행하되 아래 델타 적용. 후보 풀은 본 모델에서 이미 정확(리스너 봇 포함)하므로 로직 변경 없음.

**델타:**
- Task 1~2(파킹): `PlaybackReactionSimulationService`를 **party**에 생성 + admin `simulateReactions` 위임 + admin `ReactionSimulationService` 삭제. (파킹 플랜 코드 그대로.)
- Task 3(파킹): `ConfigKey` `vdj.reaction.enabled/probability_percent` + `VirtualDjReactionConfig`.
- Task 4(파킹): party `PartyroomQueryService.getCurrentPlaybackState` + `CurrentPlaybackView`.
- Task 5(파킹): `BotReactionService.tryReact` — 후보=`findActivePersonaBotsInRoom`−현재DJ crewId(리스너봇+비현재크루봇 자동 포함).
- Task 6(파킹): 스윕 wiring — 단, Task 4.1에서 스윕 reconcile을 이미 제거했으므로 스윕은 [self-update 루프] + [반응 루프]만. **추가: 스윕 최상단 점검 게이트** `if (maintenanceGate.isUnderMaintenance()) return;`.

- [ ] **Step 1:** 파킹 플랜 Task 1~6을 위 델타로 실행(각 Task의 test-first/커밋 그대로).
- [ ] **Step 2:** 스윕 점검 게이트 테스트 추가 — `maintenanceGate.isUnderMaintenance()==true`면 반응·self-update 모두 미호출.
- [ ] **Step 3:** 전체 통과 + `grep -rn "ReactionSimulationService" app/src`(admin) → 0건.
- [ ] **Step 4: 커밋** — 파킹 플랜 커밋 메시지 + `feat(vdj): 60초 스윕 점검 게이트`

---

## Chunk 7: ArchUnit · 페르소나 보장 · 마무리

### Task 7.1: ArchUnit admin-ban 확대

**Files:**
- Modify: `app/src/test/java/.../virtualdj/VirtualDjArchitectureTest.java`

> ⚠️ **주의(리뷰 지적)**: 기존 `orchestratorMustNotDependOnAdmin`(VirtualDjArchitectureTest:54-62)은 `*Orchestrator*` 이름 클래스에 대해 `..admin..` **와** `..administration..` **둘 다** 금지한다. 순진하게 이름 한정만 없애면 `..administration..` 금지도 패키지 전체로 확대돼 **Task 4.3의 `VirtualDjMaintenanceListener`(administration 이벤트 import)가 ArchUnit 실패**한다(spec §13: administration 구독은 허용).

- [ ] **Step 1:** 규칙을 **분리**한다. (a) `..admin..` import 금지 → `..virtualdj..` **패키지 전체**로 확대. (b) `..administration..` import 금지 → **`*Orchestrator*` 이름 한정 유지**(리스너/서비스는 administration 이벤트 구독 허용, orchestrator만 금지). `PlaybackReactionSimulationService`는 party에 있어 (a) 통과, `VirtualDjMaintenanceListener`는 orchestrator 아니라 (b) 통과.
- [ ] **Step 2:** Run: `... ./gradlew test --tests "*VirtualDjArchitectureTest"` → PASS.
- [ ] **Step 3: 커밋** — `test(vdj): admin 금지 virtualdj 전체 확대(administration은 orchestrator 한정 유지)`

### Task 7.2: 페르소나 배정 보장

**Files:**
- Modify: 봇 claim/provision 경로(`VirtualUserPoolService.provision` or 배치 시점) — 페르소나 미배정 봇 배제 or 자동 배정
- Test: 해당 서비스 테스트

- [ ] **Step 1~5:** 리스너/DJ 봇이 배치될 때 페르소나 보장(미배정이면 배치 대상 제외 또는 `BotPersonaAssignmentService`로 배정). 카운트는 페르소나 비게이트(§13)라 왜곡 없음. test-first→구현→커밋.

### Task 7.3: 완료 기준 / 수동 검증

- [ ] 전체 유닛 GREEN: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew test`
- [ ] `grep -rn "companionFloor\|FROZEN\|DesiredBotCalculator\|FlapGuard" app/src` → 0건(마이그레이션 제외)
- [ ] `grep -rn "ReactionSimulationService" app/src` → party만
- [ ] **로컬 docker compose fresh DB 풀부팅**(마이그레이션 + Hibernate validate 통과, reference: 로컬 부팅 게이트)
- [ ] Flyway 버전 머지 직전 재확정(uniq -d)
- [ ] **로컬 풀스택 e2e**(dev 머지 전, feedback): stg 유사 환경에서
  - applyConfig(target=3, dj=2) → 크루봇 2(각 다른 트랙 조각) + 리스너봇 1 투입
  - 사람 입장해도 봇 수 불변(고정)
  - 호스트가 크루봇 kick → 재등록 안 됨
  - `vdj.reaction.enabled=true` → 재생 중 리스너/비현재크루 봇이 좋아요, 현재 DJ 봇은 자기 곡에 안 함
  - `/drain-resources` → 봇 제거·MANAGED 유지, `/revive` → 재배치
  - 점검 시작 → 봇 드레인, 점검 종료/취소 → 부활
  - 재기동 → 부팅 부활(중복 없음)
- [ ] dev 머지는 **보류**(사용자 게이트)

## 비회귀 체크
- admin `simulateReactions` 위임 후 동일 동작(LIKE/GRAB 2그룹 async 보존, 파킹 플랜 Task 2 주의).
- `SystemConfigCache.readInt` ≤0 fallback → 확률 0 불가, 정지는 `enabled=false`.
- 마이그레이션 무음 축소(기존 dj_count=target_count가 트랙<target인 방에서 부팅 시 clamp) — prod dormant라 저위험, stg 기존 MANAGED 방 있으면 재적용.
