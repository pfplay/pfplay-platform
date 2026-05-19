# E/#3 PR-2 — DjQueueChangeMessage changeType payload + relay passthrough + DEACTIVATE limit (platform) 구현 계획

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** dj-queue-changed 메시지에 `changeType`(DjChangeType)를 additive 로 실어 보내고(현재 relay 가 type 을 떼고 djs 만 전송), DEACTIVATE 케이스엔 `playbackTimeLimitMinutes`(limit-only) 동봉 — 프론트(PR-3)가 "왜 빠졌나/왜 중단됐나"를 changeType 별로 안내할 수 있게.

**Architecture:** `DjQueueChangedEvent` 는 이미 `changeType` 보유. limit 은 event 의 nullable 4번째 필드로 추가(3-arg ctor 가 null 위임 → 기존 5개 publish 사이트 무변경, DEACTIVATE 1곳만 4-arg). `DjQueueChangeMessage` record 에 `changeType`+`playbackTimeLimitMinutes` 컴포넌트 additive 추가, factory 시그니처 확장(유일 caller=relay). `DomainEventRedisRelay.on` 가 type drop → passthrough.

**Tech Stack:** Java 21 (빌드 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"`), Spring, Lombok, JUnit5. 모듈 `:app`. unit=`:app:test`.

**Spec:** `docs/superpowers/specs/2026-05-19-e3-track-skip-deactivate-cascade-design.md` §3-2 (+ 2026-05-19 limit-only 개정 노트).

**Scope 경계:** PR-2 = platform payload+relay 만. web(PR-3)·배지(PR-4) 무관. PR-1 동작은 무변경(deactivate 발화 조건/스킵 로직 그대로). additive·backward-compat: Cluster A 프론트 구독은 `.partyroomId`/`.djs` 만 읽음 → 신규 컴포넌트 무영향. 롤링배포 직렬화 윈도우 = transient drop 수용(spec §4, 변경 없음).

---

## File Structure

- Modify `app/.../party/domain/event/DjQueueChangedEvent.java` — nullable `Integer playbackTimeLimitMinutes` + 4-arg ctor, 3-arg→4-arg(null) 위임.
- Modify `app/.../party/application/service/PlaybackCommandService.java` — `deactivateAndNotify` 의 DEACTIVATE publish 만 4-arg(limit) 로.
- Modify `app/.../party/adapter/in/listener/message/DjQueueChangeMessage.java` — record 에 `DjChangeType changeType` + `Integer playbackTimeLimitMinutes` 컴포넌트, `create` 시그니처 확장.
- Modify `app/.../party/adapter/out/event/DomainEventRedisRelay.java` — `on(DjQueueChangedEvent)` passthrough.
- Tests: `DjQueueChangedEventTest`(없으면 신설), `DjQueueChangeMessageTest`/relay 테스트(기존 패턴), `PlaybackCommandServiceTest`(DEACTIVATE publish 가 limit 동반 검증).

---

## Chunk 1: changeType payload + relay passthrough

### Task 1: `DjQueueChangedEvent` 에 nullable limit 추가 (additive, 단독 컴파일)

현재:
```java
@Getter
public class DjQueueChangedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final DjChangeType changeType;
    private final CrewId affectedCrewId;
    public DjQueueChangedEvent(PartyroomId partyroomId, DjChangeType changeType, CrewId affectedCrewId) { ... }
    @Override public String getAggregateId() { ... }
}
```

**Files:** Modify `app/src/main/java/com/pfplaybackend/api/party/domain/event/DjQueueChangedEvent.java`; Test `app/src/test/java/com/pfplaybackend/api/party/domain/event/DjQueueChangedEventTest.java`(기존 없으면 신설, 같은 패키지 테스트 컨벤션 따름).

- [ ] **Step 1: 실패 단위테스트** — `DjQueueChangedEventTest`:
```java
@Test @DisplayName("3-arg ctor → playbackTimeLimitMinutes null (기존 사이트 호환)")
void threeArg_limitNull() {
    var e = new DjQueueChangedEvent(new PartyroomId(1L), DjChangeType.ROTATE, new CrewId(2L));
    assertThat(e.getChangeType()).isEqualTo(DjChangeType.ROTATE);
    assertThat(e.getPlaybackTimeLimitMinutes()).isNull();
}
@Test @DisplayName("4-arg ctor → DEACTIVATE + limit 보존")
void fourArg_limitSet() {
    var e = new DjQueueChangedEvent(new PartyroomId(1L), DjChangeType.DEACTIVATE, null, 5);
    assertThat(e.getChangeType()).isEqualTo(DjChangeType.DEACTIVATE);
    assertThat(e.getPlaybackTimeLimitMinutes()).isEqualTo(5);
}
```
(`PartyroomId`/`CrewId` 생성은 그 테스트 트리의 기존 방식 따름.)

- [ ] **Step 2: 실패 확인** — `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.domain.event.DjQueueChangedEventTest"` → FAIL (`getPlaybackTimeLimitMinutes`/4-arg 없음).

- [ ] **Step 3: 구현** — 필드+ctor 추가(3-arg 는 4-arg 로 null 위임):
```java
    private final PartyroomId partyroomId;
    private final DjChangeType changeType;
    private final CrewId affectedCrewId;
    private final Integer playbackTimeLimitMinutes; // DEACTIVATE 한정 limit-only, 그 외 null

    public DjQueueChangedEvent(PartyroomId partyroomId, DjChangeType changeType, CrewId affectedCrewId) {
        this(partyroomId, changeType, affectedCrewId, null);
    }

    public DjQueueChangedEvent(PartyroomId partyroomId, DjChangeType changeType, CrewId affectedCrewId,
                               Integer playbackTimeLimitMinutes) {
        this.partyroomId = partyroomId;
        this.changeType = changeType;
        this.affectedCrewId = affectedCrewId;
        this.playbackTimeLimitMinutes = playbackTimeLimitMinutes;
    }
```
(`@Getter` 가 `getPlaybackTimeLimitMinutes()` 자동 생성. `getAggregateId()` 무변경. 기존 6 publish 사이트는 3-arg 라 무변경.)

- [ ] **Step 4: 통과 확인** — 위 gradle 명령 → PASS. `JAVA_HOME=... ./gradlew :app:compileJava` → 0 (기존 호출 사이트 호환).

- [ ] **Step 5: 커밋**
```bash
git add app/src/main/java/com/pfplaybackend/api/party/domain/event/DjQueueChangedEvent.java app/src/test/java/com/pfplaybackend/api/party/domain/event/DjQueueChangedEventTest.java
git commit -m "feat(E/#3): DjQueueChangedEvent nullable playbackTimeLimitMinutes (DEACTIVATE limit-only, 3-arg 호환)"
```

---

### Task 2: message 컴포넌트 + relay passthrough + DEACTIVATE limit 주입 (합본 커밋)

상호 의존(factory 시그니처 변경이 relay 깨뜨림 → 함께). 

**Files (modify):**
- `app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/message/DjQueueChangeMessage.java`
- `app/src/main/java/com/pfplaybackend/api/party/adapter/out/event/DomainEventRedisRelay.java`
- `app/src/main/java/com/pfplaybackend/api/party/application/service/PlaybackCommandService.java`
- Test: `app/src/test/java/.../PlaybackCommandServiceTest.java` (DEACTIVATE publish), + relay/message 단위테스트(기존 있으면 확장, 없으면 신설)

- [ ] **Step 1: 실패/회귀 테스트**
  - `DjQueueChangeMessage` 단위: `create(pid, djs, DjChangeType.DEACTIVATE, 5)` → `.changeType()==DEACTIVATE`, `.playbackTimeLimitMinutes()==5`, `eventType==DJ_QUEUE_CHANGED`, djs 보존; `create(pid, djs, DjChangeType.ROTATE, null)` → changeType ROTATE, limit null.
  - relay passthrough: `DomainEventRedisRelay` 테스트가 있으면(예: messageId/relay 테스트, C/T1-1 산출) 거기에 — `on(new DjQueueChangedEvent(pid, DEACTIVATE, null, 5))` → publish 된 `DjQueueChangeMessage.changeType()==DEACTIVATE && playbackTimeLimitMinutes()==5`; `on(... ROTATE ...)` → ROTATE·limit null. 없으면 relay 단위테스트 신설(messagePublisher mock 캡처, partyroomQueryService.getDjs stub — 기존 relay 테스트 패턴 따름).
  - `PlaybackCommandServiceTest`: 기존 `singleDj_allOverLimit_deactivates`(및 deactivate 검증 테스트)에서, 발행된 `DjQueueChangedEvent` 를 ArgumentCaptor 로 잡아 `changeType==DEACTIVATE && playbackTimeLimitMinutes == 그 파티룸 limit(min)` 추가 단언. (해당 테스트가 partyroom limit 을 어떻게 셋업하는지 그 파일 픽스처 따름 — 신규 픽스처 금지.)

- [ ] **Step 2: 실패 확인** — `JAVA_HOME=... ./gradlew :app:test --tests "*DjQueueChangeMessage*" --tests "*DomainEventRedisRelay*" --tests "com.pfplaybackend.api.party.application.service.PlaybackCommandServiceTest"` → FAIL(시그니처/필드 부재·단언 미충족).

- [ ] **Step 3: 구현**
  `DjQueueChangeMessage.java` (record 컴포넌트 2개 additive + factory 시그니처 확장; 유일 caller=relay 라 오버로드 대신 시그니처 변경):
  ```java
  import com.pfplaybackend.api.party.domain.enums.DjChangeType;
  ...
  public record DjQueueChangeMessage(
          PartyroomId partyroomId,
          MessageTopic eventType,
          String id,
          long timestamp,
          List<DjWithProfileDto> djs,
          DjChangeType changeType,
          Integer playbackTimeLimitMinutes
  ) implements Serializable, GroupBroadcastMessage {
      public static DjQueueChangeMessage create(PartyroomId partyroomId, List<DjWithProfileDto> djs,
                                                DjChangeType changeType, Integer playbackTimeLimitMinutes) {
          return new DjQueueChangeMessage(partyroomId, MessageTopic.DJ_QUEUE_CHANGED,
                  UUID.randomUUID().toString(), System.currentTimeMillis(), djs,
                  changeType, playbackTimeLimitMinutes);
      }
  }
  ```
  `DomainEventRedisRelay.java` `on(DjQueueChangedEvent)` — passthrough:
  ```java
  DjQueueChangeMessage message = DjQueueChangeMessage.create(
          event.getPartyroomId(),
          partyroomQueryService.getDjs(event.getPartyroomId()),
          event.getChangeType(),
          event.getPlaybackTimeLimitMinutes());
  ```
  `PlaybackCommandService.java` `deactivateAndNotify(PartyroomData partyroom)` — DEACTIVATE publish 만 4-arg(limit):
  ```java
  eventPublisher.publishEvent(new DjQueueChangedEvent(partyroomId, DjChangeType.DEACTIVATE, null,
          partyroom.getPlaybackTimeLimit().getMinutes()));
  ```
  (다른 5개 publish 사이트·doStart 로직·deactivatePlayback 무변경.)

- [ ] **Step 4: 통과 확인** — Step 2 명령 → PASS.

- [ ] **Step 5: 커밋 (Task2 합본)**
```bash
git add app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/message/DjQueueChangeMessage.java app/src/main/java/com/pfplaybackend/api/party/adapter/out/event/DomainEventRedisRelay.java app/src/main/java/com/pfplaybackend/api/party/application/service/PlaybackCommandService.java app/src/test/java/com/pfplaybackend/api/party/application/service/PlaybackCommandServiceTest.java <relay/message 테스트 파일>
git commit -m "feat(E/#3): DjQueueChangeMessage changeType+limit additive + relay passthrough + DEACTIVATE limit 주입"
```

---

### Task 3: 전 모듈 회귀 + scope 확인

- [ ] **Step 1:** `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :playlist:test` → 전 GREEN(특히 PlaybackCommandServiceTest 12+ / 기존 relay·djqueue 회귀; PR-1 동작 무변경 확인). `:app:integrationTest` 는 #237 fix 머지됨 — E/#3 IT(`TrackRepositoryReorderIntegrationTest`) green 확인(전체 integrationTest 의 잔여 known-pre-existing 부채는 #237 에서 처리됨; 신규 red 없는지 확인).
- [ ] **Step 2:** `git diff origin/develop..HEAD --stat` — 변경 = Task1·2 의 4 main + 테스트만. `deactivatePlayback`/`PartyroomAggregateService`/doStart 스킵로직/web/DjChangeType enum/5개 비-DEACTIVATE publish 사이트 무변경 확인. `git log --oneline origin/develop..HEAD`.

---

## 완료 정의 (DoD)

- `DjQueueChangedEvent` 3-arg 호환·4-arg limit 보존. `DjQueueChangeMessage` changeType+playbackTimeLimitMinutes additive·factory 확장. relay 가 changeType·limit passthrough(type drop 제거). `deactivateAndNotify` DEACTIVATE event 가 partyroom limit(min) 동반, 다른 changeType 은 limit null.
- 신규/회귀 단위테스트 GREEN, `:app:test`/`:playlist:test` 무회귀. PR-1 동작·scope 경계 보존(payload/relay 만).
- 범위밖: web 모달/배지(PR-3/4). PR-3 가 이 changeType+limit 을 소비.
