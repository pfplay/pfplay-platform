# Cluster A — 실시간 구독·presence·세션→룸 매핑 통합 재설계

- **작성일**: 2026-05-18
- **상태**: DESIGN (승인됨 — 구현 진입)
- **GitHub**: pfplay-platform #209(#31)·#225, web#298(#30·#5), 회귀 연계 #222
- **관련 설계**: `2026-05-09-presence-grace-window-design.md`(V16 grace), `2026-05-09-crew-grade-host-invariant-design.md`
- **진실원천(로드맵)**: `bugs/2026-05-14-bug-fix-roadmap.md` Cluster A
- **불변 제약**: piecemeal 금지 — 본 스펙은 통합 1건. 구현은 invariant별 멀티 PR이나 설계는 단일.

## 1. 문제 — 4개 증상, 한 뿌리

| ID | 증상 | repo |
|---|---|---|
| #30 | 입장 직후 의도무관 강제 퇴장 (multi-sub invariant 무력화) | web(+platform 방어) |
| #31 | 탭/브라우저 닫기 시 서버측 퇴장 미처리 (유령 크루) | platform |
| #5 | reconnect 시 stale STOMP 구독 부활 → 크로스룸 메시지 하이재킹 | web |
| #225 | 새로고침/연결끊김 시 DJ 대기열 의도무관 탈락 (grace 우회 레이스) | platform+web |

**공통 뿌리**: 라이프사이클(입퇴장)·presence·구독 격리·DJ큐 상태가 **권위적 서버 상태머신이 아니라 STOMP subscribe 타이밍 + 클라 best-effort unload exit**에 결합되어 있다. 같은 결함의 네 증상이므로 개별 패치는 상호 재파손 — 통합 재설계한다.

근거(코드 확정, 2026-05-17~18 검증):
- 세션→룸 매핑이 `SubscriptionEventListener`→`RedisSessionCacheAdapter.saveSessionCache`의 **SUBSCRIBE 시점 `getActivePartyroomByUserId` 조회**에 게이트. enter(REST)가 subscribe 전이면 캐시 미기록 → disconnect 시 `markPending` silent no-op → `exited_at` 영영 미갱신 (#31).
- 클라 `SocketClient.subscribe`가 `onConnectQueue`에 **영구 콜백** 적재, `unsubscribe`는 큐 미정리 → reconnect 시 stale 룸 부활 (#5). `unsubscribe`는 `!connected`시 `subscriptions[]` 미제거 early-return, `PartyroomClient.unsubscribeCurrentRoom`은 `subscribedRoomId=undefined` 무조건 → desync → 가드 영구 throw → `silent` 삼킴 → `router.push('/parties')` → layout unmount → `exit()` (#30 증폭).
- `app/parties/(room)/[id]/layout.tsx`가 `beforeunload`/`pagehide`/unmount에 `exit()`(일반 axios `DELETE /crews/me`, sendBeacon/keepalive 아님) 등록. DJ 제거는 `exitInternal`→`handleDjQueueOnLeave`→`removeDjFromQueue` 한 경로뿐(grace 비게이트). 새고침=unload 시 클라가 권위 exit를 쏘므로 grace 우회·비결정 레이스 (#225).

## 2. Invariant (회귀테스트로 잠금)

1. **단일 룸 구독**: 한 시점 한 클라는 ≤1개 `/sub/partyrooms/*` 구독. 클라+서버 양쪽 강제.
2. **라이프사이클 권위 = 서버 grace 상태머신**, 구동 신호는 **WS 연결 생존(STOMP CONNECT/DISCONNECT)** 단 하나.
3. **즉시 backend exit = 의도적 액션만**: {룸전환(서버 tryEnter auto-exit), penalty/expel(서버), sign-out(명시)}. 그 외(닫기·새고침·네트워크·백그라운드·룸 아닌 인앱이탈) = DISCONNECT→grace.
4. **room 진실원천 = REST crew**(enter/exit). subscribe-시점 active 조회·세션캐시 추론 폐기.
5. **DJ큐 라이프사이클은 presence 상태머신에 종속**: grace 내 재연결 시 DJ 자리·순서 보존, grace 만료시에만 탈락.

## 3. 4개 레버 (확정 결정)

- **L1 서버 권위 (user-session 레지스트리)**: STOMP CONNECT `Principal=uid` 기반 sessionId→userId / userId→Set<sessionId>. presence grace = "유저 라이브 세션 0개" 기준. 룸은 DB crew 권위로 resolve. subscribe-타이밍 의존 제거.
- **L2 클라 unload-exit 제거 (split)**: `beforeunload`/`pagehide` 자동 exit 및 unmount 백엔드-exit 제거. **클라 정리(unsubscribe+store reset+analytics)는 신규 `useTeardownPartyroom`로 unmount 유지**, 백엔드 DELETE만 의도적 액션 게이트.
- **L3 클라 구독 단일진실원천 (방향 B)**: `subscriptions[]`가 `{destination,callback}` 보유, (re)connect 시 그 배열만 reconcile, unsubscribe는 연결상태 무관 배열에서 제거. `onConnectQueue`는 subscribe 콜백 미보유.
- **L4 서버 단일룸 가드 + DJ큐 presence 통합**: SUBSCRIBE가 유저 권위 active 룸과 불일치면 서버 거부. DJ 제거는 grace 만료(`forceOffline`→`exitInternal`)에서만(자연 도출, 회귀잠금).

**SE1 결정**: 현재 재생 중 DJ도 균일 grace — 끊겨도 재생 지속, grace 만료시에만 `skipPlayback`. `doStart`에 pending DJ 체크 추가 안 함.

## 4. 컴포넌트 변경

### 4.1 서버 (pfplay-platform)

**C1 User-Session 레지스트리 (신규, Redis 백킹)**
- 키: `presence:session:{sessionId}` → userId (TTL 안전망), `presence:usersessions:{userId}` → Redis Set<sessionId>.
- V16 Redis 사용과 일관, 인스턴스 재시작·멀티노드(멀티탭 상이노드) 견딤.

**C2 리스너 재배선**
- `ConnectionEventListener`(현 passive): `SessionConnectEvent`에서 Principal=uid+sessionId 레지스트리 등록. 유저 pending이면 `clearPending`(**CONNECT 시점 — SE4 fix, SUBSCRIBE 비의존**).
  - **왜 CONNECT에 Principal이 있나(load-bearing 근거)**: `WebSocketConfig`의 `determineUser`가 handshake 단계에서 `attributes.get("uid")`로 Principal을 바인딩한다. 따라서 `SessionConnectEvent`(CONNECT 프레임)에 이미 `Principal=uid` 존재 — SUBSCRIBE를 기다릴 필요 없음. (현 `ConnectionEventListener`는 sessionId 로깅만 하고 `getUser()` 미사용이라 "CONNECT엔 인증 없음"으로 오해 금지.) 이 사실이 PR-1의 가장 load-bearing한 전제.
- `DisconnectionEventListener`: 레지스트리에서 sessionId 제거. 유저의 **마지막** 세션이면 → 유저 active 룸을 `aggregatePort.findActiveRoomByUser`(DB 권위)로 resolve → `markPending`. resolve-before-cache-delete 순서 유지.
- `SubscriptionEventListener`: presence 트리거 제거. **서버 단일룸 가드** 추가: SUBSCRIBE `/sub/partyrooms/{id}`의 id가 유저 권위 active 룸과 불일치 → 등록 거부(no-op, 로그). presence용 세션캐시 기록 제거.
  - **거부 시 클라 관측 계약(명시)**: STOMP 브로커엔 SUBSCRIBE negative-ACK가 없으므로 거부=조용히 미등록(클라는 구독했다 믿지만 메시지 0). 클라는 **SUBSCRIBE 에러에 의존하지 않고** REST `tryEnter` 권위 + replace 정책으로 자신의 룸을 판단(E2 stale 탭은 tryEnter 시 prior auto-exit로 자연 정리, 메시지 미수신은 부차 신호). PR-2(서버 가드)와 PR-3(클라)이 이 계약으로 정렬 — 클라가 SUBSCRIBE 실패를 에러 처리하려 들지 말 것.
- `UnsubscriptionEventListener`: presence 무관(연결 생존 기준). 세션캐시 정리만 잔존(타 용도 있으면 유지).

**C3 REST 재입장 clearPending (SE4 이중방어)**
- `PartyroomAccessCommandService.tryEnter` 같은-룸 재입장 분기(현 countryCode만 갱신)에 `presenceService.clearPending(partyroomId, userId)` 추가.

**C4 DJ큐 presence 통합 — 회귀잠금 중심 (신규 서버코드 최소)**
- unload-exit 제거 후 `exitInternal` 호출자 = {tryEnter auto-exit, penalty/expel, forceOffline}. 끊김 경로 DJ 제거는 자동으로 grace-게이트.
- 잠금: ①`markPending`이 DjData 불변 ②grace내 재연결 DJ 자리·순서 보존 ③grace 만료 DJ 제거 ④현재 DJ 균일 grace(재생 지속, `wasCurrentDj→skipPlayback`은 만료시에만). #222 락 확장.

**C5 SE10 문서/하드닝**
- `WebSocketConfig` 7.5s 주석 정정. 스펙·코드주석 명기: 유령 진짜 상한 = reconcile cron(60s+버퍼), heartbeat 아님. grace 사이징 현행 유지. cron stale-pending 쓸이 테스트.

**resolve 룸 권위 메서드**: 신규 또는 기존 `getActivePartyroomByUserId`를 presence 경로에서 DB crew(`is_active=true`) 권위 조회로 사용 — subscribe-시점 캐시 의존 완전 제거.

### 4.2 클라 (pfplay-web)

**C6 SocketClient `subscriptions[]` 단일진실원천 (방향 B)**
- `Subscription` 타입에 `callback` 추가. `subscribe(dest,cb)`: onConnect 래핑 중단, 동기 `subscriptions[]` 기록 + (connected면) 즉시 STOMP subscribe. `handleConnect`: `subscriptions[]` 순회 재구독. `onConnectQueue`에서 subscribe 콜백 제거(heartbeat 등 비-subscribe만 잔존).
- `unsubscribe(dest)`: 연결상태 무관 `subscriptions[]`에서 제거(+connected면 STOMP unsub). reconnect 재구독 후보서 자연 제외.
- `handleDisconnect`: 라이브 STOMP만 해제, `subscriptions[]`(desired) 보존. → #5·`!connected` 누수 동시 해소.

**C7 PartyroomClient replace 정책**
- `subscribe(partyroomId)`: throw 대신 동기 "기존 룸 unsub(있으면) → 새 룸 sub". `subscribedRoomId`/`subscriptions[]` 일관. #30 throw→silent→push 발원 제거.

**C8 exit() 분리**
- 신규 `useTeardownPartyroom`: `unsubscribeCurrentRoom`+`resetPartyroomStore`+`trackPartyroomExited`만.
- `layout.tsx`: unmount cleanup = teardown. `beforeunload`/`pagehide` 자동 exit 리스너 제거.
- 백엔드 exit 경로: 룸전환=서버 `tryEnter` auto-exit(클라 DELETE 불요), penalty/expel=서버, sign-out=명시 유지.
- `exitedOnBackend`/`markExitedOnBackend`: 이중 DELETE 방지 workaround였으므로 대부분 제거(잔존 필요처만 최소 유지). `MainPartyroomCard` 미호출 부수버그도 이 단순화로 소멸.

**C9 SE3 애널리틱스 (follow-up, 비차단)**
- 하드 unload 시 `trackPartyroomExited` best-effort. 후속 별건: 서버측 OFFLINE 확정 발행 or sendBeacon. Cluster A 차단 아님 — 별도 이슈 추적.

## 5. 데이터플로우

- **S1 끊김→grace내 재연결**: DISCONNECT→레지스트리 제거→마지막세션→DB resolve→`markPending`(DjData 불변). CONNECT→레지스트리 등록→`clearPending`. ONLINE 복원·DJ 보존·silent.
- **S2 새로고침(grace내)**: unload(exit 없음)→`markPending`. reload→CONNECT→`clearPending`(CONNECT-시점)→REST tryEnter→`clearPending`(이중)→SUBSCRIBE(가드 통과). S1과 동일·결정적.
- **S3 grace 만료**: Redis TTL→`PresenceExpirationListener`→`forceOffline`→`exitInternal`(deactivate+handleDjQueueOnLeave[DJ제거,wasCurrentDj면 skip]+EXIT broadcast). backstop=reconcile cron.
- **S4 룸전환 A→B**: 클라 teardown(A)→nav→`tryEnter(B)`가 A auto-exit(서버 권위)→enter B. 클라 replace.
- **S5 #30 무력화**: replace(throw無)→증폭경로 발원 제거. 잔여 redirect도 unmount=teardown만(DELETE無). presence=연결생존.
- **S6 #5 무력화**: subscriptions[] 단일원천, reconnect 현재룸만 reconcile, 서버 가드 이중방어.

## 6. 에러·엣지

- **E1 멀티탭 동일룸**: 비마지막 세션 종료→markPending 안 함(SE5 자기축출 해소).
- **E2 멀티탭 상이룸**: tryEnter가 prior auto-exit("last enter wins"). stale 탭 SUBSCRIBE는 서버 가드 거부→클라 "미입장" 처리. known behavior 명문화.
- **E3 멱등/원자토글**: V16 markCrewPending/clearPending 원자토글 보존.
- **E4 재연결 vs 만료 레이스**: `forceOffline`의 `isPendingExit()` 가드 + reconcile 분산락(V16) 보존.
- **E5 느린 reload가 grace 경계**: 만료시 탈락 가능 — bounded·오늘 레이스보다 우수, CONNECT-clear가 최조기점. known edge(희귀).
- **E6 서버 가드 오거부 방지**: DB 권위 active 룸 일치시 통과, 크로스룸 불일치만 거부.
- **E7 sign-out**: 명시 exit 경로 유지(impl PR 배선 검증).
- **E8 호스트 끊김**: SE7 — 특수처리 없음(룸 지속, 호스트 슬롯 grace만료시 일반 crew처럼 비움). 기존 설계.
- **E9 하드 unload 텔레메트리 갭(인지)**: C9 착수 전까지 하드 unload는 `trackPartyroomExited` 미발행(현재도 best-effort라 회귀 아님). C9 follow-up 담당자가 인지하도록 명시 — exit 이벤트·세션길이 분석 일시 누락.

## 7. 테스트 전략

**Platform(통합 Testcontainers + 단위)**:
- T1 레지스트리: CONNECT 등록 / DISCONNECT 마지막세션→markPending / 비마지막→안함(E1) / CONNECT→clearPending(SE4).
- T2 REST 같은-룸 재입장→clearPending(SE4 이중).
- T3 DJ큐 grace 락: markPending DjData 불변 / grace내 자리·순서 보존 / 만료 제거 / 현재DJ 균일grace — #222 락 확장.
- T4 #31: subscribe-before-enter 순서 무관(DB 권위 resolve) → disconnect시 markPending.
- T5 서버 단일룸 SUBSCRIBE 가드: 불일치 거부 / 일치 통과.
- T6 reconcile cron stale-pending 쓸이(SE10 backstop).
- T7 멱등/race(E3/E4) 보존.

**Web(client.test.ts 등 기존 하니스)**:
- T8 subscriptions[] 단일원천: subscribe 기록 / reconnect 현재만 / unsubscribe(!connected 포함) 제거 / A→B→reconnect=B만(#5 락).
- T9 PartyroomClient replace: 기존 존재시 unsub old+sub new, throw無, 일관.
- T10 exit 분리: unmount=teardown만(DELETE無) / beforeunload·pagehide 리스너 없음 / 룸전환=teardown+서버 auto-exit / sign-out 유지.
- T11 #30: 구독레이스/redirect → backend exit無·multi-sub無.

**수용(4증상 e2e)**: #30 입장튕김0 / #31 탭닫기→grace·cron 정리 / #5 A→B→offline/online→B만 수신 / #225 새고침 grace내→DJ자리 유지·결정적.

## 8. 구현 분해 (멀티 PR, TDD)

스펙=통합 1건. 구현 PR(repo+invariant별, 각 독립 ship 가능, develop 대상):

1. **PR-1 platform L1**: user-session 레지스트리 + 리스너 재배선(C1·C2) + REST재입장 clearPending(C3) + presence의 subscribe-시점 의존 제거. 회귀: #31, SE4. (#209)
2. **PR-2 platform L4**: 서버 단일룸 SUBSCRIBE 가드(C2 가드부) + DJ큐 grace 회귀잠금(C4) + SE10 문서/cron 테스트(C5). (#225 서버, #30 백엔드 방어)
3. **PR-3 web L3**: SocketClient subscriptions[] 단일원천(C6) + PartyroomClient replace(C7). 회귀: #5, #30 클라. (web#298)
4. **PR-4 web L2**: exit() 분리 `useTeardownPartyroom`(C8) + unload/unmount backend-exit 제거 + `exitedOnBackend` 정리. 회귀: #225a, #30 증폭. (web#298)

순서: PR-1→PR-2(platform 순차, 같은 presence 코드) ‖ PR-3→PR-4(web 순차). platform·web 트랙 병렬 가능. 4개 모두 §2 invariant로 수렴, 각 PR 회귀잠금 동반. 머지=develop(한글 PR), main 진입은 별도 release 게이트.

**배포 인터리빙 안전성(병렬 트랙 명시)**: PR-2(서버 가드 live) 가 PR-3(클라 단일원천) 보다 먼저 떠 클라 누수가 아직 있는 상태여도 — reconnect 후 leaked stale-room SUBSCRIBE는 서버 가드가 조용히 거부(= 바라던 최종상태, benign). 역순(PR-3 먼저)도 안전(클라가 이미 단일룸). 따라서 platform/web 트랙 배포 순서 무관.

## 9. 트레이드오프 (수용 확정)

- **bounded ghost(로스터)**: 진짜 이탈도 grace(DJ 30s/listener 10s) + 검출(~15-20s) + cron(≤90s) 동안 잔존. V16가 crew presence에 이미 수용 — DJ큐가 동일 상속(사용자 수용).
- **bounded absent-DJ playback**: 현재 DJ 끊김시 grace 동안 부재 DJ 트랙이 덱 점유(타 사용자는 계속 청취 가능 — 사용자 수용, SE1).
- **느린 reload edge(E5)**: 희귀, 오늘의 레이스보다 우수.

## 10. 참조

- 노트: `bugs/2026-05-16-involuntary-exit-multi-subscription.md`(#30), `bugs/2026-05-16-tab-close-no-server-exit.md`(#31), `bugs/2026-05-14-websocket-subscription-resurrection.md`(#5)
- V16: `docs/superpowers/specs/2026-05-09-presence-grace-window-design.md`
- 회귀 선행: #222(skip→reorder invariant 잠금, develop 머지)
- 메모리: single-partyroom-subscription-invariant, project_v16_presence_grace_window, feedback_root_cause_premature_lock
