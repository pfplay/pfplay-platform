# Quick-DJ One-shot Queue 구현 플랜

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검색 모달에서 곡 하나를 선택하는 즉시 그 곡으로 DJ 대기열에 등록되고, 1회 재생 후 자동 이탈하는 병렬 빠른 경로(Quick-DJ)를 pfplay-platform 백엔드에 추가한다.

**Architecture:** 곡 저장은 신규 `PlaylistType.TEMP`(per-user 1개 재사용·조회 숨김), 큐 엔트리는 신규 `DjData.kind`(NORMAL/ONE_SHOT). ONE_SHOT 이탈은 위치(order-1)가 아닌 **outgoing DJ 정체**(`PartyroomPlaybackData.getCurrentDjCrewId()`) 기준으로 `tryProceed`에서 분기하고 `doStart`는 rotate 플래그를 받는다. NORMAL 라운드로빈·dequeue quirk·활성화 경로는 무변경.

**Tech Stack:** Spring Boot 3 / JPA(Hibernate) / QueryDSL / Flyway(V35) / JUnit5+Mockito / Testcontainers MySQL+Redis IT 하네스(`AbstractIntegrationTest`, `@Tag("integration")`)

**승인 스펙:** `docs/superpowers/specs/2026-07-01-quick-dj-one-shot-queue-design.md` (2패스 리뷰 승인 · 오픈결정 전부 확정 · 이슈 [#331](https://github.com/pfplay/pfplay-platform/issues/331))

**빌드 명령 공통:** 모든 gradlew 호출은 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix 필수 (Git Bash 기준 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew ...`).

**모듈 지형(사전 지식):**
- `app` 모듈 = party 도메인(DJ큐·재생) + 컨트롤러. `playlist` 모듈 = 플레이리스트/트랙.
- party→playlist 접근은 포트 경유: `app/.../party/application/port/out/PlaylistCommandPort.java` ← 구현 `app/.../party/adapter/out/external/PlaylistCommandAdapter.java`(playlist 모듈 서비스 직접 주입).
- IT는 `app/src/test/...`에 위치, `AbstractIntegrationTest` 상속 시 `@Tag("integration")` 자동 부여 → `./gradlew integrationTest`로 실행(단일 fork, DatabaseCleaner truncate).
- 마이그레이션: `app/src/main/resources/db/migration/` — 현재 최신 **V34** → 이 작업은 **V35** 슬롯 사용. IT 하네스는 Flyway 적용+`ddl-auto: validate`이므로 엔티티와 DDL이 어긋나면 IT 부팅에서 잡힌다.

---

## Chunk 1: 데이터 모델 (DjKind · TEMP 플리 · 준비 서비스)

### Task 1: `DjKind` enum + `DjData.kind` + Flyway V35

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/enums/DjKind.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/DjData.java`
- Create: `app/src/main/resources/db/migration/V35__add_dj_kind_for_quick_dj.sql`
- Test: `app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/DjDataTest.java` (신규)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.party.domain.enums.DjKind;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DjDataTest {

    @Test
    @DisplayName("create(4-arg) — kind 미지정 생성은 NORMAL")
    void createDefaultsToNormal() {
        DjData dj = DjData.create(new PartyroomId(1L), new PlaylistId(2L), new CrewId(3L), 1);
        assertThat(dj.getKind()).isEqualTo(DjKind.NORMAL);
    }

    @Test
    @DisplayName("create(5-arg) — ONE_SHOT 지정 생성")
    void createWithOneShotKind() {
        DjData dj = DjData.create(new PartyroomId(1L), new PlaylistId(2L), new CrewId(3L), 1, DjKind.ONE_SHOT);
        assertThat(dj.getKind()).isEqualTo(DjKind.ONE_SHOT);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.entity.data.DjDataTest"`
Expected: 컴파일 실패 (`DjKind` 미존재)

- [ ] **Step 3: 구현**

`DjKind.java` 신규:
```java
package com.pfplaybackend.api.party.domain.enums;

/**
 * DJ 큐 엔트리 종류.
 * NORMAL — 플레이리스트 기반 상시 회전 엔트리(기존 동작).
 * ONE_SHOT — Quick-DJ 로 등록된 1회 재생 엔트리. 재생 완료/스킵 시 큐에서 자동 이탈한다(spec §3-3).
 */
public enum DjKind {
    NORMAL,
    ONE_SHOT
}
```

`DjData.java` 수정 — 필드 추가(orderNumber 아래), 빌더 생성자에 파라미터 추가, create 오버로드:
```java
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private DjKind kind;
```
```java
    @Builder
    public DjData(Long id, PartyroomId partyroomId, CrewId crewId, PlaylistId playlistId, int orderNumber,
                  DjKind kind, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.partyroomId = partyroomId;
        this.crewId = crewId;
        this.playlistId = playlistId;
        this.orderNumber = orderNumber;
        this.kind = kind;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
```
```java
    public static DjData create(PartyroomId partyroomId, PlaylistId playlistId, CrewId crewId, int orderNumber) {
        return create(partyroomId, playlistId, crewId, orderNumber, DjKind.NORMAL);
    }

    public static DjData create(PartyroomId partyroomId, PlaylistId playlistId, CrewId crewId, int orderNumber, DjKind kind) {
        return DjData.builder()
                .partyroomId(partyroomId)
                .playlistId(playlistId)
                .crewId(crewId)
                .orderNumber(orderNumber)
                .kind(kind)
                .build();
    }
```
import 추가: `com.pfplaybackend.api.party.domain.enums.DjKind`.

`V35__add_dj_kind_for_quick_dj.sql` 신규:
```sql
-- V35: Quick-DJ(#331) — dj.kind 추가 (NORMAL/ONE_SHOT), 기존 행 NORMAL 백필
ALTER TABLE dj
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT 'DJ 큐 엔트리 종류 (NORMAL/ONE_SHOT)' AFTER playlist_id;
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.entity.data.DjDataTest"`
Expected: PASS

- [ ] **Step 5: Flyway 슬롯 중복 사전스캔** (배치 머지 함정 회피)

Run: `ls app/src/main/resources/db/migration/ | sed -E 's/^(V[0-9]+)__.*/\1/' | sort | uniq -d`
Expected: 출력 없음 (V35 유일)

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/enums/DjKind.java \
        app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/DjData.java \
        app/src/main/resources/db/migration/V35__add_dj_kind_for_quick_dj.sql \
        app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/DjDataTest.java
git commit -m "feat(party): DjData.kind(NORMAL/ONE_SHOT) 도입 + Flyway V35 (#331)"
```

### Task 2: `PlaylistType.TEMP` + 목록/단건 조회 숨김 필터

**Files:**
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/domain/enums/PlaylistType.java`
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/out/persistence/impl/PlaylistRepositoryImpl.java` (`findAllByUserId`, `findByIdAndUserId` 양쪽)
- Test: `app/src/test/java/com/pfplaybackend/api/playlist/application/service/TempPlaylistVisibilityIntegrationTest.java` (신규 IT — QueryDSL이라 DB 필요)

- [ ] **Step 1: 실패하는 IT 작성**

`PlaylistRepository`(Spring Data + custom)를 autowire해 세 타입(PLAYLIST/GRABLIST/TEMP)의 플리를 시드하고 필터를 단언한다. 시드는 `entityManager.persist(PlaylistData.create(...))` + `flushAndClear()` 사용(`AbstractIntegrationTest` 제공). **주의: TEMP enum 값이 없어 처음엔 컴파일 실패 — 그것이 red.**

```java
package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.adapter.out.persistence.PlaylistRepository;
import com.pfplaybackend.api.playlist.application.dto.PlaylistSummaryDto;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quick-DJ(#331) — TEMP 플리는 사용자 목록/단건 조회에서 숨겨진다(spec §3-1a).
 * GRABLIST/PLAYLIST 노출은 현행 유지(회귀 잠금).
 */
@Transactional
class TempPlaylistVisibilityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PlaylistRepository playlistRepository;

    @Test
    @DisplayName("findAllByUserId — TEMP 제외, GRABLIST/PLAYLIST 는 노출")
    void findAllExcludesTemp() {
        UserId owner = new UserId(9101L);
        entityManager.persist(PlaylistData.create(0, "그랩한 곡", PlaylistType.GRABLIST, owner));
        entityManager.persist(PlaylistData.create(1, "내 플레이리스트", PlaylistType.PLAYLIST, owner));
        entityManager.persist(PlaylistData.create(0, "Quick-DJ", PlaylistType.TEMP, owner));
        flushAndClear();

        List<PlaylistSummaryDto> result = playlistRepository.findAllByUserId(owner);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PlaylistSummaryDto::type)
                .containsExactlyInAnyOrder(PlaylistType.GRABLIST, PlaylistType.PLAYLIST);
    }

    @Test
    @DisplayName("findByIdAndUserId — TEMP id 단건 조회도 새지 않는다(null)")
    void findByIdExcludesTemp() {
        UserId owner = new UserId(9102L);
        PlaylistData temp = PlaylistData.create(0, "Quick-DJ", PlaylistType.TEMP, owner);
        entityManager.persist(temp);
        PlaylistData normal = PlaylistData.create(1, "내 플레이리스트", PlaylistType.PLAYLIST, owner);
        entityManager.persist(normal);
        flushAndClear();

        assertThat(playlistRepository.findByIdAndUserId(temp.getId(), owner)).isNull();
        assertThat(playlistRepository.findByIdAndUserId(normal.getId(), owner)).isNotNull();
    }
}
```

주의: `PlaylistSummaryDto`가 record가 아니면 `extracting("type")` 형태로 조정. `PlaylistRepository` 인터페이스 정확 경로는 `playlist/.../adapter/out/persistence/PlaylistRepository.java`(custom `PlaylistRepositoryCustom` 상속) — 컴파일 에러 시 실제 이름 확인.

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*TempPlaylistVisibilityIntegrationTest"`
Expected: 컴파일 실패(TEMP 미존재)

- [ ] **Step 3: 구현**

`PlaylistType.java`:
```java
public enum PlaylistType {
    GRABLIST,
    PLAYLIST,
    /** Quick-DJ(#331) one-shot 곡 저장용 per-user 숨김 플리 — 목록/단건 조회에서 제외된다. @Enumerated(STRING)이라 append 안전. */
    TEMP
}
```

`PlaylistRepositoryImpl.java` — 두 메서드의 `.where(...)`에 TEMP 제외 조건 추가:
```java
// findAllByUserId
.where(qPlaylistData.ownerId.eq(ownerId)
        .and(qPlaylistData.type.ne(PlaylistType.TEMP)))
// findByIdAndUserId
.where(qPlaylistData.id.eq(playlistId)
        .and(qPlaylistData.ownerId.eq(ownerId))
        .and(qPlaylistData.type.ne(PlaylistType.TEMP)))
```
import 추가: `com.pfplaybackend.api.playlist.domain.enums.PlaylistType`.

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*TempPlaylistVisibilityIntegrationTest"`
Expected: PASS (2/2)

- [ ] **Step 5: 커밋**

```bash
git add playlist/src/main/java/com/pfplaybackend/api/playlist/domain/enums/PlaylistType.java \
        playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/out/persistence/impl/PlaylistRepositoryImpl.java \
        app/src/test/java/com/pfplaybackend/api/playlist/application/service/TempPlaylistVisibilityIntegrationTest.java
git commit -m "feat(playlist): PlaylistType.TEMP 도입 + 목록/단건 조회 숨김 (#331)"
```

### Task 3: `TempPlaylistService.prepareOneShotPlaylist` (find-or-create + 리셋 + 삽입)

**Files:**
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/domain/port/PlaylistAggregatePort.java` — `void deleteAllTracksByPlaylist(Long playlistId);` 추가
- Modify: PlaylistAggregatePort 구현체 (`playlist/.../adapter/out/persistence/` 아래 — `grep -rn "implements PlaylistAggregatePort" playlist/src/main`으로 위치 확인) — 기존 `TrackRepository.deleteAllByPlaylistIdValue(playlistId)`(이미 존재, `SongPackApplier:86`이 사용) 위임
- Create: `playlist/src/main/java/com/pfplaybackend/api/playlist/application/service/TempPlaylistService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/playlist/application/service/TempPlaylistServiceIntegrationTest.java` (신규 IT)

⚠️ 구현 전 확인: `TrackRepository.deleteAllByPlaylistIdValue`의 `@Modifying` 속성. `clearAutomatically=true`면 **미flush 영속성 컨텍스트 변경이 폐기**되는 함정이 있으므로(과거 사고 패턴), `prepareOneShotPlaylist` 안에서 삭제 → 삽입 순서를 지키고 삭제 전에 보류 중인 엔티티 변경이 없도록 서비스 구조를 유지한다(아래 구현이 그 순서).

- [ ] **Step 1: 실패하는 IT 작성**

```java
package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quick-DJ(#331) — TEMP 플리 준비(find-or-create + 전곡 리셋 + 단건 삽입)(spec §3-2 step3~5).
 * per-user 1개 재사용으로 행 증식이 없음을 잠근다(spec 결정6).
 */
@Transactional
class TempPlaylistServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TempPlaylistService tempPlaylistService;
    @Autowired
    private PlaylistAggregatePort aggregatePort;

    private AddTrackCommand track(String linkId, String name) {
        return new AddTrackCommand(name, linkId, "3:45", "https://i.ytimg.com/vi/" + linkId + "/mqdefault.jpg");
    }

    @Test
    @DisplayName("TEMP 미존재 → 생성 + 곡 1개 삽입")
    void createsTempWhenAbsent() {
        UserId owner = new UserId(9201L);

        Long playlistId = tempPlaylistService.prepareOneShotPlaylist(owner, track("aaa111", "곡A"));
        flushAndClear();

        List<PlaylistData> temps = aggregatePort.findPlaylistsByOwnerAndType(owner, PlaylistType.TEMP);
        assertThat(temps).hasSize(1);
        assertThat(temps.get(0).getId()).isEqualTo(playlistId);
        assertThat(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(playlistId), "aaa111")).isPresent();
    }

    @Test
    @DisplayName("재호출 — 같은 TEMP 재사용(행 증식 없음), 이전 곡은 리셋되고 새 곡만 남는다")
    void reusesAndResetsTemp() {
        UserId owner = new UserId(9202L);

        Long first = tempPlaylistService.prepareOneShotPlaylist(owner, track("aaa111", "곡A"));
        flushAndClear();
        Long second = tempPlaylistService.prepareOneShotPlaylist(owner, track("bbb222", "곡B"));
        flushAndClear();

        assertThat(second).isEqualTo(first);
        assertThat(aggregatePort.findPlaylistsByOwnerAndType(owner, PlaylistType.TEMP)).hasSize(1);
        assertThat(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(first), "aaa111")).isEmpty();
        assertThat(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(first), "bbb222")).isPresent();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*TempPlaylistServiceIntegrationTest"`
Expected: 컴파일 실패(`TempPlaylistService` 미존재)

- [ ] **Step 3: 구현**

`PlaylistAggregatePort.java` — Track 섹션에 추가:
```java
    void deleteAllTracksByPlaylist(Long playlistId);
```

구현체(어댑터)에 위임 메서드 추가:
```java
    @Override
    public void deleteAllTracksByPlaylist(Long playlistId) {
        trackRepository.deleteAllByPlaylistIdValue(playlistId);
    }
```
(구현체가 `trackRepository`를 이미 주입받는지 확인 — `saveTrack` 등 기존 위임과 동일 필드 사용.)

`TempPlaylistService.java` 신규:
```java
package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick-DJ(#331) one-shot 곡 저장용 TEMP 플리 준비.
 * per-user 1개를 재사용하며 호출마다 리셋(전곡 삭제) 후 선택 곡 1개만 삽입한다(spec §3-2 step3~5, 결정6).
 * 재생 커서(lastPlayedTrackId)는 리셋하지 않는다 — 가리키던 트랙이 삭제되면
 * peekTracksFromCursor 가 자연 순서로 fall-back 하므로 무해(spec §4).
 */
@Service
@RequiredArgsConstructor
public class TempPlaylistService {

    private final PlaylistAggregatePort aggregatePort;

    @Transactional
    public Long prepareOneShotPlaylist(UserId userId, AddTrackCommand command) {
        PlaylistData temp = aggregatePort.findPlaylistsByOwnerAndType(userId, PlaylistType.TEMP).stream()
                .findFirst()
                .orElseGet(() -> aggregatePort.savePlaylist(
                        PlaylistData.create(0, "Quick-DJ", PlaylistType.TEMP, userId)));

        aggregatePort.deleteAllTracksByPlaylist(temp.getId());

        TrackData track = TrackData.builder()
                .playlistId(new PlaylistId(temp.getId()))
                .name(command.name())
                .linkId(command.linkId())
                .duration(Duration.fromString(command.duration()))
                .orderNumber(1)
                .thumbnailImage(command.thumbnailImage())
                .build();
        aggregatePort.saveTrack(track);
        return temp.getId();
    }
}
```
(참고: `TrackData` 빌더 필드명은 `TrackCommandService.insertTrack:77-84`와 동일 — 컴파일 에러 시 그쪽을 기준으로 맞춘다. `insertTrack`을 재사용하지 않는 이유: ThreadLocal 결합·HEAD 삽입 시프트·`TrackAddedEvent` 발행이 TEMP 의미와 안 맞음.)

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*TempPlaylistServiceIntegrationTest"`
Expected: PASS (2/2)

- [ ] **Step 5: 커밋**

```bash
git add playlist/src/main/java/com/pfplaybackend/api/playlist/domain/port/PlaylistAggregatePort.java \
        playlist/src/main/java/com/pfplaybackend/api/playlist/application/service/TempPlaylistService.java \
        app/src/test/java/com/pfplaybackend/api/playlist/application/service/TempPlaylistServiceIntegrationTest.java
# + 어댑터 구현체 파일
git commit -m "feat(playlist): TEMP 플리 준비 서비스 — find-or-create·리셋·단건삽입 (#331)"
```

### Task 4: `enqueueDj` kind 오버로드

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/DjCommandService.java:43-93`
- Test: `app/src/test/java/com/pfplaybackend/api/party/application/service/DjCommandServiceTest.java` (기존 파일에 추가)

- [ ] **Step 1: 실패하는 테스트 작성** — 기존 `DjCommandServiceTest`의 enqueue 헬퍼/스텁 패턴을 그대로 따라, ONE_SHOT enqueue 시 저장되는 `DjData.kind`를 captor로 단언:

```java
    @Test
    @DisplayName("enqueueDj(kind) — ONE_SHOT 지정 시 kind 가 저장된다")
    void enqueueDjPersistsOneShotKind() {
        // given — 기존 enqueueDj happy 테스트와 동일 스텁 구성(해당 파일 참조)
        // when
        djCommandService.enqueueDj(partyroomId, playlistId, DjKind.ONE_SHOT);
        // then
        ArgumentCaptor<DjData> captor = ArgumentCaptor.forClass(DjData.class);
        verify(aggregatePort).saveDj(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo(DjKind.ONE_SHOT);
    }

    @Test
    @DisplayName("enqueueDj(2-arg) — 기존 경로는 NORMAL 유지(회귀)")
    void enqueueDjDefaultsToNormalKind() {
        // given — 동일 스텁
        // when
        djCommandService.enqueueDj(partyroomId, playlistId);
        // then
        ArgumentCaptor<DjData> captor = ArgumentCaptor.forClass(DjData.class);
        verify(aggregatePort).saveDj(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo(DjKind.NORMAL);
    }
```
(given 스텁은 기존 테스트 파일의 enqueue happy path 구성을 복제 — partyroom·playbackState·djQueue·crew·isOwned=true·isEmptyPlaylist=false·`saveDj` returns 인자.)

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.DjCommandServiceTest"`
Expected: 컴파일 실패(3-arg 오버로드 미존재)

- [ ] **Step 3: 구현** — 기존 본문을 3-arg로 이동, `DjData.create(..., kind)` 사용, 반환을 `DjData`로. 2-arg는 위임:

```java
    @Transactional
    public Long enqueueDj(PartyroomId partyroomId, PlaylistId playlistId) {
        return enqueueDj(partyroomId, playlistId, DjKind.NORMAL).getId();
    }

    @Transactional
    public DjData enqueueDj(PartyroomId partyroomId, PlaylistId playlistId, DjKind kind) {
        // ... 기존 본문 그대로, 아래 두 줄만 변경 ...
        DjData dj = DjData.create(partyroom.getPartyroomId(), playlistId, crewId, nextOrder, kind);
        // ... 마지막: return saved;  (기존 return saved.getId() 대신)
    }
```
import 추가: `DjKind`.

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.DjCommandServiceTest"`
Expected: PASS (기존 테스트 포함 전체)

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/application/service/DjCommandService.java \
        app/src/test/java/com/pfplaybackend/api/party/application/service/DjCommandServiceTest.java
git commit -m "feat(party): enqueueDj kind 오버로드 — ONE_SHOT 등록 경로 (#331)"
```

---

## Chunk 2: One-shot 이탈 분기 + Quick-DJ 오케스트레이션 + API

### Task 5: `tryProceed` outgoing-ONE_SHOT 제거 분기 + `doStart(rotate)` + `ONE_SHOT_COMPLETED`

**핵심 불변식(spec §3-3, 초안 버그 재발 방지):** 제거 기준은 위치(order-1)가 아닌 **정체**. `doStart` 선두 회전은 최초 활성화·dequeue-후-skip에서도 진입하므로, order-1 기준 제거는 미재생 ONE_SHOT을 삭제한다. outgoing = `PartyroomPlaybackData.getCurrentDjCrewId()`(deactivate에서만 클리어)로 식별하고, 분기는 `tryProceed`(완료·스킵 경로 전용)에만 둔다.

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/enums/DjChangeType.java` — `ONE_SHOT_COMPLETED` 추가
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/PlaybackCommandService.java:90-145`
- Test: `app/src/test/java/com/pfplaybackend/api/party/application/service/PlaybackCommandServiceTest.java` (기존 파일에 추가)

- [ ] **Step 1: 실패하는 테스트 작성** — 기존 `PlaybackCommandServiceTest` 하네스(Mockito, `@InjectMocks`)에 추가. **먼저 기존 setUp에 lenient 기본 스텁 1줄 추가**(신규 `findPlaybackState` 호출 때문에 기존 테스트가 NPE 나지 않도록):

```java
        // setUp() 마지막에 추가 — currentDjCrewId=null 인 기본 상태(rotate 경로 보존)
        lenient().when(aggregatePort.findPlaybackState(any(PartyroomId.class)))
                .thenAnswer(inv -> PartyroomPlaybackData.createFor(inv.getArgument(0)));
```

신규 테스트 5건:

```java
    // ── Quick-DJ(#331) — outgoing ONE_SHOT 이탈 분기 ──

    private PartyroomData partyroomFixture() {
        return PartyroomData.builder().id(partyroomId.getId()).partyroomId(partyroomId)
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(10)).build();
    }

    private PartyroomPlaybackData activatedPlaybackState(CrewId currentDjCrewId) {
        PartyroomPlaybackData state = PartyroomPlaybackData.createFor(partyroomId);
        state.activate(new PlaybackId(77L), currentDjCrewId);
        return state;
    }

    @Test
    @DisplayName("complete — outgoing ONE_SHOT 은 회전 대신 제거되고 ONE_SHOT_COMPLETED 가 발행된다")
    void completeRetiresOutgoingOneShotInsteadOfRotating() {
        CrewId outgoing = new CrewId(31L);
        DjData oneShot = DjData.create(partyroomId, new PlaylistId(2L), outgoing, 1, DjKind.ONE_SHOT);
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroomFixture());
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(activatedPlaybackState(outgoing));
        when(aggregatePort.findDj(partyroomId, outgoing)).thenReturn(Optional.of(oneShot));
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of()); // 제거 후 빈 큐

        playbackCommandService.complete(partyroomId, userId);

        verify(partyroomAggregateService).removeDjFromQueue(partyroomId, outgoing);
        verify(partyroomAggregateService, never()).rotateDjQueue(any());
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
        assertThat(events.getAllValues().stream()
                .filter(e -> e instanceof DjQueueChangedEvent)
                .map(e -> ((DjQueueChangedEvent) e).getChangeType()))
                .contains(DjChangeType.ONE_SHOT_COMPLETED);
    }

    @Test
    @DisplayName("complete — outgoing NORMAL 은 기존 회전 경로 그대로(제거 없음)")
    void completeRotatesForNormalOutgoing() {
        CrewId outgoing = new CrewId(32L);
        DjData normal = DjData.create(partyroomId, new PlaylistId(2L), outgoing, 1);
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroomFixture());
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(activatedPlaybackState(outgoing));
        when(aggregatePort.findDj(partyroomId, outgoing)).thenReturn(Optional.of(normal));
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of(normal));
        when(partyroomAggregateService.rotateDjQueue(partyroomId)).thenReturn(List.of(normal));
        // doStart 진입 후 재생가능 트랙 없음 → deactivate 로 수렴해도 본 검증엔 무관
        when(aggregatePort.findCrewById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playbackCommandService.complete(partyroomId, userId))
                .isInstanceOf(NoSuchElementException.class); // findCrewById orElseThrow — 기존 doStart 거동
        verify(partyroomAggregateService).rotateDjQueue(partyroomId);
        verify(partyroomAggregateService, never()).removeDjFromQueue(any(), any());
    }

    @Test
    @DisplayName("complete — outgoing 이 큐에 없으면(dequeue-후-skip) 제거 분기 미실행, 회전 경로")
    void completeSkipsRetireWhenOutgoingAlreadyDequeued() {
        CrewId outgoing = new CrewId(33L);
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroomFixture());
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(activatedPlaybackState(outgoing));
        when(aggregatePort.findDj(partyroomId, outgoing)).thenReturn(Optional.empty());
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of());

        playbackCommandService.complete(partyroomId, userId);

        verify(partyroomAggregateService, never()).removeDjFromQueue(any(), any());
        verify(partyroomAggregateService).deactivatePlayback(partyroomId); // 빈 큐 → 기존 deactivate
    }

    @Test
    @DisplayName("complete — currentDjCrewId=null(비활성)이면 분기 미실행(기존 거동)")
    void completeNullOutgoingKeepsLegacyPath() {
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroomFixture());
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(List.of());

        playbackCommandService.complete(partyroomId, userId);

        verify(partyroomAggregateService, never()).removeDjFromQueue(any(), any());
        verify(aggregatePort, never()).findDj(any(), any());
    }

    @Test
    @DisplayName("startPlayback(최초 활성화) — tryProceed 미경유라 ONE_SHOT 이어도 제거되지 않는다(미재생 삭제 금지)")
    void startPlaybackNeverRetiresOneShot() {
        CrewId crewId = new CrewId(34L);
        DjData oneShot = DjData.create(partyroomId, new PlaylistId(2L), crewId, 1, DjKind.ONE_SHOT);
        when(partyroomAggregateService.rotateDjQueue(partyroomId)).thenReturn(List.of(oneShot));
        when(aggregatePort.findCrewById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playbackCommandService.startPlayback(partyroomFixture()))
                .isInstanceOf(NoSuchElementException.class);
        verify(partyroomAggregateService, never()).removeDjFromQueue(any(), any());
    }
```
(`DjQueueChangedEvent`의 changeType getter 이름은 실제 클래스 확인 — `@Getter`면 `getChangeType()`.)

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PlaybackCommandServiceTest"`
Expected: 신규 5건 FAIL 또는 컴파일 실패(`ONE_SHOT_COMPLETED` 미존재)

- [ ] **Step 3: 구현**

`DjChangeType.java`:
```java
public enum DjChangeType {
    ENQUEUE,
    DEQUEUE,
    DEQUEUE_ADMIN,
    DEQUEUE_EXIT,
    ROTATE,
    DEACTIVATE,
    /** Quick-DJ(#331) — ONE_SHOT 엔트리가 1회 재생을 마치고 자연 이탈 */
    ONE_SHOT_COMPLETED
}
```

`PlaybackCommandService.java` — `tryProceed`/`doStart` 교체(기존 line 90-145):
```java
    private void tryProceed(PartyroomId partyroomId) {
        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);
        boolean oneShotRetired = retireOutgoingOneShot(partyroomId);
        List<DjData> queuedDjs = aggregatePort.findDjsOrdered(partyroomId);
        log.debug("[tryProceed] partyroomId={}, queueSize={}, oneShotRetired={}",
                partyroomId.getId(), queuedDjs.size(), oneShotRetired);

        if(!queuedDjs.isEmpty()) {
            // ONE_SHOT 제거로 이미 큐가 전진했으면 중복 회전 금지(spec §3-3)
            doStart(partyroom, !oneShotRetired);
        }else{
            log.info("[tryProceed] EMPTY_QUEUE_DEACTIVATE - partyroomId={}", partyroomId.getId());
            deactivateAndNotify(partyroom);
        }
    }

    /**
     * 방금 재생을 끝낸(outgoing) DJ 가 ONE_SHOT 이면 회전 대신 큐에서 제거한다.
     * 제거 기준은 위치(order-1)가 아닌 정체(currentDjCrewId) — doStart 의 선두 회전은
     * 최초 활성화·dequeue-후-skip 경로에서도 진입하므로 위치 기준은 미재생 엔트리를 삭제한다(spec §3-3).
     * currentDjCrewId 는 deactivate 에서만 클리어되므로 완료/스킵 시점엔 방금 끝난 DJ 를 가리킨다.
     */
    private boolean retireOutgoingOneShot(PartyroomId partyroomId) {
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroomId);
        CrewId outgoingCrewId = playbackState.getCurrentDjCrewId();
        if (outgoingCrewId == null) return false;
        return aggregatePort.findDj(partyroomId, outgoingCrewId)
                .filter(dj -> dj.getKind() == DjKind.ONE_SHOT)
                .map(dj -> {
                    partyroomAggregateService.removeDjFromQueue(partyroomId, outgoingCrewId);
                    eventPublisher.publishEvent(new DjQueueChangedEvent(partyroomId, DjChangeType.ONE_SHOT_COMPLETED, outgoingCrewId));
                    log.info("[tryProceed] ONE_SHOT_COMPLETED - partyroomId={}, crewId={}",
                            partyroomId.getId(), outgoingCrewId.getId());
                    return true;
                })
                .orElse(false);
    }

    @Override
    public void startPlayback(PartyroomData partyroom) {
        doStart(partyroom, true);
    }

    private void doStart(PartyroomData partyroom, boolean rotate) {
        long partyroomIdValue = partyroom.getPartyroomId().getId();
        List<DjData> rotatedDjs = rotate
                ? partyroomAggregateService.rotateDjQueue(partyroom.getPartyroomId())
                : aggregatePort.findDjsOrdered(partyroom.getPartyroomId());
        // ... 이하 기존 본문 무변경 ...
    }
```
import 추가: `DjKind`, `Optional`(이미 있음).

`tryProceed`의 `null` 가드: 비활성 상태에서 `findPlaybackState`가 던지는지/null 반환인지는 기존 코드( `enqueueDj:49` )가 무조건 호출하는 걸로 보아 row 는 항상 존재 — `getCurrentDjCrewId()` null 체크로 충분.

- [ ] **Step 4: 통과 확인 (신규 + 기존 회귀)**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PlaybackCommandServiceTest"`
Expected: PASS 전체(기존 테스트 포함 — 기존 것이 깨지면 setUp 기본 스텁부터 의심)

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/enums/DjChangeType.java \
        app/src/main/java/com/pfplaybackend/api/party/application/service/PlaybackCommandService.java \
        app/src/test/java/com/pfplaybackend/api/party/application/service/PlaybackCommandServiceTest.java
git commit -m "feat(party): outgoing ONE_SHOT 이탈 분기 — 정체 기준 제거 + doStart(rotate) (#331)"
```

### Task 6: `QuickDjService` + `TRACK_EXCEEDS_TIME_LIMIT` + 포트 확장

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/exception/DjException.java` — `TRACK_EXCEEDS_TIME_LIMIT("DJ-007", ...)` 추가
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/port/out/PlaylistCommandPort.java` — `Long prepareOneShotPlaylist(UserId userId, AddTrackCommand command);` 추가
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/external/PlaylistCommandAdapter.java` — `TempPlaylistService` 주입·위임
- Create: `app/src/main/java/com/pfplaybackend/api/party/application/service/QuickDjService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/party/application/service/QuickDjServiceTest.java` (신규)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.port.out.PlaylistCommandPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.DjKind;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Quick-DJ(#331) 오케스트레이션 — 시간한도 사전검증(결정7/B)·TEMP 준비·ONE_SHOT enqueue.
 */
@ExtendWith(MockitoExtension.class)
class QuickDjServiceTest {

    @Mock PartyroomQueryService partyroomQueryService;
    @Mock PlaylistCommandPort playlistCommandPort;
    @Mock DjCommandService djCommandService;

    @InjectMocks QuickDjService quickDjService;

    private final UserId userId = new UserId(1L);
    private final PartyroomId partyroomId = new PartyroomId(10L);

    @BeforeEach
    void setUp() {
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        ThreadLocalContext.setContext(authContext);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    private PartyroomData roomWithLimitMinutes(int minutes) {
        return PartyroomData.builder().id(partyroomId.getId()).partyroomId(partyroomId)
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(minutes)).build();
    }

    private AddTrackCommand command(String duration) {
        return new AddTrackCommand("곡A", "aaa111", duration, "https://i.ytimg.com/vi/aaa111/mqdefault.jpg");
    }

    @Test
    @DisplayName("곡 duration > 방 한도 → DJ-007, write 미발생")
    void rejectsTrackExceedingTimeLimit() {
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(roomWithLimitMinutes(3));
        when(partyroomQueryService.getCrewOrThrow(eq(partyroomId), any())).thenReturn(mock(CrewData.class));

        // ExceptionCreator 는 getMessage()에 errorCode 를 싣지 않는다 — 타입 + errorCode 필드로 단언
        assertThatThrownBy(() -> quickDjService.quickEnqueue(partyroomId, command("3:01")))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "DJ-007");

        verifyNoInteractions(playlistCommandPort);
        verifyNoInteractions(djCommandService);
    }

    @Test
    @DisplayName("happy — TEMP 준비 후 ONE_SHOT 으로 enqueue")
    void happyPathPreparesTempAndEnqueuesOneShot() {
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(roomWithLimitMinutes(5));
        when(partyroomQueryService.getCrewOrThrow(eq(partyroomId), any())).thenReturn(mock(CrewData.class));
        when(playlistCommandPort.prepareOneShotPlaylist(eq(userId), any(AddTrackCommand.class))).thenReturn(42L);
        DjData saved = DjData.create(partyroomId, new PlaylistId(42L), new CrewId(3L), 2, DjKind.ONE_SHOT);
        when(djCommandService.enqueueDj(partyroomId, new PlaylistId(42L), DjKind.ONE_SHOT)).thenReturn(saved);

        DjData result = quickDjService.quickEnqueue(partyroomId, command("3:45"));

        assertThat(result.getKind()).isEqualTo(DjKind.ONE_SHOT);
        assertThat(result.getOrderNumber()).isEqualTo(2);
        InOrder inOrder = inOrder(playlistCommandPort, djCommandService);
        inOrder.verify(playlistCommandPort).prepareOneShotPlaylist(eq(userId), any(AddTrackCommand.class));
        inOrder.verify(djCommandService).enqueueDj(partyroomId, new PlaylistId(42L), DjKind.ONE_SHOT);
    }

    @Test
    @DisplayName("무제한 방(limit=0) — 어떤 duration 도 통과")
    void unlimitedRoomAcceptsAnyDuration() {
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(roomWithLimitMinutes(0));
        when(partyroomQueryService.getCrewOrThrow(eq(partyroomId), any())).thenReturn(mock(CrewData.class));
        when(playlistCommandPort.prepareOneShotPlaylist(eq(userId), any())).thenReturn(42L);
        when(djCommandService.enqueueDj(eq(partyroomId), any(), eq(DjKind.ONE_SHOT)))
                .thenReturn(DjData.create(partyroomId, new PlaylistId(42L), new CrewId(3L), 1, DjKind.ONE_SHOT));

        quickDjService.quickEnqueue(partyroomId, command("2:10:00"));

        verify(djCommandService).enqueueDj(eq(partyroomId), any(), eq(DjKind.ONE_SHOT));
    }
}
```
(import 추가: `org.mockito.InOrder`, `com.pfplaybackend.api.common.exception.http.BadRequestException`. `ExceptionCreator.create`는 `ErrorType.BAD_REQUEST` → `BadRequestException(errorCode, message)`를 만들고 errorCode 는 `@Getter` 필드다.)

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.QuickDjServiceTest"`
Expected: 컴파일 실패(QuickDjService 미존재)

- [ ] **Step 3: 구현**

`DjException.java` — 추가:
```java
    TRACK_EXCEEDS_TIME_LIMIT("DJ-007", "곡 길이가 방의 재생 시간 한도를 초과합니다", ErrorType.BAD_REQUEST);
```
(`ErrorType.BAD_REQUEST` 존재 — `TrackException.INVALID_TRACK_ORDER`가 사용 중.)

`PlaylistCommandPort.java` — 추가:
```java
    /** Quick-DJ(#331) — per-user TEMP 플리를 리셋 후 선택 곡 1개를 담아 그 id 를 반환한다. */
    Long prepareOneShotPlaylist(UserId userId, com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand command);
```
(import 정리는 파일 스타일에 맞춰.)

`PlaylistCommandAdapter.java` — `TempPlaylistService` 주입 + 위임:
```java
    @Override
    public Long prepareOneShotPlaylist(UserId userId, AddTrackCommand command) {
        return tempPlaylistService.prepareOneShotPlaylist(userId, command);
    }
```

`QuickDjService.java` 신규:
```java
package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.adapter.in.web.RequestIdInterceptor;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.port.out.PlaylistCommandPort;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.DjKind;
import com.pfplaybackend.api.party.domain.exception.DjException;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick-DJ(#331) — 곡 즉석 선택 → one-shot DJ 큐 등록 오케스트레이션.
 * 시간한도 사전검증(결정7/B) → TEMP 플리 준비(리셋+삽입) → ONE_SHOT enqueue 를
 * 1 트랜잭션으로 묶어, 실패 시 TEMP 에 곡만 남는 중간상태를 남기지 않는다(spec §3-2).
 * 양자택일(이미 큐 등록 시 거부)은 enqueue 내부의 ALREADY_REGISTERED 가드가 그대로 보장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickDjService {

    private final PartyroomQueryService partyroomQueryService;
    private final PlaylistCommandPort playlistCommandPort;
    private final DjCommandService djCommandService;

    @Transactional
    public DjData quickEnqueue(PartyroomId partyroomId, AddTrackCommand command) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId userId = authContext.getUserId();
        log.info("[quickEnqueue] ENTER - requestId={}, partyroomId={}, userId={}, linkId={}",
                RequestIdInterceptor.current(), partyroomId.getId(), userId.getUid(), command.linkId());

        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);
        partyroomQueryService.getCrewOrThrow(partyroomId, userId);

        Duration duration = Duration.fromString(command.duration());
        if (partyroom.getPlaybackTimeLimit().exceedsDuration(duration)) {
            throw ExceptionCreator.create(DjException.TRACK_EXCEEDS_TIME_LIMIT);
        }

        Long tempPlaylistId = playlistCommandPort.prepareOneShotPlaylist(userId, command);
        return djCommandService.enqueueDj(partyroomId, new PlaylistId(tempPlaylistId), DjKind.ONE_SHOT);
    }
}
```
(`getCrewOrThrow` 시그니처가 `(PartyroomId, UserId)`인지 확인 — `DjCommandService:55` 참조.)

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.QuickDjServiceTest"`
Expected: PASS (3/3)

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/exception/DjException.java \
        app/src/main/java/com/pfplaybackend/api/party/application/port/out/PlaylistCommandPort.java \
        app/src/main/java/com/pfplaybackend/api/party/adapter/out/external/PlaylistCommandAdapter.java \
        app/src/main/java/com/pfplaybackend/api/party/application/service/QuickDjService.java \
        app/src/test/java/com/pfplaybackend/api/party/application/service/QuickDjServiceTest.java
git commit -m "feat(party): QuickDjService — 시간한도 사전검증·TEMP 준비·ONE_SHOT enqueue 1-tx (#331)"
```

### Task 7: Quick-DJ endpoint (`POST /{partyroomId}/dj-queue/quick`)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/payload/request/dj/QuickDjRequest.java`
- Create: `app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/payload/response/QuickDjResponse.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/DjCommandController.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/party/adapter/in/web/AbstractPartyCommandWebMvcTest.java` — **필수**: 이 `@WebMvcTest` 베이스는 서비스 빈을 명시적 `@MockBean` 목록으로만 제공한다. `DjCommandController` 생성자에 `QuickDjService`가 추가되므로 `@MockBean protected QuickDjService quickDjService;`를 추가하지 않으면 이 베이스를 상속하는 **모든** 파티 컨트롤러 테스트가 컨텍스트 로드 실패로 전멸한다.
- Test: `app/src/test/java/com/pfplaybackend/api/party/adapter/in/web/DjCommandControllerTest.java` (기존 파일에 추가 — 기존 슬라이스/모킹 하네스 그대로 따름)

- [ ] **Step 1: 실패하는 테스트 작성** — 기존 `DjCommandControllerTest`의 enqueue 테스트(POST dj-queue, line 35·62)를 본떠 3건 추가:
  1. happy: 유효 body → 201 + `djId`/`orderNumber` (QuickDjService mock이 `DjData.create(..., 2, ONE_SHOT)` 반환하도록 — id는 저장 전 null이므로 응답 단언은 orderNumber 중심 또는 mock DjData 사용)
  2. `duration: "abc"` → 400 (@Pattern 위반)
  3. `name: ""` → 400 (@NotBlank 위반)

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.adapter.in.web.DjCommandControllerTest"`
Expected: 컴파일 실패(QuickDjRequest 미존재)

- [ ] **Step 3: 구현**

`QuickDjRequest.java` (`AddTrackRequest` 미러 + duration 형식 검증 — 검증 통과 시 `Duration.fromString` 파싱이 항상 성공):
```java
package com.pfplaybackend.api.party.adapter.in.web.payload.request.dj;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Quick-DJ 등록 요청 — 검색 결과에서 선택한 곡 하나")
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class QuickDjRequest {
    @NotBlank(message = "name is required.")
    @Schema(description = "곡 이름", example = "BLACKPINK - 'Shut Down' M/V", requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
    private String name;

    @NotBlank(message = "linkId is required.")
    @Schema(description = "곡 링크 id", example = "POe9SOEKotk", requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
    private String linkId;

    @NotBlank(message = "duration is required.")
    @Pattern(regexp = "^\\d+:\\d{2}(:\\d{2})?$", message = "duration must be m:ss or h:mm:ss.")
    @Schema(description = "곡 재생 시간", example = "03:01", requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
    private String duration;

    @NotBlank(message = "thumbnailImage is required.")
    @Schema(description = "곡 썸네일 이미지", example = "https://i.ytimg.com/vi/POe9SOEKotk/mqdefault.jpg", requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
    private String thumbnailImage;
}
```

`QuickDjResponse.java`:
```java
package com.pfplaybackend.api.party.adapter.in.web.payload.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Quick-DJ 등록 응답")
public record QuickDjResponse(
        @Schema(description = "생성된 DJ ID") Long djId,
        @Schema(description = "내 대기열 순번(1-base)") int orderNumber
) {}
```

`AbstractPartyCommandWebMvcTest.java` — 기존 `@MockBean` 목록에 추가:
```java
    @MockBean
    protected QuickDjService quickDjService;
```

`DjCommandController.java` — 필드 `private final QuickDjService quickDjService;` 추가 + 메서드:
```java
    @Operation(summary = "Quick-DJ 등록",
            description = "검색한 곡 하나로 즉시 DJ 큐에 one-shot 등록합니다. 1회 재생 후 자동으로 대기열에서 이탈하며, MEMBER 권한이 필요합니다.")
    @ApiResponse(responseCode = "201", description = "Quick-DJ 등록 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({DjException.class})
    @PostMapping("/{partyroomId}/dj-queue/quick")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<ApiCommonResponse<QuickDjResponse>> quickEnqueueDj(
            @Parameter(description = "파티룸 ID") @PathVariable Long partyroomId,
            @Valid @RequestBody QuickDjRequest request) {
        DjData dj = quickDjService.quickEnqueue(new PartyroomId(partyroomId),
                new AddTrackCommand(request.getName(), request.getLinkId(), request.getDuration(), request.getThumbnailImage()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(new QuickDjResponse(dj.getId(), dj.getOrderNumber())));
    }
```
import 추가: `QuickDjService`, `QuickDjRequest`, `QuickDjResponse`, `DjData`, `AddTrackCommand`.

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.adapter.in.web.DjCommandControllerTest"`
Expected: PASS (기존 포함 전체)

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/ \
        app/src/test/java/com/pfplaybackend/api/party/adapter/in/web/DjCommandControllerTest.java
git commit -m "feat(party): POST /dj-queue/quick — Quick-DJ 등록 endpoint (#331)"
```

### Task 8: 통합 테스트 — spec §5 트레이스 잠금

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/party/application/service/QuickDjOneShotIntegrationTest.java`

시드 헬퍼는 `DjCommandIntegrationTest`(partyroom+playback+dj_queue+crew 시드, `seedAuthContext`)를 본뜨되, **PlaylistQueryPort/PlaylistCommandPort를 모킹하지 않고 실 빈으로 구동**한다(재생 선곡 `peekTracksFromCursor`까지 실경로 — 그래서 PLAYLIST/TRACK 실데이터 시드 필요). 재생 시작·완료는 `DjCommandService.enqueueDj`/`QuickDjService.quickEnqueue` → `PlaybackCommandService.complete(partyroomId, userId)` 직접 호출로 시뮬레이션. WS/Redis 릴레이 단언은 하지 않고 **DB 상태(DJ row·kind·order·playback state)**만 단언한다.

주의: `@Transactional` IT에서 expiration task 는 Redis 로 스케줄되지만(Testcontainers Redis) 단언 대상이 아님. `complete()`의 userId 인자는 점수 갱신용 — 아무 사용자나 무방.

- [ ] **Step 1: 실패하는 IT 작성** — 시나리오 5건:

```java
    // (a) 유일 ONE_SHOT 최초 활성화 — 재생됨 → 완료 시 제거 → deactivate (미재생 삭제 금지 회귀 잠금)
    //  quickEnqueue(user X) → DJ row kind=ONE_SHOT & playbackState.currentDjCrewId=X크루 & isActivated=true
    //  complete() → X의 DJ row 부재 & isActivated=false
    // (b) 혼합 큐 [A(NORMAL,재생중), X(ONE_SHOT), B(NORMAL)] — X는 1턴 후 소멸, A·B 라운드로빈 보존
    //  A enqueue(실플리+트랙) → X quickEnqueue → B enqueue
    //  complete()×1 → X 재생 중(currentDjCrewId=X크루), A는 tail
    //  complete()×2 → X row 부재, B 재생 중, 큐=[B(1),A(2)] 유지
    //  complete()×3 → A 재생 중 (라운드로빈 확인)
    // (c) 현재 DJ dequeue-후-skip — incoming ONE_SHOT 미삭제
    //  A(재생중)+X(ONE_SHOT 대기) → A dequeueDj → X의 DJ row 존재 & X 재생 시작
    // (d) 버튼 스킵 — 재생 중 ONE_SHOT 트랙 스킵 → 재등장 없이 제거
    //  X(ONE_SHOT) 단독 재생 중 + B(NORMAL) 대기 → skipPlayback → X row 부재, B 재생
    // (e-1) TRACK_EXCEEDS_TIME_LIMIT 사전거부 — limit=3분 방에 duration "3:01" quickEnqueue
    //  → DJ-007(BadRequestException, errorCode 필드 단언) & DJ row 미생성 & TEMP 무변화
    //  (write 이전 사전거부라 @Transactional 테스트 tx 안에서도 관측 가능)
    // (e-2) ALREADY_REGISTERED 양자택일 + 전체 롤백 — ⚠️ 클래스 @Transactional 로는 관측 불가:
    //  quickEnqueue 의 REQUIRED tx 가 테스트 tx 에 참여해 중간 롤백이 일어나지 않고 rollback-only
    //  마킹만 되므로, "TEMP 곡이 첫 곡 그대로" 단언이 뒤집힌다(곡B가 보임).
    //  → 이 @Test 만 @Transactional(propagation = Propagation.NOT_SUPPORTED) 로 선언하고,
    //    시드는 @Autowired TransactionTemplate 로 커밋(별도 tx), 정리는 DatabaseCleaner 에 위임.
    //    시나리오: X quickEnqueue(곡A, 커밋됨) → 재-quickEnqueue(곡B) → DJ-001(ConflictException)
    //    → 재조회: TEMP 트랙 = 곡A 그대로(곡B 리셋/삽입이 실제 롤백됨), DJ row 1개 유지.
```

각 시나리오는 독립 `@Test`로, 시드 사용자 id 대역을 분리(9300~). (e-2)를 제외한 테스트는 클래스 `@Transactional`, (e-2)만 메서드 레벨 `NOT_SUPPORTED` + `TransactionTemplate` 시드. 실플리 시드 헬퍼:
```java
    private Long persistPlaylistWithTrack(UserId owner, String linkId, String duration) {
        PlaylistData playlist = PlaylistData.create(1, "IT 플리", PlaylistType.PLAYLIST, owner);
        entityManager.persist(playlist);
        entityManager.flush();
        TrackData track = TrackData.builder()
                .playlistId(new PlaylistId(playlist.getId()))
                .name("곡-" + linkId).linkId(linkId)
                .duration(Duration.fromString(duration))
                .orderNumber(1)
                .thumbnailImage("https://i.ytimg.com/vi/" + linkId + "/mqdefault.jpg")
                .build();
        entityManager.persist(track);
        flushAndClear();
        return playlist.getId();
    }
```
enqueue/quickEnqueue 호출 전 `seedAuthContext(해당 user)` 전환. (b)의 큐 순서 단언은 `aggregatePort.findDjsOrdered(partyroomId)`의 (crewId, orderNumber, kind) 튜플로.

⚠️ ONE_SHOT quickEnqueue 사용자도 실제 회원 UserId 시드가 필요한지 확인: `enqueueDj` 내부 `startPlaybackFor`가 `djCrew.getUserId()`를 쓰므로 CrewData 시드에 UserId만 있으면 충분(별도 user 테이블 FK 없음 — `DjCommandIntegrationTest`가 임의 UserId로 통과하는 것으로 확인됨).

- [ ] **Step 2: 실행 및 green 확인** (이 태스크는 검증 잠금 성격 — 구현이 이미 끝났으므로 red 가 아니라 **전부 green 이어야 정상**. 하나라도 red면 Task 5/6 구현 버그이므로 구현을 수정 후 재실행)

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*QuickDjOneShotIntegrationTest"`
Expected: PASS (6/6 — (a)(b)(c)(d)(e-1)(e-2))

- [ ] **Step 3: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/party/application/service/QuickDjOneShotIntegrationTest.java
git commit -m "test(party): Quick-DJ one-shot 회전 트레이스 IT — spec §5 (a)~(d)+파이프라인 잠금 (#331)"
```

---

## Chunk 3: 전량 검증 + 부팅 게이트 + PR

### Task 9: 전량 회귀 (유닛 + IT)

- [ ] **Step 1: 유닛 전량**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew test`
Expected: BUILD SUCCESSFUL, 실패 0 (기준선: 기존 ~1276+)

- [ ] **Step 2: IT 전량**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew integrationTest`
Expected: BUILD SUCCESSFUL, 실패 0 (기준선: 기존 72클래스/282케이스 + 신규)
주의: 로컬 IT 전량은 느릴 수 있음(DatabaseCleaner). 실패 시 gradle Worker 좀비의 output.bin 락 가능성 — 재시도 전 잔존 Test Executor JVM kill.

- [ ] **Step 3: 실패 있으면 원인 수정 후 재실행** (플래키 의심 시에도 원인 규명 우선 — "재실행 그린"으로 넘어가지 않는다)

### Task 10: 로컬 docker 풀부팅 게이트 (fresh DB · Flyway V35 · validate)

- [ ] **Step 1: 최신 jar 빌드** (스테일 jar 함정 — Dockerfile 은 호스트 빌드 jar 를 COPY)

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:bootJar`

- [ ] **Step 2: fresh DB 풀부팅** (volume 초기화로 V1→V35 전체 적용 + validate)

```bash
docker compose -p pfplay-local --env-file .env.local -f docker-compose.local.yml down -v
docker compose -p pfplay-local --env-file .env.local -f docker-compose.local.yml up -d --build
# 부팅 로그에서 Flyway "Successfully applied ... v35" + 앱 Started 확인 (-f + head 는 파이프 조기종료 위험)
docker compose -p pfplay-local --env-file .env.local -f docker-compose.local.yml logs --tail 300 api | grep -E "flyway|Flyway|Started|ERROR"
# Started 가 안 보이면 수 초 대기 후 재실행
```
Expected: Flyway V35 적용 성공, Hibernate validate 통과, 애플리케이션 정상 기동. JPQL/스키마 오류는 여기서만 잡히는 부류이므로 반드시 통과 확인.

- [ ] **Step 3: 라이브 스모크(선택·권장)** — 로컬 스택에서 로그인 후 `POST /api/v1/partyrooms/{id}/dj-queue/quick` 1회 호출(swagger 또는 curl), 201 + DJ 큐 GET 에 ONE_SHOT 반영 확인. (admin 로그인 shared 쿠키 함정 — 회원 계정으로.)

- [ ] **Step 4: 정리 후 커밋 통합 점검** — 논리단위 커밋 유지 확인(필요시 squash, 파괴적 rebase 전 사용자 확인), push:

```bash
git push -u origin feat/quick-dj-one-shot-queue
```

- [ ] **Step 5: PR 생성(한글, 이슈 연결)**

```bash
gh pr create --base develop --title "feat: Quick-DJ — 곡 즉석 선택 one-shot DJ 대기열 등록 (#331)" --body "..."
```
PR 본문: 배경(온보딩 이탈)·설계 요지(TEMP/DjKind/outgoing-정체 분기)·스펙 문서 링크·테스트 증거(유닛/IT/부팅 게이트)·웹 후속 이슈 예고. Closes #331.
반드시 명시할 것 2건:
1. **확정 API 계약** — 스펙 §3-2의 Body 어휘(`videoId/videoTitle/runningTime/...`, 200)와 달리 구현은 기존 `AddTrackRequest` 어휘(`name/linkId/duration/thumbnailImage`) + **201**로 확정(웹 후속 PR이 이 계약을 따르도록 PR 본문이 진실원천).
2. **웹 "ONE_SHOT 큐 표식" 선결조건** — DJ 큐 GET 응답 DTO에 `kind` 미노출 상태. 표식 UI가 필요해지면 후속 백엔드 변경(조회 DTO에 kind 추가) 필요함을 기록.

- [ ] **Step 6: CI 전체 그린 확인 후 사용자 보고** (dev 머지는 로컬 e2e 게이트 통과·사용자 승인 후 — web 연동 UI 가 없으므로 백엔드 단독 머지 여부는 보고 시 사용자 결정 사항으로 명시)

---

## 회귀 가드 요약 (리뷰어 체크리스트)

1. **NORMAL 라운드로빈 무변경**: `rotateDjQueue`/`removeDjFromQueue` 로직 자체는 손대지 않는다. `doStart(rotate=true)` 경로는 기존과 바이트단위 동일 거동.
2. **미재생 ONE_SHOT 삭제 금지**: 제거 분기는 `tryProceed`에만 존재. `startPlayback`(최초 활성화)·`doStart` 내부에는 제거 로직이 없다.
3. **dequeue-후-skip quirk 보존**: outgoing 이 큐에 없으면(`findDj` empty) 분기 미실행 → 기존 rotate 경로.
4. **TEMP 누출 금지**: 목록+단건 양쪽 필터. GRABLIST 노출은 현행 유지.
5. **생성 상한 무영향**: `createPlaylist`는 PLAYLIST만 카운트 — TEMP 자동 제외(코드 무변경으로 보장).
6. **웹 이벤트 호환**: `ONE_SHOT_COMPLETED`는 신규 타입 — 기존 web 이 unknown type 을 무시하는지 여부는 web 후속 PR에서 처리(백엔드는 발행만).
