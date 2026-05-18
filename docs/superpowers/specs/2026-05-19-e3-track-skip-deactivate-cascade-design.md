# E/#3 — DJ 1명 + 길이 초과 트랙 → 파티룸 deactivate·DJ큐 전원 퇴출 근본 수정 설계

- 작성일: 2026-05-19
- 대상 이슈: pfplay-platform E/#3 (로드맵 Cluster E). 노트: `bugs/2026-05-14-playback-time-limit-deactivate-cascade.md`
- 대상 레포: pfplay-platform(party 모듈, 핵심) + pfplay-web(UX 신호·배지)
- 분류: 재생 라이프사이클 동작 결함 + UX 신호 경로 단절 (cross-cutting, cross-repo)
- 심각도: High

## 1. 배경 / 문제

DJ 큐에 등록된 사용자가 그 파티룸의 active DJ 전부일 때, 회전 차례에 파티룸
`playbackTimeLimit` 초과 트랙을 만나면 그 트랙이 패스되지 않고 **파티룸 전체가
deactivate 되며 DJ 큐의 모든 DJ 가 일괄 삭제**된다(`removeDjs`). 사용자는
"추가한 곡이 안 나오고 큐에서 영문 없이 빠짐" 으로 체감.

근본(노트 코드 트레이스, 2026-05-19 현행 코드 재확인):
1. `TrackCommandService.getFirstTrack` 이 패스 여부 무관 **항상 회전**
   (`rotateTrackOrder` 호출 후 `[0]` 반환).
2. `PlaybackCommandService.doStart`: `getNextPlaybackInPlaylist` 결과가
   `exceedsDuration` 이면 `remainingAttempts <= 1` → `deactivateAndNotify`.
   `remainingAttempts` 는 `queuedDjs.size()` 로 초기화 → **DJ 수 ≤ 1 이면 즉시
   deactivate**.
3. `PartyroomAggregateService.deactivatePlayback` 이 `findDjsOrdered` →
   `removeDjs(queuedDjs)` 로 **큐 전원 삭제**.

부수: over-limit 트랙은 자기 차례마다 회전으로 맨 뒤로 밀려 영구 미재생.
UX 갭: `DjQueueChangeMessage` 에 변경 사유(`DjChangeType`)가 없어
(`DomainEventRedisRelay` 가 type 을 떼고 djs 리스트만 전송) 프론트는 "왜
빠졌는지" 알 수 없고, 자가검출은 analytics `track` 만 하며 모달/토스트 0.

(현행 확인: observability A2~A4 머지가 `[doStart] DEACTIVATE_TRIGGERED
reason=ALL_TRACKS_EXCEEDED` 등 로그만 추가, 구조·`DjQueueChangeMessage`
무 changeType·`DjChangeType` 6값 enum 모두 노트와 일치.)

## 2. 결정 (사용자 확정)

- **핵심 정책 = 트랙 단위 스킵**: over-limit 트랙은 재생 안 하고 같은 DJ
  플레이리스트에서 한도 이내 첫 트랙을 재생. DJ 전 트랙이 over-limit 이면 그
  DJ 는 이번 사이클 패스(다음 DJ). **어느 DJ 도 재생가능 트랙이 없을 때만**
  deactivate.
- **스킵 트랙 처리 = 제자리 유지**: peek → 판정 → *재생한 트랙만* 회전.
  over-limit 트랙은 순서 보존, 선택만 안 됨(노트가 경고한 "N회 회전 → 순서
  손실" 회피).
- **UX 신호 = 번들** (이번 스코프): backend `changeType` payload + frontend
  changeType별 모달.
- **옵션 D(프론트 사전 가드) 제외**: 플레이리스트는 DJ 중에도 동적 편집되니
  add/switch 사전검사 모델 부적합 — 백엔드 skip 이 옳은 레이어.
- **잔여(over-limit 곡 silent 미재생) = 반응형 배지**: 사전검사 아닌, 방 현재
  limit 기준 플레이리스트 UI 반응형 표시.
- **DEACTIVATE 모달 = 리치(b)**: 거부 트랙명·duration·limit 포함.

## 3. 설계

### 3-1. 백엔드 동작 재설계 (pfplay-platform, party 모듈) — 핵심 (PR-1)

- **TrackCommandService**: `getFirstTrack`(rotate-then-take)의 회전 부작용을
  분리. (유일 프로덕션 caller = `PlaybackCommandService.getNextPlaybackInPlaylist`
  → `PlaylistCommandAdapter.getFirstTrack` → `TrackCommandService.getFirstTrack`;
  다른 프로덕션 caller 없음 — split 가능.)
  - `peekOrderedTracks(playlistId)` — 회전 없이 orderNumber ASC 트랙 조회(부작용
    0, read-only).
  - `rotatePlayed(playlistId, playedTrack)` — *실제 재생된* 트랙만 tail 로,
    나머지(스킵된 over-limit 포함) 상대순서 보존.

  **`rotatePlayed` 알고리즘 (명시 — 기존 seam 재사용)**: 기존 `rotateTrackOrder`
  SQL(`TrackRepository.java:23-28`, `WHEN orderNumber=1 THEN total ELSE -1`)은
  played 트랙이 항상 position 1 이라는 가정이라, skip 으로 played 트랙이
  position k>1 일 수 있는 본 설계엔 부적합. 신규 bespoke CASE SQL 대신
  **기존 `shiftUpOrderByDelete` + tail append(`musicCount+1`) 패턴**
  (`addTrackInPlaylist`/`moveTrackToPlaylist` 가 쓰는 검증된 seam)을 일반화:
  played 트랙 orderNumber=k 일 때 → `orderNumber > k` 인 트랙 전부 `-1` 시프트
  + played 트랙을 `orderNumber = total`(tail) 로. `orderNumber < k`(스킵된
  over-limit 포함) 트랙은 **불변**(제자리). 갭/충돌 없음.
  - 예: `[1:ol, 2:ol, 3:playable→played, 4, 5]` → `[1:ol, 2:ol, 4, 5, 3]`
    (4→3, 5→4, 3→5; 1·2 불변). 다음 peek 첫 playable = 옛 4. over-limit
    1·2 는 front 에 영구 "speed bump"(skip-in-place, 의도된 동작).
  - **#222 불변식 정확한 스코프**: played 트랙이 position 1 인 *정상 흐름*
    (over-limit 선행 트랙 없음)에서는 이 일반화가 옛 `orderNumber=1→total,
    rest -1` 와 **산술적으로 정확히 동일** → #222(skip→트랙 최하단) 잠긴 동작
    behaviorally identical 보존. 새 산술은 **over-limit 선행 트랙이 있을
    때만** 옛 SQL 과 달라짐(= #222 도메인 밖, 본 기능의 신규 동작). 즉
    "#222 불변" 은 *position-1 케이스 한정*이며, k>1 reorder 산술은 PR-1
    in-scope 신규 설계(blanket invariance 주장 아님).
  - **수정/신설 테스트 (TDD red 명확화)**: position-1 보존 어서션
    (`TrackCommandServiceTest` getFirstTrack rotate ~L434,
    `TrackRepositoryReorderIntegrationTest`, `PlaybackCommandServiceTest`
    #222 ~L239/260/286)은 **행위보존**(position-1 경로 동일하므로 통과 유지
    — 단 getFirstTrack→peek/rotatePlayed 분리로 *호출 와이어링* 갱신 필요).
    k>1 skip reorder + 트랙스킵 + deactivate-조건은 **신규 테스트**.
- **PlaybackCommandService.doStart**: rotation order 로 DJ 순회. 각 DJ 의
  `peekOrderedTracks` 에서 **limit 이내 첫 트랙** 선택:
  - 찾음 → 그 트랙 재생 + `rotatePlayed`.
  - 그 DJ 전 트랙 over-limit → 그 DJ 이번 사이클 **패스**(큐 잔류, 다음 DJ).
  - **deactivate 조건 정정 + 재귀/회전 정합**: 현재 `doStart` 는 진입마다
    `rotateDjQueue(...)` 를 **무조건 1회** 호출(line ~111) 후
    `remainingAttempts<=1` 면 deactivate, 아니면 `doStart(reloaded,
    remainingAttempts-1)` 재귀(재진입마다 또 rotateDjQueue). 본 설계는
    **DJ 큐 회전을 사이클당 1회로 유지**하고, "재생가능 DJ 탐색"을
    *재귀 재진입(매번 rotateDjQueue)* 이 아니라 **이미 회전된 DJ 리스트
    위의 내부 순회**로 구현: 회전된 큐를 orderNumber 순으로 스캔하며 첫
    "재생가능 트랙 보유 DJ" 를 찾고, 그 DJ 의 첫 limit-이내 트랙 재생 +
    `rotatePlayed`. **스캔이 큐 전체를 돌아도 재생가능 트랙이 0 일 때만**
    `deactivateAndNotify`(기존 `deactivatePlayback`→`removeDjs` 경로 그대로 =
    정당 케이스). 각 DJ 1 스캔당 최대 1회 평가 → 무한루프·DJ큐 이중회전
    가드. (`remainingAttempts`(=`queuedDjs.size()`) 카운터는 이 내부 스캔
    상한으로 의미 재정의.)
- 불변식: "DJ 1명 + 전 트랙 over-limit → deactivate"(정당) 보존 / "DJ 1명 +
  재생가능 트랙 있음 → 그 트랙들 재생"(버그 해소) / over-limit 트랙 순서 보존 /
  #222 position-1 정상흐름 behaviorally identical.
- 범위밖: DJ 전 트랙 over-limit 인 DJ 의 큐 자동 제거(파괴적, 미채택 — 큐 잔류
  패스). per-user `tryEnter` race 등 무관 영역.

### 3-2. UX 신호 백엔드 (pfplay-platform) — (PR-2)

- `DjQueueChangeMessage` 에 `changeType: DjChangeType` 필드 **추가(additive)**.
  `create` 시그니처 확장(changeType 인자). 호출지(도메인 이벤트→메시지 변환)
  가 `DjQueueChangedEvent` 의 changeType 을 전달.
- `DomainEventRedisRelay.on(DjQueueChangedEvent)` 가 현재 type 을 떼는 것을
  **passthrough**(이벤트 changeType 보존). djs 리스트 late-binding(AFTER_COMMIT
  query, [[reference_first_dj_silent_deactivate]]) 동작 무변경.
- **리치(b)**: DEACTIVATE 케이스에 한해 거부 트랙 식별(name·durationSec) +
  partyroom limit(min) 을 메시지에 포함(전용 필드 또는 DEACTIVATE 전용
  서브페이로드). 다른 changeType 은 미포함(YAGNI).
- *Cluster A 겹침*: 동일 dj-queue-changed 메시지를 Cluster A 프론트 구독이
  소비 → **순수 additive·기존 `.djs`/`.partyroomId` 처리 무변경 =
  backward-compat**. 구 프론트(신규 필드 미인지) 무영향.

### 3-3. UX 신호 프론트 (pfplay-web) — (PR-3, PR-2 의존)

- `use-dj-queue-changed-callback.hook.ts` self-removed 검출 분기에
  `changeType` 별 처리:
  - `DEACTIVATE` → 모달/토스트: "재생 시간 제한(<limit>분)을 초과하는 곡
    '<trackName>'(<duration>) 으로 재생이 중단되었습니다" (리치 payload 사용).
  - `DEQUEUE_ADMIN` → "관리자에 의해 DJ 대기열에서 제외되었습니다".
  - `DEQUEUE_EXIT` → silent.
  - 기존 analytics `track('DJ Deregistered', reason)` 의 하드코딩
    `reason:'admin'` → 실제 changeType 매핑.
- `use-playback-deactivated-callback.hook.ts` 는 state-reset 유지(모달은
  changeType=DEACTIVATE 단일 출처 — 더블모달 회피).
- 미지 changeType / 신규필드 부재 → 무동작(silent) = 양방향 안전.

### 3-4. 반응형 배지 (pfplay-web) — (PR-4, 독립)

- 플레이리스트 트랙 UI: `track.duration > 현재방.playbackTimeLimit` 트랙에
  "이 방에선 재생 안 됨" 배지/디밍. 방 limit 기준 반응형, **비차단**(추가
  허용). 방 `playbackTimeLimit` 을 플레이리스트 UI 컨텍스트에 노출.

## 4. 에러 / 엣지

- DJ 전 트랙 over-limit → 큐 잔류·매 사이클 패스(파괴 X). 그 DJ 유일 → 전-DJ
  -무재생 → deactivate(정당, 기존 경로·전원 removeDjs 유지).
- 빈 플레이리스트 DJ: 기존 동작 보존(별도 회귀 아님, 테스트로 확인).
- peek 부작용 0(트랜잭션/회전 없음). 무한루프: 1 해소 패스 내 각 DJ 1회.
- changeType 구·신 혼재: 프론트 미지값=무동작, 신규필드 부재=기존 경로 — 안전.
- 리치 payload: 거부 트랙이 식별 불가한 극단(플레이리스트 동시삭제 등) →
  trackName/duration null 허용, 모달은 일반 문구로 폴백.
- **롤링 배포 직렬화 윈도우 (명시 스코프)**: `DjQueueChangeMessage` 는
  `Serializable` record. 필드 추가는 *신규 메시지 생산*·*소비자 필드 독해*
  관점에선 backward-compat 이나, **롤링 배포 윈도우 중 Redis pub/sub 에 떠
  있던 구 포맷 메시지를 신 인스턴스가 deserialize**(또는 역) 할 때 record
  암묵 `serialVersionUID` 변경으로 실패 가능. Redis pub/sub 는 fire-and-forget
  ·저블래스트라 **해당 메시지 1건 transient drop 으로 허용**(다음
  dj-queue-changed 가 late-binding 전체 djs 재브로드캐스트로 self-heal).
  스탠스 = 명시적 수용(별도 `serialVersionUID` 핀은 필드 변경 cross-compat
  를 보장하지 못하므로 무의미 — 수용이 정직한 결정). 배포 윈도우 외 정상.

## 5. 테스트 (TDD)

- 백엔드(PR-1):
  - DJ1 + 재생가능·over-limit 혼재 → 재생가능만 재생, over-limit 제자리,
    deactivate 안 됨.
  - DJ1 + 전 트랙 over-limit → deactivate(+ 큐 removeDjs).
  - 다DJ, 앞 DJ 전트랙 over-limit → 다음 DJ 재생.
  - peek 부작용 0 / `rotatePlayed` 가 재생트랙만 tail·나머지 순서 보존.
  - 회귀: 기존 정상 회전·#222 skip→reorder 불변·기존 deactivate 정당 케이스.
- 백엔드(PR-2): `DjQueueChangeMessage` changeType 직렬화 + relay passthrough +
  DEACTIVATE 리치 payload 채워짐 / 다른 type 미포함.
- 프론트(PR-3): changeType별 모달 분기(DEACTIVATE 리치 문구/ADMIN/EXIT
  silent) + reason 라벨 매핑 단위.
- 프론트(PR-4): 배지 렌더(over/under limit, 방 limit 변경 반응) 단위.
- 행위보존 회귀 + 전 모듈 그린(JDK 21 빌드 [[reference_pfplay_platform_jdk]]).

## 6. 스코프 / PR 시리즈 + 조율

- **PR-1 (platform)**: §3-1 동작 재설계. 핵심·독립. 머지 게이트=사용자.
- **PR-2 (platform)**: §3-2 changeType payload + relay passthrough + 리치.
  Additive·backward-compat. PR-1 후행 권장(같은 party 모듈 충돌 최소화).
- **PR-3 (web)**: §3-3 changeType별 모달 + reason 매핑. **PR-2 의존**(payload).
- **PR-4 (web)**: §3-4 반응형 배지. 독립.
- Cluster A 조율: PR-2/3 가 dj-queue-changed 메시지·소비자 접점 — additive
  확인, 기존 djs-list·구독·single-partyroom invariant
  ([[feedback_single_partyroom_subscription_invariant]]) 무변경.
- 진행: 각 PR brainstorm 불요(본 spec 이 설계) → plan → TDD → 2단 리뷰 → 전 CI
  → 머지. 한글 커밋/PR/이슈([[feedback_korean_issue_commit_pr]]), push 전 논리
  단위 커밋 통합([[feedback_commit_consolidation_before_push]]). dev/stg 머지
  후 prod 는 별도 release 게이트(사용자). 로드맵 LIVE·메모리 갱신.

## 7. 관련 메모리

- [[reference_first_dj_silent_deactivate]] — 첫 DJ silent deactivate(의도)·
  payload late-binding. 본 수정이 "프론트 UX 책임" 미구현 갭을 실제 구현.
- [[feedback_root_cause_premature_lock]] · [[feedback_pr_series_workflow]] ·
  [[feedback_commit_consolidation_before_push]] · [[feedback_korean_issue_commit_pr]]
- [[feedback_single_partyroom_subscription_invariant]] — Cluster A 조율 접점
- [[reference_pfplay_platform_jdk]] — JDK 21 빌드 환경
- [[reference_branch_env_mapping]] — platform main=prod/release=stg/develop=dev
