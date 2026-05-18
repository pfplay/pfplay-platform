# E/#3 PR-1 — 트랙단위 스킵 + deactivate 조건 정정 (platform 핵심) 구현 계획

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** active DJ 차례 트랙이 파티룸 `playbackTimeLimit` 초과 시, 그 트랙만 스킵하고 같은/다음 DJ의 한도 이내 첫 트랙을 재생하며, **큐의 모든 DJ가 재생가능 트랙이 없을 때만** deactivate 한다 (over-limit 트랙은 제자리 보존).

**Architecture:** playlist 모듈 = 순서 메커니즘만(`peekOrderedTracks` 부작용0 + `rotatePlayed` 재생트랙만 tail·나머지 상대순서 보존). party 모듈 = 정책(limit 판정·회전된 DJ 리스트 내부 스캔·deactivate 결정). `getFirstTrack`(rotate-then-take)은 유일 caller(`getNextPlaybackInPlaylist`)만 가지므로 안전히 대체.

**Tech Stack:** Java 21 (빌드 시 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix 필수), Spring Boot, JPA, JUnit5. Gradle 모듈: `playlist`, `app`(party).

**Spec:** `docs/superpowers/specs/2026-05-19-e3-track-skip-deactivate-cascade-design.md` (§3-1 / §5)

---

## File Structure

- `playlist/.../adapter/out/persistence/TrackRepository.java` — Modify: `appendOrderToTail` 쿼리 추가 (rotatePlayed 용; 기존 `reorderTracks`·`shiftUpOrderByDelete` 보존).
- `playlist/.../domain/port/PlaylistAggregatePort.java` — Modify: `appendOrderToTail` 선언 추가.
- `playlist/.../adapter/out/persistence/PlaylistAggregateAdapter.java` — Modify: `appendOrderToTail` 구현.
- `playlist/.../application/service/TrackCommandService.java` — Modify: `peekOrderedTracks(Long)`·`rotatePlayed(Long, int)` 신설. `getFirstTrack` 는 그대로 두되 사용 안 함(다른 caller 없음 → PR 말미 제거 여부 §Task6).
- `app/.../party/application/port/out/PlaylistCommandPort.java` — Modify: `peekOrderedTracks(PlaylistId)`·`rotatePlayed(PlaylistId, int)` 선언, `getFirstTrack` 제거.
- `app/.../party/adapter/out/external/PlaylistCommandAdapter.java` — Modify: 신규 2메서드 위임, `getFirstTrack` 제거.
- `app/.../party/application/service/PlaybackCommandService.java` — Modify: `doStart` 재설계(peek+limit스캔+rotatePlayed), `getNextPlaybackInPlaylist` 시그니처/구현 변경.
- Tests: `playlist/.../service/TrackCommandServiceTest.java`, `app/.../persistence/TrackRepositoryReorderIntegrationTest.java`, `app/.../service/PlaybackCommandServiceTest.java` (#222 포함) — 갱신/신설.

빌드: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test :app:test --tests <FQN>` (Windows: Git Bash 경유). 모듈 단위 우선, 마지막 전체.

---

## Chunk 1: PR-1 — peek/rotatePlayed 분리 + 트랙스킵 + deactivate 조건

### Task 1: `rotatePlayed` 영속 계층 (TrackRepository + port + adapter)

`rotatePlayed(playlistId, k)` 알고리즘: total=count → `shiftUpOrderByDelete(playlistId, k)`(orderNumber>k → -1) → 재생트랙(orderNumber=k, >k 아니라 미변경)을 orderNumber=total 로. orderNumber<k(over-limit 제자리) 불변. 갭/충돌 없음. k=1 이면 기존 `reorderTracks`(`1→total, rest -1`)와 산술 동일(#222 position-1 보존).

**Files:**
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/out/persistence/TrackRepository.java`
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/domain/port/PlaylistAggregatePort.java`
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/out/persistence/PlaylistAggregateAdapter.java`
- Test: `app/src/test/java/com/pfplaybackend/api/playlist/adapter/out/persistence/TrackRepositoryReorderIntegrationTest.java`

- [ ] **Step 1: 실패 통합테스트 작성** — `TrackRepositoryReorderIntegrationTest.java` 에 추가 (기존 클래스 패턴/픽스처 재사용; @DataJpaTest 등 기존 설정 따름):

```java
@Test
@DisplayName("rotatePlayed: k>1 — 재생트랙만 tail, k 이전(over-limit 제자리) 불변, k 이후 -1")
void rotatePlayed_k_gt_1_moves_played_to_tail_keeps_before_intact() {
    // given: playlist P 에 트랙 5개 orderNumber 1..5 (헬퍼는 기존 테스트 방식 재사용)
    Long pid = seedPlaylistWithTracks(5); // 기존 헬퍼 없으면 기존 seed 패턴대로 5개 삽입
    // when: orderNumber=3 인 트랙을 재생했다고 가정
    trackRepository.shiftUpOrderByDelete(pid, 3);   // 4->3, 5->4
    trackRepository.appendOrderToTail(pid, 3, 5);   // 옛 orderNumber=3 → 5

    // then: [1,2 불변], [옛4->3, 옛5->4], [옛3->5]
    List<TrackData> ordered = trackRepository.findByPlaylistId... // 기존 조회 방식
        .stream().sorted(comparingInt(t -> t.getOrderNumber())).toList();
    assertThat(ordered).extracting(TrackData::getOrderNumber).containsExactly(1,2,3,4,5);
    // 옛 orderNumber=1,2 트랙 식별자가 여전히 1,2 위치 (linkId 등으로 검증)
    // 옛 orderNumber=3 트랙이 이제 orderNumber=5
}

@Test
@DisplayName("rotatePlayed: k=1 — 기존 reorderTracks(1→total, rest -1) 와 산술 동일 (#222 position-1 보존)")
void rotatePlayed_k_eq_1_equivalent_to_legacy_reorder() {
    Long pid = seedPlaylistWithTracks(4);
    trackRepository.shiftUpOrderByDelete(pid, 1);  // 2->1,3->2,4->3
    trackRepository.appendOrderToTail(pid, 1, 4);   // 옛1 -> 4
    // == 기존 reorderTracks(pid,4) 결과와 동일: 옛[1,2,3,4] -> [4,1,2,3] 의 orderNumber 매핑
    var ordered = ...; assertThat(...).containsExactly(1,2,3,4);
    // 옛 orderNumber=1 트랙이 4, 옛2->1, 옛3->2, 옛4->3 (linkId 검증)
}
```

> 구현자: 기존 `TrackRepositoryReorderIntegrationTest` 의 seed/조회 헬퍼·어노테이션을 그대로 사용. 위 의사코드의 `seedPlaylistWithTracks`/조회는 그 파일의 기존 패턴으로 치환(신규 헬퍼 만들지 말 것 — 기존 것 재사용).

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.playlist.adapter.out.persistence.TrackRepositoryReorderIntegrationTest"`
Expected: FAIL — `appendOrderToTail` 메서드 없음(컴파일 에러).

- [ ] **Step 3: 구현** — `TrackRepository.java` 에 추가 (기존 `shiftUpOrderByDelete` 바로 아래, 동일 스타일):

```java
@Modifying
@Query("UPDATE TrackData pm SET pm.orderNumber = :totalElements " +
        "WHERE pm.playlistId.id = :playlistId AND pm.orderNumber = :playedOrderNumber")
void appendOrderToTail(@Param("playlistId") Long playlistId,
                       @Param("playedOrderNumber") Integer playedOrderNumber,
                       @Param("totalElements") long totalElements);
```

`PlaylistAggregatePort.java` 에 선언 추가 (기존 `rotateTrackOrder` 인근):

```java
void rotatePlayed(Long playlistId, int playedOrderNumber, long totalCount);
```

`PlaylistAggregateAdapter.java` 에 구현 추가 (기존 `rotateTrackOrder` 구현 인근, 동일 위임 스타일):

```java
@Override
public void rotatePlayed(Long playlistId, int playedOrderNumber, long totalCount) {
    trackRepository.shiftUpOrderByDelete(playlistId, playedOrderNumber);
    trackRepository.appendOrderToTail(playlistId, playedOrderNumber, totalCount);
}
```

> `shiftUpOrderByDelete` 의 파라미터 타입은 `Integer deleteOrderNumber`. `playedOrderNumber:int` 자동 박싱 OK. `trackRepository` 가 adapter에 주입돼 있는지 확인(기존 `rotateTrackOrder` 가 `aggregatePort`/repository 어디로 위임하는지 따라 일관되게 — adapter가 repository 직접 호출하면 위처럼, 아니면 기존 패턴 준수).

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.playlist.adapter.out.persistence.TrackRepositoryReorderIntegrationTest"`
Expected: PASS (신규 2 + 기존 회귀 그린).

- [ ] **Step 5: 커밋**

```bash
git add playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/out/persistence/TrackRepository.java playlist/src/main/java/com/pfplaybackend/api/playlist/domain/port/PlaylistAggregatePort.java playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/out/persistence/PlaylistAggregateAdapter.java app/src/test/java/com/pfplaybackend/api/playlist/adapter/out/persistence/TrackRepositoryReorderIntegrationTest.java
git commit -m "feat(E/#3): rotatePlayed 영속 — shiftUpOrderByDelete+appendOrderToTail (k>1 일반화, k=1 #222 보존)"
```

---

### Task 2: `peekOrderedTracks` + `rotatePlayed` (TrackCommandService)

**Files:**
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/application/service/TrackCommandService.java`
- Test: `playlist/src/test/java/com/pfplaybackend/api/playlist/application/service/TrackCommandServiceTest.java`

- [ ] **Step 1: 실패 단위테스트 작성** — `TrackCommandServiceTest.java` 에 추가(기존 mock 셋업 재사용):

```java
@Test
@DisplayName("peekOrderedTracks: 회전 없이 orderNumber ASC 전 트랙 반환 (부작용 0)")
void peekOrderedTracks_returns_ordered_without_rotation() {
    // given queryPort.getTracksWithPagination(...) stub: 15개 한도 커버 (PageRequest size>=15)
    // when
    List<PlaybackTrackDto> r = trackCommandService.peekOrderedTracks(1L);
    // then: orderNumber ASC, rotateTrackOrder/rotatePlayed 미호출 (verify never)
    verify(aggregatePort, never()).rotateTrackOrder(anyLong(), anyLong());
    verify(aggregatePort, never()).rotatePlayed(anyLong(), anyInt(), anyLong());
    assertThat(r).extracting(PlaybackTrackDto::orderNumber).isSorted();
}

@Test
@DisplayName("rotatePlayed: aggregatePort.rotatePlayed(playlistId, k, total) 위임")
void rotatePlayed_delegates() {
    trackCommandService.rotatePlayed(1L, 3, 5L);
    verify(aggregatePort).rotatePlayed(1L, 3, 5L);
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test --tests "com.pfplaybackend.api.playlist.application.service.TrackCommandServiceTest"`
Expected: FAIL — `peekOrderedTracks`/`rotatePlayed` 미정의.

- [ ] **Step 3: 구현** — `TrackCommandService.java`:

```java
@Transactional(readOnly = true)
public List<PlaybackTrackDto> peekOrderedTracks(Long playlistId) {
    Pageable pageable = PageRequest.of(0, 15, Sort.by(Sort.Direction.ASC, "orderNumber"));
    Page<PlaylistTrackDto> page = queryPort.getTracksWithPagination(new PlaylistId(playlistId), pageable);
    return page.getContent().stream()
            .map(dto -> new PlaybackTrackDto(dto.linkId(), dto.name(),
                    dto.thumbnailImage(), dto.duration(), dto.orderNumber()))
            .toList();
}

@Transactional
public void rotatePlayed(Long playlistId, int playedOrderNumber, long totalCount) {
    aggregatePort.rotatePlayed(playlistId, playedOrderNumber, totalCount);
}
```

> size 15 = 플레이리스트 트랙 상한(`addTrackInPlaylist` `musicCount() >= 15` 가드와 정합 — 전 트랙 peek 보장). `PlaylistTrackDto` 필드는 기존 `getFirstTrack` 매핑과 동일.

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test --tests "com.pfplaybackend.api.playlist.application.service.TrackCommandServiceTest"`
Expected: PASS (신규 + 기존 `getFirstTrack` 테스트 그린 — getFirstTrack 미변경).

- [ ] **Step 5: 커밋**

```bash
git add playlist/src/main/java/com/pfplaybackend/api/playlist/application/service/TrackCommandService.java playlist/src/test/java/com/pfplaybackend/api/playlist/application/service/TrackCommandServiceTest.java
git commit -m "feat(E/#3): TrackCommandService peekOrderedTracks(부작용0)+rotatePlayed"
```

---

### Task 3: 포트/어댑터 교체 (PlaylistCommandPort / Adapter)

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/port/out/PlaylistCommandPort.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/external/PlaylistCommandAdapter.java`

- [ ] **Step 1: 포트 인터페이스 변경** — `PlaylistCommandPort.java`: `getFirstTrack` 제거, 추가:

```java
java.util.List<PlaybackTrackDto> peekOrderedTracks(PlaylistId playlistId);
void rotatePlayed(PlaylistId playlistId, int playedOrderNumber, long totalCount);
```

- [ ] **Step 2: 어댑터 구현** — `PlaylistCommandAdapter.java`: `getFirstTrack` 위임 제거, 추가:

```java
@Override
public List<PlaybackTrackDto> peekOrderedTracks(PlaylistId playlistId) {
    return trackCommandService.peekOrderedTracks(playlistId.getId());
}

@Override
public void rotatePlayed(PlaylistId playlistId, int playedOrderNumber, long totalCount) {
    trackCommandService.rotatePlayed(playlistId.getId(), playedOrderNumber, totalCount);
}
```

- [ ] **Step 3: 컴파일 확인 (의도된 PlaybackCommandService 깨짐)**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava 2>&1 | head`
Expected: FAIL — `PlaybackCommandService.getNextPlaybackInPlaylist` 가 `playlistCommandPort.getFirstTrack` 호출(제거됨). 이는 Task 4에서 해소(의도된 red).

- [ ] **Step 4: 커밋(WIP 경계 — Task3+4 한 커밋으로 합칠 것이므로 여기선 커밋 보류, Task4 Step5에서 함께)**

게이트만: 포트/어댑터 변경이 의도대로 PlaybackCommandService 만 깨뜨림 확인.

---

### Task 4: `doStart` 재설계 — 트랙스킵 + DJ 내부 스캔 + deactivate 조건 정정

핵심. 현재 `doStart`(PlaybackCommandService.java ~L109-160): `rotateDjQueue` 1회 → min-order DJ → `getNextPlaybackInPlaylist`(getFirstTrack, 회전부작용) → exceeds 면 `remainingAttempts<=1?deactivate:재귀(rotateDjQueue 또 호출)`.

신설계: `rotateDjQueue` **사이클당 1회 유지**. 회전된 DJ 리스트를 orderNumber ASC 로 **내부 순회**(재귀 재진입 금지). 각 DJ: `peekOrderedTracks(dj.playlistId)` → limit 이내 첫 트랙 선택. 찾으면 그 트랙 재생 + `rotatePlayed(playlistId, 그트랙.orderNumber, total)` + 기존 publish/schedule. 그 DJ 전 트랙 over-limit → 다음 DJ. **모든 DJ 스캔 후 재생가능 0 → `deactivateAndNotify`**(기존 경로 그대로).

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/PlaybackCommandService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/party/application/service/PlaybackCommandServiceTest.java`

- [ ] **Step 1: 실패/회귀 테스트 작성** — `PlaybackCommandServiceTest.java`. 기존 #222·deactivate 테스트의 mock 셋업 패턴 재사용. 신규 + 기존 갱신:

```java
@Test @DisplayName("E/#3: DJ1 + [over-limit, playable] → playable 재생, deactivate 안 됨, over-limit 제자리")
void singleDj_skipsOverLimit_playsPlayable() {
  // given: queuedDjs=[dj1]; peekOrderedTracks(dj1.pl)=[t1(over limit), t2(<=limit)]
  // when tryProceed/startPlayback
  // then: playbackRepository.save( t2 ), rotatePlayed(pl, t2.orderNumber, total) 호출,
  //       deactivateAndNotify 미호출, PlaybackStartedEvent(t2) publish
}
@Test @DisplayName("E/#3: DJ1 + 전트랙 over-limit → deactivate (정당, 큐 removeDjs)")
void singleDj_allOverLimit_deactivates() {
  // peek=[t1,t2 모두 over limit] → deactivateAndNotify 호출(DjQueueChangedEvent DEACTIVATE)
}
@Test @DisplayName("E/#3: 다DJ — 앞 DJ 전트랙 over-limit → 다음 DJ 재생, rotateDjQueue 1회만")
void multiDj_firstAllOverLimit_playsNextDj() {
  // queued=[dj1(all over), dj2(playable)] → dj2 트랙 재생, partyroomAggregateService.rotateDjQueue 1회 verify
}
```

기존 테스트 갱신: `getFirstTrack` mock → `peekOrderedTracks`/`rotatePlayed` mock 으로 와이어링 교체. **#222 관련(skip→재생 트랙 최하단·DJ큐 밀림) 어서션은 동작 보존**: position-1(over-limit 선행 없음) 케이스에서 재생트랙이 tail 로 가고 DJ큐 회전 동일 → 어서션 의미 유지(메서드명만 rotatePlayed 로). 정당 deactivate 케이스 보존.

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PlaybackCommandServiceTest"`
Expected: FAIL — 신규 테스트(메서드 미구현) + 컴파일(getFirstTrack 제거).

- [ ] **Step 3: 구현** — `PlaybackCommandService.java`:

`getNextPlaybackInPlaylist` 제거(또는 시그니처 변경). `doStart` 를 아래 구조로 재작성(기존 publish/schedule/PlaybackData.create·PlaybackStartedEvent·DjQueueChangedEvent(ROTATE)·scheduleTask·playbackState 갱신 블록은 **그대로 재사용**, 선택 로직만 교체):

```java
private void doStart(PartyroomData partyroom, int remainingAttempts) {
    long pid = partyroom.getPartyroomId().getId();
    List<DjData> rotatedDjs = partyroomAggregateService.rotateDjQueue(partyroom.getPartyroomId()); // 사이클당 1회
    List<DjData> ordered = rotatedDjs.stream()
            .sorted(Comparator.comparingInt(DjData::getOrderNumber)).toList();

    for (DjData dj : ordered) {
        CrewData djCrew = aggregatePort.findCrewById(dj.getCrewId().getId()).orElseThrow();
        List<PlaybackTrackDto> tracks = playlistCommandPort.peekOrderedTracks(dj.getPlaylistId());
        PlaybackTrackDto chosen = tracks.stream()
                .filter(t -> !partyroom.getPlaybackTimeLimit().exceedsDuration(t.duration()))
                .findFirst().orElse(null);
        if (chosen == null) {
            log.warn("[doStart] DJ_ALL_TRACKS_EXCEED - partyroomId={}, djCrewId={}", pid, djCrew.getId());
            continue; // 다음 DJ
        }
        // 재생 확정: 기존 PlaybackData.create + save + playbackState.updatePlayback
        PlaybackData nextPlayback = PlaybackData.create(partyroom.getPartyroomId(), djCrew.getUserId(),
                chosen.name(), chosen.duration(), chosen.linkId(), chosen.thumbnailImage(), clock.instant());
        playlistCommandPort.rotatePlayed(dj.getPlaylistId(), chosen.orderNumber(), tracks.size());
        PlaybackData playbackData = playbackRepository.save(nextPlayback);
        playbackAggregationRepository.save(PlaybackAggregationData.createFor(new PlaybackId(playbackData.getId())));
        PartyroomPlaybackData st = aggregatePort.findPlaybackState(partyroom.getPartyroomId());
        st.updatePlayback(new PlaybackId(playbackData.getId()), new CrewId(djCrew.getId()));
        aggregatePort.savePlaybackState(st);
        scheduleTask(nextPlayback);
        PlaybackSnapshot snap = new PlaybackSnapshot(playbackData.getId(), playbackData.getLinkId(),
                playbackData.getName(), playbackData.getDuration().toDisplayString(),
                playbackData.getThumbnailImage(), playbackData.getEndTime());
        eventPublisher.publishEvent(new PlaybackStartedEvent(partyroom.getPartyroomId(), new CrewId(djCrew.getId()), snap));
        eventPublisher.publishEvent(new DjQueueChangedEvent(partyroom.getPartyroomId(), DjChangeType.ROTATE, new CrewId(djCrew.getId())));
        return;
    }
    log.warn("[doStart] DEACTIVATE_TRIGGERED - partyroomId={}, reason=ALL_DJS_NO_PLAYABLE_TRACK", pid);
    deactivateAndNotify(partyroom);
}
```

> `remainingAttempts` 파라미터: 호출지(`tryProceed`/`startPlayback`) 시그니처 호환 위해 유지하되 내부 스캔이 큐 전체를 1회 순회하므로 미사용(또는 호출지에서 제거 — 더 깔끔하면 `doStart(PartyroomData)` 로 단순화하고 `tryProceed`/`startPlayback` 도 인자 제거. 구현자 판단; 단 다른 caller 없음 확인 후). 무한루프 불가(for 1회·재귀 없음). `getNextPlaybackInPlaylist` 제거로 그 메서드 참조 전부 정리. import: `java.util.List`, `Comparator` 등.

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PlaybackCommandServiceTest"`
Expected: PASS (신규 3 + 갱신된 #222/deactivate 회귀 그린).

- [ ] **Step 5: 커밋 (Task3+4 합본)**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/application/port/out/PlaylistCommandPort.java app/src/main/java/com/pfplaybackend/api/party/adapter/out/external/PlaylistCommandAdapter.java app/src/main/java/com/pfplaybackend/api/party/application/service/PlaybackCommandService.java app/src/test/java/com/pfplaybackend/api/party/application/service/PlaybackCommandServiceTest.java
git commit -m "feat(E/#3): doStart 트랙단위 스킵 + 회전된 DJ 내부 스캔 + 전-DJ-무재생 시에만 deactivate (rotateDjQueue 사이클당 1회)"
```

---

### Task 5: 전 모듈 회귀 + 행위보존 검증

- [ ] **Step 1: 영향 모듈 전체 테스트**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test :app:test`
Expected: 전 그린. 특히 #222(skip→reorder)·기존 정당 deactivate·DJ큐 회전 회귀 그린(position-1 동작 보존).

- [ ] **Step 2: 잔존 `getFirstTrack` 정리 판단**

`getFirstTrack`(TrackCommandService)·`reorderTracks`(미사용 시) 의 다른 프로덕션 caller 0 확인되면(grep) **제거**(YAGNI·죽은코드). 테스트만 참조면 테스트도 정리. caller 있으면 보존. 결정·근거 커밋 메시지에 명시.

```bash
# grep 확인 후
git add -A && git commit -m "refactor(E/#3): 미사용 getFirstTrack/reorderTracks 정리 (peek/rotatePlayed 대체, caller 0 확인)"
```
(caller 존재 시 이 Task는 no-op·스킵.)

- [ ] **Step 3: 최종 게이트**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test :app:test`
Expected: 전 그린. `git log --oneline origin/develop..HEAD` 로 논리커밋 확인(push 전 통합은 PR 생성 단계서).

---

## 완료 정의 (DoD)

- DJ1+혼재 → playable 재생·over-limit 제자리·deactivate 안 됨 / DJ1+전트랙 over-limit → deactivate(큐 removeDjs) / 다DJ 앞-전트랙-over → 다음 DJ, `rotateDjQueue` 사이클당 1회.
- `peekOrderedTracks` 부작용 0, `rotatePlayed` k>1 일반화·k=1 #222 산술 동일(보존).
- 신규 단위/통합 + 기존 #222·deactivate·회전 회귀 전 그린(`:playlist:test :app:test`).
- 무한루프/DJ큐 이중회전 불가(for 1회·재귀 제거).
- 범위 = PR-1(동작)만. PR-2(changeType payload)·PR-3(web UX)·PR-4(배지)는 별 계획/PR. DEACTIVATE 시 발행되는 `DjQueueChangedEvent(DEACTIVATE,null)` payload 변경은 PR-1 범위 밖(기존 그대로).
