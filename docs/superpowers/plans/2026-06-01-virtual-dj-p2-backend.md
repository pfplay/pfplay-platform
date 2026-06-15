# 가상 사용자 능동 디제잉 (P2) — 백엔드 구현 플랜 (Plan A)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어드민이 파티룸에 "가상 DJ(봇)"를 두어 빈 방에서도 음악·머릿수가 유지되고, 진짜 사람이 DJ를 하면 봇이 동반자만 남기고 양보하도록, 봇을 **실제 계정**으로 만들어 **기존 도메인 진입점**을 통해 구동하는 백엔드를 만든다.

**Architecture:** 봇 = 실제 `user_account`(`is_dummy=true`). in-process `VirtualDjOrchestrator`가 도메인 이벤트(`CrewAccessedEvent`/`DjQueueChangedEvent`/`PartyroomTerminatedEvent`)에 반응해 방별 `reconcileRoom`을 실행, **ThreadLocal 봇 임퍼소네이션**으로 실유저가 쓰는 `tryEnter`/`enqueueDj`/`dequeueDj`/`exit`를 그대로 호출(=path A). persistence/publisher 직접 접근은 **ArchUnit으로 금지**. 룸 직렬화는 기존 `DistributedLockExecutor` 재사용. anti-flap(디바운스/최소체류) + 저빈도 안전망 reconcile.

**Tech Stack:** Java/Spring (멀티모듈 Gradle: `common, realtime, playlist, user, avatar, app`), JPA + QueryDSL, Flyway(MySQL), Redis(분산락), JUnit5 + Mockito + Testcontainers, ArchUnit(`archunit-junit5:1.2.1`).

**Spec:** `docs/superpowers/specs/2026-06-01-virtual-dj-p2-design.md` (결정 D1~D6, §4.1 산식/anti-flap, §5.1 송팩 복사).

**전제 / 컨벤션:**
- 빌드/테스트는 JDK 21: 모든 gradle 호출에 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix.
- 단위: `./gradlew test`  / 통합: `./gradlew integrationTest`(또는 `-Pinclude-integration`). 통합 테스트는 `AbstractIntegrationTest`(Testcontainers MySQL+Redis) 상속.
- 신규 코드 배치 모듈: **`app`** (party aggregate와 강결합). 패키지 루트: `com.pfplaybackend.api.virtualdj.*`, 기존 헥사고날 구조(`adapter.in.web` / `application` / `domain` / `adapter.out.persistence`) 따른다.
- 커밋: TDD 마이크로 커밋(로컬 OK), push/PR 직전 논리단위 squash. 한글 커밋 메시지. git 브랜치/이슈는 사용자 게이트(아직 X).
- 이름 컨벤션: 엔티티 `*Data`(예 `VirtualSongPackData`), value `*Id`, repo 인터페이스 + `*RepositoryImpl`(QueryDSL).

---

## Chunk 1: 스키마 · 엔티티 · 리포지토리

**책임:** P2가 쓸 영속 구조를 만든다. 가산적·최소. 봇 식별 플래그, 송 팩(+트랙), 방별 가상 DJ config, 봇 playlist의 송팩 출처 추적.

**Files:**
- Create: `app/src/main/resources/db/migration/V24__create_virtual_dj.sql`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java` (`isDummy` 필드)
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/domain/entity/data/PlaylistData.java` (`sourceSongPackId` 필드)
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/domain/entity/data/VirtualSongPackData.java`
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/domain/entity/data/VirtualSongPackTrackData.java`
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/domain/entity/data/PartyroomVirtualDjConfigData.java`
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/domain/enums/VirtualDjStatus.java`
- Create: repositories `VirtualSongPackRepository`, `VirtualSongPackTrackRepository`, `PartyroomVirtualDjConfigRepository` (+ `*RepositoryImpl` if QueryDSL needed)
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/VirtualDjPersistenceIT.java`

- [ ] **Step 0 (verify, §9-1):** Read `PlaylistCommandPort.peekTracksFromCursor` / `advancePlaybackCursor` 구현부를 열어 **플레이리스트 끝에서 커서가 맨 앞으로 wrap 하는지** 확인. wrap 한다면 추가 작업 없음. wrap 안 하면 이 플랜에 "봇 playlist 끝→앞 wrap 보정" 태스크를 추가(별도 노트). 결과를 이 파일 상단에 한 줄 기록.

- [ ] **Step 1: 마이그레이션 작성** — `V24__create_virtual_dj.sql`

```sql
-- =====================================================
-- V24: 가상 사용자 능동 디제잉 (P2)
-- =====================================================

-- 봇 식별 플래그
ALTER TABLE user_account
    ADD COLUMN is_dummy TINYINT(1) NOT NULL DEFAULT 0 COMMENT '가상 사용자(봇) 여부';

-- 봇 playlist 의 송팩 출처(라이브 상태 비교용)
ALTER TABLE playlist
    ADD COLUMN source_song_pack_id BIGINT UNSIGNED NULL COMMENT '이 playlist 가 복사된 송 팩 (봇 전용)';

-- 재사용 송 팩
CREATE TABLE virtual_song_pack (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255) NULL,
    created_at  DATETIME NOT NULL,
    updated_at  DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_virtual_song_pack_name (name)
);

CREATE TABLE virtual_song_pack_track (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    song_pack_id    BIGINT UNSIGNED NOT NULL,
    order_number    INT UNSIGNED NOT NULL,
    link_id         VARCHAR(255) NOT NULL COMMENT 'YouTube videoId',
    name            VARCHAR(255) NOT NULL,
    duration        VARCHAR(255) NOT NULL COMMENT 'MM:SS, playbackTimeLimit 사전검증됨',
    thumbnail_image VARCHAR(255) NULL,
    PRIMARY KEY (id),
    KEY idx_vspt_song_pack_id (song_pack_id)
);

-- 방별 가상 DJ 제어 (2축: target + status)
CREATE TABLE partyroom_virtual_dj_config (
    partyroom_id    BIGINT UNSIGNED NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'OFF' COMMENT 'OFF/MANAGED/FROZEN',
    target_count    INT UNSIGNED NOT NULL DEFAULT 2,
    companion_floor INT UNSIGNED NOT NULL DEFAULT 1,
    song_pack_id    BIGINT UNSIGNED NULL,
    created_at      DATETIME NOT NULL,
    updated_at      DATETIME NOT NULL,
    PRIMARY KEY (partyroom_id)
);
```

- [ ] **Step 2: `is_dummy` / `source_song_pack_id` 엔티티 필드 추가**

`UserAccountData`에 (패턴: 기존 `mustChangePassword` 참고):
```java
@Column(name = "is_dummy", nullable = false, columnDefinition = "TINYINT(1)")
private boolean isDummy;

public boolean isDummy() { return isDummy; }
```
`PlaylistData`에 nullable `Long sourceSongPackId` + getter + `void bindSongPackSource(Long songPackId)`.

- [ ] **Step 3: enum + 엔티티 작성** — `VirtualDjStatus { OFF, MANAGED, FROZEN }`. `VirtualSongPackData`/`VirtualSongPackTrackData`/`PartyroomVirtualDjConfigData`는 `TrackData` 패턴(`@Entity @DynamicInsert/Update`, protected 기본생성자, `@Builder`, 정적 팩토리 `create(...)`) 따른다. `PartyroomVirtualDjConfigData`는 `partyroom_id`가 PK이고 도메인 메서드 `applyManaged(target, floor, songPackId)`, `freeze()`, `turnOff()`, `desiredBot(humanDjCount)` 보유(산식은 Chunk 4에서 테스트, 여기선 필드/전이만).

- [ ] **Step 4: 리포지토리 작성** — `JpaRepository` 상속. 필요한 파생 쿼리:
  - `PartyroomVirtualDjConfigRepository`: `Optional<...> findByPartyroomId(Long)`, `List<...> findByStatus(VirtualDjStatus)` (안전망 reconcile용 — MANAGED 전체).
  - `VirtualSongPackTrackRepository`: `List<...> findBySongPackIdOrderByOrderNumberAsc(Long)`.
  - `VirtualSongPackRepository`: `boolean existsByName(String)`.

- [ ] **Step 5: 통합 테스트 작성 (failing)** — `VirtualDjPersistenceIT extends AbstractIntegrationTest`

```java
@Test
void 송팩과_트랙_저장_조회() {
    VirtualSongPackData pack = virtualSongPackRepository.save(VirtualSongPackData.create("K-POP Hot", "케이팝"));
    virtualSongPackTrackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 1, "POe9SOEKotk", "Shut Down", "03:01", "https://i.ytimg.com/..."));
    List<VirtualSongPackTrackData> tracks = virtualSongPackTrackRepository.findBySongPackIdOrderByOrderNumberAsc(pack.getId());
    assertThat(tracks).hasSize(1);
    assertThat(tracks.get(0).getLinkId()).isEqualTo("POe9SOEKotk");
}

@Test
void config_기본값과_전이() {
    PartyroomVirtualDjConfigData cfg = PartyroomVirtualDjConfigData.create(1L);
    assertThat(cfg.getStatus()).isEqualTo(VirtualDjStatus.OFF);
    cfg.applyManaged(2, 1, 10L);
    assertThat(cfg.getStatus()).isEqualTo(VirtualDjStatus.MANAGED);
    partyroomVirtualDjConfigRepository.save(cfg);
    assertThat(partyroomVirtualDjConfigRepository.findByPartyroomId(1L)).isPresent();
}
```

- [ ] **Step 6: 마이그레이션+통합 테스트 실행 → PASS 확인**
Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew integrationTest --tests "*VirtualDjPersistenceIT"`
Expected: Flyway가 V24 적용 후 2 tests PASS.

- [ ] **Step 7: 커밋** — `feat(virtual-dj): V24 스키마 + 송팩/config 엔티티·리포지토리`

---

## Chunk 2: 봇 정체성 임퍼소네이션 · 봇 풀 프로비저닝

**책임:** (1) 봇으로서 도메인 메서드를 호출하는 `withBotIdentity` 유틸(§1.1). (2) `is_dummy` 실계정 + 그 playlist 를 생성/조회하는 봇 풀 서비스.

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/identity/BotIdentityExecutor.java`
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/VirtualUserPoolService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/BotIdentityExecutorTest.java`, `VirtualUserPoolServiceIT.java`

- [ ] **Step 0 (verify):** `ThreadLocalContext` / `AuthContext` 실제 타입을 열어 봇 AuthContext 구성에 필요한 필드(userId, authorityTier 등)와 빌더/생성자, `setContext`/`clear` 시그니처를 확인. 기존 HTTP 필터가 AuthContext 만드는 코드를 참고. (테스트는 `ThreadLocalContext.setContext(mock)`를 이미 사용 — 프로덕션 구성 경로 확인이 목적.)

- [ ] **Step 1: `BotIdentityExecutorTest` 작성 (failing)** — 핵심 불변: 실행 전후 ThreadLocal 정리, 예외 시에도 정리.

```java
@Test
void 봇_신원으로_실행하고_종료후_정리한다() {
    UserId bot = new UserId(1000_000_001L);
    String[] inside = new String[1];
    executor.runAs(bot, () -> { inside[0] = ThreadLocalContext.getAuthContext().getUserId().toString(); });
    assertThat(inside[0]).contains("1000000001");
    assertThatThrownBy(() -> ThreadLocalContext.getAuthContext()).isInstanceOf(RuntimeException.class); // 정리됨
}

@Test
void 예외가_나도_ThreadLocal_정리된다() {
    assertThatThrownBy(() -> executor.runAs(new UserId(1L), () -> { throw new IllegalStateException("x"); }))
        .isInstanceOf(IllegalStateException.class);
    // 정리 확인
}
```

- [ ] **Step 2: `BotIdentityExecutor` 구현** — Step 0에서 확인한 AuthContext 빌더로 구성.

```java
@Component
@RequiredArgsConstructor
public class BotIdentityExecutor {
    // 봇 tier 는 실유저 일반 회원과 동일하게 구성(예: FM). Step 0 결과대로.
    public void runAs(UserId botUserId, Runnable action) {
        AuthContext prev = ThreadLocalContext.hasContext() ? ThreadLocalContext.getAuthContext() : null; // 헬퍼 유무는 실제 API 따름
        try {
            ThreadLocalContext.setContext(buildBotAuthContext(botUserId));
            action.run();
        } finally {
            if (prev != null) ThreadLocalContext.setContext(prev); else ThreadLocalContext.clear();
        }
    }
    private AuthContext buildBotAuthContext(UserId botUserId) { /* Step 0 결과 */ }
}
```

- [ ] **Step 3: 테스트 실행 → PASS**
Run: `JAVA_HOME=... ./gradlew test --tests "*BotIdentityExecutorTest"`

- [ ] **Step 4: `VirtualUserPoolServiceIT` 작성 (failing)** — 봇 N명 생성: 실 `user_account`(`is_dummy=true`, provider LOCAL) + 현실적 닉네임 + 기본 아바타 프로필 + **유저당 playlist 1개** 생성. idle 봇 조회.

```java
@Test
void 봇_N명_생성_실계정과_playlist_보유() {
    List<UserId> bots = poolService.provision(3);
    assertThat(bots).hasSize(3);
    for (UserId b : bots) {
        UserAccountData acc = userAccountRepository.findById(b).orElseThrow();
        assertThat(acc.isDummy()).isTrue();
        assertThat(playlistQueryPort.findByOwner(b.getUid())).isPresent(); // 봇 playlist 존재
    }
}

@Test
void idle_봇_조회_방에_없는_봇만() {
    // 봇 3명 중 1명을 임의 방 crew 로 만든 뒤 findIdleBots() 가 2명 반환
}
```

- [ ] **Step 5: `VirtualUserPoolService` 구현** — 기존 회원/프로필 생성 경로 재사용(닉네임 충돌 회피: [[pfplay-web i18n]] 무관, V15 unique 제약 주의 — UUID/시퀀스 기반 현실적 닉네임). 아바타는 기본값. playlist 생성은 기존 회원가입 시 playlist 생성 경로 재사용. `findIdleBots()` = `is_dummy=true` AND 활성 crew 없음(QueryDSL: user_account LEFT JOIN crew is_active).

- [ ] **Step 6: 테스트 실행 → PASS / Step 7: 커밋** — `feat(virtual-dj): 봇 임퍼소네이션 + 풀 프로비저닝`

---

## Chunk 3: 송 팩 · 봇 playlist 로 복사

**책임:** 어드민이 빌드한 송 팩의 트랙을 봇 playlist로 1회 복사(§5.1). duration 사전검증.

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/VirtualSongPackService.java` (CRUD + duration 검증)
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/SongPackApplier.java` (팩→봇 playlist 복사)
- Test: `VirtualSongPackServiceTest.java`(단위), `SongPackApplierIT.java`(통합)

- [ ] **Step 0 (verify, 1회 기록):** `playbackTimeLimit`의 출처를 확정한다 — **전역 단일값인지 방별(`PartyroomData.getPlaybackTimeLimit()`)인지.** 방별이면 "빌드 시점엔 보수적 상한으로 검증, 적용(`SongPackApplier`) 시 그 방 한계로 재필터"로 확정하고, 그 보수적 상한 출처(예: 시스템 최대 또는 config 키)를 정한다. 결과를 이 Chunk 상단에 한 줄 기록 — Step 1(빌드 검증)과 Chunk 4 `addBots`(적용 재필터)가 같은 답을 재사용.

- [ ] **Step 1: `VirtualSongPackServiceTest` (failing)** — 팩 생성 시 `playbackTimeLimit` 초과 트랙은 거부.

```java
@Test
void duration_초과_트랙은_거부() {
    // playbackTimeLimit = 10분 가정. 12:00 트랙 추가 시도 → 예외 또는 reject 목록 반환
    AddPackTrackCommand tooLong = new AddPackTrackCommand("vid", "롱트랙", "12:00", "thumb");
    assertThatThrownBy(() -> service.addTrack(packId, tooLong))
        .isInstanceOf(VirtualDjException.class); // TRACK_EXCEEDS_PLAYBACK_LIMIT
}
```
(전역 `playbackTimeLimit` 출처는 Step 0에서 확인 — 방별 값이면 "팩은 가장 빡빡한 한계 기준" 또는 적용 시점 검증으로 결정. 일단 전역/보수적 상한으로 빌드시 검증, 적용 시 방 한계로 재필터.)

- [ ] **Step 2: `VirtualSongPackService` 구현** — create/rename/addTrack/removeTrack/delete. 삭제는 **참조 중(config.song_pack_id 또는 배치된 봇 playlist.source_song_pack_id) 차단**(§5.1). duration 파싱·검증 유틸은 기존 `DurationConverter`/`Duration` value 재사용.

- [ ] **Step 3: 테스트 → PASS**

- [ ] **Step 4: `SongPackApplierIT` (failing)** — 봇 playlist에 팩 트랙 복사 + `source_song_pack_id` 바인딩 + 방 `playbackTimeLimit` 초과 트랙 제외.

```java
@Test
void 팩을_봇_playlist로_복사하고_출처기록() {
    Long packId = /* 트랙 3개(전부 한계내) */;
    applier.applyToBot(botUserId, packId, partyroomPlaybackTimeLimit);
    Long botPlaylistId = playlistQueryPort.findByOwner(botUserId.getUid()).get();
    assertThat(trackRepository.countByPlaylistId(botPlaylistId)).isEqualTo(3);
    assertThat(playlistRepository.findById(botPlaylistId).get().getSourceSongPackId()).isEqualTo(packId);
}
```

- [ ] **Step 5: `SongPackApplier` 구현** — 봇 playlist 비우고(기존 track 삭제) 팩 트랙을 `TrackData` 로 복사(방 `playbackTimeLimit` 초과분 제외), `playlist.bindSongPackSource(packId)`. **주의:** 여기서는 `TrackData`/`PlaylistData` 영속을 직접 다루되, 이는 봇 자신의 playlist 준비(도메인 캐스케이드 무관한 데이터 셋업)이므로 ArchUnit 규칙 대상이 아니다(규칙은 *오케스트레이터* 패키지에만 적용 — Chunk 6). Applier는 `application.service`에 두고 오케스트레이터가 호출.

- [ ] **Step 6: 테스트 → PASS / Step 7: 커밋** — `feat(virtual-dj): 송팩 CRUD + 봇 playlist 복사`

---

## Chunk 4: 오케스트레이터 코어 — reconcileRoom

**책임:** 방 하나의 desired 봇 수를 산식대로 맞춘다(§4.1). 룸 직렬락. 봇 투입/제거는 **임퍼소네이션 + public 메서드**로만.

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/port/VirtualDjOrchestrator.java` (interface)
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/VirtualDjOrchestratorImpl.java`
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/domain/service/DesiredBotCalculator.java` (순수 산식)
- Test: `DesiredBotCalculatorTest.java`(단위, 순수), `VirtualDjOrchestratorIT.java`(통합)

- [ ] **Step 0 (verify):** `removeBots`의 "가장 최근 합류 봇부터 제거"(§4.1)를 위해 **`dj` 또는 `crew` row에 합류시각 컬럼이 있는지** 확인(`created_at` 등). 있으면 그걸로 정렬. 없으면 정렬 기준을 명시적으로 정한다(예: `dj.order_number` 역순 = 마지막 등록 = 가장 최근). 결과를 `readActiveDjs`의 `botsMostRecentFirst()` 정렬 기준으로 고정 — anti-flap 제거 순서가 결정적이어야 함.

- [ ] **Step 1: `DesiredBotCalculatorTest` (failing)** — §4.1 산식.

```java
@ParameterizedTest
@CsvSource({ "0,2", "1,1", "2,0", "3,0" })   // T=2, floor=1
void desiredBot(int human, int expected) {
    assertThat(DesiredBotCalculator.desiredBot(human, 2, 1)).isEqualTo(expected);
}
@Test void floor_가_T보다_클때_경계() { assertThat(DesiredBotCalculator.desiredBot(1, 2, 3)).isEqualTo(2); } // max(floor,T-1) 캡은 T
```

- [ ] **Step 2: `DesiredBotCalculator` 구현 (순수 함수)**

```java
public final class DesiredBotCalculator {
    public static int desiredBot(int human, int target, int floor) {
        if (human == 0) return target;
        if (human == 1) return Math.min(target, Math.max(floor, target - 1));
        return Math.max(0, target - human);
    }
}
```
Run → PASS.

- [ ] **Step 3: `VirtualDjOrchestratorIT` (failing)** — 실제 방·봇으로 reconcile 검증(통합).

```java
@Test
void 사람0명_MANAGED방_봇이_T까지_DJ로_채워진다() {
    // 방 1 MANAGED(T=2,floor=1, 송팩 지정), idle 봇 ≥2
    orchestrator.reconcileRoom(new PartyroomId(roomId));
    assertThat(activeBotDjCount(roomId)).isEqualTo(2);
    // 봇 crew 생성 + dj row 생성 확인
}

@Test
void 사람1명_DJ면_봇은_floor(1)만_남는다() {
    // 사람 DJ 1명 등록된 상태에서 reconcile
    orchestrator.reconcileRoom(new PartyroomId(roomId));
    assertThat(activeBotDjCount(roomId)).isEqualTo(1);
}

@Test
void FROZEN_방은_건드리지_않는다() { /* status=FROZEN → reconcile no-op */ }
```

- [ ] **Step 4: `VirtualDjOrchestratorImpl.reconcileRoom` 구현**

```java
@Service
@RequiredArgsConstructor
public class VirtualDjOrchestratorImpl implements VirtualDjOrchestrator {
    private final PartyroomVirtualDjConfigRepository configRepository;
    private final PartyroomQueryService partyroomQueryService;   // 현재 활성 DJ 조회(읽기)
    private final VirtualUserPoolService poolService;
    private final SongPackApplier songPackApplier;
    private final BotIdentityExecutor botIdentity;
    private final PartyroomAccessCommandService accessCommandService; // tryEnter / exit (cascade)
    private final DjCommandService djCommandService;                  // enqueueDj / dequeueDj (cascade)
    private final DistributedLockExecutor lock;

    @Override
    public void reconcileRoom(PartyroomId partyroomId) {
        lock.performTaskWithLock(":virtualdj:" + partyroomId.getId(), () -> { doReconcile(partyroomId); return null; });
    }

    private void doReconcile(PartyroomId partyroomId) {
        PartyroomVirtualDjConfigData cfg = configRepository.findByPartyroomId(partyroomId.getId()).orElse(null);
        if (cfg == null || cfg.getStatus() != VirtualDjStatus.MANAGED) return;     // OFF/FROZEN/없음 → no-op

        DjSnapshot snap = readActiveDjs(partyroomId);   // 현재 *커밋된* 활성 DJ 집합 (사람/봇 분리)
        int desired = DesiredBotCalculator.desiredBot(snap.humanCount(), cfg.getTargetCount(), cfg.getCompanionFloor());
        int current = snap.botCount();

        if (current < desired) addBots(partyroomId, cfg, desired - current);
        else if (current > desired) removeBots(partyroomId, snap, current - desired); // anti-flap 게이팅은 Chunk5에서 래핑
    }

    private void addBots(PartyroomId pid, PartyroomVirtualDjConfigData cfg, int n) {
        List<UserId> bots = poolService.findIdleBots(n);
        int playbackTimeLimit = partyroomQueryService.getPartyroomById(pid).getPlaybackTimeLimit().getMinutes();
        for (UserId bot : bots) {
            songPackApplier.applyToBot(bot, cfg.getSongPackId(), playbackTimeLimit); // playlist 준비
            botIdentity.runAs(bot, () -> {
                accessCommandService.tryEnter(pid, null);               // crew (cascade + 1-room guard)
                Long botPlaylistId = poolService.playlistIdOf(bot);
                djCommandService.enqueueDj(pid, new PlaylistId(botPlaylistId)); // DJ (cascade)
            });
        }
    }

    private void removeBots(PartyroomId pid, DjSnapshot snap, int n) {
        // 가장 최근 합류 봇부터 n명 (dwell 게이팅은 Chunk5에서 적용)
        for (UserId bot : snap.botsMostRecentFirst().subList(0, n)) {
            botIdentity.runAs(bot, () -> accessCommandService.exit(pid));  // dequeue+exit cascade
        }
    }
}
```
- `readActiveDjs`/`DjSnapshot`: `partyroomQueryService.getDjs(pid)` + 각 dj 의 crew→userId→`is_dummy` 판정으로 human/bot 분리, 합류시각 정렬. (읽기 쿼리는 허용 — ArchUnit은 *쓰기* 캐스케이드 우회만 금지.)

- [ ] **Step 5: 통합 테스트 → PASS** (`./gradlew integrationTest --tests "*VirtualDjOrchestratorIT"`)

- [ ] **Step 6: 커밋** — `feat(virtual-dj): reconcileRoom 산식 + 임퍼소네이션 투입/제거`

---

## Chunk 5: 반응형 컨트롤러 — 이벤트 · anti-flap · 안전망

**책임:** 도메인 이벤트를 reconcile로 잇고(§4.1), thrash 방지(디바운스/최소체류), 저빈도 안전망 + terminate 정리.

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/adapter/in/event/VirtualDjEventListener.java`
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/FlapGuard.java` (디바운스/최소체류 상태)
- Modify: `VirtualDjOrchestratorImpl.removeBots` (FlapGuard 게이팅)
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/VirtualDjReconcileScheduler.java`
- Modify: `app/.../SystemConfigCache.java` + `ConfigKey.java` (`virtualdj.bot_yield_debounce_ms`, `virtualdj.bot_min_dwell_ms`)
- Test: `VirtualDjEventListenerIT.java`, `FlapGuardTest.java`

- [ ] **Step 1: config 키 추가** — `ConfigKey` 상수 + `SystemConfigCache.getBotYieldDebounceMs()/getBotMinDwellMs()`(fail-open 기본 5000/10000), 기존 `readInt(key, fallback)` 패턴. **추가로 D1 "구별 가능 모드" 토글 키도 여기서 pre-seed**: `ConfigKey VIRTUALDJ_DISTINGUISHABLE = new ConfigKey("virtualdj.distinguishable")` + `SystemConfigCache.isVirtualDjDistinguishable()`(fail-open 기본 false). **백엔드는 이 플래그 + `is_dummy` 노출만 책임**(예: DJ/crew DTO에 플래그 켜졌을 때만 `isBot` 필드 노출). 실제 뱃지/AI 라벨 *렌더링*은 Plan B(프론트). 이로써 여론 악화 시 코드 배포 없이 토글 가능(D1 의도). — 토글이 조용히 누락되지 않도록 여기 명시.

- [ ] **Step 2: `FlapGuardTest` (failing)** — 디바운스 창 내 반복 입퇴장이 제거를 트리거하지 않음 / 최소체류 전 제거 차단. (시간은 주입된 `Clock` 으로 제어 — 기존 코드가 `Clock` DI 사용.)

- [ ] **Step 3: `FlapGuard` 구현** — 룸별 "봇 제거 의도 시작시각" 기록. `shouldRemove(roomId, now)` = 의도가 디바운스 창을 넘겨 지속됐을 때만 true. `canRemoveBot(botUserId, now)` = 투입후 dwell 경과. 산식의 **제거만 게이팅**(투입은 즉시).

- [ ] **Step 4: `removeBots` 게이팅 적용** — FlapGuard 통과한 봇만 exit. (Chunk4 메서드 수정 + 단위/통합 회귀.)

- [ ] **Step 5: `VirtualDjEventListenerIT` (failing)** — 사람 DJ enqueue 이벤트 후 (디바운스 경과 시) 봇이 floor까지 줄고, 사람 dequeue/exit 후 봇이 T까지 복귀.

```java
@Test
void 사람DJ_등록되면_디바운스후_봇이_floor까지_양보() { ... }
@Test
void 사람DJ_나가면_봇이_T까지_복귀() { ... }
@Test
void 사람이_enqueue했으나_silent_deactivate면_봇이_안빠진다() { // §4.1 내성
    // 첫 트랙이 playbackTimeLimit 초과 → 사람이 활성 DJ 로 안 잡힘 → desired_bot 그대로 T
}
@Test
void 방_terminate되면_config_OFF로_정리() { ... }
```

- [ ] **Step 6: `VirtualDjEventListener` 구현** — 기존 `DomainEventRedisRelay` 패턴(`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` + `@Transactional(REQUIRES_NEW)`):

```java
@Component
@RequiredArgsConstructor
public class VirtualDjEventListener {
    private final VirtualDjOrchestrator orchestrator;
    private final PartyroomVirtualDjConfigRepository configRepository;

    @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = REQUIRES_NEW)
    public void onCrewAccessed(CrewAccessedEvent e) { orchestrator.reconcileRoom(e.getPartyroomId()); }

    @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = REQUIRES_NEW)
    public void onDjQueueChanged(DjQueueChangedEvent e) { orchestrator.reconcileRoom(e.getPartyroomId()); }

    @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = REQUIRES_NEW)
    public void onTerminated(PartyroomTerminatedEvent e) { /* config turnOff + save */ }
}
```
**무한루프 주의:** 봇 enqueue/exit 자체가 `DjQueueChangedEvent`를 발행 → 다시 onDjQueueChanged 호출. 룸락이 직렬화하나, reconcile이 "이미 desired 상태면 no-op"이므로 수렴(추가 변경 없음→이벤트 없음). 이 수렴을 **테스트로 잠근다**: 봇으로 채운 뒤 재이벤트(재reconcile) 시 **`accessCommandService`/`djCommandService`에 추가 호출이 0건**임을 spy `verify(..., never())`로 단언(단순히 최종 봇 수 동일이 아니라, 불필요한 dequeue+enqueue churn이 없음을 확인 — churn은 이벤트를 재발생시켜 무한루프 위험).

- [ ] **Step 7: `VirtualDjReconcileScheduler` 구현** — `@Scheduled(fixedDelay=60_000)` 으로 `configRepository.findByStatus(MANAGED)` 전체에 `reconcileRoom` 호출(안전망, §4.1/§7). 패턴: `PartyroomPresenceService.reconcileStalePending`.

- [ ] **Step 8: 통합/단위 테스트 → PASS / Step 9: 커밋** — `feat(virtual-dj): 이벤트 반응 + anti-flap + 안전망 reconcile`

---

## Chunk 6: 어드민 엔드포인트 · ArchUnit 가드 · 리더보드 제외

**책임:** 어드민 제어 API(풀/팩/방 config/drain/freeze) + 골격 보호 ArchUnit + 봇 점수 제외.

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/adapter/in/web/AdminVirtualDjController.java` (+ request/response payloads)
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/VirtualDjAdminService.java` (config apply/freeze/off/drain, 일괄 적용)
- Modify: 리더보드/DJ_PNT 집계 쿼리 (is_dummy 제외)
- Create: `app/src/test/java/com/pfplaybackend/api/virtualdj/VirtualDjArchitectureTest.java` (ArchUnit)
- Test: `AdminVirtualDjControllerIT.java`, 리더보드 제외 테스트

- [ ] **Step 1: `VirtualDjArchitectureTest` (failing→PASS keystone)** — §8 키스톤.

```java
@AnalyzeClasses(packages = "com.pfplaybackend.api.virtualdj")
class VirtualDjArchitectureTest {
    @ArchTest static final ArchRule 오케스트레이터는_persistence_publisher_직접의존_금지 =
        noClasses().that().resideInAPackage("..virtualdj.application.service..")
                   .and().haveSimpleNameContaining("Orchestrator")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..adapter.out.persistence..", "..messaging..", "..redis..")
        .orShould().dependOnClassesThat().haveSimpleNameEndingWith("AggregatePort")
        .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Repository");
    // 의도: 오케스트레이터는 command service + 임퍼소네이션 + query(읽기)만. 쓰기 캐스케이드 우회 차단.
}
```
(패키지/이름 매처는 실제 클래스명에 맞춰 1차 실행 후 조정. **반드시 한 번 빨갛게 만든 뒤** 통과시킬 것 — 규칙이 실제로 무언가를 막는지 확인.)

- [ ] **Step 2: 리더보드 is_dummy 제외 테스트 (failing)** — 봇이 트랙 완료로 DJ_PNT 가 쌓여도 리더보드 쿼리 결과에서 제외.

```java
@Test
void 봇은_리더보드에_안나온다() {
    // 봇 + 실유저 둘 다 DJ_PNT 보유 상태에서 리더보드 조회 → 봇 제외
}
```

- [ ] **Step 3: 리더보드 쿼리 수정** — Step 0에서 찾은 집계 쿼리에 `JOIN user_account ua ... WHERE ua.is_dummy = false`. (Amplitude opt-out 재사용 지점은 §9-7 — P2에서 봇은 SDK 미발화이므로 클라 노출 자체가 없음. 서버 집계 제외만 P2 필수. 확인 후 불필요하면 스킵.)

- [ ] **Step 4: `AdminVirtualDjControllerIT` (failing)** — 어드민 권한으로 config 적용/일괄적용/freeze/drain. 패턴: `AdminCrewPenaltyCommandController`(`@PreAuthorize("@adminAuth.isAdmin()")`, `@SecurityRequirement(name="cookieAuth")`).

```java
@Test void config_적용시_MANAGED로_전이되고_reconcile_트리거() { ... }
@Test void drain_은_방의_모든_봇을_퇴장시킨다() { ... }
@Test void 체크박스_일괄적용_여러방() { ... }
@Test void 비어드민은_403() { ... }
```

- [ ] **Step 5: `AdminVirtualDjController` + `VirtualDjAdminService` 구현** — 엔드포인트(예): 
  - `POST /api/v1/admin/virtual-dj/pool` (N명 프로비저닝)
  - `POST/PUT/DELETE /api/v1/admin/virtual-dj/song-packs[/{id}]` + 트랙 추가/삭제(검색은 어드민 프론트가 기존 music-search 사용 → 이 API는 선택된 트랙 수신)
  - `PUT /api/v1/admin/partyrooms/{id}/virtual-dj` (apply: target/floor/songPackId/status)
  - `POST /api/v1/admin/partyrooms/{id}/virtual-dj/drain`, `.../freeze`
  - `PUT /api/v1/admin/virtual-dj/bulk` (체크박스 다중 방 일괄 config)
  - `GET /api/v1/admin/partyrooms/{id}/virtual-dj` (라이브 상태: 봇 x/T, status). 
  config apply/drain 후 `orchestrator.reconcileRoom` 직접 호출(이벤트 외 명시적 트리거).

- [ ] **Step 6: 전체 테스트 → PASS**
Run: `JAVA_HOME=... ./gradlew test integrationTest`
Expected: 신규 + 기존 회귀 GREEN.

- [ ] **Step 7: 커밋** — `feat(virtual-dj): 어드민 API + ArchUnit 가드 + 리더보드 봇 제외`

---

## 마무리 체크 (구현 후, push 전)

- [ ] 전체 `./gradlew test integrationTest` GREEN (JDK21).
- [ ] §9-1 커서 wrap 확인 결과 반영(추가 보정 있었으면).
- [ ] R4 부하: 로컬 docker compose 풀스택에서 MANAGED 방 N=20/50 — 트랙 `complete`가 duration+ε 내 발화(스케줄러 누락 없음) 확인. 초과 시 별도 follow-up 이슈.
- [ ] presence 비간섭 회귀: 봇 crew(세션X)가 `reconcileStalePending` sweep 비대상.
- [ ] 마이크로 커밋 → 논리단위 squash.
- [ ] **Plan B(어드민 프론트 pfplay-admin)** 별도 작성: 풀/팩 빌더(music-search 재사용)/방 config 체크박스 일괄/라이브 상태 컬럼/drain·freeze.

## 미해결·후속
- Amplitude opt-out 재사용(§9-7): 봇이 클라 SDK를 발화할 일이 없으므로 P2 영향 적음 — Step 3에서 확인 후 결정.
- P1(아바타 일괄 커스터마이징), P3(AI: 채팅응답·플레이리스트 자가갱신·컨셉), 크로스룸 자동 점유 reconcile(#264 통합)은 별도 단계.
