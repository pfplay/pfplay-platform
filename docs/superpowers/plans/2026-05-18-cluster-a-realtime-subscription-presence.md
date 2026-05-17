# Cluster A 실시간 구독·presence·세션→룸 통합 재설계 — 구현 계획

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (subagents 가용) 으로 실행. 스텝은 `- [ ]` 체크박스로 추적.

**Goal:** #30·#31·#5·#225 4증상을 단일 뿌리에서 통합 제거 — 라이프사이클·presence·구독격리를 서버 권위(WS 연결생존) + 클라 단일진실원천으로 재설계.

**Architecture:** L1 user-session 레지스트리(서버 presence 권위, subscribe-타이밍 의존 제거) / L2 클라 unload-exit 분리·제거 / L3 클라 `subscriptions[]` 단일진실원천 / L4 서버 단일룸 가드 + DJ큐 presence 통합. 스펙: `docs/superpowers/specs/2026-05-18-cluster-a-realtime-subscription-presence-design.md` (승인). piecemeal 금지 — 통합 1설계, 구현 4 PR.

**Tech Stack:** pfplay-platform = Java/Spring Boot, JDK 21 (`JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix 필수), JUnit5/Mockito/AssertJ, Testcontainers(`@Tag("integration")` → `:app:integrationTest`), Redis(V16 presence). pfplay-web = Next.js/React/TS, **vitest**(러너 — `package.json` `"test":"vitest run"`, `vitest.config.ts` include `src/**/*.test.{ts,tsx}`, jsdom, globals; **jest 아님**), 단일 파일: `yarn vitest run <path>`. `@stomp/stompjs`. 브랜치: 각 PR `feature/cluster-a-pr<n>-*` off `origin/develop`, develop 대상 한글 PR. 회귀잠금 = 각 PR 동반.

**공통 규약:** TDD(red→green→commit). 마이크로 커밋 로컬 OK, push 전 논리단위 squash. 이슈/커밋/PR 한글. verification before completion(테스트 결과 XML 확인). `:app:test`는 `@Tag("integration")` 제외 → 통합테스트는 `:app:integrationTest`.

---

## 파일 구조 (생성/수정 + 책임)

### PR-1 platform L1 (서버 권위)
- Create `app/.../party/application/service/UserSessionRegistry.java` — Redis 백킹 sessionId↔userId / userId→Set<sessionId>. 책임: STOMP 세션 생존 추적, "마지막 세션" 판정.
- Presence 룸 resolve: 기존 `PartyroomQueryPort.getActivePartyroomByUserId(UserId): Optional<ActivePartyroomDto>` 재사용(adapter `PartyroomAggregateAdapter:182`). **신규 포트 메서드 불요** — presence 경로에서 이 query port 호출 후 `dto.id()` → `new PartyroomId(...)` 매핑. (subscribe-타이밍 무관하게 DB crew 권위.)
- Modify `realtime/.../event/ConnectionEventListener.java` — passive→레지스트리 등록 + reconnect clearPending(CONNECT 시점).
- Modify `realtime/.../event/DisconnectionEventListener.java` — 레지스트리 제거 + 마지막세션→markPending(DB resolve).
- Modify `realtime/.../event/SubscriptionEventListener.java` — presence 트리거(onSessionConnected/세션캐시) 제거.
- Modify `app/.../party/adapter/out/realtime/PresencePortAdapter.java` — resolve를 세션캐시→레지스트리/DB 권위로 전환.
- Modify `realtime/.../port/PresencePort.java` (필요시 시그니처) — sessionId 대신 (userId|room) 기반 onConnected/onDisconnected.
- Modify `app/.../party/application/service/PartyroomAccessCommandService.java` — 같은-룸 재입장 분기에 `clearPending` 추가.
- Tests: `app/src/test/.../UserSessionRegistryTest.java`(단위), `.../party/application/service/PartyroomPresenceServiceClusterATest.java` 또는 통합 `.../ClusterAPresenceIntegrationTest.java`(Testcontainers), `PartyroomAccessCommandServiceTest`(재입장 clearPending).

### PR-2 platform L4 (단일룸 가드 + DJ큐 grace 잠금)
- Modify `realtime/.../event/SubscriptionEventListener.java` — 서버 단일룸 가드(권위 룸 불일치 SUBSCRIBE 거부, no-op+log).
- Modify `realtime/.../config/WebSocketConfig.java` — 7.5s 주석 정정(상한=reconcile cron 명기).
- Tests: `SubscriptionEventListenerTest`(가드), `app/src/test/.../DjQueueGraceIntegrationTest.java`(통합: markPending DjData불변/grace내 보존/만료 제거/현재DJ 균일), `PartyroomPresenceServiceTest`(reconcile stale sweep).

### PR-3 web L3 (클라 단일진실원천)
- Modify `src/shared/api/websocket/client.ts` — `Subscription`에 callback, `subscriptions[]` desired-state, (re)connect reconcile, unsubscribe 무조건 제거.
- Modify `src/entities/partyroom-client/lib/partyroom-client.ts` — `subscribe` replace 정책(throw 제거).
- Tests: `src/shared/api/websocket/client.test.ts`, `src/entities/partyroom-client/lib/partyroom-client.test.ts`.

### PR-4 web L2 (exit 분리)
- Create `src/features/partyroom/exit/lib/use-teardown-partyroom.ts` — (3) 클라 정리만.
- Modify `src/app/parties/(room)/[id]/layout.tsx` — unmount=teardown, beforeunload/pagehide 제거.
- Modify `src/features/partyroom/exit/lib/use-exit-partyroom.ts` — 명시 exit 전용으로 축소.
- Modify (정리) `exitedOnBackend`/`markExitedOnBackend` 사용처 — 불요처 제거.
- Tests: `src/app/parties/(room)/[id]/layout.test.tsx`(신규 생성, 현재 부재)·`use-teardown-partyroom.test.ts`·`use-exit-partyroom.test.ts`.

---

## Chunk 1: PR-1 — platform L1 user-session 레지스트리 + 리스너 재배선

브랜치: `git checkout -b feature/cluster-a-pr1-user-session-registry origin/develop`

### Task 1.1: UserSessionRegistry 단위 — 등록/제거/마지막세션 판정

**Files:** Create `app/src/main/java/com/pfplaybackend/api/party/application/service/UserSessionRegistry.java`; Test `app/src/test/java/com/pfplaybackend/api/party/application/service/UserSessionRegistryTest.java`

- [ ] **Step 1: 실패 테스트** — `UserSessionRegistryTest`: (a) `register(sid,uid)` 후 `isLastSession` 판정, (b) 동일 uid 2세션 중 1개 `unregister`→`wasLastSession`=false, (c) 마지막 `unregister`→true, (d) `findUserBySession(sid)` 반환. Redis는 `@Mock RedisTemplate` 또는 임베디드. 우선 in-memory 추상화 인터페이스로 단위(Redis 어댑터는 통합에서).

```java
@ExtendWith(MockitoExtension.class)
class UserSessionRegistryTest {
  // given registry backed by a fake store
  // when register("s1", U1); register("s2", U1)
  // then unregister("s1") -> wasLast=false ; unregister("s2") -> wasLast=true
  // and findUserBySession("s2")==U1 before unregister
}
```

- [ ] **Step 2: 실패 확인** — `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserSessionRegistryTest" --console=plain` → FAIL(클래스 없음).
- [ ] **Step 3: 최소 구현** — `UserSessionRegistry`: `presence:session:{sid}`→uid, `presence:usersessions:{uid}`→Set<sid>. 메서드 `register(sid,uid)`, `unregister(sid)`→`UnregisterResult{userId, wasLastSession}`, `findUserBySession(sid)`. Redis 연산은 주입 포트 뒤. 원자성: usersessions Set `SREM` 후 `SCARD`==0 이면 wasLast=true(Lua 또는 파이프라인; 단위는 fake로).
- [ ] **Step 4: 통과 확인** — 동일 명령 PASS. 결과 XML 확인 `app/build/test-results/test/TEST-*UserSessionRegistryTest.xml` tests>0 failures=0.
- [ ] **Step 5: 커밋** — `git add` 신규 2파일; `git commit -m "feat(presence): user-session 레지스트리 — 세션 생존·마지막세션 판정 (#209)"`

### Task 1.2: Presence 룸 resolve = 기존 PartyroomQueryPort 재사용 (신규 포트 불요)

**Files:** 확인만 — `app/.../party/application/port/...PartyroomQueryPort.java` 의 `getActivePartyroomByUserId(UserId): Optional<ActivePartyroomDto>` 및 adapter `PartyroomAggregateAdapter` (~line 182). Test 통합 `ClusterAPresenceIntegrationTest`(`@Tag("integration")`, extends `AbstractIntegrationTest`).

- [ ] **Step 1: 실패 테스트** — `ClusterAPresenceIntegrationTest`: active crew 1행 저장 후 `getActivePartyroomByUserId(userId)` 가 그 `ActivePartyroomDto`(`.id()`=partyroomId) 반환, 비active면 `Optional.empty()`. (presence 경로가 이 query port로 룸 권위 resolve 함을 잠금.)
- [ ] **Step 2: 실패 확인** — `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*ClusterAPresenceIntegrationTest" --console=plain` → FAIL(클래스 없음/RED).
- [ ] **Step 3: 구현** — 신규 포트 메서드 추가하지 말 것. presence resolve 헬퍼(Task 1.3 PresencePortAdapter 내부)가 `partyroomQueryPort.getActivePartyroomByUserId(userId)` 호출 → `dto -> new PartyroomId(dto.id())` 매핑. 의존 주입 추가(기존 query port 빈). 이 태스크 산출=통합테스트가 query port의 active-by-user 계약을 잠금(GREEN이 되도록 의존 배선은 Task 1.3에서 완성 — 본 태스크는 계약 characterization).
- [ ] **Step 4: 통과 확인** — integrationTest PASS, XML `app/build/test-results/integrationTest/TEST-*ClusterAPresenceIntegrationTest.xml` tests>0 failures=0. (참고: 본 태스크는 기존 query-port 계약 characterization 잠금 — 곧바로 GREEN 정상, RED 강요 금지. 실제 resolve 배선은 Task 1.3에서 완성.)
- [ ] **Step 5: 커밋** — `test(presence): 룸 권위 resolve = PartyroomQueryPort.getActivePartyroomByUserId 계약 잠금 (#209)`

### Task 1.3: PresencePort/Adapter — sessionId 캐시 의존 제거, 레지스트리/DB 권위

**Files:** Modify `realtime/.../port/PresencePort.java`, `app/.../adapter/out/realtime/PresencePortAdapter.java`; Test `PresencePortAdapterTest`(신규/수정, Mockito).

- [ ] **Step 1: 실패 테스트** — `onSessionConnected(sessionId)`: 레지스트리에서 uid resolve → `partyroomQueryPort.getActivePartyroomByUserId(uid)` 있으면 `presenceService.clearPending(new PartyroomId(dto.id()),uid)`. `onSessionDisconnected(sessionId)`: 레지스트리 `unregister`→wasLast면 동일 query port로 룸 resolve→`markPending`. 세션캐시(`sessionCachePort`) resolve 미사용 검증(no interaction).
- [ ] **Step 2: 실패 확인** — `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*PresencePortAdapterTest" --console=plain` FAIL.
- [ ] **Step 3: 구현** — `PresencePortAdapter`가 `UserSessionRegistry` + `partyroomQueryPort.getActivePartyroomByUserId`(→`new PartyroomId(dto.id())`) 사용. 세션캐시 기반 resolve 제거. `PresencePort` 시그니처 유지(sessionId in) — 내부 resolve만 교체(리스너 영향 최소, 리뷰 risk2 회피).
- [ ] **Step 4: 통과 확인** — PASS + XML.
- [ ] **Step 5: 커밋** — `refactor(presence): PresencePortAdapter resolve를 레지스트리·DB 권위로 (#209)`

### Task 1.4: ConnectionEventListener — 등록 + reconnect clearPending(CONNECT 시점)

**Files:** Modify `realtime/.../event/ConnectionEventListener.java`; Test `ConnectionEventListenerTest`(신규).

- [ ] **Step 1: 실패 테스트** — `SessionConnectEvent`(Principal=uid, sessionId) → `registry.register(sid,uid)` 호출 + `presencePort.onSessionConnected(sid)` 호출. Principal null이면 무시(로그).
- [ ] **Step 2: 실패 확인** FAIL.
- [ ] **Step 3: 구현** — `ConnectionEventListener implements ApplicationListener<SessionConnectEvent>`: `StompHeaderAccessor`에서 `getUser()`(Principal=uid, WebSocketConfig.determineUser가 handshake서 바인딩)·sessionId 추출 → register + onSessionConnected.
- [ ] **Step 4: 통과 확인** PASS + XML.
- [ ] **Step 5: 커밋** — `feat(presence): ConnectionEventListener 레지스트리 등록 + CONNECT 시점 clearPending (#209 SE4)`

### Task 1.5: DisconnectionEventListener — 레지스트리 제거 + 마지막세션 markPending

**Files:** Modify `realtime/.../event/DisconnectionEventListener.java`; Test `DisconnectionEventListenerTest`(수정).

- [ ] **Step 1: 실패 테스트** — DISCONNECT: `registry.unregister(sid)` 호출, wasLast=true면 `presencePort.onSessionDisconnected(sid)`→markPending 경로; wasLast=false면 markPending 미발생(멀티탭 E1). resolve-before 순서: 세션캐시 delete 제거(레지스트리가 진실).
- [ ] **Step 2: 실패 확인** FAIL.
- [ ] **Step 3: 구현** — 기존 `presencePort.onSessionDisconnected` 호출을 레지스트리 unregister 결과 게이트로(wasLast일 때만 진행). **세션캐시 delete 라인(`sessionCachePort.deleteSessionCache(sessionId)`, 현 `DisconnectionEventListener:28`) 제거** — PR-1에서 resolve가 레지스트리/DB 권위로 이동하므로 presence가 세션캐시를 더는 안 씀. 세션캐시는 presence 외 프로덕션 용도 없음(Subscription/Unsubscription/Disconnection만 presence 목적 사용) → 호출 완전 제거. (Subscription 쪽 save 제거는 Task 1.6, Unsubscription delete 잔존 제거는 Task 1.6에서 일괄.)
- [ ] **Step 4: 통과 확인** PASS + XML.
- [ ] **Step 5: 커밋** — `feat(presence): DisconnectionEventListener 마지막세션만 markPending, 멀티탭 자기축출 해소 (#209 SE5)`

### Task 1.6: SubscriptionEventListener — presence 트리거 제거

**Files:** Modify `realtime/.../event/SubscriptionEventListener.java`; Test `SubscriptionEventListenerTest`(수정).

- [ ] **Step 1: 실패 테스트** — SUBSCRIBE 시 `presencePort.onSessionConnected` **미호출**(presence는 CONNECT 책임), presence용 `saveSessionCache` 미호출. (단일룸 가드는 PR-2.)
- [ ] **Step 2: 실패 확인** FAIL.
- [ ] **Step 3: 구현** — `SubscriptionEventListener`에서 `presencePort.onSessionConnected(...)` 및 presence 목적 `sessionCachePort.saveSessionCache(...)` 호출 제거(presence는 CONNECT 책임). 세션캐시는 presence 외 프로덕션 용도 없음(Task 1.5에서 확인) → 관련 호출 일괄 제거, 잔존 시 주석으로 비-presence 용도 명시. either/or 금지 — 제거가 기본.
- [ ] **Step 4: 통과 확인** PASS + XML.
- [ ] **Step 5: 커밋** — `refactor(presence): SubscriptionEventListener presence 트리거 제거 — subscribe-타이밍 의존 종결 (#209 #31)`

### Task 1.7: REST 같은-룸 재입장 clearPending (SE4 이중방어)

**Files:** Modify `app/.../party/application/service/PartyroomAccessCommandService.java` (같은-룸 재입장 분기, 검증된 ~lines 93-104); Test `PartyroomAccessCommandServiceTest`.

- [ ] **Step 1: 실패 테스트** — 같은-룸 재입장(이미 active) 시 `presenceService.clearPending(partyroomId,userId)` 호출됨 verify. 다른-룸/신규 입장 분기엔 영향 없음.
- [ ] **Step 2: 실패 확인** `:app:test --tests "*PartyroomAccessCommandServiceTest"` FAIL.
- [ ] **Step 3: 구현** — 같은-룸 분기에 `presenceService.clearPending(partyroomId, userId);` 추가(countryCode 갱신 인접).
- [ ] **Step 4: 통과 확인** PASS + XML.
- [ ] **Step 5: 커밋** — `feat(presence): 같은-룸 REST 재입장 시 clearPending — SE4 레이스 이중방어 (#209 #225)`

### Task 1.8: 통합 회귀잠금 — #31 / SE4 / 멀티탭

**Files:** `app/src/test/.../ClusterAPresenceIntegrationTest.java`(확장, `@Tag("integration")`)

- [ ] **Step 1: 실패 테스트** — 시나리오 통합: (#31) subscribe-before-enter 순서여도 disconnect→markPending(레지스트리 CONNECT 등록 기반, subscribe 타이밍 무관) → grace 만료 forceOffline→exited_at 갱신. (SE4) disconnect→markPending→CONNECT 재연결→clearPending(pending=NULL). (E1) 2세션 중 1 disconnect→markPending 미발생.
- [ ] **Step 2: 실패 확인** `:app:integrationTest --tests "*ClusterAPresenceIntegrationTest"` FAIL(미구현 시) 또는 시나리오 RED.
- [ ] **Step 3: 구현/보정** — 앞 태스크 통합으로 GREEN 되게 누락분 보정.
- [ ] **Step 4: 통과 확인** integrationTest PASS, XML tests>0 failures=0.
- [ ] **Step 5: 커밋** — `test(presence): #31·SE4·멀티탭 통합 회귀잠금 (#209)`

### Task 1.9: PR-1 통합검증 + PR

- [ ] **Step 1** — 영향 테스트 스위트 전체 GREEN: `:app:test` 관련 + `:app:integrationTest --tests "*ClusterA*" "*Presence*" "*EventListener*"`. 회귀 확인: 기존 `PartyroomPresenceServiceTest`,`SubscriptionEventListenerTest`,`PartyroomAccessCommandServiceTest`.
- [ ] **Step 2** — 마이크로커밋 논리단위 squash(필요시).
- [ ] **Step 3** — push `-u origin feature/cluster-a-pr1-user-session-registry`; `gh pr create --base develop` 한글 본문(스펙 §4 C1-C3, #209/#225, 회귀 #31/SE4/E1, 트레이드오프 §9). CI green 확인.
- [ ] **Step 4** — 로드맵 LIVE + 메모리: A/#31·#225 "PR-1 develop" 갱신.

---

## Chunk 2: PR-2 — platform L4 서버 단일룸 가드 + DJ큐 grace 잠금 + SE10

브랜치: `git checkout -b feature/cluster-a-pr2-single-room-guard origin/develop` (PR-1 머지 후 rebase 또는 develop 기준; presence 코드 충돌 시 PR-1 선행)

### Task 2.1: 서버 단일룸 SUBSCRIBE 가드

**Files:** Modify `realtime/.../event/SubscriptionEventListener.java`; Test `SubscriptionEventListenerTest`.

- [ ] **Step 1: 실패 테스트** — SUBSCRIBE `/sub/partyrooms/{id}`에서 id가 `partyroomQueryPort.getActivePartyroomByUserId(uid)`의 `dto.id()`(권위 룸)와 불일치 → 등록 거부(no-op, WARN 로그), 일치 → 통과. 권위 룸 없음(미입장, `Optional.empty()`)인데 SUBSCRIBE → 거부(로그). negative-ACK 없음(클라 계약: 조용한 미등록).
- [ ] **Step 2: 실패 확인** `:app:test --tests "*SubscriptionEventListenerTest"` FAIL.
- [ ] **Step 3: 구현** — destination 파싱 partyroomId vs 권위 룸 비교. 불일치 시 early-return + `log.warn`. (subscribe 자체 STOMP 등록은 막을 수 없으니 서버측 처리/세션 연관만 거부 — broadcast 라우팅은 destination 기반이므로 추가 서버측 차단 필요 시 메시지 라우팅 단계 가드도 검토; 최소: 서버 부수효과/세션연관 미수행 + 로그. 스펙 §4 C2 계약 준수.)
- [ ] **Step 4: 통과 확인** PASS + XML.
- [ ] **Step 5: 커밋** — `feat(presence): 서버 단일룸 SUBSCRIBE 가드 — #30 백엔드 방어 (web#298)`

### Task 2.2: DJ큐 grace 통합 회귀잠금

**Files:** `app/src/test/.../DjQueueGraceIntegrationTest.java`(신규, `@Tag("integration")`). **성격: characterization 회귀잠금** — 설계상 PR-1 후 자연 도출이라 첫 작성에 곧바로 GREEN일 수 있음. 그게 정상(= invariant 잠금 성공). **RED를 억지로 찾아 루프 돌지 말 것**; 만약 RED면 그 시나리오가 실제 미보장 → 최소 보정.

- [ ] **Step 1: 실패 테스트** — (a) `markPending` 호출 후 DjData 행 불변(orderNumber·존재). (b) DJ가 disconnect→markPending→grace내 재연결(clearPending)→DjData 자리·순서 보존. (c) grace 만료 forceOffline→exitInternal→handleDjQueueOnLeave→DjData 제거 + (wasCurrentDj면) skipPlayback. (d) 현재 DJ disconnect: grace 동안 doStart가 pending DJ 그대로 재생(스킵 안 함), 만료시에만 skip.
- [ ] **Step 2: 실패 확인** `:app:integrationTest --tests "*DjQueueGraceIntegrationTest"` FAIL/RED.
- [ ] **Step 3: 구현/보정** — 대부분 GREEN(설계상 자연 도출). pending DJ 체크를 doStart에 **추가하지 않음**(SE1). 누락 행위만 최소 보정.
- [ ] **Step 4: 통과 확인** integrationTest PASS, XML.
- [ ] **Step 5: 커밋** — `test(presence): DJ큐 라이프사이클 presence grace 통합 회귀잠금 (#225 SE1)`

### Task 2.3: SE10 — 7.5s 주석 정정 + reconcile backstop 테스트

**Files:** Modify `realtime/.../config/WebSocketConfig.java`(주석); Test `PartyroomPresenceServiceTest` 또는 통합 reconcile sweep.

- [ ] **Step 1: 실패 테스트** — reconcile cron characterization: `pending_exit_at < threshold` stale crew → forceOffline 정리(이미 존재하면 회귀잠금만, 첫 작성 GREEN 정상 — RED 강요 금지). 신규 단언: 유령 상한 보장 주체가 cron(maxGrace+버퍼)임(heartbeat 미검출 시뮬레이션 → cron 정리).
- [ ] **Step 2: 실패/통과 확인** — characterization이므로 GREEN이면 잠금 성공으로 진행. RED면 누락 보정.
- [ ] **Step 3: 구현** — WebSocketConfig 7.5s 주석을 "실제 유령 상한 = reconcile cron(maxGrace+버퍼), heartbeat는 fast-path"로 정정. 로직 변경 없음(있다면 최소).
- [ ] **Step 4: 통과 확인** PASS + XML.
- [ ] **Step 5: 커밋** — `docs+test(presence): SE10 — 유령 상한=reconcile cron 명기 + backstop 회귀 (#225)`

### Task 2.4: PR-2 통합검증 + PR

- [ ] **Step 1** — `:app:test` 관련 + `:app:integrationTest --tests "*DjQueueGrace*" "*SubscriptionEventListener*"` GREEN. 기존 `PlaybackCommandServiceTest`·`TrackRepositoryReorderIntegrationTest`(#222) 회귀 확인.
- [ ] **Step 2** — squash; push; `gh pr create --base develop` 한글(스펙 §4 C2가드/C4/C5, web#298 백엔드방어, #225, 회귀).
- [ ] **Step 3** — 로드맵/메모리 갱신.

---

## Chunk 3: PR-3 — web L3 SocketClient 단일진실원천 + PartyroomClient replace

레포: `pfplay-web`. 브랜치: `cd ../pfplay-web && git fetch origin && git checkout -b feature/cluster-a-pr3-subscriptions-sot origin/develop` (develop 기준 — web 환경 매핑 확인).

### Task 3.1: SocketClient — subscriptions[]에 callback + 단일진실원천 reconcile

**Files:** Modify `src/shared/api/websocket/client.ts`; Test `src/shared/api/websocket/client.test.ts`.

- [ ] **Step 1: 실패 테스트**(vitest, `yarn vitest run src/shared/api/websocket/client.test.ts`) — (a) `subscribe(d,cb)` 후 `subscriptions`에 `{destination:d,callback:cb}` 동기 존재(connected/!connected 무관). (b) reconnect(handleConnect) 시 `subscriptions` 순회 재구독, `onConnectQueue`엔 subscribe 콜백 없음. (c) `unsubscribe(d)`가 `!connected`여도 `subscriptions`에서 제거 → 이후 reconnect 재구독 안 함. (d) A subscribe→unsubscribe→B subscribe→disconnect→connect ⇒ STOMP subscribe는 B만.
- [ ] **Step 2: 실패 확인** — `cd "../pfplay-web" && yarn vitest run src/shared/api/websocket/client.test.ts` → FAIL.
- [ ] **Step 3: 구현** — `interface Subscription { destination; callback; ... }`. `subscribe`: onConnect 래핑 제거 → `subscriptions.push({destination,callback})` 동기 + `if(connected) this.client.subscribe(...)`. `handleConnect`: `subscriptions` 순회하여 각 `this.client.subscribe(destination,callback)` 재바인딩(중복 없게 기존 STOMP핸들 정리). `unsubscribe`: `if(connected)` STOMP unsub, **무조건** `subscriptions=subscriptions.filter(...)`. `handleDisconnect`: 라이브 핸들만 해제, `subscriptions`(desired) 보존. `onConnectQueue`에서 subscribe 콜백 미적재(heartbeat 등만).
- [ ] **Step 4: 통과 확인** vitest PASS.
- [ ] **Step 5: 커밋** — `fix(websocket): subscriptions[] 단일진실원천 — reconnect stale 부활·!connected 누수 해소 (#5 #30)`

### Task 3.2: PartyroomClient — replace 정책(throw 제거)

**Files:** Modify `src/entities/partyroom-client/lib/partyroom-client.ts`; Test `partyroom-client.test.ts`.

- [ ] **Step 1: 실패 테스트** — 기존 룸 구독 중 `subscribe(B)` → throw 안 함, 기존 룸 unsubscribe 후 B subscribe, `subscribedRoomId=B`, `socketClient.subscriptions` 길이 1(B). 기존 throw 케이스 테스트 제거/수정.
- [ ] **Step 2: 실패 확인** vitest FAIL.
- [ ] **Step 3: 구현** — `subscribe(partyroomId)`: `if(this.subscribedRoomId!=null && this.subscribedRoomId!==partyroomId) this.unsubscribeCurrentRoom();` 그 후 `socketClient.subscribe(...)` + `subscribedRoomId=partyroomId`. throw 제거.
- [ ] **Step 4: 통과 확인** vitest PASS.
- [ ] **Step 5: 커밋** — `fix(partyroom-client): subscribe replace 정책 — #30 throw→silent→push 증폭 발원 제거 (web#298)`

### Task 3.3: PR-3 회귀 + PR

- [ ] **Step 1** — web 테스트 스위트 GREEN(`client.test.ts`,`partyroom-client.test.ts`,`handle-subscription-event.test.ts` 등 영향분). A→B→offline/online→B만 수신 시나리오 잠금.
- [ ] **Step 2** — squash; push; `gh pr create --base develop`(web) 한글(스펙 §4 C6/C7, #5/#30, 회귀 T8/T9).
- [ ] **Step 3** — 로드맵/메모리 갱신(web#298 PR-3 develop).

---

## Chunk 4: PR-4 — web L2 exit() 분리 + unload/unmount backend-exit 제거

레포: `pfplay-web`. 브랜치: `feature/cluster-a-pr4-exit-split` off develop (PR-3 후).

### Task 4.1: useTeardownPartyroom 신규 (클라 정리만)

**Files:** Create `src/features/partyroom/exit/lib/use-teardown-partyroom.ts`; Test `...use-teardown-partyroom.test.ts`.

- [ ] **Step 1: 실패 테스트** — 호출 시 `unsubscribeCurrentRoom`+`resetPartyroomStore`+`trackPartyroomExited`만, 백엔드 mutation 미호출.
- [ ] **Step 2: 실패 확인** vitest FAIL(없음).
- [ ] **Step 3: 구현** — `useExitPartyroom` 본문에서 (3)만 추출한 훅.
- [ ] **Step 4: 통과 확인** vitest PASS.
- [ ] **Step 5: 커밋** — `feat(partyroom): useTeardownPartyroom — 클라 정리/백엔드 exit 분리 (web#298 #225)`

### Task 4.2: layout.tsx — unmount=teardown, beforeunload/pagehide 제거

**Files:** Modify `src/app/parties/(room)/[id]/layout.tsx`; **Create** `src/app/parties/(room)/[id]/layout.test.tsx`(현재 부재 확정 — 신규 생성. vitest include `src/**/*.test.{ts,tsx}` 가 `.tsx` 포함 확인됨, jsdom).

- [ ] **Step 1: 실패 테스트** — `layout.test.tsx` 신규: unmount cleanup 시 teardown 호출·백엔드 exit 미호출. `window.addEventListener('beforeunload'|'pagehide', ...)` 미등록(spy).
- [ ] **Step 2: 실패 확인** FAIL.
- [ ] **Step 3: 구현** — `enter()` 유지. `beforeunload`/`pagehide` add/removeEventListener 제거. cleanup `return () => useTeardownPartyroom()` 사용.
- [ ] **Step 4: 통과 확인** vitest PASS.
- [ ] **Step 5: 커밋** — `fix(partyroom): unload/unmount 백엔드-exit 제거, unmount=teardown — #225a #30 증폭 종결 (web#298)`

### Task 4.3: 명시 exit 경로 정리 + exitedOnBackend 단순화

**Files:** Modify `src/features/partyroom/exit/lib/use-exit-partyroom.ts`(명시 전용), 사용처(`partyroom-card`·`use-penalty-alert`·`use-sign-out`·`use-enter-partyroom`) `markExitedOnBackend` 불요분 제거; Test 각 영향.

- [ ] **Step 1: 실패 테스트** — sign-out은 명시 backend exit 유지(호출 verify). 룸전환은 서버 tryEnter auto-exit 의존(클라 DELETE 미호출, teardown만). penalty/expel은 서버 처리(클라 teardown만). `exitedOnBackend` 불요 분기 제거 후 회귀(이중 DELETE 없음).
- [ ] **Step 2: 실패 확인** FAIL.
- [ ] **Step 3: 구현** — `useExitPartyroom`을 명시 backend-exit 전용으로 축소. `markExitedOnBackend` workaround 사용처 정리(스펙 §4 C8). `MainPartyroomCard` 부수버그 자연 소멸 확인.
- [ ] **Step 4: 통과 확인** vitest PASS.
- [ ] **Step 5: 커밋** — `refactor(partyroom): exit 경로 명시-전용 축소 + exitedOnBackend workaround 정리 (web#298)`

### Task 4.4: PR-4 회귀 + PR

- [ ] **Step 1** — web 스위트 GREEN. 시나리오: 새고침 grace내→DJ자리 유지(서버 PR-1/2와 합쳐 수용테스트는 스테이징/수동), 룸전환 정상, sign-out exit 유지.
- [ ] **Step 2** — squash; push; `gh pr create --base develop`(web) 한글(스펙 §4 C8/C9, #225a/#30, 회귀 T10/T11, §9 트레이드오프, C9 follow-up 이슈 링크).
- [ ] **Step 3** — 로드맵/메모리: Cluster A 4 PR 상태 갱신. C9(SE3 애널리틱스) 별도 follow-up 이슈 등록.

---

## 완료 정의 (Cluster A)

- PR-1~4 develop 머지, 각 회귀잠금 GREEN(XML 검증).
- 4증상 수용: #30 입장튕김0 / #31 탭닫기→grace·cron 정리 / #5 A→B→offline/online→B만 / #225 새고침 grace내 DJ자리 유지·결정적.
- 로드맵 LIVE Cluster A 행 갱신, 메모리 진입점 갱신. C9(SE3) follow-up 이슈 분리.
- prod(main) 진입은 별도 release 게이트(스펙 §8).

## 참조 스킬
- @superpowers:test-driven-development (red→green)
- @superpowers:subagent-driven-development (실행)
- @superpowers:verification-before-completion (XML 증거)
- @superpowers:finishing-a-development-branch (PR/머지)
