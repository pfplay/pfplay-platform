# 가상 크루 재배치(replace) 구현 플랜 (platform #327 / admin #26)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 송팩 변경 시 자동 drain→place + 명시적 `/replace` 엔드포인트(platform) + 어드민 콘솔 「재배치」 버튼(admin)으로, "송팩 교체/편집이 기존 배치 봇에 반영 안 되는 무음 no-op"을 제거한다.

**Architecture:** `VirtualCrewOrchestrator`에 `replaceRoom`(룸 분산락 1회 안에서 언락 프리미티브 `drainResources`→`placeToTarget` 직접 호출) 추가. `VirtualCrewAdminService.applyConfig`는 MANAGED 적용 시 `previousSongPackId != songPackId`면 `replaceRoom`, 아니면 기존 `reconcileRoom`. 컨트롤러에 `POST .../replace` 추가. admin은 기존 revive/drain-resources 3종 세트(api fn + mutation hook + 카드 버튼) 패턴 복제 + 확인 다이얼로그.

**Tech Stack:** Spring Boot 3(Java 21) + JUnit5/Mockito/Testcontainers IT(패턴A) / React+Vite+TanStack Query+msw+vitest

**Spec:** `docs/superpowers/specs/2026-07-11-virtual-crew-replace-design.md` (platform 레포, 커밋됨)
**Branches:** platform `feat/virtual-crew-replace-327` / admin `feat/virtual-crew-replace-button-26` (둘 다 origin/develop 분기, 생성됨)
**Working dirs:** `C:\Users\Eisen\Desktop\Labs\[projects] pfplay\pfplay-platform` / `...\pfplay-admin`
**빌드 주의:** platform gradle은 항상 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix 필수.

**확인된 기존 코드 사실 (재검증 불필요):**
- `VirtualCrewOrchestratorImpl` = `DistributedLockExecutor lock` + `BotPlacementService` 2필드, `reconcileRoom`/`drainRoom` 모두 `@Transactional` + `lock.performTaskWithLock("virtualcrew:" + partyroomId.getId(), () -> { ...; return null; })`.
- `DistributedLockExecutor`는 **비재진입**(SETNX 1회 시도, 점유 중이면 warn 후 no-op) — replaceRoom 내부에서 락 잡힌 `drainRoom`/`reconcileRoom` 합성 금지, 언락 프리미티브 직접 호출.
- `VirtualCrewAdminService.applyConfig`(라인 78-94): `loadOrCreate` → `applyStatus`(검증 포함) → `saveAndFlush` → `if (MANAGED) reconcileRoom / else if (OFF) drainRoom`.
- 컨트롤러 기존 액션 형태: `@Operation` + `@SecurityRequirement(name="cookieAuth")` + `@PreAuthorize("@adminAuth.canManageVirtualCrew()")` + `ResponseEntity.noContent().build()` (drain/drain-resources/revive 동일).
- IT 하네스: `VirtualCrewAdminServiceIT`(@Transactional, AbstractIntegrationTest) — `seedRoom(분)`·`seedSongPack()`(vidA/vidB 2트랙)·`activeBotDjCount(roomId)`·`poolService.provision(n)`·`flushAndClear()` 헬퍼 기존재. `VirtualCrewOrchestratorIT`에 `TrackRepository`로 linkId 단언 선례.
- admin: `virtual-crew-room-api.ts`(base fn들), `use-revive-virtual-crew.ts`(mutation+invalidate 2키+`mutationSuccessToast`), 훅 테스트 `__tests__/use-revive-virtual-crew.test.tsx`(msw+renderHook), 카드 `virtual-crew-config-card.tsx`(버튼 4개 flex, `isLive = live.status === "MANAGED"` 게이트, drain confirm Dialog 선례), 카드 테스트 `ui/__tests__/virtual-crew-config-card.test.tsx` 기존재.

---

## Chunk 1: platform — replaceRoom + applyConfig 분기 + 엔드포인트

### Task 1: `VirtualCrewOrchestrator.replaceRoom` (TDD)

**Files:**
- Test(Create): `app/src/test/java/com/pfplaybackend/api/virtualcrew/VirtualCrewOrchestratorImplTest.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/virtualcrew/application/port/VirtualCrewOrchestrator.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/virtualcrew/application/service/VirtualCrewOrchestratorImpl.java`

- [ ] **Step 1: `DistributedLockExecutor.performTaskWithLock` 시그니처 확인** (`app/src/main/java/com/pfplaybackend/api/party/application/service/lock/DistributedLockExecutor.java`) — **`public void performTaskWithLock(String lockSuffix, Supplier<Void> action)` (void 반환)로 확인됨.** void라서 `when(...)` 스텁은 컴파일 불가 — 아래 테스트처럼 `doAnswer(...).when(lock)...` 형태를 쓴다.

- [ ] **Step 2: 실패하는 유닛 테스트 작성** (개념 코드 — Step 1 확인한 타입으로 조정):

```java
package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.application.service.BotPlacementService;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewOrchestratorImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** replaceRoom — 락 1회 안에서 drain→place 순차(언락 프리미티브 직접 호출) 검증. */
@ExtendWith(MockitoExtension.class)
class VirtualCrewOrchestratorImplTest {

    @Mock private DistributedLockExecutor lock;
    @Mock private BotPlacementService botPlacementService;
    @InjectMocks private VirtualCrewOrchestratorImpl orchestrator;

    @Test
    @DisplayName("replaceRoom — 룸 락 1회 획득 안에서 drainResources → placeToTarget 순서로 호출한다")
    void replaceRoom_drainThenPlace_underSingleLock() {
        PartyroomId roomId = new PartyroomId(7L);
        // ⚠️ performTaskWithLock 는 void 반환(Supplier<Void> 파라미터) — when(...)은 컴파일 불가, doAnswer 필수
        doAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get())
                .when(lock).performTaskWithLock(eq("virtualcrew:7"), any());

        orchestrator.replaceRoom(roomId);

        verify(lock, times(1)).performTaskWithLock(eq("virtualcrew:7"), any());
        InOrder inOrder = inOrder(botPlacementService);
        inOrder.verify(botPlacementService).drainResources(roomId);
        inOrder.verify(botPlacementService).placeToTarget(roomId);
        verifyNoMoreInteractions(botPlacementService);
    }
}
```

주의: 락 키 문자열은 기존 구현이 `"virtualcrew:" + partyroomId.getId()`이므로 `PartyroomId(7L)` → `"virtualcrew:7"`. `PartyroomId.getId()` 반환형이 Long이면 그대로 성립. `BotPlacementService.drainResources/placeToTarget`의 파라미터 타입(PartyroomId vs Long)도 실제 시그니처로 맞출 것.

- [ ] **Step 3: 실패 확인**

Run: `cd "C:\Users\Eisen\Desktop\Labs\[projects] pfplay\pfplay-platform" && JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.virtualcrew.VirtualCrewOrchestratorImplTest"`
Expected: 컴파일 실패 — `replaceRoom` 미존재.

- [ ] **Step 4: 포트 + 구현 추가**

`VirtualCrewOrchestrator.java`에 추가:

```java
    /**
     * 재배치(replace) — 룸의 모든 봇을 회수(drain)한 뒤 현재 config·송팩 기준으로 즉시 재배치한다.
     *
     * <p>용도: 송팩 교체/곡 구성 변경 반영, djBotCount 변경 후 트랙 파티션 재분배, 자기갱신 드리프트 리셋.
     * 봇은 배치 시점 송팩 스냅샷을 재생하므로({@code SongPackApplier}), 팩 변경은 회수→재배치로만 전파된다.
     * 분산 락 1회로 회수·재배치 사이 개입 창을 봉쇄한다.
     */
    void replaceRoom(PartyroomId partyroomId);
```

`VirtualCrewOrchestratorImpl.java`에 추가 (기존 두 메서드 아래):

```java
    /**
     * {@link #reconcileRoom} 과 동일한 per-call(룸 단위) {@code @Transactional} 경계.
     *
     * <p><b>락 합성 금지:</b> {@link DistributedLockExecutor} 는 비재진입(점유 중이면 무음 skip)이므로
     * 락이 걸린 {@code drainRoom}/{@code reconcileRoom} 을 이어 부르지 않고, 락 1회 안에서
     * 언락 프리미티브({@code drainResources}→{@code placeToTarget})를 직접 호출한다.
     *
     * <p><b>복구 모델:</b> place 단계 예외 시 — {@code /replace} 엔드포인트 경로는 config 무변경(MANAGED
     * 유지)이라 revive 재시도로 복구, applyConfig 자동 replace 경로는 같은 트랜잭션이라 송팩 변경까지
     * 롤백(기존 reconcile 실패 시맨틱과 동일).
     *
     * <p>⚠️ 락 TTL 대비 임계구간이 drain+place 로 길어진다 — placeToTarget 단독도 초과 가능한
     * 선재 리스크 클래스로, 본 변경에서 확장하지 않는다.
     */
    @Override
    @Transactional
    public void replaceRoom(PartyroomId partyroomId) {
        lock.performTaskWithLock("virtualcrew:" + partyroomId.getId(), () -> {
            botPlacementService.drainResources(partyroomId);
            botPlacementService.placeToTarget(partyroomId);
            return null;
        });
    }
```

- [ ] **Step 5: 통과 확인** — Step 3 명령 재실행, PASS.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/virtualcrew/application/port/VirtualCrewOrchestrator.java app/src/main/java/com/pfplaybackend/api/virtualcrew/application/service/VirtualCrewOrchestratorImpl.java app/src/test/java/com/pfplaybackend/api/virtualcrew/VirtualCrewOrchestratorImplTest.java
git commit -m "feat(virtualcrew): replaceRoom — 룸 락 1회 안 drain→place 재배치 프리미티브 (#327)"
```

### Task 2: `applyConfig` 송팩 변경 분기 + `replace()` 서비스 (TDD)

**Files:**
- Test(Create): `app/src/test/java/com/pfplaybackend/api/virtualcrew/VirtualCrewAdminServiceTest.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/virtualcrew/application/service/VirtualCrewAdminService.java`

- [ ] **Step 1: 실패하는 유닛 테스트 작성** — 트리거 분기 선택만 mock으로 검증(무거운 IT는 Task 4). `applyStatus`가 MANAGED+songPackId!=null이면 `partyroomQueryService.getPartyroomById`(PlaybackTimeLimit 필요)와 `songPackApplier.countPlayableTracks`를 호출하므로 스텁 필요 — `PartyroomData`는 mock으로 `getPlaybackTimeLimit()`이 `PlaybackTimeLimit`을 반환하게(중첩 mock 또는 실제 enum/VO 사용, 실제 타입 확인 후 조정):

```java
package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.PartyroomVirtualCrewConfigRepository;
import com.pfplaybackend.api.virtualcrew.application.port.VirtualCrewOrchestrator;
import com.pfplaybackend.api.virtualcrew.application.service.ActiveDjSnapshotService;
import com.pfplaybackend.api.virtualcrew.application.service.SongPackApplier;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewAdminService;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualUserPoolService;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.PartyroomVirtualCrewConfigData;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** applyConfig 트리거 분기 — 송팩 변경=replaceRoom / 팩 동일=reconcileRoom / OFF=drainRoom. */
@ExtendWith(MockitoExtension.class)
class VirtualCrewAdminServiceTest {

    @Mock private PartyroomVirtualCrewConfigRepository configRepository;
    @Mock private VirtualCrewOrchestrator orchestrator;
    @Mock private ActiveDjSnapshotService activeDjSnapshotService;
    @Mock private VirtualUserPoolService poolService;
    @Mock private BotPoolQueryRepository botPoolQueryRepository;
    @Mock private PartyroomQueryService partyroomQueryService;
    @Mock private SongPackApplier songPackApplier;

    private VirtualCrewAdminService service;

    private static final PartyroomId ROOM = new PartyroomId(7L);

    @BeforeEach
    void setUp() {
        service = new VirtualCrewAdminService(configRepository, orchestrator, activeDjSnapshotService,
                poolService, botPoolQueryRepository, partyroomQueryService, songPackApplier);
        // applyStatus 의 송팩 검증 게이트 통과 스텁 (songPackId != null 케이스)
        PartyroomData room = mock(PartyroomData.class, RETURNS_DEEP_STUBS);
        lenient().when(room.getPlaybackTimeLimit().getMinutes()).thenReturn(5);
        lenient().when(partyroomQueryService.getPartyroomById(any())).thenReturn(room);
        lenient().when(songPackApplier.countPlayableTracks(anyLong(), anyInt())).thenReturn(10);
        lenient().when(configRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void givenExistingConfig(VirtualCrewStatus status, Integer target, Integer djBot, Long packId) {
        PartyroomVirtualCrewConfigData cfg = PartyroomVirtualCrewConfigData.create(ROOM.getId());
        if (status == VirtualCrewStatus.MANAGED) {
            cfg.applyManaged(target, djBot, packId);
        }
        when(configRepository.findByPartyroomId(ROOM.getId())).thenReturn(Optional.of(cfg));
    }

    @Test
    @DisplayName("MANAGED 적용 + 송팩 변경 → replaceRoom (reconcile 아님)")
    void managed_packChanged_triggersReplace() {
        givenExistingConfig(VirtualCrewStatus.MANAGED, 3, 2, 100L);
        service.applyConfig(ROOM, VirtualCrewStatus.MANAGED, 3, 2, 200L);
        verify(orchestrator).replaceRoom(ROOM);
        verify(orchestrator, never()).reconcileRoom(any());
    }

    @Test
    @DisplayName("MANAGED 적용 + 송팩 동일(카운트만 변경) → reconcileRoom (봇 전원 교체 아님)")
    void managed_samePack_triggersReconcile() {
        givenExistingConfig(VirtualCrewStatus.MANAGED, 3, 2, 100L);
        service.applyConfig(ROOM, VirtualCrewStatus.MANAGED, 5, 2, 100L);
        verify(orchestrator).reconcileRoom(ROOM);
        verify(orchestrator, never()).replaceRoom(any());
    }

    @Test
    @DisplayName("신규 config(prev pack=null)에 송팩 지정 → replaceRoom (drain은 무해 no-op)")
    void newConfig_withPack_triggersReplace() {
        when(configRepository.findByPartyroomId(ROOM.getId())).thenReturn(Optional.empty());
        service.applyConfig(ROOM, VirtualCrewStatus.MANAGED, 3, 2, 100L);
        verify(orchestrator).replaceRoom(ROOM);
    }

    @Test
    @DisplayName("MANAGED 적용 + 송팩 null화 → replaceRoom (place는 게이트 skip → 봇 회수 의미)")
    void managed_packNulled_triggersReplace() {
        givenExistingConfig(VirtualCrewStatus.MANAGED, 3, 2, 100L);
        service.applyConfig(ROOM, VirtualCrewStatus.MANAGED, 3, 2, null);
        verify(orchestrator).replaceRoom(ROOM);
    }

    @Test
    @DisplayName("OFF 적용 → drainRoom (기존 동작 불변)")
    void off_triggersDrain() {
        givenExistingConfig(VirtualCrewStatus.MANAGED, 3, 2, 100L);
        service.applyConfig(ROOM, VirtualCrewStatus.OFF, null, null, null);
        verify(orchestrator).drainRoom(ROOM);
        verify(orchestrator, never()).replaceRoom(any());
        verify(orchestrator, never()).reconcileRoom(any());
    }

    @Test
    @DisplayName("replace() — orchestrator.replaceRoom 위임")
    void replace_delegates() {
        service.replace(ROOM);
        verify(orchestrator).replaceRoom(ROOM);
    }
}
```

주의: `VirtualCrewAdminService`는 `@RequiredArgsConstructor` — 생성자 파라미터 순서는 필드 선언 순서(configRepository, orchestrator, activeDjSnapshotService, poolService, botPoolQueryRepository, partyroomQueryService, songPackApplier)와 일치해야 함. self 프록시 필드는 setter 주입이라 생성자 무관. `PartyroomVirtualCrewConfigData.create/applyManaged` 접근이 테스트에서 가능하지 않으면(가시성) `givenExistingConfig`를 mock 기반(`when(cfg.getSongPackId())...`)으로 전환.

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.virtualcrew.VirtualCrewAdminServiceTest"`
Expected: 컴파일 실패 — `replace` 미존재 (분기 테스트는 replaceRoom 미호출로 실패).

- [ ] **Step 3: 구현** — `VirtualCrewAdminService.applyConfig` 트리거 분기 교체 + `replace` 추가:

applyConfig 수정 (기존 라인 78-94 범위):

```java
    /** 단일 룸 config 적용 후 MANAGED 면 reconcile(송팩 변경 시 replace), OFF 면 drain 을 같은 트랜잭션에서 트리거. */
    @Transactional
    public void applyConfig(PartyroomId partyroomId, VirtualCrewStatus status, Integer targetCount,
                            Integer djBotCount, Long songPackId) {
        PartyroomVirtualCrewConfigData cfg = loadOrCreate(partyroomId);
        Long previousSongPackId = cfg.getSongPackId();
        applyStatus(cfg, status, targetCount, djBotCount, songPackId);
        // saveAndFlush: reconcile/drain 의 봇 명령 경로가 영속성 컨텍스트를 clear 할 수 있어,
        // config 변경을 즉시 DB 에 확정한 뒤 reconcile(자기 config 재조회)/drain 을 트리거한다.
        configRepository.saveAndFlush(cfg);
        log.info("[VirtualCrewAdmin.applyConfig] partyroomId={} status={} target={} djBotCount={} songPackId={}",
                partyroomId.getId(), status, targetCount, djBotCount, songPackId);

        if (status == VirtualCrewStatus.MANAGED) {
            if (!Objects.equals(previousSongPackId, songPackId)) {
                // 송팩 변경 — 봇은 배치 시점 스냅샷을 재생하므로 reconcile(카운트 수렴)만으로는
                // 기존 봇이 옛 팩을 계속 튼다(무음 no-op). 회수→재배치로 새 팩 스냅샷을 강제한다.
                orchestrator.replaceRoom(partyroomId);
            } else {
                orchestrator.reconcileRoom(partyroomId);
            }
        } else if (status == VirtualCrewStatus.OFF) {
            orchestrator.drainRoom(partyroomId);
        }
    }
```

(import `java.util.Objects` 추가.)

`revive` 아래에 추가:

```java
    /**
     * 재배치 — 봇 전원 회수 후 현재 config·송팩 기준으로 즉시 재배치. config 미변경(MANAGED 유지).
     * 용도: 송팩 곡 구성 편집 반영, djBotCount 변경 후 파티션 재분배, 자기갱신 드리프트 리셋.
     */
    @Transactional
    public void replace(PartyroomId partyroomId) {
        orchestrator.replaceRoom(partyroomId);
        log.info("[VirtualCrewAdmin.replace] partyroomId={}", partyroomId.getId());
    }
```

- [ ] **Step 4: 통과 확인** — Step 2 명령 재실행, PASS (7 tests).

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/virtualcrew/application/service/VirtualCrewAdminService.java app/src/test/java/com/pfplaybackend/api/virtualcrew/VirtualCrewAdminServiceTest.java
git commit -m "feat(virtualcrew): applyConfig 송팩 변경 시 자동 재배치 + replace 서비스 (#327)"
```

### Task 3: `/replace` 엔드포인트

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/virtualcrew/adapter/in/web/AdminVirtualCrewController.java` (revive 메서드 바로 아래)

- [ ] **Step 1: 엔드포인트 추가** (기존 revive와 동일 형태):

```java
    @Operation(summary = "룸 재배치(replace)", description = "봇 전원 회수 후 현재 config·송팩 기준 재배치 — 송팩 교체/곡 구성 변경 반영용")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualCrew()")
    @PostMapping("/partyrooms/{partyroomId}/virtual-crew/replace")
    public ResponseEntity<Void> replace(@PathVariable("partyroomId") Long partyroomId) {
        adminService.replace(new PartyroomId(partyroomId));
        return ResponseEntity.noContent().build();
    }
```

- [ ] **Step 2: 컨트롤러 테스트 추가** — `app/src/test/.../AdminVirtualCrewControllerTest.java`의 기존 revive 3종 세트(`revive_admin_returns204` / `revive_member_returns403` / `revive_missingCsrf_returns403`, 라인 303~319 부근)를 그대로 미러해 `replace_admin_returns204` / `replace_member_returns403` / `replace_missingCsrf_returns403` 추가 (URL만 `/replace`, 서비스 verify는 `adminService.replace(...)`). **fail-first**: 엔드포인트 추가 전 테스트 먼저 작성해 404/컴파일 실패 확인 후 Step 1 구현 순서로 진행해도 좋다(권장).

- [ ] **Step 3: 테스트 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminVirtualCrewControllerTest"`
Expected: PASS (기존 + 신규 3)

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/virtualcrew/adapter/in/web/AdminVirtualCrewController.java app/src/test/java/com/pfplaybackend/api/virtualcrew/adapter/in/web/AdminVirtualCrewControllerTest.java
git commit -m "feat(virtualcrew): POST /partyrooms/{id}/virtual-crew/replace 엔드포인트 (#327)"
```
(⚠️ 컨트롤러 테스트 파일 실제 경로는 기존 파일 위치를 따를 것 — `find app/src/test -name "AdminVirtualCrewControllerTest.java"`)

### Task 4: 기능 IT — 무음 no-op 회귀 방지 핵심 단언 (TDD)

**Files:**
- Modify: `app/src/test/java/com/pfplaybackend/api/virtualcrew/VirtualCrewAdminServiceIT.java`

- [ ] **Step 1: 기존 IT 픽스처 파악** — `VirtualCrewOrchestratorIT`에서 봇 플레이리스트 트랙(linkId) 단언에 쓰는 리포지토리/헬퍼(`TrackRepository`, `VirtualUserPoolService.playlistIdOf` 또는 동등물)를 확인해 그대로 재사용한다. 활성 봇 userId 조회는 `BotPoolQueryRepository.findActiveBotCrewUserIdsByJoinedDesc` 사용(OrchestratorIT 선례 확인).

- [ ] **Step 2: 실패하는 IT 3건 추가** (개념 코드 — 픽스처 시그니처는 Step 1 확인분으로 조정):

```java
    private Long seedSongPackWith(String vid1, String vid2) {
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("Pack-" + System.nanoTime(), "IT"));
        packTrackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 1, vid1, "Song " + vid1, "2:00", null));
        packTrackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 2, vid2, "Song " + vid2, "3:00", null));
        return pack.getId();
    }

    /** 룸의 활성 봇 전원의 개인 플레이리스트 트랙 linkId 집합. */
    private Set<String> activeBotPlaylistLinkIds(long roomId) { /* Step 1 픽스처로 구현 */ }

    @Test
    @DisplayName("applyConfig 송팩 교체 — 기존 배치 봇이 새 팩 스냅샷으로 재복사된다 (무음 no-op 회귀 방지)")
    void applyConfig_packSwap_rebuildsBotSnapshots() {
        long roomId = seedRoom(5);
        Long packA = seedSongPackWith("vidA", "vidB");
        Long packB = seedSongPackWith("vidC", "vidD");
        poolService.provision(4);
        flushAndClear();

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packA);
        flushAndClear();
        assertThat(activeBotPlaylistLinkIds(roomId)).containsExactlyInAnyOrder("vidA", "vidB");

        // 카운트 동일 + 송팩만 교체 → 종전엔 무음 no-op, 이제 replace 로 새 팩 반영
        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packB);
        flushAndClear();

        assertThat(activeBotDjCount(roomId)).isEqualTo(2);
        assertThat(activeBotPlaylistLinkIds(roomId)).containsExactlyInAnyOrder("vidC", "vidD");
    }

    @Test
    @DisplayName("applyConfig 카운트만 변경 — 기존 봇 유지(전원 교체 아님), 추가분만 투입")
    void applyConfig_countOnly_keepsExistingBots() {
        long roomId = seedRoom(5);
        Long packId = seedSongPackWith("vidA", "vidB");
        poolService.provision(4);
        flushAndClear();

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packId);
        flushAndClear();
        List<UserId> before = activeBotUserIds(roomId); // findActiveBotCrewUserIdsByJoinedDesc 반환형 = List<UserId> (value equals)

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 3, 2, packId);
        flushAndClear();

        assertThat(activeBotUserIds(roomId)).containsAll(before); // 기존 봇 그대로 + 리스너 1 추가
    }

    @Test
    @DisplayName("replace() — 송팩 곡 구성 편집이 재배치로 반영된다")
    void replace_appliesEditedPackContents() {
        long roomId = seedRoom(5);
        Long packId = seedSongPackWith("vidA", "vidB");
        poolService.provision(4);
        flushAndClear();

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packId);
        flushAndClear();

        // 팩 내용 편집(트랙 추가) — 기존 봇에는 미반영 상태
        packTrackRepository.save(VirtualSongPackTrackData.create(packId, 3, "vidE", "Song vidE", "2:30", null));
        flushAndClear();

        adminService.replace(new PartyroomId(roomId));
        flushAndClear();

        assertThat(activeBotDjCount(roomId)).isEqualTo(2);
        assertThat(activeBotPlaylistLinkIds(roomId)).contains("vidE"); // 2봇 청크 분배에 vidE 포함
    }
```

주의: `activeBotPlaylistLinkIds`의 "청크 분배" 단언 — 2 DJ봇에 3트랙이면 TrackDistribution이 조각을 나누므로 **합집합**으로 단언(개별 봇 단위 아님). replace 후 배치되는 봇은 풀에서 다시 뽑히므로 이전과 다른 계정일 수 있음 — userId가 아니라 "활성 봇들의 플리 내용"으로 단언한다.

- [ ] **Step 3: 실패 확인 (fail-first)** — Task 2 구현이 이미 들어갔으므로 순수 fail-first가 어려움. 대신 **핵심 단언의 가치 증명**: `applyConfig_packSwap_rebuildsBotSnapshots`에서 `replaceRoom` 분기를 잠시 `reconcileRoom`으로 되돌린 로컬 변경(미커밋)으로 테스트가 `vidA/vidB` 잔존으로 **실패함을 확인** 후 원복 — 이 테스트가 무음 no-op을 실제로 잡는다는 증거.

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "com.pfplaybackend.api.virtualcrew.VirtualCrewAdminServiceIT"`

- [ ] **Step 4: 원복 후 통과 확인** — 같은 명령, 전체 PASS (기존 IT 포함).

- [ ] **Step 5: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/virtualcrew/VirtualCrewAdminServiceIT.java
git commit -m "test(virtualcrew): 송팩 교체/편집 재배치 반영 IT — 무음 no-op 회귀 방지 (#327)"
```

### Task 5: platform 회귀 (검증만)

- [ ] **Step 1: 전체 유닛** — `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test` → GREEN (기존 ~1313 유지+신규).
- [ ] **Step 2: virtualcrew 패키지 IT 전체** — `JAVA_HOME=... ./gradlew :app:integrationTest --tests "com.pfplaybackend.api.virtualcrew.*"` → GREEN. (전량 IT는 Chunk 3 게이트에서. ⚠️IT 중단 시 gradle worker 좀비가 output.bin 락 가능 — `reference_gradle_worker_zombie_locks_output_bin` 메모리 절차로 해제.)

## Chunk 2: admin — 「재배치」 버튼

### Task 6: api 함수 + 훅 (TDD)

**Files (admin 레포 `C:\Users\Eisen\Desktop\Labs\[projects] pfplay\pfplay-admin`):**
- Modify: `src/features/partyrooms/api/virtual-crew-room-api.ts`
- Create: `src/features/partyrooms/api/use-replace-virtual-crew.ts`
- Test(Create): `src/features/partyrooms/api/__tests__/use-replace-virtual-crew.test.tsx`

- [ ] **Step 1: 실패하는 훅 테스트 작성** — `use-revive-virtual-crew.test.tsx`를 그대로 미러(엔드포인트 `/replace`, 토스트 문구 "재배치 완료"). 성공 시 invalidate 2키(`["virtual-crew","room",id]`, `["partyrooms"]`) + `toast.success("재배치 완료")`, 에러 시 invalidate 안 함 — 2케이스.

- [ ] **Step 2: 실패 확인** — `yarn test:run src/features/partyrooms/api/__tests__/use-replace-virtual-crew.test.tsx` (⚠️ `yarn test`는 watch 모드 — 반드시 `test:run`) → 모듈 없음 FAIL.

- [ ] **Step 3: 구현**

`virtual-crew-room-api.ts` — 주석 블록에 `POST .../replace → 204 (봇 전원 회수 후 현재 config·송팩 기준 재배치)` 한 줄 추가 + 함수:

```ts
export async function replace(id: number): Promise<void> {
  await http<void>(`${base(id)}/replace`, { method: "POST" })
}
```

`use-replace-virtual-crew.ts` (use-revive 미러):

```ts
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { replace } from "./virtual-crew-room-api"
import { mutationSuccessToast, mutationErrorToast } from "@/shared/lib/mutation-toast"

// 봇 전원 회수 후 현재 config·송팩 기준으로 재배치 — 송팩 교체/곡 구성 변경 반영용.
export function useReplaceVirtualCrew(partyroomId: number) {
  const qc = useQueryClient()
  return useMutation<void, unknown, void>({
    mutationFn: () => replace(partyroomId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["virtual-crew", "room", partyroomId] })
      qc.invalidateQueries({ queryKey: ["partyrooms"] })
      mutationSuccessToast("재배치 완료")
    },
    onError: mutationErrorToast,
  })
}
```

- [ ] **Step 4: 통과 확인** — Step 2 명령 재실행 PASS.
- [ ] **Step 5: 커밋** — `git add` 3파일, `git commit -m "feat(virtual-crew): 재배치 api·훅 — replace 뮤테이션 (#26)"`

### Task 7: 카드 버튼 + 확인 다이얼로그 (TDD)

**Files:**
- Modify: `src/features/partyrooms/ui/virtual-crew-config-card.tsx`
- Test(Modify): `src/features/partyrooms/ui/__tests__/virtual-crew-config-card.test.tsx`

- [ ] **Step 1: 기존 카드 테스트 파일을 읽고 패턴 파악** (렌더 셋업·msw·버튼 단언 방식) — 아래 테스트를 그 파일 컨벤션으로 작성.

- [ ] **Step 2: 실패하는 테스트 추가** (행동 스펙):
  1. MANAGED live 상태에서 「재배치」 버튼 렌더 + enabled, OFF면 disabled (기존 부활 버튼 게이트 테스트 미러).
  2. 「재배치」 클릭 → 확인 다이얼로그 표시(제목 "봇 재배치"), 확인 클릭 → `POST .../replace` 호출(msw spy)·다이얼로그 닫힘. ⚠️버튼명 "재배치"가 카드 트리거/다이얼로그 확인 2곳 — 기존 drain confirm 테스트처럼 `within(dialog)`로 스코프.
  3. 다이얼로그 취소 → 호출 없음.

- [ ] **Step 3: 실패 확인** — `yarn test:run src/features/partyrooms/ui/__tests__/virtual-crew-config-card.test.tsx` → 신규 케이스 FAIL.

- [ ] **Step 4: 구현** — 카드에 추가:
- import `useReplaceVirtualCrew`, 훅: `const replaceMutation = useReplaceVirtualCrew(partyroomId)`, state: `const [replaceOpen, setReplaceOpen] = useState(false)`.
- 버튼(부활과 리소스 회수 사이, outline·isLive 게이트 동일):

```tsx
          <Button
            variant="outline"
            onClick={() => setReplaceOpen(true)}
            disabled={!isLive || replaceMutation.isPending}
            title="봇 전원 회수 후 현재 설정·송팩 기준 재배치 — 송팩 교체/곡 편집 반영, DJ봇 수 변경 후 재분배, 드리프트 리셋"
          >
            {replaceMutation.isPending ? "재배치 중..." : "재배치"}
          </Button>
```

- 다이얼로그(기존 drain Dialog 아래, **비파괴 톤** — confirm 버튼 default variant):

```tsx
      {/* replace = 봇 전원 교체 → 가벼운 confirm (drain 과 달리 비파괴: MANAGED 유지) */}
      <Dialog open={replaceOpen} onOpenChange={setReplaceOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>봇 재배치</DialogTitle>
            <DialogDescription>
              이 파티룸의 가상 DJ 봇을 전부 회수한 뒤 현재 설정·송팩 기준으로
              다시 배치합니다. 송팩 교체/곡 구성 변경을 반영하거나 트랙 분배를
              다시 계산할 때 사용하세요. 운영(운영중) 상태는 유지됩니다.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setReplaceOpen(false)}
              disabled={replaceMutation.isPending}
            >
              취소
            </Button>
            <Button
              type="button"
              disabled={replaceMutation.isPending}
              onClick={() =>
                replaceMutation.mutate(undefined, {
                  onSuccess: () => setReplaceOpen(false),
                })
              }
            >
              {replaceMutation.isPending ? "재배치 중..." : "재배치"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
```

- [ ] **Step 5: 통과 확인** — Step 3 명령 재실행 PASS + `yarn test:run` 전체 GREEN(기존 686+신규) + `yarn build`(tsc+vite) PASS.
- [ ] **Step 6: 커밋** — `git commit -m "feat(virtual-crew): 파티룸 카드 재배치 버튼 + 확인 다이얼로그 (#26)"`

## Chunk 3: 로컬 검증 게이트 + PR (lockstep)

### Task 8: platform 전량 회귀 + 부팅 게이트 + 라이브 e2e

- [ ] **Step 1: 전량 IT** — `JAVA_HOME=... ./gradlew :app:integrationTest` → GREEN. (플래키 #320 `PartyroomAccessCommandServiceRaceIT`는 선재 — 해당 1건만 빨간 경우 재실행으로 판정.)
- [ ] **Step 2: fresh DB docker 부팅 게이트** — `JAVA_HOME=... ./gradlew :app:bootJar -x test` → `docker compose -f docker-compose.local.yml -p pfplay-local down -v` → `up -d --build` → "Started Application" + `/v3/api-docs` 200. (스키마 무변경이지만 부팅 게이트는 룰.)
- [ ] **Step 3: 라이브 e2e** (로컬 admin 계정 `admin@pfplay.local`, POST `/api/v1/auth/admin/login`, Origin `http://localhost:3000` 헤더 필수):
  1. 송팩 2개 생성(트랙 각 2) + 봇 풀 충원 + 방 1개에 packA로 MANAGED(2,2) 적용 → 배치 확인(live status).
  2. **packB로 교체 적용** → live status·DB(`playlist.source_song_pack_id`/track linkId)로 **새 팩 반영 확인** (본 기능의 핵심 시나리오).
  3. packB에 트랙 추가 → `POST .../replace` → 반영 확인.
  4. 로그에 무음 skip/에러 없는지 확인.
- [ ] **Step 4: push 전 커밋 정리** — Task별 커밋이 논리단위(4~5개)면 유지, 과분할 시 통합(파괴적 rebase 전 사용자 확인 룰).

### Task 9: PR 생성 (lockstep)

- [ ] **Step 1: platform PR** — base `develop`, 제목 `feat(virtualcrew): 재배치(replace) — 송팩 변경 자동 반영 + 명시적 엔드포인트 (#327)`, 본문 한글(스펙 링크·검증 증거·admin #26 lockstep 명시). CI 확인.
- [ ] **Step 2: admin PR** — base `develop`, 제목 `feat(virtual-crew): 파티룸 재배치 원버튼 (#26)`, 본문에 **platform #327 머지·dev 배포 후 머지** 의존 명시.
- [ ] **Step 3: 머지는 사용자 게이트** — platform 머지→Deploy to dev green→admin 머지 순서 보고.
