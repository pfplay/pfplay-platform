# Crew Grade Host Invariant Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce the invariant "if `userId == partyroom.hostId` then `CrewData.gradeType == HOST`" inside `PartyroomAccessCommandService.tryEnter`, so super admin (and any future host registered without a pre-existing HOST CrewData row) is correctly displayed as HOST in Grade tab. Auto-heals existing prod LISTENER row on next entry. No manual SQL needed.

**Architecture:** Single private helper `enforceHostInvariant(partyroom, userId, crew)` invoked at two call sites in `tryEnter` — (1) same-room re-entry branch, (2) post-`ensureCrewActive` (guarded against INSERT race-loser path which has rollback-only outer tx). `CrewActivationResult` record extended with `raceLoser` discriminator.

**Tech Stack:** Java 21, Spring Boot, JUnit 5 + Mockito (existing test setup), MySQL via Hibernate (`@DynamicUpdate` on `CrewData`).

**Spec:** [`docs/superpowers/specs/2026-05-09-crew-grade-host-invariant-design.md`](../specs/2026-05-09-crew-grade-host-invariant-design.md)

**Build prefix (Windows PowerShell):** `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` per memory `reference_pfplay_platform_jdk.md`. All gradle commands below assume this prefix is set in the executing shell.

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandService.java` | Modify | Add `enforceHostInvariant` helper; extend `CrewActivationResult` record; update 4 return sites in `ensureCrewActive`; add 2 call sites in `tryEnter`; update Javadoc on `ensureCrewActive`. |
| `app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomCommandService.java` | Modify (comment only) | Replace stale comment in `createMainStage` per spec §4.5. |
| `app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceTest.java` | Modify | Add 8 test cases per spec §6. |

---

## Task 1 — Regression baselines (non-host grade preserved)

Goal: lock in current correct behavior with two regression tests before any production code changes. These will pass without modification, serving as a safety net.

**Files:**
- Test: `app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceTest.java`

- [ ] **Step 1: Add Test #2 — fresh non-host entry stays LISTENER**

Append at the end of the existing test class (before the closing `}` on line 313). Match existing imports — they are already present (`assertThat`, `any`, `eq`, `Mockito.*`, `Optional`, `LocalDateTime`, `CrewData`, etc.).

```java
@Test
@DisplayName("tryEnter: fresh entry, non-host user → CrewData created with LISTENER grade (regression)")
void tryEnter_freshNonHostEntry_assignsListenerGrade() {
    // given — partyroom hosted by someone else, no existing crew row
    UserId hostId = new UserId(99L);
    PartyroomData partyroom = mock(PartyroomData.class);
    when(partyroom.getPartyroomId()).thenReturn(partyroomId);
    when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
    when(partyroom.isSuspended()).thenReturn(false);
    when(partyroom.getHostId()).thenReturn(hostId);

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
    when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
    when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.empty());
    when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(0);
    when(aggregatePort.saveCrew(any(CrewData.class)))
            .thenAnswer(inv -> inv.getArgument(0));

    // when
    CrewData result = partyroomAccessCommandService.tryEnter(partyroomId, null);

    // then
    assertThat(result.getGradeType()).isEqualTo(GradeType.LISTENER);
}
```

- [ ] **Step 2: Add Test #4 — existing LISTENER non-host stays LISTENER**

Append after Test #2.

```java
@Test
@DisplayName("tryEnter: existing LISTENER row, non-host user → grade unchanged, updateGrade not invoked (regression)")
void tryEnter_existingListenerNonHost_unchanged() {
    // given — partyroom hosted by someone else, user already a listener, currently inactive
    UserId hostId = new UserId(99L);
    PartyroomData partyroom = mock(PartyroomData.class);
    when(partyroom.getPartyroomId()).thenReturn(partyroomId);
    when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
    when(partyroom.isSuspended()).thenReturn(false);
    when(partyroom.getHostId()).thenReturn(hostId);

    CrewData existingCrew = spy(CrewData.builder()
            .id(20L)
            .partyroomId(partyroomId)
            .userId(userId)
            .gradeType(GradeType.LISTENER)
            .isActive(false)
            .build());

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
    when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
    when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(existingCrew));
    when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(1);
    when(aggregatePort.saveCrew(any(CrewData.class))).thenAnswer(inv -> inv.getArgument(0));

    // when
    CrewData result = partyroomAccessCommandService.tryEnter(partyroomId, null);

    // then
    assertThat(result.getGradeType()).isEqualTo(GradeType.LISTENER);
    verify(existingCrew, never()).updateGrade(any(GradeType.class));
}
```

- [ ] **Step 3: Verify both pass against current `develop` code**

Run:
```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest.tryEnter_freshNonHostEntry_assignsListenerGrade" --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest.tryEnter_existingListenerNonHost_unchanged"
```

Expected: both PASS (current code already preserves LISTENER for non-hosts).

- [ ] **Step 4: Commit**

```powershell
git add app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceTest.java
git commit -m "test(crew-grade): add regression baselines for non-host grade preservation"
```

---

## Task 2 — `enforceHostInvariant` helper + post-`ensureCrewActive` call

Goal: introduce the helper and wire the first call site. This makes Tests #1, #3, #5 pass.

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceTest.java`

- [ ] **Step 1: Add Test #1 — fresh host entry returns HOST**

Append after Test #4.

```java
@Test
@DisplayName("tryEnter: fresh entry, user is partyroom host → returned CrewData has HOST grade")
void tryEnter_freshHostEntry_assignsHostGrade() {
    // given — partyroom hosted by THIS user, no existing crew row
    PartyroomData partyroom = mock(PartyroomData.class);
    when(partyroom.getPartyroomId()).thenReturn(partyroomId);
    when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
    when(partyroom.isSuspended()).thenReturn(false);
    when(partyroom.getHostId()).thenReturn(userId);

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
    when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
    when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.empty());
    when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(0);
    when(aggregatePort.saveCrew(any(CrewData.class)))
            .thenAnswer(inv -> inv.getArgument(0));

    // when
    CrewData result = partyroomAccessCommandService.tryEnter(partyroomId, null);

    // then
    assertThat(result.getGradeType()).isEqualTo(GradeType.HOST);
}
```

- [ ] **Step 2: Run the new test — confirm RED**

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest.tryEnter_freshHostEntry_assignsHostGrade"
```

Expected: FAIL — assertion `Expected: HOST but was: LISTENER`. (Current code creates LISTENER and never promotes.)

- [ ] **Step 3: Add `enforceHostInvariant` helper to `PartyroomAccessCommandService`**

In `PartyroomAccessCommandService.java`, insert the new method below `ensureCrewActive` (i.e., between current `findCrewInNewTransaction` (line 168-172) and the `CrewActivationResult` record (line 174)). Place it just before the record declaration:

```java
/**
 * Host invariant 강제: 진입 user가 partyroom host인데 grade가 HOST가 아니면 승격.
 * Idempotent — 이미 HOST면 no-op. createMainStage가 enterByHost를 건너뛰는 경우와
 * 기존 잘못된 grade row를 자동 healing.
 *
 * 호출 측 PRECONDITION: outer @Transactional이 rollback-only 상태가 아닐 것.
 * INSERT race-loser 분기에서는 호출하지 말 것 — outer tx가 rollback-only이므로
 * saveCrew가 UnexpectedRollbackException을 던진다. CrewActivationResult.raceLoser()
 * 플래그로 식별하여 skip한다.
 */
private void enforceHostInvariant(PartyroomData partyroom, UserId userId, CrewData crew) {
    if (!userId.equals(partyroom.getHostId())) return;
    if (crew.getGradeType() == GradeType.HOST) return;
    GradeType prev = crew.getGradeType();
    crew.updateGrade(GradeType.HOST);
    aggregatePort.saveCrew(crew);
    log.info("[enforceHostInvariant] HEALED - userId={}, partyroomId={}, crewId={}, {} → HOST",
            userId, partyroom.getPartyroomId().getId(), crew.getId(), prev);
}
```

- [ ] **Step 4: Wire first call site — after `ensureCrewActive` returns**

In `PartyroomAccessCommandService.tryEnter`, find the block at the bottom (currently lines 107-116):

```java
        CrewActivationResult result = ensureCrewActive(partyroom, userId, countryCode);
        if (result.transitioned) {
            log.info("[tryEnter] SUCCESS - userId={}, partyroomId={}, crewId={}",
                    userId, partyroomId.getId(), result.crew.getId());
            publishAccessChangedEvent(partyroom.getPartyroomId(), result.crew, userId);
        } else {
            log.info("[tryEnter] IDEMPOTENT - already active or concurrent insert loser, no event. userId={}, partyroomId={}",
                    userId, partyroomId.getId());
        }
        return result.crew;
```

Replace the final `return result.crew;` line with:

```java
        enforceHostInvariant(partyroom, userId, result.crew);
        return result.crew;
```

(Race-loser guard is added in Task 5 — this initial wiring is intentionally unguarded; Test #8 in Task 5 will surface the issue and force the guard.)

- [ ] **Step 5: Run Test #1 — confirm GREEN**

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest.tryEnter_freshHostEntry_assignsHostGrade"
```

Expected: PASS.

- [ ] **Step 6: Add Test #3 — existing LISTENER host promoted on re-entry**

Append after Test #1.

```java
@Test
@DisplayName("tryEnter: existing LISTENER row, user is host, re-activate path → grade promoted to HOST")
void tryEnter_existingListenerHost_promotesToHost() {
    // given — partyroom hosted by THIS user, existing inactive LISTENER row
    PartyroomData partyroom = mock(PartyroomData.class);
    when(partyroom.getPartyroomId()).thenReturn(partyroomId);
    when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
    when(partyroom.isSuspended()).thenReturn(false);
    when(partyroom.getHostId()).thenReturn(userId);

    CrewData staleCrew = CrewData.builder()
            .id(30L)
            .partyroomId(partyroomId)
            .userId(userId)
            .gradeType(GradeType.LISTENER)
            .isActive(false)
            .build();

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
    when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
    when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(staleCrew));
    when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(1);
    when(aggregatePort.saveCrew(any(CrewData.class))).thenAnswer(inv -> inv.getArgument(0));

    // when
    CrewData result = partyroomAccessCommandService.tryEnter(partyroomId, null);

    // then
    assertThat(result.getGradeType()).isEqualTo(GradeType.HOST);
}
```

- [ ] **Step 7: Add Test #5 — already-HOST host is idempotent**

Append after Test #3.

```java
@Test
@DisplayName("tryEnter: existing HOST row, user is host → updateGrade not invoked (helper short-circuit)")
void tryEnter_existingHostHost_idempotent() {
    // given
    PartyroomData partyroom = mock(PartyroomData.class);
    when(partyroom.getPartyroomId()).thenReturn(partyroomId);
    when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
    when(partyroom.isSuspended()).thenReturn(false);
    when(partyroom.getHostId()).thenReturn(userId);

    CrewData hostCrew = spy(CrewData.builder()
            .id(40L)
            .partyroomId(partyroomId)
            .userId(userId)
            .gradeType(GradeType.HOST)
            .isActive(false)
            .build());

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
    when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
    when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(hostCrew));
    when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(1);
    when(aggregatePort.saveCrew(any(CrewData.class))).thenAnswer(inv -> inv.getArgument(0));

    // when
    partyroomAccessCommandService.tryEnter(partyroomId, null);

    // then — helper must short-circuit when grade already HOST
    verify(hostCrew, never()).updateGrade(any(GradeType.class));
}
```

- [ ] **Step 8: Run Tests #3 and #5 — confirm GREEN**

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest.tryEnter_existingListenerHost_promotesToHost" --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest.tryEnter_existingHostHost_idempotent"
```

Expected: both PASS.

- [ ] **Step 9: Run full `PartyroomAccessCommandServiceTest` — verify no regressions**

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest"
```

Expected: all PASS (existing tests + 4 new ones #1, #2, #3, #4, #5).

- [ ] **Step 10: Commit**

```powershell
git add app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandService.java app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceTest.java
git commit -m "feat(crew-grade): enforce host invariant after ensureCrewActive in tryEnter"
```

---

## Task 3 — Same-room re-entry call site

Goal: cover the same-room re-entry branch (websocket reconnect / page reload) so prod LISTENER row heals on every reconnect, not only on full exit-then-reenter.

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceTest.java`

- [ ] **Step 1: Add Test #6 — same-room re-entry promotes stale LISTENER**

Append after Test #5.

```java
@Test
@DisplayName("tryEnter: same-room re-entry, host with stale LISTENER row → grade promoted to HOST")
void tryEnter_sameRoomReentry_promotesHostIfStale() {
    // given — partyroom hosted by THIS user, user already active in SAME room with stale LISTENER grade
    PartyroomData partyroom = mock(PartyroomData.class);
    when(partyroom.getPartyroomId()).thenReturn(partyroomId);
    when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
    when(partyroom.isSuspended()).thenReturn(false);
    when(partyroom.getHostId()).thenReturn(userId);

    CrewData staleCrew = CrewData.builder()
            .id(50L)
            .partyroomId(partyroomId)
            .userId(userId)
            .gradeType(GradeType.LISTENER)
            .isActive(true)
            .build();

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(1L);
    when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(staleCrew));
    when(aggregatePort.saveCrew(any(CrewData.class))).thenAnswer(inv -> inv.getArgument(0));

    // 같은 룸에 이미 active — same-room re-entry path
    ActivePartyroomDto activeRoomInfo = mock(ActivePartyroomDto.class);
    when(activeRoomInfo.id()).thenReturn(1L);
    when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeRoomInfo));

    // when
    CrewData result = partyroomAccessCommandService.tryEnter(partyroomId, null);

    // then
    assertThat(result.getGradeType()).isEqualTo(GradeType.HOST);
}
```

- [ ] **Step 2: Run Test #6 — confirm RED**

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest.tryEnter_sameRoomReentry_promotesHostIfStale"
```

Expected: FAIL — same-room re-entry returns at line 102 BEFORE the post-`ensureCrewActive` healing call. Grade is still LISTENER.

- [ ] **Step 3: Wire second call site — same-room re-entry branch**

In `PartyroomAccessCommandService.tryEnter`, find the same-room re-entry block (currently lines 93-103):

```java
            } else {
                // 같은 룸 재입장 (websocket 재연결 등) — 이미 active인 경로.
                // ⚠️ 이전 코드는 여기서도 ENTER 이벤트 발행 → counter inflate.
                // PR 7: countryCode만 갱신, 이벤트 발행 금지 (spec §7.2 spurious ENTER 차단).
                log.info("[tryEnter] Same room re-entry — countryCode 갱신만, no ENTER publish. userId={}, partyroomId={}",
                        userId, partyroomId.getId());
                CrewData crew = existingCrew.orElseThrow(() ->
                        ExceptionCreator.create(CrewException.INVALID_ACTIVE_ROOM));
                crew.updateCountryCode(countryCode);
                return aggregatePort.saveCrew(crew);
            }
```

Replace the last two lines (`crew.updateCountryCode(countryCode);` through `return aggregatePort.saveCrew(crew);`) with:

```java
                crew.updateCountryCode(countryCode);
                CrewData saved = aggregatePort.saveCrew(crew);
                enforceHostInvariant(partyroom, userId, saved);
                return saved;
```

- [ ] **Step 4: Run Test #6 — confirm GREEN**

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest.tryEnter_sameRoomReentry_promotesHostIfStale"
```

Expected: PASS.

- [ ] **Step 5: Run full `PartyroomAccessCommandServiceTest` — verify no regression in existing same-room re-entry test**

The existing `tryEnterSameRoomReEntryShouldNotPublishEnterEvent` (line 100-) uses a `CrewData` built without an explicit hostId on the partyroom (default `null`), so `enforceHostInvariant` will short-circuit (userId.equals(null) → false). No regression expected.

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest"
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandService.java app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceTest.java
git commit -m "feat(crew-grade): enforce host invariant on same-room re-entry path"
```

---

## Task 4 — Silent healing assertion

Goal: lock in that grade promotion does NOT publish a `CrewGradeChangedEvent` (spec §4.5.3 — silent healing decision).

**Files:**
- Test: `app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceTest.java`

- [ ] **Step 1: Add Test #7 — healing publishes no CrewGradeChangedEvent**

Append after Test #6. Note: the import for `CrewGradeChangedEvent` needs to be added at the top of the file with the other imports:

Add to imports section (after line 16 `import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;`):
```java
import com.pfplaybackend.api.party.domain.event.CrewGradeChangedEvent;
```

Then add the test:

```java
@Test
@DisplayName("tryEnter healing: grade promotion is silent — no CrewGradeChangedEvent published")
void tryEnter_healing_doesNotPublishGradeChangeEvent() {
    // given — same-as Test #3 setup (LISTENER → HOST healing path)
    PartyroomData partyroom = mock(PartyroomData.class);
    when(partyroom.getPartyroomId()).thenReturn(partyroomId);
    when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
    when(partyroom.isSuspended()).thenReturn(false);
    when(partyroom.getHostId()).thenReturn(userId);

    CrewData staleCrew = CrewData.builder()
            .id(60L)
            .partyroomId(partyroomId)
            .userId(userId)
            .gradeType(GradeType.LISTENER)
            .isActive(false)
            .build();

    when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
    when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
    when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
    when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(staleCrew));
    when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(1);
    when(aggregatePort.saveCrew(any(CrewData.class))).thenAnswer(inv -> inv.getArgument(0));

    // when
    partyroomAccessCommandService.tryEnter(partyroomId, null);

    // then — silent healing: no CrewGradeChangedEvent
    verify(eventPublisher, never()).publishEvent(any(CrewGradeChangedEvent.class));
}
```

- [ ] **Step 2: Run Test #7 — confirm GREEN immediately (helper does not publish events)**

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest.tryEnter_healing_doesNotPublishGradeChangeEvent"
```

Expected: PASS.

- [ ] **Step 3: Commit**

```powershell
git add app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceTest.java
git commit -m "test(crew-grade): assert silent healing publishes no CrewGradeChangedEvent"
```

---

## Task 5 — Race-loser guard (`CrewActivationResult.raceLoser`)

Goal: prevent `enforceHostInvariant` from running on the INSERT race-loser path where outer tx is rollback-only.

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceTest.java`

- [ ] **Step 1: Add Test #8 — race-loser branch skips healing**

Append after Test #7. This test follows the existing `tryEnterConcurrentInsertLoserShouldNotPublishEnter` pattern (line 265-312) which already wires `requiresNewReadOnlyTx` via `ReflectionTestUtils`.

```java
@Test
@DisplayName("tryEnter race-loser: helper skipped to avoid write on rollback-only outer tx")
void tryEnter_raceLoser_skipsHealingToAvoidRollbackOnlyTx() {
    // given — same setup as tryEnterConcurrentInsertLoserShouldNotPublishEnter, but
    // user IS the host and winner row is LISTENER. The healing helper would normally
    // try to promote → saveCrew on rollback-only outer tx → UnexpectedRollbackException.
    // The raceLoser guard must skip the helper entirely.
    PartyroomId racePartyroomId = new PartyroomId(701L);
    UserId raceUserId = new UserId(8002L);
    AuthContext authContext = new AuthContext(raceUserId, AuthorityTier.GT);
    ThreadLocalContext.setContext(authContext);

    try {
        PartyroomData partyroom = mock(PartyroomData.class);
        when(partyroom.getPartyroomId()).thenReturn(racePartyroomId);
        when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
        when(partyroom.isSuspended()).thenReturn(false);
        when(partyroom.getHostId()).thenReturn(raceUserId);
        when(partyroomQueryService.getPartyroomById(racePartyroomId)).thenReturn(partyroom);
        when(aggregatePort.countActiveCrews(racePartyroomId)).thenReturn(0L);
        when(partyroomQueryService.getMyActivePartyroom(raceUserId)).thenReturn(Optional.empty());

        when(aggregatePort.activateCrew(eq(racePartyroomId), eq(raceUserId), any())).thenReturn(0);

        // Winner row from REQUIRES_NEW: a LISTENER row that WOULD trigger healing
        // if the guard were missing.
        CrewData winnerCrew = mock(CrewData.class);
        when(winnerCrew.getGradeType()).thenReturn(GradeType.LISTENER);
        when(aggregatePort.findCrew(eq(racePartyroomId), eq(raceUserId)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerCrew));
        when(aggregatePort.saveCrew(any()))
                .thenThrow(new DataIntegrityViolationException("uk_crew_partyroom_user"));

        // Wire requiresNewReadOnlyTx (skipped by @InjectMocks since @PostConstruct doesn't fire)
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(txStatus);
        ReflectionTestUtils.invokeMethod(partyroomAccessCommandService, "initTxTemplates");

        // when
        CrewData result = partyroomAccessCommandService.tryEnter(racePartyroomId, null);

        // then — guard prevents the helper from invoking updateGrade or extra saveCrew
        assertThat(result).isSameAs(winnerCrew);
        verify(winnerCrew, never()).updateGrade(any(GradeType.class));
    } finally {
        ThreadLocalContext.clearContext();
    }
}
```

- [ ] **Step 2: Run Test #8 — confirm RED**

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest.tryEnter_raceLoser_skipsHealingToAvoidRollbackOnlyTx"
```

Expected: FAIL. Two possible failure modes (either is acceptable as RED):
- Mockito verify failure: `winnerCrew.updateGrade(HOST)` was called once (helper ran without guard).
- Or stub mismatch on extra `saveCrew` call from helper: helper's `saveCrew` re-triggers the `DataIntegrityViolationException` stub.

- [ ] **Step 3: Extend `CrewActivationResult` record with `raceLoser` field**

In `PartyroomAccessCommandService.java`, find the record declaration (currently line 174):

```java
    private record CrewActivationResult(CrewData crew, boolean transitioned) {}
```

Replace with:

```java
    private record CrewActivationResult(CrewData crew, boolean transitioned, boolean raceLoser) {}
```

- [ ] **Step 4: Update all 4 return sites in `ensureCrewActive`**

In `PartyroomAccessCommandService.ensureCrewActive`:

- Line 139 (activate==1 branch): change `return new CrewActivationResult(aggregatePort.saveCrew(crew), true);` → `return new CrewActivationResult(aggregatePort.saveCrew(crew), true, false);`
- Line 146 (INSERT success): change `return new CrewActivationResult(aggregatePort.saveCrew(newCrew), true);` → `return new CrewActivationResult(aggregatePort.saveCrew(newCrew), true, false);`
- Line 152 (INSERT race-loser catch): change `return new CrewActivationResult(winner, false);` → `return new CrewActivationResult(winner, false, true);`
- Line 159 (already-active): change `return new CrewActivationResult(aggregatePort.saveCrew(crew), false);` → `return new CrewActivationResult(aggregatePort.saveCrew(crew), false, false);`

- [ ] **Step 5: Update `ensureCrewActive` Javadoc to mention raceLoser**

Replace the existing Javadoc on `ensureCrewActive` (currently lines 119-130):

```java
    /**
     * Crew를 active 상태로 만든다 (idempotent). 호출자에게 transitioned 플래그를 돌려 ENTER 이벤트
     * 발행 여부를 판단하게 한다.
     *
     * 흐름:
     *  1. activateCrew atomic toggle 시도 → 1이면 inactive→active 전이 성공.
     *  2. 0 (row missing 또는 이미 active) → findCrew 분기:
     *     a. row 없음 → INSERT. 동시 INSERT 패배자는 DataIntegrityViolationException —
     *        outer 트랜잭션이 rollback-only 상태가 되므로 winner 조회는 별 트랜잭션(REQUIRES_NEW)에서
     *        수행. 본 호출자는 idempotent return.
     *     b. row 있고 active → countryCode만 갱신, idempotent.
     */
```

with:

```java
    /**
     * Crew를 active 상태로 만든다 (idempotent). 호출자에게 transitioned 플래그를 돌려 ENTER 이벤트
     * 발행 여부를, raceLoser 플래그를 돌려 추가 mutation(예: enforceHostInvariant healing) skip 여부를
     * 판단하게 한다.
     *
     * 흐름:
     *  1. activateCrew atomic toggle 시도 → 1이면 inactive→active 전이 성공. (transitioned=true, raceLoser=false)
     *  2. 0 (row missing 또는 이미 active) → findCrew 분기:
     *     a. row 없음 → INSERT. 동시 INSERT 패배자는 DataIntegrityViolationException —
     *        outer 트랜잭션이 rollback-only 상태가 되므로 winner 조회는 별 트랜잭션(REQUIRES_NEW)에서
     *        수행. 본 호출자는 idempotent return하며 raceLoser=true로 표시한다 — 호출자 측에서
     *        추가 saveCrew 호출 시 UnexpectedRollbackException 위험이 있으므로 skip해야 한다.
     *     b. row 있고 active → countryCode만 갱신, idempotent. (transitioned=false, raceLoser=false)
     */
```

- [ ] **Step 6: Add the guard at the post-`ensureCrewActive` call site**

In `PartyroomAccessCommandService.tryEnter`, find the line added in Task 2 Step 4:

```java
        enforceHostInvariant(partyroom, userId, result.crew);
        return result.crew;
```

Replace with:

```java
        if (!result.raceLoser()) {
            enforceHostInvariant(partyroom, userId, result.crew);
        }
        return result.crew;
```

- [ ] **Step 7: Run Test #8 — confirm GREEN**

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest.tryEnter_raceLoser_skipsHealingToAvoidRollbackOnlyTx"
```

Expected: PASS.

- [ ] **Step 8: Run full `PartyroomAccessCommandServiceTest` — verify no regressions**

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.party.application.service.PartyroomAccessCommandServiceTest"
```

Expected: all 8 new tests + all existing tests PASS.

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandService.java app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandServiceTest.java
git commit -m "feat(crew-grade): skip host invariant healing on INSERT race-loser branch

CrewActivationResult.raceLoser flag prevents enforceHostInvariant from
issuing saveCrew on a rollback-only outer transaction. Race-loser entries
heal on the next non-race entry instead."
```

---

## Task 6 — Update stale comment in `createMainStage`

Goal: replace stale assumption documentation with reference to the new runtime invariant.

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomCommandService.java`

- [ ] **Step 1: Replace comment in `createMainStage`**

In `PartyroomCommandService.java`, find lines 44-50:

```java
    @Transactional
    public void createMainStage(CreatePartyroomCommand command, UserId adminId) {
        // 도메인 invariant: 프로필 없는 사용자는 partyroom에 active crew로 등록하지 않는다.
        // V5-seeded super-admin은 profile이 없으므로 enterByHost를 호출하면 customer GET /api/v1/partyrooms
        // 응답 빌드 시 ProfileSettingDto null lookup → NPE. 호스트 권한은 partyroom.host_id로 충분하며
        // 본 스테이지엔 crew row가 불필요. (PA-7)
        createPartyroom(command, StageType.MAIN, adminId);
    }
```

Replace the comment (lines 45-48) with:

```java
    @Transactional
    public void createMainStage(CreatePartyroomCommand command, UserId adminId) {
        // 본 스테이지는 host crew row를 사전 생성하지 않는다. host의 grade는
        // PartyroomAccessCommandService.tryEnter의 enforceHostInvariant가 진입 시점에
        // 자동 보장한다 (host_id == userId면 HOST로 승격/생성). PA-7의 NPE 회피는
        // ApplicationReadyEventListener.finalizeSuperAdminProfile() 에서 처리됨.
        createPartyroom(command, StageType.MAIN, adminId);
    }
```

- [ ] **Step 2: Build to confirm no syntax error**

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomCommandService.java
git commit -m "docs(comment): update createMainStage rationale to reference enforceHostInvariant"
```

---

## Task 7 — Final verification

Goal: full project test run + manual prod verification checklist.

- [ ] **Step 1: Full app test run**

```powershell
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test
```

Expected: all tests PASS, including:
- All 8 new test cases in `PartyroomAccessCommandServiceTest`
- All existing partyroom/crew tests (`CrewDataGradeTest`, `PartyroomAccessCommandServiceDjQueueChangeTest`, `PartyroomAccessCommandControllerTest`, etc.)
- Integration tests in `app/src/test/.../administration/...` if part of `:app:test`

If any pre-existing flaky/failing test surfaces, do not fix it in this PR — note it and continue.

- [ ] **Step 2: Inspect git log**

```powershell
git log --oneline develop..HEAD
```

Expected commits (in order):
1. `docs(spec): crew grade host invariant fix 설계`
2. `docs(spec): race-loser 분기 healing skip 가드 + 테스트 case 추가`
3. `docs(spec): test #8 셋업 노트 추가 (race-loser TransactionTemplate mocking)`
4. `test(crew-grade): add regression baselines for non-host grade preservation`
5. `feat(crew-grade): enforce host invariant after ensureCrewActive in tryEnter`
6. `feat(crew-grade): enforce host invariant on same-room re-entry path`
7. `test(crew-grade): assert silent healing publishes no CrewGradeChangedEvent`
8. `feat(crew-grade): skip host invariant healing on INSERT race-loser branch`
9. `docs(comment): update createMainStage rationale to reference enforceHostInvariant`

- [ ] **Step 3: Manual prod verification (post-deploy)**

After PR merge → release branch → prod deploy:

1. admin 콘솔 SharedToken 다시 발급
2. pfplay.xyz Main Stage 진입
3. Grade 탭 확인 → **HOST** 표시 확인
4. 백엔드 로그에 다음 패턴의 INFO 로그 1회 기록 확인 (super admin healing):
   ```
   [enforceHostInvariant] HEALED - userId=1, partyroomId=<main stage id>, crewId=<crew id>, LISTENER → HOST
   ```
5. (선택) DB 직접 확인:
   ```sql
   SELECT c.grade_type FROM crew c
   JOIN partyroom p ON p.partyroom_id = c.partyroom_id
   WHERE c.user_id = 1 AND p.link_domain = 'main';
   ```
   → `0` (HOST ordinal)

If healing log does not appear OR Grade tab still shows LISTENER:
- Check server logs for any `UnexpectedRollbackException` or other exceptions in `tryEnter` path
- Verify the deployed commit hash matches the merged PR
- Check whether super admin's tryEnter actually goes through (e.g., admin origin guard, CSRF, etc. — see memory `project_admin_csrf_oauth2_wrapping.md`)

- [ ] **Step 4: Push branch and open PR**

```powershell
git push -u origin fix/crew-grade-host-invariant
gh pr create --base develop --title "fix(crew-grade): super admin이 Main Stage에서 LISTENER로 표시되는 버그 수정" --body "$(cat <<'EOF'
## Summary
- `PartyroomAccessCommandService.tryEnter`에 `enforceHostInvariant` helper 추가 — `userId == partyroom.hostId`이면 `CrewData.gradeType`을 자동으로 HOST로 승격
- `CrewActivationResult` record에 `raceLoser` 플래그 추가 → INSERT race-loser 분기에서 healing skip (rollback-only outer tx 회피)
- `createMainStage` stale 코멘트 갱신 (현재 호출 흐름과 일치)

## Why
admin 콘솔 첫 prod 진입(2026-05-09) 후 super admin이 SharedToken으로 Main Stage 진입 시 Grade 탭에서 LISTENER로 표시되는 버그 발견.
- root cause: `createMainStage`가 `enterByHost`를 의도적으로 건너뛰는 데(stale assumption — super admin profile 없음 가정) 반해, `ApplicationReadyEventListener.finalizeSuperAdminProfile()`이 추가되어 그 가정이 깨짐. customer 진입 경로 `tryEnter`가 신규 row를 hardcoded LISTENER로 INSERT.
- 자세한 분석: `docs/superpowers/specs/2026-05-09-crew-grade-host-invariant-design.md`

런타임 invariant로 강제하여 prod 기존 LISTENER row는 super admin 다음 진입 1회로 자동 healing — 수동 SQL 마이그레이션 불필요.

## Test plan
- [x] 8 신규 unit test (fresh host, fresh non-host, existing LISTENER host promotion, existing LISTENER non-host preserved, existing HOST idempotent, same-room re-entry promotion, silent healing, race-loser skip)
- [x] 기존 `PartyroomAccessCommandServiceTest`, `CrewDataGradeTest`, `PartyroomAccessCommandServiceDjQueueChangeTest` 회귀 0건
- [ ] prod 배포 후 verification: admin 콘솔 → Main Stage 진입 → Grade 탭 HOST 표시 + healing INFO 로그 1회 기록 확인

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

(Push and PR creation should be confirmed with the user before execution — see CLAUDE.md guidance on shared-state actions.)

---

## Notes for the executing agent

- **Build prefix**: every gradle command MUST be prefixed with `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` per memory.
- **Memory references** (helpful context for unexpected friction):
  - `reference_pfplay_platform_jdk.md` — JDK version
  - `feedback_pr_series_workflow.md` — chunk confirmation pattern
  - `project_admin_csrf_oauth2_wrapping.md` — admin CSRF/cookie quirks (verification-time)
- **DRY**: do NOT duplicate the `enforceHostInvariant` body across call sites. Single helper, two call sites.
- **YAGNI**: do NOT add `CrewGradeChangedEvent` publication, do NOT clean up `countryCode`, do NOT touch `enterByHost` / `exit` / `expel` / `expelInternal`. Out-of-scope per spec §4.5.
- **Frequent commits**: each task ends with a commit. If a task's tests do not pass after the implementation, debug before commit; do NOT amend prior commits.
- **TDD discipline**: write the test before the implementation in Tasks 2/3/5. Run RED, then GREEN, then commit.
