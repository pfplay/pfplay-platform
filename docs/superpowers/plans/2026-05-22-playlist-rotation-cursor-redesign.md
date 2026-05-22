# 플레이리스트 재생 회전 제거 + 영속 재생 커서 구현 플랜

> **For agentic workers:** REQUIRED: Use @superpowers:executing-plans (in-session, 본 변경은 여러 파일이 함께 바뀌어야 해 cross-file 컨텍스트가 필요) to implement this plan. Steps use checkbox (`- [ ]`) syntax.

**Goal:** `track.order_number`의 이중 용도(사용자 순서 + 재생 회전 커서)를 분리해, 재생은 `PLAYLIST.last_played_track_id` 커서로 결정하고 회전을 제거한다. 동시에 신규 곡 추가를 맨 위(order 1)로 바꾼다. → #262 + 기획 요청을 한 PR로 해결.

**Architecture:** 헥사고날(party 모듈이 playlist 모듈을 port로 호출). `orderNumber`는 add/delete/reorder에서만 변경(재생은 불변). DJ 턴 곡 선택 = "커서 트랙 다음부터 wrap 정렬한 목록에서 첫 재생가능 곡" → 선택 곡으로 커서 갱신.

**Tech Stack:** Java 21, Spring Boot, JPA/Hibernate(@DynamicUpdate), Flyway, QueryDSL, JUnit5/Mockito, Gradle multi-module.

**빌드/테스트 prefix (필수):** 모든 gradle 호출에 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` 를 붙인다. Bash 툴(git bash)에서 `./gradlew` 사용.

스펙: `docs/superpowers/specs/2026-05-22-playlist-rotation-cursor-redesign-design.md`

---

## File Structure

생성:
- `app/src/main/resources/db/migration/V21__add_playlist_playback_cursor.sql` — 커서 컬럼 마이그레이션
- `playlist/src/main/java/com/pfplaybackend/api/playlist/domain/enums/InsertPosition.java` — HEAD/TAIL enum

수정 (main):
- `playlist/.../domain/entity/data/PlaylistData.java` — `lastPlayedTrackId` 필드
- `playlist/.../application/dto/PlaybackTrackDto.java` — `trackId` 필드 추가
- `playlist/.../adapter/out/persistence/TrackRepository.java` — `shiftAllOrdersDown` 추가, `rotatePlayedOrder` 제거
- `playlist/.../adapter/out/persistence/PlaylistRepository.java` — `updateLastPlayedTrackId` @Modifying 추가
- `playlist/.../domain/port/PlaylistAggregatePort.java` — `rotatePlayed` 제거; `shiftAllOrdersDown`/`findPlaylistById`/`updateLastPlayedTrackId` 추가
- `playlist/.../adapter/out/persistence/PlaylistAggregateAdapter.java` — 위 port 구현 반영
- `playlist/.../application/service/TrackCommandService.java` — `insertTrack`(HEAD/TAIL) 추출, `peekTracksFromCursor`/`advancePlaybackCursor` 추가, `rotatePlayed` 제거
- `playlist/.../application/service/GrabTrackService.java` — TAIL 보존 호출
- `app/.../party/application/port/out/PlaylistCommandPort.java` — `rotatePlayed`/`peekOrderedTracks` 제거; `peekTracksFromCursor`/`advancePlaybackCursor` 추가
- `app/.../party/adapter/out/external/PlaylistCommandAdapter.java` — 위 반영
- `app/.../party/application/service/PlaybackCommandService.java` — `doStart`/`startPlaybackFor` 커서 기반으로 교체

수정 (test):
- `playlist/.../application/service/TrackCommandServiceTest.java`
- `app/.../party/application/service/PlaybackCommandServiceTest.java`
- `app/.../playlist/adapter/out/persistence/TrackRepositoryReorderIntegrationTest.java` — rotate 제거 → `shiftAllOrdersDown` 검증으로 repurpose

---

## Chunk 1: 스키마 · 엔티티 · DTO

### Task 1: 마이그레이션 V21 + PlaylistData 커서 필드

**Files:**
- Create: `app/src/main/resources/db/migration/V21__add_playlist_playback_cursor.sql`
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/domain/entity/data/PlaylistData.java`

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- V21__add_playlist_playback_cursor.sql
ALTER TABLE playlist
    ADD COLUMN last_played_track_id bigint unsigned NULL COMMENT '재생 커서 — 마지막 재생 트랙 id';
```

- [ ] **Step 2: PlaylistData 에 필드 + 빌더 + 갱신 메서드 추가**

`type` 필드 선언 다음(클래스 내), 빌더 생성자/`create`는 그대로 두고 필드와 도메인 메서드만 추가:

```java
    @Comment("재생 커서 — 마지막 재생 트랙 id")
    @Column(name = "last_played_track_id", columnDefinition = "bigint unsigned")
    private Long lastPlayedTrackId;
```

(빌더 생성자 파라미터에는 추가하지 않는다 — 생성 시엔 항상 null. `@Getter`가 `getLastPlayedTrackId()` 제공. 영속화는 Task 4의 @Modifying 쿼리로 직접 처리하므로 setter 불필요.)

- [ ] **Step 3: 컴파일 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/resources/db/migration/V21__add_playlist_playback_cursor.sql playlist/src/main/java/com/pfplaybackend/api/playlist/domain/entity/data/PlaylistData.java
git commit -m "feat(playlist): V21 재생 커서 컬럼 + PlaylistData.lastPlayedTrackId"
```

### Task 2: PlaybackTrackDto 에 trackId 추가

**Files:**
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/application/dto/PlaybackTrackDto.java`
- Modify: `playlist/.../application/service/TrackCommandService.java` (peekOrderedTracks 매핑)

- [ ] **Step 1: record 에 trackId 추가**

```java
public record PlaybackTrackDto(
        Long trackId,
        String linkId,
        String name,
        String thumbnailImage,
        Duration duration,
        int orderNumber
) {
}
```

- [ ] **Step 2: 유일 생성처(peekOrderedTracks) 매핑 보강** — `PlaylistTrackDto.trackId()` 사용

`TrackCommandService.peekOrderedTracks` 의 map 람다:

```java
            .map(dto -> new PlaybackTrackDto(dto.trackId(), dto.linkId(), dto.name(),
                    dto.thumbnailImage(), dto.duration(), dto.orderNumber()))
```

- [ ] **Step 3: 전체 컴파일 (생성처 누락 검출)**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew compileJava -q`
Expected: BUILD SUCCESSFUL. (실패 시 PlaybackTrackDto 생성처가 더 있다는 뜻 → 해당 위치도 trackId 추가)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(playlist): PlaybackTrackDto 에 trackId 추가 (커서 갱신용)"
```

---

## Chunk 2: 영속화 plumbing (repository · aggregate port)

### Task 3: TrackRepository — shiftAllOrdersDown 추가 + rotatePlayedOrder 제거

**Files:**
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/adapter/out/persistence/TrackRepository.java`

- [ ] **Step 1: `rotatePlayedOrder` 메서드(@Query 포함) 삭제하고 `shiftAllOrdersDown` 추가**

```java
    @Modifying
    @Query("UPDATE TrackData pm SET pm.orderNumber = pm.orderNumber + 1 " +
            "WHERE pm.playlistId.id = :playlistId")
    void shiftAllOrdersDown(@Param("playlistId") Long playlistId);
```

(shiftUpOrderByDelete / shiftUpOrderByDnD / shiftDownOrderByDnD 는 그대로 유지.)

- [ ] **Step 2: 컴파일** — `rotatePlayedOrder` 호출처(PlaylistAggregateAdapter)가 깨지는 것을 확인

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:compileJava -q`
Expected: FAIL — `PlaylistAggregateAdapter` 의 `rotatePlayedOrder` 참조 에러. (Task 4에서 해소)

### Task 4: PlaylistAggregatePort/Adapter + PlaylistRepository 커서 메서드

**Files:**
- Modify: `playlist/.../domain/port/PlaylistAggregatePort.java`
- Modify: `playlist/.../adapter/out/persistence/PlaylistAggregateAdapter.java`
- Modify: `playlist/.../adapter/out/persistence/PlaylistRepository.java`

- [ ] **Step 1: PlaylistRepository 에 커서 업데이트 쿼리 추가** (import `@Modifying`, `@Query`, `@Param`)

```java
    @Modifying
    @Query("UPDATE PlaylistData p SET p.lastPlayedTrackId = :trackId WHERE p.id = :playlistId")
    void updateLastPlayedTrackId(@Param("playlistId") Long playlistId, @Param("trackId") Long trackId);
```

- [ ] **Step 2: PlaylistAggregatePort 변경** — `rotatePlayed` 제거, 신규 3개 추가

```java
    // 제거: void rotatePlayed(Long playlistId, int playedOrderNumber, long totalCount);

    // 추가 (Track Reordering 섹션 근처)
    void shiftAllOrdersDown(Long playlistId);

    // 추가 (Root: PlaylistData 섹션 근처)
    Optional<PlaylistData> findPlaylistById(Long playlistId);
    void advancePlaybackCursor(Long playlistId, Long trackId);
```

- [ ] **Step 3: PlaylistAggregateAdapter 구현** — `rotatePlayed` 삭제, 신규 구현

```java
    @Override
    public void shiftAllOrdersDown(Long playlistId) {
        trackRepository.shiftAllOrdersDown(playlistId);
    }

    @Override
    public Optional<PlaylistData> findPlaylistById(Long playlistId) {
        return playlistRepository.findById(playlistId);
    }

    @Override
    public void advancePlaybackCursor(Long playlistId, Long trackId) {
        playlistRepository.updateLastPlayedTrackId(playlistId, trackId);
    }
```

- [ ] **Step 4: 컴파일**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:compileJava -q`
Expected: FAIL — 이제 `TrackCommandService.rotatePlayed` 가 제거된 `aggregatePort.rotatePlayed` 를 참조. (Task 5에서 해소)

---

## Chunk 3: TrackCommandService — add-to-head + 커서 선택/갱신

### Task 5: InsertPosition + insertTrack 추출 (add=HEAD, grab=TAIL)

**Files:**
- Create: `playlist/.../domain/enums/InsertPosition.java`
- Modify: `playlist/.../application/service/TrackCommandService.java`
- Modify: `playlist/.../application/service/GrabTrackService.java`
- Test: `playlist/.../application/service/TrackCommandServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성** — add=HEAD(shift+order1), grab 경로용 TAIL

`TrackCommandServiceTest` 에 추가 (기존 mock 설정 패턴 따름: `aggregatePort`, `playlistQueryService` 등 @Mock):

```java
@Test
@DisplayName("addTrackInPlaylist 는 신규 곡을 맨 위(order 1)로 넣고 기존 전체를 +1 shift 한다")
void addTrack_insertsAtHead() {
    // given: 소유 플레이리스트 존재, 중복 없음, 3곡 보유
    // (기존 테스트의 given 셋업 재사용: findPlaylistByIdAndOwner, findTrackByPlaylistAndLink=empty,
    //  playlistQueryService.getPlaylist → musicCount=3, AuthContext stub)
    // when
    trackCommandService.addTrackInPlaylist(playlistId, addCommand);
    // then
    verify(aggregatePort).shiftAllOrdersDown(playlistId);
    ArgumentCaptor<TrackData> captor = ArgumentCaptor.forClass(TrackData.class);
    verify(aggregatePort).saveTrack(captor.capture());
    assertThat(captor.getValue().getOrderNumber()).isEqualTo(1);
}
```

(정확한 given 스텁은 기존 `addTrackInPlaylist` 성공 케이스 테스트를 복제해 맞춘다. AuthContext 는 기존 테스트의 ThreadLocalContext 셋업 방식 그대로.)

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test --tests "*TrackCommandServiceTest" -q`
Expected: FAIL (shiftAllOrdersDown 미호출 / order!=1)

- [ ] **Step 3: InsertPosition enum 생성**

```java
package com.pfplaybackend.api.playlist.domain.enums;

public enum InsertPosition {
    HEAD, TAIL
}
```

- [ ] **Step 4: insertTrack 추출 + addTrackInPlaylist=HEAD**

`addTrackInPlaylist` 본문을 `insertTrack(playlistId, command, InsertPosition.HEAD)` 위임으로 바꾸고 공통 로직을 private 으로 추출:

```java
@Transactional
public Long addTrackInPlaylist(Long playlistId, AddTrackCommand command) {
    return insertTrack(playlistId, command, InsertPosition.HEAD);
}

@Transactional
public Long insertTrack(Long playlistId, AddTrackCommand command, InsertPosition position) {
    AuthContext authContext = ThreadLocalContext.getAuthContext();
    PlaylistData playlistData = aggregatePort.findPlaylistByIdAndOwner(playlistId, authContext.getUserId())
            .orElseThrow(() -> ExceptionCreator.create(PlaylistException.NOT_FOUND_PLAYLIST));
    Optional<TrackData> optional = aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(playlistData.getId()), command.linkId());
    if (optional.isPresent()) throw ExceptionCreator.create(TrackException.DUPLICATE_TRACK_IN_PLAYLIST);
    PlaylistSummaryDto playlistSummary = playlistQueryService.getPlaylist(playlistId);
    if (playlistSummary.musicCount() >= MAX_PLAYLIST_TRACK_COUNT) throw ExceptionCreator.create(TrackException.EXCEEDED_TRACK_LIMIT);

    int orderNumber;
    if (position == InsertPosition.HEAD) {
        aggregatePort.shiftAllOrdersDown(playlistData.getId());
        orderNumber = 1;
    } else { // TAIL — 기존 동작 보존
        orderNumber = playlistSummary.musicCount() == 0 ? 1 : (int) playlistSummary.musicCount() + 1;
    }

    TrackData trackData = TrackData.builder()
            .playlistId(new PlaylistId(playlistData.getId()))
            .name(command.name())
            .linkId(command.linkId())
            .duration(Duration.fromString(command.duration()))
            .orderNumber(orderNumber)
            .thumbnailImage(command.thumbnailImage())
            .build();
    TrackData saved = aggregatePort.saveTrack(trackData);
    eventPublisher.publishEvent(new TrackAddedEvent(new PlaylistId(playlistId), command.linkId(), command.name()));
    return saved.getId();
}
```

- [ ] **Step 5: GrabTrackService 가 TAIL 사용하도록 변경**

`GrabTrackService.grabTrack` 의 호출부:

```java
Long trackId = trackCommandService.insertTrack(playlistData.getId(), command, InsertPosition.TAIL);
```

(import `InsertPosition` 추가.)

- [ ] **Step 6: 테스트 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test --tests "*TrackCommandServiceTest" -q`
Expected: PASS (단, 아직 rotatePlayed 제거 전이면 컴파일 에러 가능 — Task 6과 함께 통과시킬 것)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(playlist): 신규 곡 추가를 맨 위로(add-to-head), grab은 tail 보존 (insertTrack/InsertPosition)"
```

### Task 6: 커서 기반 peekTracksFromCursor + advancePlaybackCursor, rotatePlayed 제거

**Files:**
- Modify: `playlist/.../application/service/TrackCommandService.java`
- Test: `playlist/.../application/service/TrackCommandServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성** — wrap 선택 로직

`queryPort.getTracksWithPagination` 가 정렬된 `PlaylistTrackDto` 페이지를 반환하도록 stub, `aggregatePort.findPlaylistById` 로 커서 stub. 헬퍼로 `PlaylistTrackDto(trackId, linkId, name, orderNumber, duration, thumb)` 목록 구성.

```java
@Test
@DisplayName("peekTracksFromCursor: 커서 다음부터 wrap 정렬 (커서=B → C,A,B)")
void peek_wrapsAfterCursor() {
    // given: 트랙 A(1,id=10),B(2,id=20),C(3,id=30), 커서 last_played=20(B)
    stubTracks(playlistId, A_1_10, B_2_20, C_3_30);
    given(aggregatePort.findPlaylistById(playlistId))
        .willReturn(Optional.of(playlistWithCursor(20L)));
    // when
    List<PlaybackTrackDto> result = trackCommandService.peekTracksFromCursor(playlistId);
    // then: C, A, B 순
    assertThat(result).extracting(PlaybackTrackDto::trackId).containsExactly(30L, 10L, 20L);
}

@Test
@DisplayName("peekTracksFromCursor: 커서 null → 자연 순서(top부터)")
void peek_nullCursor_naturalOrder() {
    stubTracks(playlistId, A_1_10, B_2_20, C_3_30);
    given(aggregatePort.findPlaylistById(playlistId)).willReturn(Optional.of(playlistWithCursor(null)));
    assertThat(trackCommandService.peekTracksFromCursor(playlistId))
        .extracting(PlaybackTrackDto::trackId).containsExactly(10L, 20L, 30L);
}

@Test
@DisplayName("peekTracksFromCursor: 커서가 가리키던 트랙 삭제됨(목록에 없음) → top부터")
void peek_deletedCursor_naturalOrder() {
    stubTracks(playlistId, A_1_10, B_2_20, C_3_30);
    given(aggregatePort.findPlaylistById(playlistId)).willReturn(Optional.of(playlistWithCursor(999L)));
    assertThat(trackCommandService.peekTracksFromCursor(playlistId))
        .extracting(PlaybackTrackDto::trackId).containsExactly(10L, 20L, 30L);
}

@Test
@DisplayName("advancePlaybackCursor 는 aggregatePort.advancePlaybackCursor 로 위임")
void advance_delegates() {
    trackCommandService.advancePlaybackCursor(playlistId, 30L);
    verify(aggregatePort).advancePlaybackCursor(playlistId, 30L);
}
```

(stubTracks/playlistWithCursor 헬퍼는 테스트 클래스에 작성. `playlistWithCursor` 는 `PlaylistData` 빌드 후 리플렉션/빌더로 cursor 세팅 — 빌더에 없으면 `org.springframework.test.util.ReflectionTestUtils.setField(p,"lastPlayedTrackId",v)` 사용.)

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test --tests "*TrackCommandServiceTest" -q`
Expected: FAIL (메서드 미존재 컴파일 에러)

- [ ] **Step 3: 구현 — rotatePlayed 삭제, peekTracksFromCursor/advancePlaybackCursor 추가**

`rotatePlayed` 메서드 삭제. 추가:

```java
@Transactional(readOnly = true)
public List<PlaybackTrackDto> peekTracksFromCursor(Long playlistId) {
    Pageable pageable = PageRequest.of(0, MAX_PLAYLIST_TRACK_COUNT, Sort.by(Sort.Direction.ASC, "orderNumber"));
    List<PlaylistTrackDto> ordered = queryPort.getTracksWithPagination(new PlaylistId(playlistId), pageable).getContent();

    Long cursor = aggregatePort.findPlaylistById(playlistId)
            .map(PlaylistData::getLastPlayedTrackId)
            .orElse(null);

    int start = 0; // 커서 null 또는 미발견 → top(0)부터
    if (cursor != null) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).trackId().equals(cursor)) {
                start = i + 1; // 커서 "다음"부터
                break;
            }
        }
    }

    int n = ordered.size();
    List<PlaybackTrackDto> rotated = new java.util.ArrayList<>(n);
    for (int k = 0; k < n; k++) {
        PlaylistTrackDto dto = ordered.get((start + k) % n);
        rotated.add(new PlaybackTrackDto(dto.trackId(), dto.linkId(), dto.name(),
                dto.thumbnailImage(), dto.duration(), dto.orderNumber()));
    }
    return rotated;
}

@Transactional
public void advancePlaybackCursor(Long playlistId, Long trackId) {
    aggregatePort.advancePlaybackCursor(playlistId, trackId);
}
```

(`n == 0` 가드: `for` 가 안 돌아 빈 리스트 반환 — `%` 0 호출 안 됨. 안전.)

- [ ] **Step 4: 테스트 통과 + 모듈 컴파일**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test --tests "*TrackCommandServiceTest" -q`
Expected: PASS

- [ ] **Step 5: rotatePlayed_delegates 테스트 삭제** (있다면) — 제거된 메서드 검증 테스트.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(playlist): 커서 기반 peekTracksFromCursor/advancePlaybackCursor, rotatePlayed 제거"
```

---

## Chunk 4: party 포트 + PlaybackCommandService 배선

### Task 7: party PlaylistCommandPort/Adapter 교체

**Files:**
- Modify: `app/.../party/application/port/out/PlaylistCommandPort.java`
- Modify: `app/.../party/adapter/out/external/PlaylistCommandAdapter.java`

- [ ] **Step 1: 포트 인터페이스 변경** — `peekOrderedTracks`/`rotatePlayed` 제거, 신규 2개 추가

```java
public interface PlaylistCommandPort {
    AddedTrackInfo grabTrack(UserId userId, String linkId);
    java.util.List<PlaybackTrackDto> peekTracksFromCursor(PlaylistId playlistId);
    void advancePlaybackCursor(PlaylistId playlistId, Long trackId);
}
```

- [ ] **Step 2: 어댑터 구현 교체**

```java
    @Override
    public List<PlaybackTrackDto> peekTracksFromCursor(PlaylistId playlistId) {
        return trackCommandService.peekTracksFromCursor(playlistId.getId());
    }

    @Override
    public void advancePlaybackCursor(PlaylistId playlistId, Long trackId) {
        trackCommandService.advancePlaybackCursor(playlistId.getId(), trackId);
    }
```

(기존 `peekOrderedTracks`/`rotatePlayed` override 삭제.)

- [ ] **Step 3: 컴파일** — PlaybackCommandService 가 깨지는 것 확인

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava -q`
Expected: FAIL — PlaybackCommandService 의 `peekOrderedTracks`/`rotatePlayed` 참조 에러. (Task 8에서 해소)

### Task 8: PlaybackCommandService doStart/startPlaybackFor 커서 기반 교체

**Files:**
- Modify: `app/.../party/application/service/PlaybackCommandService.java` (lines ~119-150)

- [ ] **Step 1: doStart 의 peek 호출 교체** (line 122)

```java
            List<PlaybackTrackDto> peeked = playlistCommandPort.peekTracksFromCursor(dj.getPlaylistId());
```

- [ ] **Step 2: startPlaybackFor 의 rotate → advance 교체** (line 150)

`startPlaybackFor` 시그니처에서 `long total` 파라미터 제거(이제 미사용), 본문 line 150 교체:

```java
        playlistCommandPort.advancePlaybackCursor(dj.getPlaylistId(), chosen.trackId());
```

호출부(line 138)도 `startPlaybackFor(partyroom, dj, djCrew, chosen);` 로 수정.

- [ ] **Step 3: app 전체 컴파일**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava :app:compileTestJava -q`
Expected: FAIL (테스트가 아직 rotatePlayed/peekOrderedTracks 를 stub/verify → Task 9에서 해소). main 컴파일은 SUCCESS 여야 함.

- [ ] **Step 4: Commit (main 한정)**

```bash
git add app/src/main/java/com/pfplaybackend/api/party
git commit -m "feat(party): 재생 곡 선택을 커서 기반으로 교체 (peekTracksFromCursor + advancePlaybackCursor)"
```

---

## Chunk 5: 테스트 rewire + 회귀 + 품질 게이트

### Task 9: PlaybackCommandServiceTest rewire

**Files:**
- Modify: `app/.../party/application/service/PlaybackCommandServiceTest.java`

- [ ] **Step 1: stub/verify 치환**
  - `given(playlistCommandPort.peekOrderedTracks(any())).willReturn(...)` → `peekTracksFromCursor`
  - `verify(playlistCommandPort).rotatePlayed(any(), anyInt(), anyLong())` → `verify(playlistCommandPort).advancePlaybackCursor(eq(playlistId), eq(<chosen trackId>))`
  - `verify(...,never()).rotatePlayed(...)` (singleDj_allOverLimit) → `never().advancePlaybackCursor(...)`
  - peeked 트랙 fixture 의 `PlaybackTrackDto` 생성에 `trackId` 추가(첫 인자).

- [ ] **Step 2: app 테스트 컴파일+실행**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*PlaybackCommandServiceTest" -q`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "test(party): PlaybackCommandServiceTest 커서 기반으로 rewire"
```

### Task 10: TrackRepositoryReorderIntegrationTest repurpose + #262 회귀

**Files:**
- Modify: `app/.../playlist/adapter/out/persistence/TrackRepositoryReorderIntegrationTest.java`

- [ ] **Step 1: rotatePlayedOrder 2케이스 삭제, shiftAllOrdersDown 통합 테스트로 교체**

기존 DB 셋업(@DataJpaTest 등) 패턴 유지하고:

```java
@Test
@DisplayName("shiftAllOrdersDown: 플레이리스트 전체 order_number 를 +1 한다")
void shiftAllOrdersDown_incrementsAll() {
    // given: 같은 playlist 에 order 1,2,3 트랙 저장
    // when
    trackRepository.shiftAllOrdersDown(playlistId);
    em.flush(); em.clear();
    // then: 2,3,4
    List<TrackData> tracks = trackRepository.findAll(); // 또는 playlist 별 조회
    assertThat(tracks).extracting(TrackData::getOrderNumber)
        .containsExactlyInAnyOrder(2, 3, 4);
}
```

클래스 javadoc 의 "#222 회귀 잠금(rotatePlayedOrder)" 설명은 "회전 제거(재설계)로 대체됨; #262 회귀는 §아래 reorder 안정성 테스트로 이관" 으로 갱신.

- [ ] **Step 2: #262 회귀 — 재생(커서 advance) 후 order 불변 + reorder 일관**

같은 파일 또는 `TrackCommandServiceTest` 에 (mock 레벨이면 후자) 추가:

```java
@Test
@DisplayName("#262 회귀: advancePlaybackCursor 는 order_number 를 변경하지 않는다")
void cursorAdvance_doesNotMutateOrder() {
    // given order 1,2,3
    // when: 커서를 여러 번 advance
    playlistRepository.updateLastPlayedTrackId(playlistId, t1.getId());
    playlistRepository.updateLastPlayedTrackId(playlistId, t2.getId());
    em.flush(); em.clear();
    // then: order_number 그대로 1,2,3
    assertThat(...).containsExactly(1,2,3);
}
```

- [ ] **Step 3: 실행**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*TrackRepositoryReorderIntegrationTest" -q`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "test: rotate IT를 shiftAllOrdersDown + #262 커서 불변 회귀로 교체"
```

### Task 11: 전체 품질 게이트

- [ ] **Step 1: 전체 빌드 + 테스트**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew build -q`
Expected: BUILD SUCCESSFUL (모든 모듈 테스트 GREEN)

- [ ] **Step 2: rotatePlayed 잔존 참조 0 확인** — `rotatePlayed`/`rotatePlayedOrder`/`peekOrderedTracks`(party port) 검색해 dead reference 없음 확인.

- [ ] **Step 3: 미세 커밋 정리(squash) — push 전** (메모리: push 전 논리 단위 통합). develop 기준 논리 단위로 정리.

---

## 검증 시나리오 (수동/통합 — 선택)

- 1곡 [A] 디제잉 → A 재생 → B 추가 → 다음 턴 B (제보 해결)
- 멀티 [A,B,C] → A,B 재생 후 D 추가 → 다음 사이클 C → D
- 디제잉 중 reorder → INVALID_TRACK_ORDER 팝업 없이 일관 동작 (#262)
- grab → GRABLIST tail 유지(동작 변경 없음)

## PR 노트 (작성 시 포함)
- `TrackRepositoryReorderIntegrationTest` 의 #222 회귀 의도는 "회전 제거"로 대체되며, #262 회귀(커서 advance가 order 불변)가 후속 커버리지임을 명시.
- grab(GRABLIST) tail 보존은 의도된 범위 한정 결정(스펙 §9).
- Closes #262.
