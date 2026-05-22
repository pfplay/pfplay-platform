# 플레이리스트 재생 회전 제거 + 영속 재생 커서 재설계

- 작성일: 2026-05-22
- 상태: 설계 승인됨 (구현 전)
- 관련: pfplay-platform #262 (디제잉 중 순서변경 충돌), 기획 요청(신규 곡 추가 = 맨 위로)
- 범위: pfplay-platform 단일 PR (백엔드)

## 1. 배경 / 문제

현재 `TrackData.orderNumber` 컬럼은 **두 가지 책임을 동시에** 진다:
1. 사용자가 큐레이션하는 플레이리스트 순서(표시·드래그 reorder의 기준)
2. 재생 회전 커서 — 곡이 재생될 때마다 `rotatePlayed`가 재생한 곡을 맨 뒤로 보내고 뒤 곡들을 앞당긴다.

이 이중 용도에서 두 가지 결함이 파생된다.

### 결함 A — 디제잉 중 순서 변경 충돌 (#262)
재생이 `orderNumber`를 디제잉 중 몰래 바꾸는데 프론트는 재동기화하지 않는다. 사용자는 stale한 순서를 보고 절대 슬롯(`nextOrderNumber`)으로 reorder를 보내고, 서버는 회전된 최신 `orderNumber`를 prev로 읽어 검증 → `prev == nextOrderNumber` 충돌 시 `INVALID_TRACK_ORDER`("유효하지 않은 트랙 순서입니다") 팝업 + 미반영, 충돌하지 않는 곡은 의도와 다른 위치로 silent 재배치.

### 결함 B — 신규 곡이 한 바퀴 뒤에야 재생 (기획 요청 대상)
신규 곡은 `orderNumber = count + 1`로 **맨 뒤에 append**된다. 특히 **1곡 플레이리스트**에서 디제잉 중 곡을 추가하면, 다음 내 차례에 방금 들은 직전 곡이 다시 재생된다(신규 곡은 맨 뒤라서). 사용자 경험을 해친다.

두 결함의 근본 원인은 동일하다: **재생 회전이 사용자 순서를 파괴적으로 덮어쓴다.** 회전을 제거하고 재생 위치를 별도 커서로 분리하면 두 결함이 동시에 소멸하며, 백엔드 단일 변경으로 한 PR에 담을 수 있다(결함 A가 프론트 변경 없이 백엔드만으로 해결됨).

## 2. 목표 / 비목표

목표:
- `orderNumber`를 **재생이 절대 변경하지 않는** 안정된 사용자 순서로 만든다.
- 다음 재생 곡 선택을 **영속 재생 커서**로 결정한다.
- 신규 곡 추가를 **맨 위(`orderNumber = 1`)** 로 바꾼다.
- 결함 A(#262)를 프론트 변경 없이 해소한다.

비목표:
- 프론트엔드 변경(별도 확인 포인트만 둠, §8).
- DJ 큐 회전(`DjData.orderNumber`) 변경 — 트랙 회전과 무관한 별개 메커니즘이며 손대지 않는다.
- 멀티탭 동시 편집 동시성 강화(별도 백로그).

## 3. 설계

### 3.1 데이터 모델
- `TrackData.orderNumber`: 안정된 사용자 순서. **add / delete / reorder(사용자 액션)에서만 변경.** 재생은 변경하지 않는다.
- 신규 컬럼 `PLAYLIST.last_played_track_id` (nullable `bigint unsigned`, hard FK 없음 — 코드베이스의 application-level FK 스타일 준수) = 재생 커서. **플레이리스트별 영속**(디제잉을 다시 시작해도 중단 지점부터 이어짐).

### 3.2 곡 선택 알고리즘 (DJ 턴, `PlaybackCommandService.doStart`)
1. 커서가 가리키는 트랙의 현재 `orderNumber` `c`를 조회. 커서가 `null`이거나 가리키던 트랙이 삭제되어 없으면 `c = 0`으로 취급.
2. `orderNumber` 오름차순 목록에서 **`c` 다음 위치부터 wrap 스캔**하며 첫 재생가능(`playbackTimeLimit` 이내) 트랙을 선택. `playbackTimeLimit` 필터는 party 모듈에 유지(현행 위치 보존), 커서 mechanics는 playlist 모듈이 담당.
3. 선택된 트랙으로 커서 갱신: `last_played_track_id = chosen.id`.
4. 재생가능 트랙이 하나도 없으면 현행대로 다음 DJ로 skip(`DJ_NO_PLAYABLE_TRACK`); 모든 DJ가 불가면 deactivate.

over-limit으로 건너뛴 트랙에는 커서가 머물지 않는다(실제 재생된 트랙으로만 커서가 이동).

#### 트레이스 (확정 동작)
- 1곡 `[A]`, 커서=A → wrap → **A** (1곡은 항상 A)
- 1곡 `[A]` → B를 top 추가 `[B,A]`, 커서=A → wrap → **B** ✓ (결함 B 해소)
- 멀티 `[A(1),B(2),C(3)]` 커서=B → **C** → wrap → A → …
- 멀티 커서=B, D top 추가 `[D(1),A(2),B(3),C(4)]` → 남은 사이클 **C** → 그다음 wrap → **D**
  - = "다음 사이클 top에서 재생" (결정 Q1). 커서 위치를 흩트리지 않아 깨끗하며, 1곡 케이스는 wrap으로 즉시 다음 재생이 보장된다.

### 3.3 신규 곡 추가 = 맨 위로 (`addTrackInPlaylist`)
- 기존: `nextMusicOrderNumber = count == 0 ? 1 : count + 1` (tail).
- 변경: 기존 트랙 전체 `orderNumber + 1` shift 후 신규 곡 `orderNumber = 1` (head).
- 신규 repository 쿼리 `shiftAllOrdersDown(playlistId)` (`UPDATE TrackData SET orderNumber = orderNumber + 1 WHERE playlistId.id = :playlistId`) 추가.
- `MAX_PLAYLIST_TRACK_COUNT`(100) 초과 검사, 중복 검사 등 기존 가드 유지.
- 커서는 track-id 기반이라 renumber 영향 없음.

### 3.4 결함 A(#262) 해소 — 부수 효과
재생이 `orderNumber`를 더 이상 변경하지 않으므로 디제잉 중 클라이언트 뷰가 stale될 일이 없다 → 기존 `updateTrackOrderInPlaylist`의 절대 슬롯 검증이 그대로 정상 동작한다. **reorder 로직/프론트 변경 불필요.** (멀티탭 동시 편집 시 `prev == nextOrderNumber` 엣지는 잔존하나 허용 범위 — 회전起因 staleness만 제거하면 #262 제보 시나리오는 소멸.)

## 4. 마이그레이션 (V21)

`V21__add_playlist_playback_cursor.sql`:
```sql
ALTER TABLE playlist
    ADD COLUMN last_played_track_id bigint unsigned NULL COMMENT '재생 커서 — 마지막 재생 트랙 id';
```
- 기존 `track.order_number`는 그대로 둔다(현재 회전이 남긴 상태가 그대로 안정 순서가 됨). 별도 데이터 보정 없음.
- 모든 플레이리스트의 커서는 초기 `NULL` → 배포 후 첫 재생은 각자 현재 `orderNumber` 최소값(top)부터 시작.
- dev/stg는 기존 DB reset 정책 활용 가능.

## 5. 영향 컴포넌트

추가/변경:
- `PlaylistData`: `lastPlayedTrackId` 필드 + 갱신 메서드.
- playlist command port: `peekTracksFromCursor(playlistId)`, `advancePlaybackCursor(playlistId, chosenTrackId)` 추가.
- `TrackCommandService`: `addTrackInPlaylist`(head 삽입), 커서 기반 peek/advance 구현.
- `TrackRepository`: `shiftAllOrdersDown` 추가.
- `PlaybackTrackDto`: `trackId` 필드 추가(커서 갱신용).
- `PlaybackCommandService.doStart` / `startPlaybackFor`: peek+filter+rotate → peek-from-cursor+filter+advance-cursor.

제거:
- `TrackRepository.rotatePlayedOrder` 쿼리.
- `TrackCommandService.rotatePlayed` + playlist command port의 `rotatePlayed` + 어댑터.
- `TrackRepositoryReorderIntegrationTest` 중 **회전(rotate) 전용 케이스만** 제거. shiftUp(delete)/shiftDown(DnD) reorder 케이스는 유지.

`peekOrderedTracks`(자연 순서)는 표시용 등 다른 소비자가 있으면 유지하고, 재생 경로만 커서 변형으로 교체한다(구현 시 usage audit).

## 6. 테스트 전략 (TDD)

커서 선택(playlist/party):
- 1곡 wrap (A→A)
- 1곡 add-to-head → 신규 곡이 다음 재생 (결함 B 회귀)
- 멀티곡 사이클 진행 + wrap
- 멀티곡 add-to-head → 다음 사이클 top에서 재생 (Q1)
- over-limit 트랙 skip (커서는 실제 재생곡으로만 이동)
- 커서 null(최초) → top부터
- 커서가 가리키던 트랙 삭제됨 → top부터(graceful)

add-to-head:
- 빈 플레이리스트 추가 → order 1
- 기존 N곡 → 신규 order 1 + 기존 전체 +1

reorder 회귀(#262):
- 재생(커서 advance) 시뮬레이션 후에도 `orderNumber` 불변 → 동일 입력 reorder가 일관되게 성공.

## 7. 롤아웃

- 단일 PR(pfplay-platform), 한글 커밋/PR.
- develop → (release) → main 게이트는 사용자 소관(메모리 정책).

## 8. 프론트엔드 영향 (확인 포인트, 본 PR 범위 밖)

- 플레이리스트 편집기는 `orderNumber` 순 표시 → 디제잉 중에도 안정. 신규 곡 top 추가는 add-track 캐시 invalidate로 자연 반영(현행 추정).
- **확인 필요**: 클라가 `orderNumber == 1`을 "다음 재생곡"으로 표시하는 마커가 있는지. 있으면 진실원천이 서버 커서이므로 오해 소지 → pfplay-web 후속으로 분리. 없을 것으로 추정(현재곡/현재 DJ는 `PLAYBACK_STARTED` 이벤트 기반).

## 9. 결정 로그

- 모델: **회전 제거 + 영속 재생 커서**(대안: 회전 유지+프론트 resync 2 PR — 기각, 한 PR 불가 + 뷰 스크램블 잔존).
- Q1 멀티곡 add-to-head: **다음 사이클 top에서 재생**(대안: 무조건 즉시 다음 — 기각, 커서 강제 이동으로 사이클 위치 훼손/starvation).
- Q2 커서 영속성: **플레이리스트별 영속**(대안: 세션마다 top 리셋 — 기각, 리셋 시점 정의로 범위 증가 + 현 동작과 괴리).
- 마이그레이션: 기존 `order_number` 보존(현 상태=안정 순서), 커서 NULL 시작.
