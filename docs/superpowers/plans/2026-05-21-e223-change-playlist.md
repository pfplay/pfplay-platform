# E/#223 — 대기 중 DJ 플레이리스트 변경 API (Change Playlist) 구현 계획

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DJ 대기열에서 대기 중인 본인의 디제잉 플레이리스트를 큐 순서를 보존하며 변경하는 PATCH 엔드포인트 신설. playlist 소유권 검증을 신규 PATCH 와 기존 enqueue 양쪽에 동반 보강.

**Architecture:** party 모듈 = 정책(허용범위·spec). playlist 모듈 = ownership query. 도메인 layer: `DjData.updatePlaylist` mutator + 신규 `DjChangePlaylistSpecification` + `DjEnqueueSpecification` arity 확장. application: `DjCommandService.changePlaylist` 신규 + `enqueueDj` ownership 동반. adapter/in/web: `PATCH .../dj-queue/me` + `ChangePlaylistRequest` DTO. WS broadcast 없음(본인만 결과 확인).

**Tech Stack:** Java 21 (Gradle 호출 시 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix 필수), Spring Boot, JPA, JUnit5, Mockito, AssertJ. Gradle 모듈: `playlist`, `app`(party).

**Spec:** `docs/superpowers/specs/2026-05-21-e223-change-playlist-design.md` (브랜치 `feature/e223-change-playlist`, commits `37ea9dd2` 초안 → `337f21d9` round-1 → `f0c21670` round-2 polish, reviewer 2-round Approved).

---

## File Structure

**playlist 모듈 (신규 1 / 수정 0)**
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/application/service/PlaylistQueryService.java` — `isOwnedBy(Long, UserId): boolean` 메서드 추가. `findByIdAndUserId(...)` 의 null/non-null 활용 또는 동등 효율의 exists 형태(plan 단계 결정 = null 체크 재사용, exists 신설 회피).
- Test: `playlist/src/test/java/com/pfplaybackend/api/playlist/application/service/PlaylistQueryServiceTest.java` — `isOwnedBy` 케이스 추가.

**party 모듈 (신규 4 / 수정 7)**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/exception/DjException.java` — DJ-005 `NOT_OWNED_PLAYLIST` · DJ-006 `CURRENT_DJ_CANNOT_CHANGE_PLAYLIST` enum 추가.
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/DjData.java` — `updatePlaylist(PlaylistId)` mutator 추가.
- Modify: `app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/DjDataTest.java` — `updatePlaylist` 케이스 추가.
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/specification/DjChangePlaylistSpecification.java` — 4 invariant validate.
- Create: `app/src/test/java/com/pfplaybackend/api/party/domain/specification/DjChangePlaylistSpecificationTest.java` — happy + 4 fail + 우선순위 잠금.
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/specification/DjEnqueueSpecification.java` — `validate` arity 3→4 (`isOwned` 추가).
- Modify: `app/src/test/java/com/pfplaybackend/api/party/domain/specification/DjEnqueueSpecificationTest.java` — 기존 3 case 픽스처 수정 + 신규 ownership 2 case.
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/port/out/PlaylistQueryPort.java` — `isOwnedBy(Long, Long): boolean` interface 메서드 추가.
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/external/PlaylistQueryAdapter.java` — `isOwnedBy` impl, playlist 모듈 service 위임.
- Create: `app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/payload/request/dj/ChangePlaylistRequest.java` — `@NotNull @Positive Long playlistId` (class + @Getter, `RegisterDjRequest` 컨벤션 정합).
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/DjCommandService.java` — `changePlaylist(PartyroomId, PlaylistId)` 신규 + `enqueueDj` ownership 보강(기존 `isEmptyPlaylist` 옆에 `isOwned` 계산 + spec 호출 시 4-ary 전달).
- Modify: `app/src/test/java/com/pfplaybackend/api/party/application/service/DjCommandServiceTest.java` — 기존 enqueue 4 case 의 `isOwnedBy=true` mock 보정 + ownership 회귀 1 + changePlaylist 7 신규.
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/DjCommandController.java` — `@PatchMapping("/{partyroomId}/dj-queue/me")` 신규.
- Modify: `app/src/test/java/com/pfplaybackend/api/party/adapter/in/web/DjCommandControllerTest.java` — changePlaylist 7 WebMvc 케이스 추가.
- Create: `app/src/test/java/com/pfplaybackend/api/party/application/service/DjCommandIntegrationTest.java` — happy / order 보존 / idempotent (3 case).

빌드 명령(Windows Git Bash):
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test :app:test :app:integrationTest
```
모듈 단위 우선 → 전체. 빌드 캐시 hit 최대화.

---

## Chunk 1: 도메인 기반 (DjException, DjData, Specification)

### Task 1: `DjException` DJ-005 / DJ-006 enum 확장

순수 enum 확장이라 별도 테스트 없음(다음 task 들이 이 코드를 인용).

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/exception/DjException.java`

- [ ] **Step 1: 신규 enum 항목 2개 추가**

`DjException.java` 의 `NOT_FOUND_DJ` 항목 뒤에 추가:

```java
NOT_OWNED_PLAYLIST("DJ-005", "본인 소유 플레이리스트가 아닙니다", ErrorType.FORBIDDEN),
CURRENT_DJ_CANNOT_CHANGE_PLAYLIST("DJ-006", "재생 중 DJ는 플레이리스트를 변경할 수 없습니다", ErrorType.CONFLICT);
```

> `NOT_FOUND_DJ` 줄 끝 세미콜론을 콤마로 바꿔야 함. 새 마지막 enum 만 세미콜론.

- [ ] **Step 2: 컴파일 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/exception/DjException.java
git commit -m "feat(e223): DjException DJ-005/006 추가 (NOT_OWNED_PLAYLIST, CURRENT_DJ_CANNOT_CHANGE_PLAYLIST) (#223)"
```

---

### Task 2: `DjData.updatePlaylist` mutator + Test

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/DjData.java`
- Test: `app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/DjDataTest.java`

- [ ] **Step 1: 실패 단위 테스트 작성**

`DjDataTest.java` 의 `updateOrderNumberUpdatesOrder` 테스트 뒤에 추가:

```java
@Test
@DisplayName("updatePlaylist — playlist 가 변경되고 orderNumber 는 보존된다")
void updatePlaylistKeepsOrderNumber() {
    // given
    PlaylistId oldId = new PlaylistId(10L);
    PlaylistId newId = new PlaylistId(99L);
    DjData dj = DjData.create(new PartyroomId(1L), oldId, new CrewId(20L), 3);

    // when
    dj.updatePlaylist(newId);

    // then
    assertThat(dj.getPlaylistId()).isEqualTo(newId);
    assertThat(dj.getOrderNumber()).isEqualTo(3);     // 보존
    assertThat(dj.getCrewId()).isEqualTo(new CrewId(20L)); // 보존
    assertThat(dj.getPartyroomId()).isEqualTo(new PartyroomId(1L)); // 보존
}
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.entity.data.DjDataTest.updatePlaylistKeepsOrderNumber"
```
Expected: FAIL — `cannot find symbol: method updatePlaylist(PlaylistId)`.

- [ ] **Step 3: 최소 구현**

`DjData.java` 의 `updateOrderNumber` 메서드 뒤에 추가:

```java
public void updatePlaylist(PlaylistId playlistId) {
    this.playlistId = playlistId;
}
```

- [ ] **Step 4: 통과 확인**

Run: 위 Step 2 와 동일.
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/DjData.java \
        app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/DjDataTest.java
git commit -m "feat(e223): DjData.updatePlaylist mutator — orderNumber 보존 (#223)"
```

---

### Task 3: `DjChangePlaylistSpecification` + Test

평가 순서 = queue-closed → currentDj → ownership(보안) → empty(콘텐츠). spec §3-2 정합.

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/specification/DjChangePlaylistSpecification.java`
- Create: `app/src/test/java/com/pfplaybackend/api/party/domain/specification/DjChangePlaylistSpecificationTest.java`

- [ ] **Step 1: 실패 단위 테스트 작성**

신규 파일 `DjChangePlaylistSpecificationTest.java`:

```java
package com.pfplaybackend.api.party.domain.specification;

import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DjChangePlaylistSpecificationTest {

    private DjChangePlaylistSpecification spec;

    @BeforeEach
    void setUp() {
        spec = new DjChangePlaylistSpecification();
    }

    private DjQueueData openQueue() { return DjQueueData.createFor(new PartyroomId(1L)); }
    private DjQueueData closedQueue() {
        DjQueueData q = DjQueueData.createFor(new PartyroomId(1L));
        q.close();
        return q;
    }

    @Test
    @DisplayName("정상 변경 — 예외 없음 (queue open, not current, owned, non-empty)")
    void validChange() {
        assertThatNoException().isThrownBy(() ->
            spec.validate(openQueue(), false, true, false));
    }

    @Test
    @DisplayName("큐 닫힘 — QUEUE_CLOSED (DJ-002)")
    void queueClosed() {
        assertThatThrownBy(() -> spec.validate(closedQueue(), false, true, false))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("재생 중 DJ — CURRENT_DJ_CANNOT_CHANGE_PLAYLIST (DJ-006)")
    void currentDjThrows() {
        assertThatThrownBy(() -> spec.validate(openQueue(), true, true, false))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("타인 소유 playlist — NOT_OWNED_PLAYLIST (DJ-005)")
    void notOwnedThrows() {
        assertThatThrownBy(() -> spec.validate(openQueue(), false, false, false))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("빈 playlist — EMPTY_PLAYLIST (DJ-003)")
    void emptyPlaylistThrows() {
        assertThatThrownBy(() -> spec.validate(openQueue(), false, true, true))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("평가 순서 잠금 — currentDj + not-owned 동시: CURRENT_DJ_CANNOT_CHANGE_PLAYLIST 가 먼저(ConflictException)")
    void currentDjBeatsOwnership() {
        assertThatThrownBy(() -> spec.validate(openQueue(), true, false, true))
            .isInstanceOf(ConflictException.class);  // DJ-006 (FORBIDDEN-vs-CONFLICT 의 ConflictException 으로 구분)
    }
}
```

> 평가 순서 잠금 테스트(case 6 `currentDjBeatsOwnership`)는 ForbiddenException vs ConflictException 의 **다른 예외 타입** 으로 우선순위를 직접 확인. DJ-006 = ConflictException, DJ-005 = ForbiddenException. 두 조건 동시 발화 시 ConflictException 가 잡히면 currentDj 가 먼저 평가됐음을 증명.
>
> **참고 (queue-closed vs ownership 평가 순서)**: 두 invariant 모두 `ForbiddenException` 을 던져 spec unit 레벨에서는 type 만으로 구분 불가. 의도는 controller WebMvc 테스트(Task 11)의 `jsonPath("$.error.errorCode").value("DJ-002"/"DJ-005")` 를 통해 잠금 — 별도 spec case 추가 불요(reviewer round-1 advisory 반영).

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.specification.DjChangePlaylistSpecificationTest"
```
Expected: FAIL — `cannot find symbol: class DjChangePlaylistSpecification`.

- [ ] **Step 3: 최소 구현**

신규 파일 `DjChangePlaylistSpecification.java`:

```java
package com.pfplaybackend.api.party.domain.specification;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.exception.DjException;

public class DjChangePlaylistSpecification {

    public void validate(DjQueueData djQueue, boolean isCurrentDj,
                         boolean isOwned, boolean isEmptyPlaylist) {
        djQueue.validateOpen();
        if (isCurrentDj)     throw ExceptionCreator.create(DjException.CURRENT_DJ_CANNOT_CHANGE_PLAYLIST);
        if (!isOwned)        throw ExceptionCreator.create(DjException.NOT_OWNED_PLAYLIST);
        if (isEmptyPlaylist) throw ExceptionCreator.create(DjException.EMPTY_PLAYLIST);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: 위 Step 2 와 동일.
Expected: 6 tests PASS (reviewer round-2 advisory 로 case 6 `queueClosedBeatsOwnership` 드롭, controller WebMvc 에서 errorCode 잠금).

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/specification/DjChangePlaylistSpecification.java \
        app/src/test/java/com/pfplaybackend/api/party/domain/specification/DjChangePlaylistSpecificationTest.java
git commit -m "feat(e223): DjChangePlaylistSpecification — queue-closed→currentDj→ownership→empty 평가순서 (#223)"
```

---

### Task 4: `DjEnqueueSpecification` arity 3→4 + Test 보강

**breaking signature change** — 같은 PR 안에서 caller(`DjCommandService.enqueueDj`) 와 test 파일 동시 갱신 필요. caller 갱신은 Chunk 3 Task 10 에서, 본 task 는 spec + test 만.

> **빌드 일시 빨강**: `DjEnqueueSpecification.validate` 시그니처가 바뀌면 `DjCommandService.enqueueDj` 의 기존 호출이 컴파일 에러. Task 4 commit 직후엔 `:app:compileJava` 가 실패한다. **이는 의도된 일시 빨강**(red-as-process-guard) — Chunk 3 Task 10 에서 caller 를 동반 갱신해 해소. Task 4 commit 메시지에 그 점 명시.

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/specification/DjEnqueueSpecification.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/party/domain/specification/DjEnqueueSpecificationTest.java`

- [ ] **Step 1: 기존 spec 테스트 픽스처 수정 + ownership 신규 테스트 추가**

`DjEnqueueSpecificationTest.java` 전체 교체:

```java
package com.pfplaybackend.api.party.domain.specification;

import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DjEnqueueSpecificationTest {

    private DjEnqueueSpecification spec;

    @BeforeEach
    void setUp() {
        spec = new DjEnqueueSpecification();
    }

    private DjQueueData openQueue() { return DjQueueData.createFor(new PartyroomId(1L)); }
    private DjQueueData closedQueue() {
        DjQueueData q = DjQueueData.createFor(new PartyroomId(1L));
        q.close();
        return q;
    }

    @Test
    @DisplayName("정상 DJ 등록 — 예외 없음")
    void validEnqueue() {
        assertThatNoException().isThrownBy(() ->
                spec.validate(openQueue(), false, true, false));
    }

    @Test
    @DisplayName("큐 닫힘 — QUEUE_CLOSED (DJ-002)")
    void queueClosed() {
        assertThatThrownBy(() -> spec.validate(closedQueue(), false, true, false))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("타인 소유 playlist — NOT_OWNED_PLAYLIST (DJ-005, 신규)")
    void notOwnedThrows() {
        assertThatThrownBy(() -> spec.validate(openQueue(), false, false, false))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("빈 플레이리스트 — EMPTY_PLAYLIST (DJ-003)")
    void emptyPlaylist() {
        assertThatThrownBy(() -> spec.validate(openQueue(), false, true, true))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("이미 등록된 DJ — ALREADY_REGISTERED (DJ-001)")
    void alreadyRegistered() {
        assertThatThrownBy(() -> spec.validate(openQueue(), true, true, false))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("평가 순서 잠금 — already-registered + not-owned 동시: NOT_OWNED_PLAYLIST 가 먼저 (보안 우선)")
    void ownershipBeatsAlreadyRegistered() {
        // 둘 다 던지지만 not-owned 가 먼저 평가되면 ForbiddenException(DJ-005);
        // already-registered 가 먼저면 ConflictException(DJ-001).
        // spec §3-2 = ownership 먼저 → ForbiddenException 가 잡혀야 함.
        assertThatThrownBy(() -> spec.validate(openQueue(), true, false, false))
                .isInstanceOf(ForbiddenException.class);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.specification.DjEnqueueSpecificationTest"
```
Expected: **컴파일 실패** — `validate(...)` 3-arg call 인 production 코드(`DjEnqueueSpecification.validate(...)`)가 여전히 3-arg signature 이라 test 의 4-arg 호출 컴파일 에러. 또는 test 가 컴파일된 뒤 production validate 가 무관계 호출에서 컴파일 에러일 수 있음(test/main 동시 컴파일).

- [ ] **Step 3: spec 구현 변경 (arity 3→4)**

`DjEnqueueSpecification.java` 전체 교체:

```java
package com.pfplaybackend.api.party.domain.specification;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.exception.DjException;

public class DjEnqueueSpecification {

    public void validate(DjQueueData djQueue, boolean isAlreadyRegistered,
                         boolean isOwned, boolean isEmptyPlaylist) {
        djQueue.validateOpen();
        if (!isOwned)        throw ExceptionCreator.create(DjException.NOT_OWNED_PLAYLIST);
        if (isEmptyPlaylist) throw ExceptionCreator.create(DjException.EMPTY_PLAYLIST);
        if (isAlreadyRegistered) throw ExceptionCreator.create(DjException.ALREADY_REGISTERED);
    }
}
```

- [ ] **Step 4: 테스트 PASS / production caller 일시 빨강 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.specification.DjEnqueueSpecificationTest" 2>&1 | tail -30
```
Expected: **build halts at `:app:compileJava` with `DjCommandService.enqueueDj` 3-arg call error — `DjEnqueueSpecificationTest` does not execute until Task 10 unblocks the caller.** Chunk 1~2 진행은 가능(playlist 모듈·party port interface·DTO 는 `app/compileJava` 와 독립). 본 spec test 의 PASS 시각은 **Task 10 Step 4**.

- [ ] **Step 5: 커밋 (caller 일시 빨강 명시)**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/specification/DjEnqueueSpecification.java \
        app/src/test/java/com/pfplaybackend/api/party/domain/specification/DjEnqueueSpecificationTest.java
git commit -m "$(cat <<'EOF'
feat(e223): DjEnqueueSpecification ownership 보강 (arity 3→4, 평가순서 ownership-first) (#223)

DJ-005 NOT_OWNED_PLAYLIST 추가. spec §3-2.
※ DjCommandService.enqueueDj caller 의 3-arg 호출은 Chunk 3 Task 10 에서 동반 갱신 — 임시 컴파일 빨강(red-as-process-guard).
EOF
)"
```

---

## Chunk 2: Port 확장 (PlaylistQueryPort.isOwnedBy)

playlist 모듈 service 신규 메서드 → party 모듈 port interface 확장 → party adapter 위임. 의존 방향상 playlist 먼저, party 다음.

### Task 5: `PlaylistQueryService.isOwnedBy(Long, UserId)` 신규 + Test

**Files:**
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/application/service/PlaylistQueryService.java`
- Modify: `playlist/src/test/java/com/pfplaybackend/api/playlist/application/service/PlaylistQueryServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`PlaylistQueryServiceTest.java` 마지막에 추가 (기존 `userId` 필드·setUp 재사용):

```java
@Test
@DisplayName("isOwnedBy — playlist 가 본인 소유면 true")
void isOwnedByReturnsTrueWhenOwned() {
    // given
    PlaylistSummaryDto owned = new PlaylistSummaryDto(99L, "My Playlist", 5, PlaylistType.PLAYLIST, 1L);
    when(queryPort.findByIdAndUserId(99L, userId)).thenReturn(owned);

    // when
    boolean result = playlistQueryService.isOwnedBy(99L, userId);

    // then
    assertThat(result).isTrue();
}

@Test
@DisplayName("isOwnedBy — playlist 가 타인 소유거나 미존재면 false")
void isOwnedByReturnsFalseWhenNotOwned() {
    // given (port 는 null 또는 절대 null 반환)
    when(queryPort.findByIdAndUserId(99L, userId)).thenReturn(null);

    // when
    boolean result = playlistQueryService.isOwnedBy(99L, userId);

    // then
    assertThat(result).isFalse();
}
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test --tests "com.pfplaybackend.api.playlist.application.service.PlaylistQueryServiceTest"
```
Expected: FAIL — `cannot find symbol: method isOwnedBy(Long, UserId)`.

- [ ] **Step 3: 최소 구현**

`PlaylistQueryService.java` 의 `getPlaylist` 메서드 뒤에 추가:

```java
@Transactional(readOnly = true)
public boolean isOwnedBy(Long playlistId, UserId userId) {
    return queryPort.findByIdAndUserId(playlistId, userId) != null;
}
```

import 추가: `import com.pfplaybackend.api.common.domain.value.UserId;` (이미 사용 중이면 무시).

- [ ] **Step 4: 통과 확인**

Run: 위 Step 2 와 동일.
Expected: 기존 + 신규 2 PASS.

> 만약 `findByIdAndUserId` 가 미존재 시 예외를 던지는 형태(spec §3-3 의 "또는 동등 효율의 exists 쿼리" 대체 옵션 — null 반환 시 PASS, 예외 시 FAIL with Mockito stub)라면 try-catch 또는 `Optional`-wrap 으로 재시도. **구현 전에 1단계 빠른 확인**: `PlaylistAggregateAdapter.findByIdAndUserId` 구현 보고 null-return semantic 확정. (코드 보면 `playlistRepository.findByIdAndUserId` → `PlaylistRepositoryImpl` → 미존재 시 null 반환이 일반적 QueryDSL 패턴.)

- [ ] **Step 5: 커밋**

```bash
git add playlist/src/main/java/com/pfplaybackend/api/playlist/application/service/PlaylistQueryService.java \
        playlist/src/test/java/com/pfplaybackend/api/playlist/application/service/PlaylistQueryServiceTest.java
git commit -m "feat(e223): PlaylistQueryService.isOwnedBy(Long, UserId) — playlist 소유권 boolean (#223)"
```

---

### Task 6: party `PlaylistQueryPort.isOwnedBy(Long, Long)` interface 확장

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/port/out/PlaylistQueryPort.java`

interface signature 만 추가. 구현은 Task 7. 테스트 없음(interface).

- [ ] **Step 1: interface 메서드 추가**

```java
public interface PlaylistQueryPort {
    boolean isEmptyPlaylist(Long playlistId);
    boolean isOwnedBy(Long playlistId, Long userId);  // 신규
}
```

- [ ] **Step 2: 컴파일 일시 빨강 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava 2>&1 | tail -20
```
Expected: **빨강** — `PlaylistQueryAdapter` 가 새 메서드 unimplement → compile error. Task 7 에서 해소.

- [ ] **Step 3: 커밋 (일시 빨강 명시)**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/application/port/out/PlaylistQueryPort.java
git commit -m "feat(e223): party PlaylistQueryPort.isOwnedBy 시그니처 (Task 7 에서 adapter 구현 동반) (#223)"
```

---

### Task 7: party `PlaylistQueryAdapter.isOwnedBy` 구현 (UserId VO wrap)

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/external/PlaylistQueryAdapter.java`

- [ ] **Step 1: adapter impl 추가**

`PlaylistQueryAdapter.java` 전체 교체:

```java
package com.pfplaybackend.api.party.adapter.out.external;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.port.out.PlaylistQueryPort;
import com.pfplaybackend.api.playlist.application.service.PlaylistQueryService;
import com.pfplaybackend.api.playlist.application.service.TrackQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlaylistQueryAdapter implements PlaylistQueryPort {
    private final TrackQueryService trackQueryService;
    private final PlaylistQueryService playlistQueryService;

    @Override
    public boolean isEmptyPlaylist(Long playlistId) {
        return trackQueryService.isEmptyPlaylist(playlistId);
    }

    @Override
    public boolean isOwnedBy(Long playlistId, Long userId) {
        return playlistQueryService.isOwnedBy(playlistId, new UserId(userId));
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/adapter/out/external/PlaylistQueryAdapter.java
git commit -m "feat(e223): party PlaylistQueryAdapter.isOwnedBy — playlist 모듈 위임 + UserId VO wrap (#223)"
```

---

## Chunk 3: 서비스 + 컨트롤러 + IT

### Task 8: `ChangePlaylistRequest` DTO

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/payload/request/dj/ChangePlaylistRequest.java`

`RegisterDjRequest` 컨벤션(class + @Getter + bean validation) 정합.

- [ ] **Step 1: DTO 작성**

```java
package com.pfplaybackend.api.party.adapter.in.web.payload.request.dj;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;

@Getter
public class ChangePlaylistRequest {
    @NotNull(message = "playlistId is required.")
    @Positive(message = "playlistId must be positive.")
    private Long playlistId;
}
```

- [ ] **Step 2: 컴파일 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/payload/request/dj/ChangePlaylistRequest.java
git commit -m "feat(e223): ChangePlaylistRequest DTO — @NotNull @Positive playlistId (#223)"
```

---

### Task 9: `DjCommandService.changePlaylist` 신규 (TDD red→green)

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/DjCommandService.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/party/application/service/DjCommandServiceTest.java`

- [ ] **Step 1: 실패 단위 테스트 작성 (7 case)**

`DjCommandServiceTest.java` 의 `dequeueDjNotCurrentDjNoSkip` 메서드 뒤에 추가:

```java
@Test
@DisplayName("changePlaylist — 큐에 없으면 NOT_FOUND_DJ")
void changePlaylistNotInQueueThrows() {
    // given
    PartyroomData partyroom = PartyroomData.builder()
            .id(partyroomId.getId()).partyroomId(partyroomId).build();
    PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
    DjQueueData djQueue = DjQueueData.createFor(partyroomId);
    CrewData crew = CrewData.builder()
            .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
    when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
    when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
    when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.empty());

    // when & then
    assertThatThrownBy(() -> djCommandService.changePlaylist(partyroomId, new PlaylistId(200L)))
            .isInstanceOf(com.pfplaybackend.api.common.exception.http.NotFoundException.class);
}

@Test
@DisplayName("changePlaylist — 재생 중 DJ 면 CURRENT_DJ_CANNOT_CHANGE_PLAYLIST")
void changePlaylistCurrentDjThrows() {
    // given
    PartyroomData partyroom = PartyroomData.builder()
            .id(partyroomId.getId()).partyroomId(partyroomId).build();
    PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
    playbackState.activate(null, new CrewId(1L));  // 본인이 current dj
    DjQueueData djQueue = DjQueueData.createFor(partyroomId);
    CrewData crew = CrewData.builder()
            .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
    DjData me = DjData.create(partyroomId, playlistId, new CrewId(1L), 1);

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
    when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
    when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
    when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.of(me));

    // when & then
    assertThatThrownBy(() -> djCommandService.changePlaylist(partyroomId, new PlaylistId(200L)))
            .isInstanceOf(ConflictException.class);
}

@Test
@DisplayName("changePlaylist — 타인 소유 playlist 면 NOT_OWNED_PLAYLIST")
void changePlaylistNotOwnedThrows() {
    // given — playback inactive, queue open, me 존재, isOwnedBy=false
    PartyroomData partyroom = PartyroomData.builder()
            .id(partyroomId.getId()).partyroomId(partyroomId).build();
    PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
    DjQueueData djQueue = DjQueueData.createFor(partyroomId);
    CrewData crew = CrewData.builder()
            .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
    DjData me = DjData.create(partyroomId, playlistId, new CrewId(1L), 1);

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
    when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
    when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
    when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.of(me));
    when(playlistQueryPort.isOwnedBy(200L, userId.getUid())).thenReturn(false);

    // when & then
    assertThatThrownBy(() -> djCommandService.changePlaylist(partyroomId, new PlaylistId(200L)))
            .isInstanceOf(ForbiddenException.class);
}

@Test
@DisplayName("changePlaylist — 미존재 playlistId 도 isOwnedBy=false 로 NOT_OWNED_PLAYLIST (security boundary)")
void changePlaylistNonExistentPlaylistThrows() {
    // identical wiring to notOwned case — semantic clarification only
    PartyroomData partyroom = PartyroomData.builder()
            .id(partyroomId.getId()).partyroomId(partyroomId).build();
    PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
    DjQueueData djQueue = DjQueueData.createFor(partyroomId);
    CrewData crew = CrewData.builder()
            .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
    DjData me = DjData.create(partyroomId, playlistId, new CrewId(1L), 1);

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
    when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
    when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
    when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.of(me));
    when(playlistQueryPort.isOwnedBy(999_999L, userId.getUid())).thenReturn(false);

    assertThatThrownBy(() -> djCommandService.changePlaylist(partyroomId, new PlaylistId(999_999L)))
            .isInstanceOf(ForbiddenException.class);
}

@Test
@DisplayName("changePlaylist — 빈 playlist 면 EMPTY_PLAYLIST")
void changePlaylistEmptyThrows() {
    PartyroomData partyroom = PartyroomData.builder()
            .id(partyroomId.getId()).partyroomId(partyroomId).build();
    PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
    DjQueueData djQueue = DjQueueData.createFor(partyroomId);
    CrewData crew = CrewData.builder()
            .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
    DjData me = DjData.create(partyroomId, playlistId, new CrewId(1L), 1);

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
    when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
    when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
    when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.of(me));
    when(playlistQueryPort.isOwnedBy(200L, userId.getUid())).thenReturn(true);
    when(playlistQueryPort.isEmptyPlaylist(200L)).thenReturn(true);

    assertThatThrownBy(() -> djCommandService.changePlaylist(partyroomId, new PlaylistId(200L)))
            .isInstanceOf(ForbiddenException.class);
}

@Test
@DisplayName("changePlaylist — happy path: me.playlistId 갱신 + 이벤트 발행 0회")
void changePlaylistHappy() {
    PartyroomData partyroom = PartyroomData.builder()
            .id(partyroomId.getId()).partyroomId(partyroomId).build();
    PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
    DjQueueData djQueue = DjQueueData.createFor(partyroomId);
    CrewData crew = CrewData.builder()
            .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
    DjData me = DjData.create(partyroomId, playlistId, new CrewId(1L), 1);  // old = 100L

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
    when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
    when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
    when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.of(me));
    when(playlistQueryPort.isOwnedBy(200L, userId.getUid())).thenReturn(true);
    when(playlistQueryPort.isEmptyPlaylist(200L)).thenReturn(false);

    // when
    djCommandService.changePlaylist(partyroomId, new PlaylistId(200L));

    // then — me 의 playlistId 가 새 값으로, orderNumber 보존
    assertThat(me.getPlaylistId()).isEqualTo(new PlaylistId(200L));
    assertThat(me.getOrderNumber()).isEqualTo(1);
    // 이벤트 발행 0회 (WS broadcast 없음, spec §2-2)
    verify(eventPublisher, never()).publishEvent(any());
}

@Test
@DisplayName("changePlaylist — idempotent: 같은 playlistId 입력 예외 없음")
void changePlaylistIdempotent() {
    PartyroomData partyroom = PartyroomData.builder()
            .id(partyroomId.getId()).partyroomId(partyroomId).build();
    PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
    DjQueueData djQueue = DjQueueData.createFor(partyroomId);
    CrewData crew = CrewData.builder()
            .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
    DjData me = DjData.create(partyroomId, playlistId, new CrewId(1L), 1);  // old = playlistId = 100L

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
    when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
    when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
    when(aggregatePort.findDj(partyroomId, new CrewId(1L))).thenReturn(java.util.Optional.of(me));
    when(playlistQueryPort.isOwnedBy(playlistId.getId(), userId.getUid())).thenReturn(true);
    when(playlistQueryPort.isEmptyPlaylist(playlistId.getId())).thenReturn(false);

    // when — 같은 playlistId
    djCommandService.changePlaylist(partyroomId, playlistId);

    // then — 예외 없음, me 무변
    assertThat(me.getPlaylistId()).isEqualTo(playlistId);
}
```

import 추가:
```java
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import static org.assertj.core.api.Assertions.assertThat;
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.DjCommandServiceTest"
```
Expected: 7 신규 case 가 **컴파일 실패** — `djCommandService.changePlaylist` 미존재.

- [ ] **Step 3: `DjCommandService.changePlaylist` 구현**

`DjCommandService.java` 의 `dequeueDj(PartyroomId, DjId)` 메서드 뒤에 추가:

```java
@Transactional
public void changePlaylist(PartyroomId partyroomId, PlaylistId newPlaylistId) {
    AuthContext authContext = ThreadLocalContext.getAuthContext();
    Long userId = authContext.getUserId().getUid();
    log.info("[changePlaylist] ENTER - requestId={}, partyroomId={}, userId={}, newPlaylistId={}",
            RequestIdInterceptor.current(), partyroomId.getId(), userId, newPlaylistId.getId());

    PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);
    PartyroomPlaybackData playback = aggregatePort.findPlaybackState(partyroomId);
    DjQueueData djQueue = aggregatePort.findDjQueueState(partyroomId);

    CrewData crew = partyroomQueryService.getCrewOrThrow(partyroomId, authContext.getUserId());
    CrewId crewId = new CrewId(crew.getId());

    DjData me = aggregatePort.findDj(partyroomId, crewId)
            .orElseThrow(() -> ExceptionCreator.create(DjException.NOT_FOUND_DJ));

    boolean isCurrentDj     = playback.isActivated() && playback.isCurrentDj(crewId);
    boolean isOwned         = playlistQueryPort.isOwnedBy(newPlaylistId.getId(), userId);
    boolean isEmptyPlaylist = playlistQueryPort.isEmptyPlaylist(newPlaylistId.getId());

    new DjChangePlaylistSpecification().validate(djQueue, isCurrentDj, isOwned, isEmptyPlaylist);

    Long oldPlaylistId = me.getPlaylistId() != null ? me.getPlaylistId().getId() : null;
    me.updatePlaylist(newPlaylistId);
    // ※ saveDj 명시 호출 안 함 — me 는 @Transactional 컨텍스트의 managed entity,
    //   JPA dirty check 가 commit 시 UPDATE 자동 발행. spec §3-4

    log.info("[changePlaylist] OK - requestId={}, partyroomId={}, crewId={}, oldPlaylistId={}, newPlaylistId={}",
            RequestIdInterceptor.current(), partyroomId.getId(), crewId.getId(), oldPlaylistId, newPlaylistId.getId());
    // 도메인 이벤트 발행 없음 — WS broadcast 불필요(spec §2-2)
}
```

import 추가:
```java
import com.pfplaybackend.api.party.domain.specification.DjChangePlaylistSpecification;
```

> **로그 레벨 INFO**: party.application.service 는 [[project_observability_b1b2_merged]] / observability A4 정책상 INFO pin-allowed 비즈니스 패키지. state-mutating command 의 ENTER / OK 두 라인은 의도된 observability surface.

- [ ] **Step 4: 컴파일 빨강 carry-over 확인**

Run: 위 Step 2 와 동일.
Expected: **build halts at `:app:compileJava`** — `DjCommandService.enqueueDj` 가 여전히 `DjEnqueueSpecification.validate` 의 옛 3-arg signature 를 호출 (Task 4 의 일시 빨강 carry-over). 본 task 의 changePlaylist 7 신규 테스트는 **이번 step 에서는 실행되지 않음**. PASS 시각 = **Task 10 Step 4** (caller 4-arg 정합 직후 changePlaylist + enqueue 모두 GREEN).

> 본 task 의 Step 3 까지 production·test 코드는 모두 정확. 빨강의 원천은 Task 4 의 caller 미정합 단 하나 — Task 10 Step 3 가 그 caller 호출 라인 1줄만 4-arg 로 교체하면 동시 해소.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/application/service/DjCommandService.java \
        app/src/test/java/com/pfplaybackend/api/party/application/service/DjCommandServiceTest.java
git commit -m "feat(e223): DjCommandService.changePlaylist — happy/4 fail/idempotent (Task 4/10 caller 정합 대기) (#223)"
```

---

### Task 10: `DjCommandService.enqueueDj` ownership 동반 보강 + 회귀 가드

Chunk 1 Task 4 의 일시 빨강 해소 + 기존 4 case 픽스처에 `isOwnedBy=true` mock 보정 + 신규 ownership 회귀 1.

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/DjCommandService.java` (enqueueDj 내부)
- Modify: `app/src/test/java/com/pfplaybackend/api/party/application/service/DjCommandServiceTest.java`

- [ ] **Step 1: enqueueDj 픽스처 sweep + ownership 회귀 신규**

기존 enqueue 4 case (`enqueueDjQueueClosedThrows` 제외 — 큐 닫힘은 validateOpen 단계서 throw, ownership mock 도달 안 함; `lenient()` 안 쓰면 unnecessary stubbing 에러)에 한 줄씩 추가:

```java
// 각 case 의 stubbing 블록에 추가:
when(playlistQueryPort.isOwnedBy(playlistId.getId(), userId.getUid())).thenReturn(true);
```

추가 위치:
- `enqueueDjAlreadyRegisteredThrows`: `when(playlistQueryPort.isEmptyPlaylist...)` 옆
- `enqueueDjEmptyPlaylistThrows`: `when(playlistQueryPort.isEmptyPlaylist...)` 옆 (이 케이스는 isOwned=true 가 정상 흐름, empty 가 throw 원인)
- `enqueueDjFirstDjStartsPlayback`: `when(playlistQueryPort.isEmptyPlaylist...)` 옆

> `enqueueDjQueueClosedThrows` 는 queue.close() 가 먼저 throw → 후속 stub 실행 안 됨. 추가 안 함.

신규 ownership 회귀 테스트 (4 case 뒤에 추가):

```java
@Test
@DisplayName("enqueueDj — 타인 소유 playlist 면 NOT_OWNED_PLAYLIST (Task 4/10 회귀 가드)")
void enqueueDjNotOwnedThrows() {
    PartyroomData partyroom = PartyroomData.builder()
            .id(partyroomId.getId()).partyroomId(partyroomId).build();
    PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
    DjQueueData djQueue = DjQueueData.createFor(partyroomId);
    CrewData crew = CrewData.builder()
            .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
    when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
    when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
    when(aggregatePort.isDjRegistered(partyroomId, new CrewId(1L))).thenReturn(false);
    when(playlistQueryPort.isOwnedBy(playlistId.getId(), userId.getUid())).thenReturn(false);  // 핵심
    // empty 호출은 ownership-first 평가로 안 도달

    assertThatThrownBy(() -> djCommandService.enqueueDj(partyroomId, playlistId))
            .isInstanceOf(ForbiddenException.class);
}
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.DjCommandServiceTest"
```
Expected:
- **컴파일 실패**: `enqueueDj` 가 `DjEnqueueSpecification.validate(djQueue, isAlreadyRegistered, isEmptyPlaylist)` 3-arg 호출 → arity 4 불일치.

- [ ] **Step 3: enqueueDj caller 정합 (3→4 arg)**

`DjCommandService.enqueueDj` 의 spec validate 호출 라인 교체:

```java
// before:
// new DjEnqueueSpecification().validate(djQueue, isAlreadyRegistered, isEmptyPlaylist);

// after:
boolean isOwned = playlistQueryPort.isOwnedBy(playlistId.getId(), authContext.getUserId().getUid());
boolean isEmptyPlaylist = playlistQueryPort.isEmptyPlaylist(playlistId.getId());
new DjEnqueueSpecification().validate(djQueue, isAlreadyRegistered, isOwned, isEmptyPlaylist);
```

> 기존 `boolean isEmptyPlaylist = playlistQueryPort.isEmptyPlaylist(playlistId.getId());` 라인은 위 블록의 `isEmptyPlaylist` 로 흡수(중복 제거). `boolean isAlreadyRegistered = aggregatePort.isDjRegistered(...)` 는 그대로.

- [ ] **Step 4: 통과 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.DjCommandServiceTest" 2>&1 | tail -30
```
Expected: 전 case PASS (changePlaylist 7 + enqueue 4 기존 + enqueue 회귀 1 + dequeue 1 = 13+).

- [ ] **Step 5: party 모듈 전체 단위 테스트 회귀 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL — 다른 테스트(특히 DjEnqueueSpecificationTest 6 + DjChangePlaylistSpecificationTest 7 + DjDataTest 3) 도 GREEN.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/application/service/DjCommandService.java \
        app/src/test/java/com/pfplaybackend/api/party/application/service/DjCommandServiceTest.java
git commit -m "feat(e223): enqueueDj ownership 동반 보강 (spec arity 3→4 정합) + 회귀 가드 (#223)"
```

---

### Task 11: `DjCommandController.changePlaylist` WebMvc (TDD red→green)

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/DjCommandController.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/party/adapter/in/web/DjCommandControllerTest.java`

- [ ] **Step 1: 실패 WebMvc 테스트 작성 (7 case)**

`DjCommandControllerTest.java` 의 `enqueueDjUnauthenticatedReturns401` 뒤에 추가 (import 는 patch / status, ForbiddenException 등 보강):

```java
@Test
@DisplayName("changePlaylist — 204 No Content")
void changePlaylistReturns204() throws Exception {
    String body = """
            { "playlistId": 99 }
            """;
    // service 는 void → stub 안 함. 호출 검증만.

    mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                    .with(jwt().authorities(() -> "ROLE_MEMBER"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isNoContent());
}

@Test
@DisplayName("changePlaylist — playlistId null 이면 400")
void changePlaylistNullPlaylistIdReturns400() throws Exception {
    mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                    .with(jwt().authorities(() -> "ROLE_MEMBER"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isBadRequest());
}

@Test
@DisplayName("changePlaylist — playlistId 0 이면 400")
void changePlaylistZeroPlaylistIdReturns400() throws Exception {
    mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                    .with(jwt().authorities(() -> "ROLE_MEMBER"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"playlistId\": 0}"))
            .andExpect(status().isBadRequest());
}

@Test
@DisplayName("changePlaylist — 인증 없으면 401")
void changePlaylistUnauthenticatedReturns401() throws Exception {
    mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"playlistId\": 99}"))
            .andExpect(status().isUnauthorized());
}

@Test
@DisplayName("changePlaylist — DJ-004 NOT_FOUND_DJ → 404")
void changePlaylistNotFoundReturns404() throws Exception {
    org.mockito.Mockito.doThrow(com.pfplaybackend.api.common.exception.ExceptionCreator.create(
                    com.pfplaybackend.api.party.domain.exception.DjException.NOT_FOUND_DJ))
            .when(djCommandService).changePlaylist(any(), any());

    mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                    .with(jwt().authorities(() -> "ROLE_MEMBER"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"playlistId\": 99}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.errorCode").value("DJ-004"));
}

@Test
@DisplayName("changePlaylist — DJ-005 NOT_OWNED_PLAYLIST → 403 (미존재 playlistId 포함)")
void changePlaylistNotOwnedReturns403() throws Exception {
    org.mockito.Mockito.doThrow(com.pfplaybackend.api.common.exception.ExceptionCreator.create(
                    com.pfplaybackend.api.party.domain.exception.DjException.NOT_OWNED_PLAYLIST))
            .when(djCommandService).changePlaylist(any(), any());

    mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                    .with(jwt().authorities(() -> "ROLE_MEMBER"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"playlistId\": 99}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.errorCode").value("DJ-005"));
}

@Test
@DisplayName("changePlaylist — DJ-003 EMPTY_PLAYLIST → 403")
void changePlaylistEmptyReturns403() throws Exception {
    org.mockito.Mockito.doThrow(com.pfplaybackend.api.common.exception.ExceptionCreator.create(
                    com.pfplaybackend.api.party.domain.exception.DjException.EMPTY_PLAYLIST))
            .when(djCommandService).changePlaylist(any(), any());

    mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                    .with(jwt().authorities(() -> "ROLE_MEMBER"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"playlistId\": 99}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.errorCode").value("DJ-003"));
}

@Test
@DisplayName("changePlaylist — DJ-006 CURRENT_DJ_CANNOT_CHANGE_PLAYLIST → 409")
void changePlaylistCurrentDjReturns409() throws Exception {
    org.mockito.Mockito.doThrow(com.pfplaybackend.api.common.exception.ExceptionCreator.create(
                    com.pfplaybackend.api.party.domain.exception.DjException.CURRENT_DJ_CANNOT_CHANGE_PLAYLIST))
            .when(djCommandService).changePlaylist(any(), any());

    mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                    .with(jwt().authorities(() -> "ROLE_MEMBER"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"playlistId\": 99}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.errorCode").value("DJ-006"));
}
```

import 추가: `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;`

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.adapter.in.web.DjCommandControllerTest"
```
Expected: 8 신규 case 가 **405 Method Not Allowed** (PATCH 핸들러 미존재) — Spring MVC 기본 동작.

- [ ] **Step 3: controller 핸들러 추가**

`DjCommandController.java` 의 `dequeueDj(Long, Long)` 메서드 뒤에 추가:

```java
@Operation(summary = "본인 DJ 플레이리스트 변경",
           description = "DJ 대기열에 등록된 본인의 디제잉 플레이리스트를 변경합니다. 큐 순서는 보존되며, 재생 중 DJ 는 변경할 수 없습니다.")
@ApiResponse(responseCode = "204", description = "변경 성공")
@SecurityRequirement(name = "cookieAuth")
@ApiErrorCodes({DjException.class})
@PatchMapping("/{partyroomId}/dj-queue/me")
@PreAuthorize("hasRole('MEMBER')")
public ResponseEntity<Void> changePlaylist(
        @Parameter(description = "파티룸 ID") @PathVariable Long partyroomId,
        @Valid @RequestBody ChangePlaylistRequest request) {
    djCommandService.changePlaylist(new PartyroomId(partyroomId), new PlaylistId(request.getPlaylistId()));
    return ResponseEntity.noContent().build();
}
```

import 추가:
```java
import com.pfplaybackend.api.party.adapter.in.web.payload.request.dj.ChangePlaylistRequest;
```

- [ ] **Step 4: 통과 확인**

Run: 위 Step 2 와 동일.
Expected: 전 case PASS.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/adapter/in/web/DjCommandController.java \
        app/src/test/java/com/pfplaybackend/api/party/adapter/in/web/DjCommandControllerTest.java
git commit -m "feat(e223): DjCommandController PATCH /dj-queue/me — changePlaylist 7 WebMvc (#223)"
```

---

### Task 12: `DjCommandIntegrationTest.changePlaylist` (3 IT case)

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/party/application/service/DjCommandIntegrationTest.java`

기존 party 모듈 IT 들의 패턴을 따른다(Flyway + H2 또는 Testcontainers, `@SpringBootTest` + `@Transactional`/`@AfterEach` cleanup). 작업 시작 전 기존 IT 한 개를 열어 base 설정·픽스처 헬퍼를 확인하고 그대로 재사용. **신규 베이스 클래스/헬퍼 작성 금지** — 회귀잠금 IT 의 패턴(예: `PartyroomAccessCommandServiceRaceIT`, `DjQueueGraceIntegrationTest` 등)을 모방.

- [ ] **Step 1: 기존 IT 패턴 파악 + 선택한 base 파일 기록**

먼저 IT 디렉토리 구조 확인:
```bash
git ls-files "app/src/integrationTest/**/*.java" | head -30
git ls-files "app/src/test/**/*IntegrationTest.java" | head -30
```

선택한 base IT 파일명을 plan-execution 로그에 명시(예: `Selected base IT pattern: <path>`). 후보:
- `app/src/integrationTest/java/.../DjQueueGraceIntegrationTest.java`
- `app/src/integrationTest/java/.../PartyroomAccessCommandServiceRaceIT.java`
- `app/src/integrationTest/java/.../PartyroomCounterListenerIT.java`

(보통 `@SpringBootTest` + `@ActiveProfiles("integration-test")` + Testcontainers MySQL.)

- [ ] **Step 2: 실패 IT 작성 (3 case)**

```java
package com.pfplaybackend.api.party.application.service;

// imports — 기존 IT 패턴 따름 (@SpringBootTest, @Autowired DjCommandService, AuthContext setup, Flyway/JPA)

class DjCommandIntegrationTest /* extends 적절한 base, 또는 @SpringBootTest 직접 */ {

    @Test
    @DisplayName("changePlaylist IT — happy: playlist_id 갱신, order_number 보존")
    void changePlaylistHappyDbUpdated() {
        // given: 파티룸 + 본인 user + 본인 playlist A + B 두 개 시드, enqueue(A)
        // when: changePlaylist(B)
        // then: DB 재조회 DjData.playlistId == B, orderNumber 무변
    }

    @Test
    @DisplayName("changePlaylist IT — invariant: 본인 변경이 다른 DJ orderNumber 무영향")
    void changePlaylistOtherDjOrderPreserved() {
        // given: DJ1(orderNumber=1, playlist A1) + DJ2(orderNumber=2, playlist A2) enqueue
        // when: DJ2.changePlaylist(B2)
        // then: DJ1.orderNumber=1·playlistId=A1 무변, DJ2.orderNumber=2·playlistId=B2
    }

    @Test
    @DisplayName("changePlaylist IT — idempotent: 같은 playlistId → 최종 row 상태 무변")
    void changePlaylistIdempotentNoLogicalChange() {
        // given: enqueue(A)
        // when: changePlaylist(A)  ← same id
        // then: DB 재조회 DjData.playlistId == A, orderNumber 무변, 예외 0
    }
}
```

> 실제 시드/조회 코드는 base IT 의 헬퍼(`seedPartyroom`, `seedPlaylistWithTracks` 등 — 정확한 이름은 base IT 보고 채움) 와 `@Autowired` 의존 주입(`DjCommandService`, `PlaylistRepository`, `partyroomAggregateAdapter` 등)로 채운다. **신규 헬퍼 만들지 말 것**.

- [ ] **Step 3: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "com.pfplaybackend.api.party.application.service.DjCommandIntegrationTest" 2>&1 | tail -30
```
Expected: 시드 단계 컴파일/runtime error — base IT 의 헬퍼 시그니처 정확히 일치 안 함.

- [ ] **Step 4: 헬퍼 시그니처 정합 + 실 DB 어서션 채움**

base IT 코드 보고 정확한 시드 메서드 호출/Repository 빈 이름으로 교체. 어서션은 직접 `aggregatePort.findDj(partyroomId, crewId).get()` 으로 재조회 후 `.getPlaylistId()`·`.getOrderNumber()` 검증.

> AuthContext 시드 = base IT 의 기존 패턴 모방. 보통 `ThreadLocalContext.setContext(mock(AuthContext.class))` + `when(authContext.getUserId()).thenReturn(new UserId(seededUserId))` 또는 base 의 `@WithMockUser` 동등.

- [ ] **Step 5: 통과 확인**

Run: 위 Step 3 와 동일.
Expected: 3 case PASS.

- [ ] **Step 6: party 모듈 전체 IT 회귀 확인 + DB cleanup 위생 점검**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL.

**위생 가드** ([[reference_mysql_datetime0_rounding]] · [[feedback_pr_series_workflow]] forward-evolution): 다른 IT 와 user_id 공간 충돌 회피 — base IT 의 `@AfterEach` cleanup 패턴 따름. 본 신규 IT 가 host_uid/user_uid 공간을 점유한다면 cleanup 명시.

- [ ] **Step 7: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/party/application/service/DjCommandIntegrationTest.java
git commit -m "feat(e223): DjCommandIntegrationTest.changePlaylist — happy / order 보존 / idempotent 3 case (#223)"
```

---

## 최종 회귀 확인

- [ ] **Step 1: 전 모듈 단위·통합 테스트 GREEN**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test :app:test :app:integrationTest 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL — playlist 모듈 신규 2 + party 모듈 신규/회귀 모두 GREEN.

- [ ] **Step 2: 브랜치 상태 점검 + push**

```bash
git status                    # working tree clean
git log --oneline origin/develop..HEAD   # 12 commits + spec 3 commit = 15 commits
git push -u origin feature/e223-change-playlist
```

- [ ] **Step 3: PR 생성**

```bash
gh pr create --title "feat(e223): 대기 중 DJ 플레이리스트 변경 API (Change Playlist)" --body "$(cat <<'EOF'
## 요약

- `PATCH /api/v1/partyrooms/{partyroomId}/dj-queue/me` 신설 — 대기 중 본인 DJ 의 디제잉 플레이리스트를 큐 순서를 보존하며 변경.
- playlist 소유권 검증을 신규 PATCH 와 기존 enqueue 양쪽에 동반 보강(`DJ-005 NOT_OWNED_PLAYLIST`).
- 재생 중 DJ 의 playlist 재지정은 범위 밖(`DJ-006 CURRENT_DJ_CANNOT_CHANGE_PLAYLIST`).
- WS broadcast 없음 (본인만 결과 확인, `DjWithProfileDto` 가 playlist 정보 비포함).

## 결정 잠금 (사용자, 2026-05-21)

1. 허용 범위 = **대기 중 DJ만**
2. WS 통지 = **불필요** (REST 204 만)
3. 빈 playlist = **거부** (DJ-003 재사용)
4. playlist 소유권 = **PATCH + enqueue 동반 보강**

## 산출물

- 신규: `DjChangePlaylistSpecification`, `ChangePlaylistRequest`, `DjCommandIntegrationTest`
- 확장: `DjData.updatePlaylist`, `DjException` (DJ-005/006), `DjEnqueueSpecification` arity 3→4, party `PlaylistQueryPort.isOwnedBy`, `PlaylistQueryAdapter` 위임, `DjCommandService.changePlaylist`/`enqueueDj` ownership, `DjCommandController.changePlaylist`, playlist `PlaylistQueryService.isOwnedBy`
- 무변: `PartyroomAggregatePort.findDj` 기존 메서드 재사용

## 테스트

- :playlist:test PASS (신규 2 + 기존 무변)
- :app:test PASS (신규: DjChangePlaylistSpecificationTest 6 / DjCommandServiceTest changePlaylist 7 + enqueue 회귀 1 / DjCommandControllerTest changePlaylist 8 / DjDataTest 1, 수정: DjEnqueueSpecificationTest 6)
- :app:integrationTest PASS (DjCommandIntegrationTest 3 신규)

## 스펙 / 계획

- spec: `docs/superpowers/specs/2026-05-21-e223-change-playlist-design.md`
- plan: `docs/superpowers/plans/2026-05-21-e223-change-playlist.md`
- 이슈: #223

## 후속

- frontend (pfplay-web): `widgets/partyroom-djing-dialog/ui/body.component.tsx:147` placeholder → PATCH 호출 + playlist picker (별 PR)
- 재생 중 DJ 의 playlist 재지정 (out-of-scope, WS broadcast 동반 필요)
EOF
)"
```

> PR title·body 모두 한글 ([[feedback_korean_issue_commit_pr]]).

---

## 참고

- spec: `docs/superpowers/specs/2026-05-21-e223-change-playlist-design.md`
- 인접 작업: E/#3 (2026-05-19, `doStart` DJ별 playable 스캔 — 빈 playlist 자동 skip 으로 본 spec §2-3 결정 정합), E/#222 (2026-05-18, skip→reorder invariant 회귀잠금)
- 메모리: [[feedback_pr_series_workflow]], [[feedback_commit_consolidation_before_push]], [[feedback_korean_issue_commit_pr]], [[feedback_elegant_no_code_dirtying]], [[reference_pfplay_platform_jdk]], [[reference_mysql_datetime0_rounding]]
