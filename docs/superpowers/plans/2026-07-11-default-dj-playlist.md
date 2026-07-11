# 신규 가입자 기본 플레이리스트 구현 플랜 (#329)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 신규 가입자에게 가입 시 GRABLIST와 함께 PLAYLIST 타입 기본 플리("내 플레이리스트")를 자동 생성해 DJ 온보딩의 "플레이리스트 생성" 단계를 제거한다. 백엔드 단독, 스키마/정책/웹 무변경.

**Architecture:** `PlaylistCommandService.createDefaultDjPlaylist` 신설(기존 GRABLIST 생성과 동일 패턴 — 정책 우회, order 1) → `PlaylistSetupPort`/`PlaylistSetupAdapter` 확장 → 사람 가입 경로 2곳(`MemberSignService.initializeNewMember`, `TemporaryUserInitializeService.addAssociateMember`)에서 기존 `createDefaultPlaylist` 다음 줄에 호출. 봇/가상멤버 경로(`AdminUserService.createVirtualMember`)는 단건 조회(`playlistIdOf`) 보호를 위해 의도적 제외.

**Tech Stack:** Spring Boot 3(Java 21), JUnit5/Mockito, Testcontainers IT(AbstractIntegrationTest·패턴A)

**Spec:** `docs/superpowers/specs/2026-07-11-default-dj-playlist-design.md`
**Branch:** `feat/default-dj-playlist-329` (origin/develop 분기, 스펙 커밋 `af888905` 존재)
**Working dir:** `C:\Users\Eisen\Desktop\Labs\[projects] pfplay\pfplay-platform`
**빌드:** gradle은 항상 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix.

**확인된 기존 코드 사실 (재검증 불필요):**
- `PlaylistCommandService.createDefaultPlaylist`(라인 29-33): `PlaylistData.create(0, "그랩한 곡", PlaylistType.GRABLIST, userId)` + `aggregatePort.savePlaylist(playlist)`, `@Transactional`, 정책 미호출.
- `PlaylistSetupPort`(user BC, 메서드 1개) / `PlaylistSetupAdapter`(`app/.../bootstrap/adapter/`, `PlaylistCommandService` 위임).
- 호출 지점: `MemberSignService.java:126`(step 4 주석 아래) / `TemporaryUserInitializeService.java:113`(orElseGet 신규 멤버 분기 안).
- 기존 유닛 테스트 verify 지점: `MemberSignServiceTest` 라인 84·113(never)·145·169·191(never) / `TemporaryUserInitializeServiceTest:85`. `AdminUserServiceTest:91`은 adminPlaylistPort라 무관(무변경).
- `PlaylistCommandServiceTest`: `@ExtendWith(MockitoExtension)` + `@Mock PlaylistAggregatePort` + `ThreadLocalContext.setContext(mock AuthContext)` 패턴, `@AfterEach` clear.
- 봇 IT: `app/src/test/java/com/pfplaybackend/api/virtualcrew/VirtualUserPoolServiceIT.java` 기존재 — provision 검증 확장 적소.
- `PlaylistCreationPolicy.enforce(tier, count)`: AM=1, PLAYLIST 타입만 카운트, 초과 시 `ConflictException`(PLL-002).

---

## Chunk 1: 구현 (TDD)

### Task 1: `createDefaultDjPlaylist` 서비스 메서드

**Files:**
- Modify: `playlist/src/main/java/com/pfplaybackend/api/playlist/application/service/PlaylistCommandService.java`
- Test(Modify): `playlist/src/test/java/com/pfplaybackend/api/playlist/application/service/PlaylistCommandServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 추가** (기존 테스트 클래스에 — 파일의 기존 픽스처/패턴 재사용):

```java
    @Test
    @DisplayName("createDefaultDjPlaylist — PLAYLIST 타입 '내 플레이리스트'(order 1)를 정책 검사 없이 저장한다")
    void createDefaultDjPlaylist_savesPlaylistTypeWithFixedName() {
        playlistCommandService.createDefaultDjPlaylist(userId);

        ArgumentCaptor<PlaylistData> captor = ArgumentCaptor.forClass(PlaylistData.class);
        verify(aggregatePort).savePlaylist(captor.capture());
        PlaylistData saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(PlaylistType.PLAYLIST);
        assertThat(saved.getName()).isEqualTo("내 플레이리스트");
        assertThat(saved.getOrderNumber()).isEqualTo(1);
        assertThat(saved.getOwnerId()).isEqualTo(userId);
        // 정책 우회: findPlaylistsByOwnerAndType(상한 카운트용 조회)가 호출되지 않아야 한다
        verify(aggregatePort, never()).findPlaylistsByOwnerAndType(any(), any());
    }
```

(`ArgumentCaptor` import 추가. ✅리뷰 확정: `PlaylistData.create(Integer, String, PlaylistType, UserId)` / getter는 `getName()`·`getOrderNumber()`(Integer)·`getType()`·`getOwnerId()` — 위 코드 그대로 유효.)

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :playlist:test --tests "com.pfplaybackend.api.playlist.application.service.PlaylistCommandServiceTest"`
Expected: 컴파일 실패 — `createDefaultDjPlaylist` 미존재.

- [ ] **Step 3: 구현** — `createDefaultPlaylist` 바로 아래에 추가:

```java
    /**
     * 신규 가입자 온보딩용 기본 DJ 플레이리스트(#329).
     * GRABLIST 생성({@link #createDefaultPlaylist})과 동일하게 시스템 생성이라 생성 상한 정책을 거치지
     * 않는다(가입 시점 1회). AM 상한(1)은 이 플리가 소모하며, 유저는 rename/삭제가 자유롭고 삭제 시
     * 슬롯이 해제된다(정책은 현존 행 수 기준).
     *
     * <p>⚠️ 호출처는 <b>사람 가입 경로 한정</b>(MemberSignService·TemporaryUserInitializeService).
     * 봇/가상 멤버({@code AdminUserService.createVirtualMember} 경유 — 데모 멤버 포함)에는 배선하지
     * 않는다: 봇은 provision 이 자체 PLAYLIST 를 만들며, 송팩 적용이
     * {@code findByOwnerIdAndType(PLAYLIST)} 단건 조회에 의존하므로 두 번째 PLAYLIST 는 이를 파손한다.
     */
    @Transactional
    public void createDefaultDjPlaylist(UserId userId) {
        PlaylistData playlist = PlaylistData.create(1, "내 플레이리스트", PlaylistType.PLAYLIST, userId);
        aggregatePort.savePlaylist(playlist);
    }
```

- [ ] **Step 4: 통과 확인** — Step 2 명령 재실행, 클래스 전체 PASS(기존+신규).

- [ ] **Step 5: 커밋**

```bash
git add playlist/src/main/java/com/pfplaybackend/api/playlist/application/service/PlaylistCommandService.java playlist/src/test/java/com/pfplaybackend/api/playlist/application/service/PlaylistCommandServiceTest.java
git commit -m "feat(playlist): 기본 DJ 플레이리스트 생성 메서드 — 정책 우회·봇 경로 제외 명문화 (#329)"
```

### Task 2: 포트/어댑터 확장 + 소셜 가입 배선

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/application/port/out/PlaylistSetupPort.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/bootstrap/adapter/PlaylistSetupAdapter.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/application/service/MemberSignService.java`
- Test(Modify): `user/src/test/java/com/pfplaybackend/api/user/application/service/MemberSignServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 갱신** — `MemberSignServiceTest`의 verify 지점 5곳을 짝으로 확장:
  - 라인 84: `verify(playlistSetupPort).createDefaultPlaylist(savedAccount.getUserId());` 다음 줄에 `verify(playlistSetupPort).createDefaultDjPlaylist(savedAccount.getUserId());`
  - 라인 145·169: 동일 패턴으로 각각 추가.
  - 라인 113·191(never 케이스): `verify(playlistSetupPort, never()).createDefaultDjPlaylist(any(UserId.class));` 추가.

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=... ./gradlew :user:test --tests "com.pfplaybackend.api.user.application.service.MemberSignServiceTest"`
Expected: 컴파일 실패 — 포트에 메서드 없음.

- [ ] **Step 3: 구현**
  - `PlaylistSetupPort`: `void createDefaultDjPlaylist(UserId userId);` 추가 (javadoc: 신규 가입자 온보딩용 기본 PLAYLIST — 사람 가입 경로 전용).
  - `PlaylistSetupAdapter`: `@Override public void createDefaultDjPlaylist(UserId userId) { playlistCommandService.createDefaultDjPlaylist(userId); }`
  - `MemberSignService.java:126` 다음 줄: `playlistSetupPort.createDefaultDjPlaylist(userAccount.getUserId());` (step 4 주석을 "default playlists" 복수로 갱신).

- [ ] **Step 4: 통과 확인** — Step 2 명령 + `JAVA_HOME=... ./gradlew :app:compileJava` PASS.

- [ ] **Step 5: 커밋**

```bash
git add user/src/main/java/com/pfplaybackend/api/user/application/port/out/PlaylistSetupPort.java app/src/main/java/com/pfplaybackend/api/bootstrap/adapter/PlaylistSetupAdapter.java user/src/main/java/com/pfplaybackend/api/user/application/service/MemberSignService.java user/src/test/java/com/pfplaybackend/api/user/application/service/MemberSignServiceTest.java
git commit -m "feat(user): 소셜 가입 시 기본 DJ 플레이리스트 생성 배선 (#329)"
```

### Task 3: 게스트→회원 전환 배선

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/application/service/initialize/TemporaryUserInitializeService.java`
- Test(Modify): `user/src/test/java/com/pfplaybackend/api/user/application/service/initialize/TemporaryUserInitializeServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 갱신** — `:85`의 `verify(playlistSetupPort).createDefaultPlaylist(any(UserId.class));` 다음 줄에 `verify(playlistSetupPort).createDefaultDjPlaylist(any(UserId.class));` 추가. (✅리뷰 확정: 그 파일에 기존-멤버 재진입 테스트 없음 — never() 짝 불필요.)

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=... ./gradlew :user:test --tests "com.pfplaybackend.api.user.application.service.initialize.TemporaryUserInitializeServiceTest"`
Expected: FAIL — 신규 verify 미충족.

- [ ] **Step 3: 구현** — `TemporaryUserInitializeService.java:113` 다음 줄에 `playlistSetupPort.createDefaultDjPlaylist(userId);`

- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS.

- [ ] **Step 5: 커밋**

```bash
git add user/src/main/java/com/pfplaybackend/api/user/application/service/initialize/TemporaryUserInitializeService.java user/src/test/java/com/pfplaybackend/api/user/application/service/initialize/TemporaryUserInitializeServiceTest.java
git commit -m "feat(user): 게스트→회원 전환 시 기본 DJ 플레이리스트 생성 배선 (#329)"
```

### Task 4: IT 4건 — 가입 세트·봇 회귀·AM 상한·삭제 재생성

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/playlist/DefaultDjPlaylistIT.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/virtualcrew/VirtualUserPoolServiceIT.java`

- [ ] **Step 1: 기존 IT 픽스처 파악** — `VirtualUserPoolServiceIT` 읽고 provision 픽스처(mock/시드) 재사용 방식 확인. `AbstractIntegrationTest`의 `flushAndClear()`·`ThreadLocalContext` 관례는 `VirtualCrewAdminServiceIT` 참조. `TemporaryUserInitializeService.addAssociateMember`(또는 실제 public 진입 메서드 시그니처)를 확인해 가입 IT 진입점으로 사용 — MemberSignService 경로는 OAuth 의존이 무거우면 게스트→회원 경로로 가입 초기화를 대표 검증(두 경로 모두 유닛으로 배선 검증됨).

- [ ] **Step 2: 실패하는 IT 작성** (개념 코드 — Step 1 확인분으로 시그니처 조정):

`DefaultDjPlaylistIT` (신규, `@Transactional`, AbstractIntegrationTest):

```java
    @Test
    @DisplayName("가입 초기화 — GRABLIST 1 + '내 플레이리스트'(PLAYLIST) 1 이 생성된다")
    void signupInitialization_createsGrablistAndDefaultDjPlaylist() {
        UserId userId = initializeNewMemberFixture(); // ✅확정: temporaryUserInitializeService.addAssociateMember(UserId, String email) → MemberData (user BC @Service, app IT에서 주입 가능)
        flushAndClear();

        List<PlaylistData> grab = aggregatePort.findPlaylistsByOwnerAndType(userId, PlaylistType.GRABLIST);
        List<PlaylistData> normal = aggregatePort.findPlaylistsByOwnerAndType(userId, PlaylistType.PLAYLIST);
        assertThat(grab).hasSize(1);
        assertThat(normal).hasSize(1);
        assertThat(normal.get(0).getName()).isEqualTo("내 플레이리스트");
    }

    @Test
    @DisplayName("AM 유저(기본 플리 보유)가 추가 생성 시도 → PLL-002 EXCEEDED_PLAYLIST_LIMIT")
    void amUserWithDefaultPlaylist_cannotCreateAnother() {
        UserId userId = initializeNewMemberFixture();
        setAuthContext(userId, AuthorityTier.AM); // ThreadLocalContext 세팅 헬퍼
        assertThatThrownBy(() -> playlistCommandService.createPlaylist("두번째"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("기본 플리 삭제 후엔 다시 1개 생성 가능 — 락아웃 없음")
    void afterDeletingDefault_amUserCanCreateAgain() {
        UserId userId = initializeNewMemberFixture();
        setAuthContext(userId, AuthorityTier.AM);
        Long defaultId = /* PLAYLIST 타입 단건 조회로 id 획득 */;
        playlistCommandService.deletePlaylist(List.of(defaultId));
        PlaylistData recreated = playlistCommandService.createPlaylist("직접 만든 플리");
        assertThat(recreated.getType()).isEqualTo(PlaylistType.PLAYLIST);
    }
```

`VirtualUserPoolServiceIT`에 추가:

```java
    @Test
    @DisplayName("봇 provision — PLAYLIST 타입이 정확히 1개(봇 전용)여야 한다 (#329 기본 플리 이중 생성 회귀 방지)")
    void provision_botHasExactlyOnePlaylistTypePlaylist() {
        List<UserId> bots = poolService.provision(1);
        flushAndClear();
        List<PlaylistData> playlists = aggregatePort.findPlaylistsByOwnerAndType(bots.get(0), PlaylistType.PLAYLIST);
        assertThat(playlists).hasSize(1); // 두 번째 PLAYLIST가 생기면 playlistIdOf 단건 조회 파손
    }
```

(✅리뷰 확정 조회 수단: `PlaylistAggregatePort.findPlaylistsByOwnerAndType(UserId, PlaylistType)` → List — 이것을 사용. ⚠️`PlaylistRepository.findByOwnerIdAndType`는 **단건 반환**이라 2행이면 예외 — hasSize 단언용으로 금지. AuthContext 헬퍼 선례: `new AuthContext(userId, AuthorityTier.AM)` + `ThreadLocalContext.setContext`(CreateGeneralPartyRoomInvariantIntegrationTest:127 패턴). Task 2 verify 삽입은 라인번호가 밀리므로 인용된 verify 문 기준으로 앵커.)

- [ ] **Step 3: 실패/통과 확인** — 신규 IT 실행:

Run: `JAVA_HOME=... ./gradlew :app:integrationTest --tests "com.pfplaybackend.api.playlist.DefaultDjPlaylistIT" --tests "com.pfplaybackend.api.virtualcrew.VirtualUserPoolServiceIT"`
Expected: 전부 PASS (Tasks 1-3 구현이 선행돼 있으므로 그린이 정상 — 봇 테스트는 "지금도 1개"를 고정하는 회귀 가드).

- [ ] **Step 4: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/playlist/DefaultDjPlaylistIT.java app/src/test/java/com/pfplaybackend/api/virtualcrew/VirtualUserPoolServiceIT.java
git commit -m "test(playlist): 가입 기본 플리 세트·봇 단일 PLAYLIST 회귀·AM 상한·삭제 재생성 IT (#329)"
```

## Chunk 2: 검증 게이트 + PR

### Task 5: 전량 회귀 + 부팅 게이트 + 라이브 확인

- [ ] **Step 1: 전량 유닛** — `JAVA_HOME=... ./gradlew test`(전 모듈: app·user·playlist 등) → GREEN.
- [ ] **Step 2: 전량 IT** — `JAVA_HOME=... ./gradlew :app:integrationTest` → GREEN (선재 플래키 #320 1건만 빨간 경우 재실행 판정).
- [ ] **Step 3: fresh DB 부팅 게이트** — `./gradlew :app:bootJar -x test` → `docker compose -f docker-compose.local.yml -p pfplay-local down -v` → `--env-file .env.local up -d --build` → "Started Application" + `/v3/api-docs` 200 + 로그 ERROR 0.
- [ ] **Step 4: 라이브 확인** — 게스트 sign(`POST /api/v1/users/guests/sign`) → 회원 전환/신규 가입 경로(로컬 dev 경로, `EasyUserManagementController`는 !prod 노출) 후. 참고(리뷰): 부팅 시드 temp 유저(`addTemporaryUsers`)도 기본 플리를 받게 됨 — 의도된 부수효과. 플리 목록 조회로 "내 플레이리스트" 존재 확인. DB 직접 확인도 병행: `SELECT name,type FROM playlist WHERE user_id=<신규 userId>` → GRABLIST+PLAYLIST 각 1행.
- [ ] **Step 5: 웹 dj-register e2e 회귀** — pfplay-web을 `development` 브랜치로 체크아웃 후 로컬 풀스택 e2e: `E2E_BASE_URL="http://localhost:3000" E2E_API_BASE="http://localhost:8080/api/" npx playwright test --project=e2e-b` + `--project=mobile e2e/mobile/dj-register.spec.ts` (next dev 기동·예열 절차는 기존 메모리 준수). 기존 `createPlaylistWithTracks` 헬퍼 플로우가 기본 플리 존재로 깨지지 않는지 확인(추가 플리가 있어도 이름 기반 셀렉터라 무영향 예상 — 깨지면 원인 보고). 완료 후 web 브랜치 원복.

### Task 6: PR

- [ ] **Step 1: 커밋 정리** — Task별 4~5커밋이 논리단위면 유지(과분할 시 통합, 파괴적 rebase 전 사용자 확인).
- [ ] **Step 2: push + PR** — base `develop`, 제목 `feat(playlist): 신규 가입자 기본 플레이리스트 — DJ 온보딩 생성 단계 제거 (#329)`, 본문 한글(스펙 링크·확정 결정 4개·검증 증거·봇 제외 근거). CI 그린 확인. 머지=사용자 게이트.
