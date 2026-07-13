# Quick-DJ — 곡 즉석 선택 → One-shot DJ 대기열 등록 설계

- 작성일: 2026-07-01
- 대상 이슈: 미등록(신규 기능, 착수 시 pfplay-platform + pfplay-web 이슈 발행)
- 대상 레포: pfplay-platform(playlist + party 모듈, backend) / pfplay-web(검색모달 UX, 별 PR)
- 분류: 신규 기능(온보딩 이탈 완화) — 도메인 모델 확장(신규 PlaylistType·DjData.kind) + 신규 오케스트레이션 endpoint
- 상태: **설계 파킹**(코드 미착수, 사용자 지시 시 plan → 구현)

## 1. 배경 / 문제

첫 사용자가 DJ가 되려면 모달이 **①플레이리스트 생성 → ②곡 등록 → ③플리 선택 → ④DJ 대기열 등록** 다단계를 강요 → 복잡해서 이탈. (검증된 pain, 관련 파킹 아이디어 = `default_playlist_dj_onboarding_idea`.)

→ **검색 모달에서 곡을 검색·선택하는 즉시 그 곡과 함께 DJ 대기열에 등록되는 병렬 빠른 경로(Quick-DJ)** 추가.

### 현행 코드 사실(2026-07-01 확인)

- **DJ 큐 = 크루 1슬롯 모델.** `DjData` = {id, partyroomId, crewId, playlistId, orderNumber}, 엔트리 종류 필드 없음 (`app/.../party/domain/entity/data/DjData.java:26-43`).
- **enqueue** `DjCommandService.enqueueDj(PartyroomId, PlaylistId)` (`app/.../party/application/service/DjCommandService.java:43-93`): `getCrewOrThrow`로 활성 크루 확인 → 가드 `isAlreadyRegistered`(crewId) · `isOwned` · `isEmptyPlaylist` → `DjEnqueueSpecification.validate` → `nextOrder = queuedDjs.size()+1`(맨 뒤) → `DjData.create(...)` 저장. 비활성 재생 시 playback 활성화·start.
- **가드** `DjEnqueueSpecification`: `validateOpen`(QUEUE_CLOSED) · `NOT_OWNED_PLAYLIST` · `EMPTY_PLAYLIST` · `ALREADY_REGISTERED`. → **`ALREADY_REGISTERED`(crewId 기준)가 "1크루 1엔트리"를 이미 강제.**
- **회전** `PartyroomAggregateService.rotateDjQueue`(`app/.../party/domain/service/PartyroomAggregateService.java:46-58`): head(order 1)→tail, 나머지 −1. **영구 라운드로빈, 재생 완료로 이탈하는 경로 없음.**
- **회전 호출점** `PlaybackCommandService.doStart`(`app/.../party/application/service/PlaybackCommandService.java:108-145`)가 트랙 완료 시(complete→tryProceed→doStart) **맨 처음(line 110) `rotateDjQueue` 무조건 호출** → 그 다음 재생 가능한 새 head 선택(`peekTracksFromCursor`, line 122).
- **제거** `PartyroomAggregateService.removeDjFromQueue(partyroomId, crewId)`(line 25-41): crewId의 모든 행 삭제 후 재번호. 호출점 = 자발 dequeue·관리자 dequeue·방 이탈(`handleDjQueueOnLeave`)뿐. **트랙 완료로는 호출 안 됨.**
- **1곡 플리 재생 거동**: `peekTracksFromCursor`(`playlist/.../application/service/TrackCommandService.java:174-200`)가 커서 다음부터 wrap → n=1이면 **같은 곡 무한 반복**.
- **플리 타입** `PlaylistType` = {GRABLIST, PLAYLIST} (`playlist/.../domain/enums/PlaylistType.java`).
- **목록 조회** `PlaylistRepositoryImpl.findAllByUserId`(`playlist/.../adapter/out/persistence/impl/PlaylistRepositoryImpl.java:20-38`): WHERE = ownerId뿐, **타입 필터 없음**(GRABLIST 포함 전부 반환).
- **생성 상한** `PlaylistCreationPolicy`(FM=10/AM=1)는 `createPlaylist`가 **`PlaylistType.PLAYLIST`만 카운트**(`PlaylistCommandService.java:39-42`)해 적용. `createDefaultPlaylist`(GRABLIST, line 30-33)는 정책 우회 = **per-user 시스템 플리 생성 선례.**

## 2. 결정 (사용자 확정, 2026-07-01)

1. **대상 = 모두를 위한 병렬 빠른 경로.** 단 **큐 등록은 양자택일** — 이미 큐에 등록된(NORMAL이든 ONE_SHOT이든) 크루는 Quick-DJ 불가. → 기존 `ALREADY_REGISTERED`(crewId)가 그대로 보장, **가드 변경 불필요.**
2. **관심사 분리**: 곡 **저장 = TEMP 플리**(신규 타입) / 불변식 **#2 = ONE_SHOT 큐 엔트리**(신규 `DjData.kind`). #2를 플리 비우기/삭제로 흉내내지 않는다(1곡 무한반복 방지).
3. **하나의 DJ 큐에 NORMAL·ONE_SHOT 혼합** 등록. 회전 경계에서 타입으로 분기.
4. **ONE_SHOT 배치 = 맨 뒤 append**(기존 `nextOrder` 그대로). "빠른"은 *등록 과정*이 빠른 것. 우선순위 삽입은 out-of-scope(붐빔이 실 pain으로 확인되면 후속).
5. **ONE_SHOT 이탈 트리거 = 재생 완료 + 스킵.** 방 이탈·전체 deactivate는 기존 경로가 이미 커버.
6. **TEMP 플리 = per-user 1개 재사용**, Quick-DJ마다 리셋(이전 곡 비우고 새 곡 삽입). GRABLIST와 일관, 행 증식 없음. 리셋-중-재생 레이스는 양자택일이 원천 차단(재생 중엔 등록 상태 → 재-Quick-DJ 불가).
7. **곡 duration 사전검증(옵션 B 확정, 2026-07-01)**: 선택 곡 재생시간이 방 `playbackTimeLimit` 초과면 **quick-enqueue 시점에 400 거부**. 단일 곡이라 duration을 미리 알 수 있으므로, "조용히 안 나오는 곡"(§4)을 등록 전에 차단.

## 3. 설계

### 3-1. 데이터 모델 변경

**(a) `PlaylistType`에 `TEMP` 추가**
```
enum PlaylistType { GRABLIST, PLAYLIST, TEMP }
```
- **마이그레이션 안전 확정**: `PlaylistData.type`은 `@Enumerated(EnumType.STRING)` 매핑(`playlist/.../domain/entity/data/PlaylistData.java:45-46`). 문자열 저장이므로 **enum에 TEMP를 어디에 append하든 안전**(ordinal 재정렬·데이터 rewrite 위험 없음). 별도 Flyway 데이터 마이그레이션 불필요.
- 목록/단건 조회 **양쪽**에 `type != TEMP` 필터 추가:
  - `findAllByUserId`(`PlaylistRepositoryImpl.java:20-38`) — WHERE에 `type.ne(TEMP)`
  - `findByIdAndUserId`(`PlaylistRepositoryImpl.java:40-61`) — 단건 fetch도 모든 타입 반환하므로 TEMP id가 새지 않게 동일 predicate 추가
  - (GRABLIST는 현행대로 노출 유지, TEMP만 숨김.)
- 생성 상한 = 이미 PLAYLIST만 카운트(`createPlaylist:39`) → TEMP 자동 제외(정책 변경 불필요).

**(b) `DjData`에 `kind` 추가**
```
enum DjKind { NORMAL, ONE_SHOT }
DjData { ..., @Enumerated(STRING) DjKind kind }   // 기본 NORMAL
```
- STRING 매핑으로 통일(ordinal 위험 회피). Flyway: `dj_data.kind` VARCHAR NOT NULL, 기존 행 default `'NORMAL'` 백필.
- `DjData.create(...)`에 kind 파라미터 추가(또는 ONE_SHOT용 오버로드). 기존 호출부(NORMAL)는 기본값 유지.

### 3-2. Quick-DJ 오케스트레이션 (신규, 1 트랜잭션)

신규 application 서비스(예: `QuickDjService`) 또는 기존 서비스 확장:

```
quickEnqueue(partyroomId, VideoSelection):   // userId는 ThreadLocalContext.getAuthContext()에서 유도(기존 관례)
  1. crew = getCrewOrThrow(partyroomId, userId)          // 활성 크루 확인(재사용)
  2. partyroom = getPartyroomById(partyroomId)
     if partyroom.playbackTimeLimit.exceedsDuration(VideoSelection.duration):
         throw 400 TRACK_EXCEEDS_TIME_LIMIT               // 결정7(B) — 등록 전 사전거부
  3. tempPlaylist = findOrCreateTempPlaylist(userId)     // TEMP 없으면 생성(createDefaultPlaylist 패턴)
  4. resetTracks(tempPlaylist)                            // 기존 곡 전부 삭제 + 커서 초기화
  5. insertTrack(tempPlaylist, VideoSelection)           // TrackCommandService 재사용
  6. enqueueDj(partyroomId, tempPlaylist.id, ONE_SHOT)   // 기존 enqueue 경로 + kind
```
- duration 원천: `VideoSelection.runningTime`(검색결과 표시문자열, 예 "3:45")을 `Duration`으로 파싱 → `PartyroomData.getPlaybackTimeLimit().exceedsDuration(Duration)`(기존 `doStart:125`와 동일 비교기)로 검증. 파싱 실패/누락 시 400.
- 3~6 단일 `@Transactional`. 실패 시 전체 롤백(TEMP에 곡만 남고 큐 미등록 방지). 검증(2)은 write 이전이라 조기 반환.
- 5의 가드(`isAlreadyRegistered`)가 양자택일 자동 보장 → 이미 큐에 있으면 `ALREADY_REGISTERED`로 거부.
- `isEmptyPlaylist` 통과(1곡). `isOwned` 통과(TEMP = 본인 소유).
- **구체 서브태스크(리뷰 반영)**: 기존 `enqueueDj(PartyroomId, PlaylistId)`는 userId를 컨텍스트에서 유도하고 kind 파라미터가 없다 → `enqueueDj(PartyroomId, PlaylistId, DjKind)` 오버로드/확장(기본 NORMAL 유지). `getCrewOrThrow`가 step1과 `enqueueDj` 내부에서 **2회 해석**되나 무해(동일 결과) — 필요 시 내부 enqueue에 crew 전달하는 형태로 정리 가능(선택).

**API 계약(확정)**
```
POST /api/v1/partyrooms/{partyroomId}/dj-queue/quick
Auth: cookieAuth, hasRole('MEMBER')
Body: { videoId, videoTitle, watchUrl, runningTime, thumbnailUrl }   // 검색결과 항목
Success: 200 + 등록된 DJ 요약(웹이 등록 성공/내 순번 피드백에 사용)
```
Error: 기존 enqueue 에러(DJ-002 QUEUE_CLOSED / ALREADY_REGISTERED) 재사용 + 곡 payload 검증(400 INVALID_REQUEST) + **신규 400 TRACK_EXCEEDS_TIME_LIMIT**(결정7/B, 곡 duration > 방 한도).

### 3-3. One-shot 이탈 (불변식 #2, 핵심 신규) — **outgoing 정체(identity) 기준**

> ⚠️ 리뷰 반영(초안 폐기): "order-1이 ONE_SHOT이면 retire"는 **틀렸다.** `doStart`의 선두 `rotateDjQueue`(line 110)는 재생 완료·스킵뿐 아니라 **최초 활성화**(`startPlayback`→`doStart`)와 **현재 DJ dequeue-후-skip**에서도 진입하며, 그때 order-1은 *아직 재생 안 한* DJ다. order-1을 무조건 제거하면 **미재생 ONE_SHOT을 삭제**(최초 활성화 시 유일 ONE_SHOT이 재생도 못 하고 사라짐). 따라서 제거 기준은 **위치(order-1)가 아니라 "방금 재생을 끝낸 그 DJ"의 정체**여야 한다.

**정체 원천**: 현재 재생 DJ는 `PartyroomPlaybackData`에 crewId로 기록된다(`PlaybackCommandService.startPlaybackFor:156` `playbackState.updatePlayback(playbackId, crewId)`). `aggregatePort.findPlaybackState(partyroomId)`로 outgoing DJ를 정확히 식별.

**설계**: `doStart`의 선두 회전을 그대로 두되, **완료·스킵 경로(`tryProceed`)에서 회전 직전에 outgoing DJ가 ONE_SHOT이면 회전 대신 제거**로 분기. `doStart`는 회전 여부를 파라미터로 받는다.

```
tryProceed(partyroomId):
    playbackState = findPlaybackState(partyroomId)
    outgoingCrewId = playbackState.currentDjCrewId          // 방금 재생을 끝낸 DJ(없으면 null)
    outgoingDj = queue에서 outgoingCrewId로 조회             // dequeue로 이미 빠졌으면 null
    if outgoingDj != null && outgoingDj.kind == ONE_SHOT:
        removeDjFromQueue(partyroomId, outgoingCrewId)      // 회전 대신 제거(1엔트리라 crewId 안전)
        broadcast(DjQueueChangedEvent, ONE_SHOT_COMPLETED, outgoingCrewId)
        doStart(partyroom, rotate=false)                   // 이미 큐를 전진시켰으므로 중복 회전 금지
    else:
        doStart(partyroom, rotate=true)                    // 기존 동작 그대로(NORMAL 회전·dequeue quirk·활성화)
```

**정확성 근거(트레이스)** — 기존 NORMAL 라운드로빈과 활성화를 **완전 보존**하면서 ONE_SHOT만 1회 후 이탈:
- **최초 활성화**(유일 ONE_SHOT X): enqueue→`startPlayback`→`doStart(rotate=true)`. 이 경로는 `tryProceed`를 안 지나므로 제거 분기 미실행 → rotate는 n=1 no-op → X **재생됨**. X 완료 → `complete`→`tryProceed`: outgoing=X(ONE_SHOT, 큐 존재) → 제거 → `doStart(rotate=false)` → 큐 빔 → deactivate. ✅ (§4 엣지#1 충족)
- **중간 ONE_SHOT** [A(NORMAL,재생), X(ONE_SHOT), B(NORMAL)]: A완료→outgoing=A(NORMAL)→`doStart(rotate=true)`: A→tail, X 재생. X완료→outgoing=X(ONE_SHOT)→제거→`doStart(rotate=false)`→B 재생. B완료→A→tail→A 재생. **A·B 라운드로빈 보존, X는 1회 후 소멸.** ✅
- **현재 DJ dequeue-후-skip**: `dequeueDj`가 current를 먼저 `removeDjFromQueue` → `skipPlayback`→`tryProceed`: outgoing(=제거된 그 crew)은 큐에 없음 → 제거 분기 미실행 → `doStart(rotate=true)` = **기존 동작 그대로**(기존 quirk 포함 무변경). 들어오는 ONE_SHOT이 있어도 rotate는 tail 이동일 뿐 **삭제 아님** → 미재생 삭제 없음. ✅

**변경 요약**: `doStart`에 `rotate` 플래그 추가(기존 호출부는 `true`) + `tryProceed`에 outgoing-ONE_SHOT 제거 분기. `rotateDjQueue`/`removeDjFromQueue`/기존 NORMAL 경로 로직 자체는 **무변경**.

**확정 사실(2차 리뷰 검증)**:
- outgoing 식별 = `PartyroomPlaybackData.getCurrentDjCrewId()`(클래스 `@Getter`, 필드 line 36). `updatePlayback(playbackId, crewId)`(line 70-73)로 세팅, **`deactivate()`에서만 클리어**되므로 완료→`tryProceed` 시점엔 방금 끝난 DJ를 정확히 가리킨다. (`isCurrentDj(CrewId)` 헬퍼도 있어 구현 시 택일 가능.)
- 큐-내-존재 조회 = `aggregatePort.findDj(partyroomId, crewId)`(기존, `DjCommandService:154`). dequeue로 이미 빠진 outgoing은 null → 분기 미실행(가드).
- **empty-queue 처리 보존**: 두 분기 모두 결국 `doStart`로 수렴하고 `doStart`가 빈 큐 시 fall-through로 `deactivateAndNotify`(line 144). 기존 `tryProceed`의 명시적 `EMPTY_QUEUE_DEACTIVATE` 로그(line 95-100)를 유지할지, `doStart`의 `DEACTIVATE_TRIGGERED`로 일원화할지는 plan에서 로그 정합만 결정(기능 동일).

### 3-4. 브로드캐스트

ONE_SHOT 재생-후-제거 시 웹 큐 갱신용 이벤트 필요.
- **방식**: 기존 `DjQueueChangedEvent` 발행. `DjChangeType`에 **`ONE_SHOT_COMPLETED` 신규 추가** 권장(vs `DEQUEUE` 재사용) — 웹이 "1회 재생 종료로 자연 이탈"을 정상 dequeue와 구분해 표시할 수 있게. (재사용해도 기능은 되나 의미 소실.)
- 기존 enqueue의 `ENQUEUE` 이벤트는 그대로.

## 4. 엣지 케이스

- **ONE_SHOT이 유일/첫 엔트리**: enqueue가 playback 활성화(`startPlayback`→`doStart(rotate=true)`, `tryProceed` 미경유) → **1회 재생됨** → 완료 시 `tryProceed`가 outgoing=ONE_SHOT 감지·제거 → 큐 빔 → deactivate. OK (§3-3 트레이스).
- **재-Quick-DJ**: 이전 곡 종료·제거로 미등록 상태 → TEMP 리셋 후 재등록 가능. OK.
- **ONE_SHOT 대기 중 방 이탈/추방**: `handleDjQueueOnLeave`가 crewId로 제거 → 기존 커버.
- **버튼 스킵(본인/관리자)으로 ONE_SHOT의 트랙을 스킵**: 스킵 시점 outgoing=그 ONE_SHOT(현재 재생 중) → `tryProceed`가 제거 → 재등장 없음. OK(§3-3 outgoing 기준이라 완료·스킵 동일 처리).
- **ONE_SHOT 곡이 방 재생시간 초과(playbackTimeLimit)** → **결정7(B)로 해소**: quick-enqueue 시점(§3-2 step2)에 400 `TRACK_EXCEEDS_TIME_LIMIT`로 사전거부 → 애초에 큐에 안 들어옴("조용히 안 나오는 곡" 방지). (미검증 시 발생하던 잔류 경로 = `doStart` per-DJ 스킵으로 current 못 됨 → 완료이벤트 없음 → 제거 트리거 없음.)
- **TEMP 커서(`lastPlayedTrackId`)**: 1곡·리셋 반복이라 무해(다음 리셋 시 재초기화).

## 5. 테스트 전략 (TDD)

- 단위: `DjData.create(kind=ONE_SHOT)`; `tryProceed` outgoing 분기(outgoing=ONE_SHOT→제거+`rotate=false` / outgoing=NORMAL·null→`rotate=true`); TEMP 리셋+삽입.
- 서비스: `quickEnqueue` 1트랜잭션(성공/롤백); `ALREADY_REGISTERED`로 양자택일 거부; `isEmptyPlaylist` 통과; **곡 duration > 방 한도면 400 `TRACK_EXCEEDS_TIME_LIMIT` + write 미발생(TEMP 리셋/삽입 안 됨)**(결정7).
- IT(재현) — §3-3 트레이스를 그대로 테스트로:
  - **(a) 유일 ONE_SHOT 최초 활성화**: enqueue→**재생됨**→완료 후 제거→deactivate. (초안 버그 회귀 방지 = 미재생 삭제 금지)
  - **(b) 중간 ONE_SHOT 혼합 큐** [NORMAL, ONE_SHOT, NORMAL]: ONE_SHOT 1턴 후 제거, NORMAL 순환 보존.
  - **(c) 현재 DJ dequeue-후-skip에 incoming ONE_SHOT**: 들어오는 ONE_SHOT이 **삭제되지 않음**(rotate=tail 이동일 뿐).
  - **(d) 버튼 스킵으로 ONE_SHOT 트랙 스킵**: 재등장 없이 제거.
- 회귀: NORMAL 라운드로빈 순서 무변경; 목록/단건 조회 TEMP 제외; GRABLIST 여전히 노출; 생성 상한 무영향.

## 6. Out-of-scope

- ONE_SHOT 우선순위/큐 점프(맨 뒤 append 고정).
- 재생 중 DJ의 ONE_SHOT 전환/취소.
- Quick-DJ 남용 방지(rate limit) — 필요 시 후속.
- 웹 상세 UX(별 PR, pfplay-web): 검색모달 "이 곡으로 바로 DJ" CTA, ONE_SHOT 큐 표식, 등록 후 피드백.

## 7. 재사용 vs 신규 요약

| | 항목 |
|---|---|
| **재사용(그대로)** | enqueue 경로·`DjEnqueueSpecification`·`ALREADY_REGISTERED` 양자택일·재생/커서(`peekTracksFromCursor`)·`rotateDjQueue`/`removeDjFromQueue` 로직·방이탈/deactivate 경로·`TrackCommandService` 삽입·`createDefaultPlaylist` 패턴·생성상한(무변)·**NORMAL 라운드로빈 순서(무변)** |
| **신규(국소)** | `PlaylistType.TEMP`+목록/단건 필터 · `DjData.kind`(STRING)+Flyway · **`tryProceed` outgoing-ONE_SHOT 제거 분기 + `doStart` rotate 플래그** · `enqueueDj` kind 오버로드 · `QuickDjService` 오케스트레이션 · `DjChangeType.ONE_SHOT_COMPLETED` · quick endpoint · 웹 UX |

## 8. 오픈 결정 (전부 확정)

- ~~ONE_SHOT 곡 duration > 방 `playbackTimeLimit`~~ → **결정7 (B) 확정(2026-07-01)**: quick-enqueue 사전검증·400 `TRACK_EXCEEDS_TIME_LIMIT`. (§2-7, §3-2, §4)
- 나머지(enum STRING · outgoing-identity 메커니즘 · API 200+요약)도 확정. **남은 오픈 결정 없음 → planning-ready.**
