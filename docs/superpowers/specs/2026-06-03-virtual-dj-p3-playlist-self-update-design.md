# 가상 사용자 AI 에이전트화 — P3-B (플레이리스트 반응 적응 자가갱신) 설계 문서

- 작성일: 2026-06-03
- 상태: 설계 합의 완료 (구현 전)
- 범위: 전체 비전 3단계 중 **P3 의 두 번째 분할 = P3-B (플레이리스트 자가갱신)**
- 대상 레포: `pfplay-platform`(백엔드, 단일)
- 선행: P3-A (방 컨셉 + LLM 채팅, `feature/virtual-dj-p3-chat`) — 구현·검증 완료, 미머지

---

## 0. 배경과 동기

전체 비전은 빈 파티룸 문제를 "살아있는 방"으로 해결하는 3단계 누적 구조다:

| 단계 | 서브프로젝트 | 상태 |
|---|---|---|
| P1 | 가상 사용자 아바타 일괄/개별 셋팅 (콘솔) | 완료 (dev/stg) |
| P2 | 가상 사용자 능동 디제잉 (콘솔) | 완료 (dev/stg) |
| **P3** | **AI 에이전트화** (채팅 응답 + 플레이리스트 자가갱신 + 방 컨셉) | **본 문서가 그 일부** |

P3 비전의 3가지 독립 능력 중:

1. **채팅 응답 LLM** — P3-A 완료.
2. **플레이리스트 자가갱신** — **본 문서(P3-B) 스코프**.
3. **방 컨셉 추종** — 채팅 측면은 P3-A, 선곡 측면은 P3-B 가 흡수.

P2 까지의 봇 playlist 는 `SongPackApplier.applyToBot` 가 봇 투입 직전 **송 팩을 1회 통째로 복사**한 뒤
**정적**이다. 룸에서 어떤 곡이 사랑받든 무시당하든 playlist 는 변하지 않는다. P3-B 는 이 정적 1회 복사를
**그 방의 청취자 반응에 적응하는 지속적 자가갱신**으로 증분한다.

### 0.1 1차 목적 (성공 기준)

**청취자 반응 적응.** 그 방에서 실제 반응(좋아요/싫어요/완주)이 좋은 곡 계열은 남기고 키우며, 저반응 곡은
빼고 LLM 추천으로 유사·컨셉 곡을 채운다. 성공 = "세션이 길어질수록 봇 playlist 가 그 방 취향으로 수렴한다."

방 컨셉 추종(비전 ③ 선곡 측면)은 본 목적의 **부차 입력**으로 들어간다(LLM refill 프롬프트에 방 컨셉 주입).
단독 1차 목적은 아니다.

---

## 1. 핵심 아키텍처 원칙 — path A 유지 (P2/P3-A 계승)

- 봇은 **실제 계정**(`is_dummy=true`, 진짜 crew/playlist row)이다.
- virtualdj 패키지는 **다른 BC 의 AggregatePort / MessagePublisher / repository 를 직접 조작하지 않는다.**
  party·playback·playlist BC 와의 상호작용은 모두 **그 BC 의 application query/command service 를 경유**한다.
  ArchUnit `VirtualDjArchitectureTest` 로 강제한다.
- 단, P3-B 의 모든 변경은 **봇 자신의 playlist/track** 에 대한 것이며 도메인 가드(재생 제한·소유권)를 통과한다.
  봇 playlist 의 track 직접 조작은 `SongPackApplier` 가 이미 하던 것과 동일 레벨(자기 소유 playlist 의
  persistence 접근)이므로 path A 위반이 아니다(P2 §1: 경계는 "도메인 가드·캐스케이드 통과 여부").

---

## 2. 불변식 (Invariants)

설계 전체가 지켜야 할 계약. 테스트로 강제한다.

- **INV-1 (크기 하한):** 한 갱신 사이클이 끝났을 때 playlist 크기는 직전 크기 또는 목표 T 아래로 떨어지지 않는다.
  **prune 은 성공적으로 추가한 곡 수만큼만 한다(never prune more than you add).**
- **INV-2 (조용한 방 LLM 0):** 새 반응이 임계 K 미만인 방은 **LLM 을 절대 호출하지 않는다.** 비용 폭발 차단의
  1차 게이트. 빈 방/더미 점유 방은 사실상 영원히 no-op.
- **INV-3 (재생 보호):** 현재 재생 중인 트랙과 임박(다음 커서) 트랙은 **절대 prune 되지 않는다.**
  현재곡 식별 소스 = `partyroom_playback.current_playback_id` → 그 `playback.link_id`, 임박곡 = PR263
  재생 커서의 다음 track. 두 linkId 를 prune 후보에서 제외한다.
- **INV-4 (fail-closed):** 전역 게이트 `vdj.playlist.self_update.enabled` 가 false 면 어떤 갱신도 일어나지
  않는다. 키 부재/오타도 false 로 폴백한다(코드 default false).
- **INV-5 (룸 격리):** 한 룸의 갱신 실패(예외)는 다른 룸의 sweep 을 막지 않는다.
- **INV-6 (진동 방지):** 직전 사이클에 prune 된 linkId 는 쿨다운 기간 동안 다시 추가되지 않는다.

---

## 3. 데이터 모델 (기존 스키마, 검증 완료)

신규 테이블 없음. 기존 재생/반응 스키마를 그대로 읽는다.

| 테이블 | 키 컬럼 | 용도 |
|---|---|---|
| `playback` | `id, user_id(=DJ), partyroom_id, link_id, duration, end_time, created_at` | 재생 이벤트 1건 |
| `playback_aggregation` | `playback_id, like_count, dislike_count, grab_count` | 재생당 반응 집계 |
| `playback_reaction_history` | `playback_id, user_id, liked, disliked, grabbed, created_at` | (재생,유저)별 반응 row — **COUNT 게이트 소스** |
| `track` | `playlist_id, link_id, name, duration, order_number, thumbnail_image` | playlist 곡 |
| `partyroom_virtual_dj_config` | `partyroom_id, status, song_pack_id, ...` | 봇 운영 설정 (**V29 로 컬럼 추가**) |

- **봇의 plays** = `playback WHERE user_id = botUserId AND partyroom_id = roomId`.
- **신규 컬럼(V29):** `partyroom_virtual_dj_config.last_self_update_at DATETIME NULL` — watermark 겸
  쿨다운 기준. cron 이 stateless 이므로 config row 에 영속한다.

> ⚠️ **완주율(completion) 신호는 채택하지 않는다 — 데이터로 산출 불가.** `playback.end_time` 은 생성 시
> `duration.calculateEndTimeEpochMilli(now)`(= 시작 + 곡 전체 길이, **예정된 종료 epoch-millis**)로 1회 기록되며
> 이후 갱신되지 않는다. "청취자가 끝까지 들었나" 정보를 0 비트도 담지 않는다. 또한 트랙 조기중단 경로(관리자
> 스킵/DJ 자가하차/DJ 퇴장/방삭제 등 `tryProceed` 로 수렴)는 전부 **기계적·관리적**이며, *청취자 선호 주도 스킵*
> (vote-skip)은 코드에 부재하다. 따라서 완주/스킵을 기록해도 선호를 거의 반영하지 못한다. 선호를 직접 재는
> 신호는 `like/dislike/grab` 이며 score 는 이 셋만 쓴다(§5.1).

> ⚠️ 마이그레이션 추가 → **로컬 풀스택 validate 부팅 게이트 필수**
> ([[reference_ddl_auto_create_drop_hides_migration_drift]]). test=create-drop 가 drift 를 가린다.

---

## 4. 트리거 & 게이트 (60s reconcile cron 재사용)

기존 `VirtualDjReconcileScheduler`(`@Scheduled(fixedDelay=60_000)`)에 자가갱신 스텝을 얹는다. 신규
스케줄러·이벤트 구독·Redis 카운터 수명주기 관리 없음(상태 최소).

각 MANAGED 룸마다 — **아래 조건 AND, 하나라도 실패 시 그 룸 no-op (LLM 미호출)**:

```
1. vdj.playlist.self_update.enabled = true              (전역 게이트, INV-4)
2. 룸 status = MANAGED
3. now − last_self_update_at ≥ cooldownSeconds          (쿨다운 키)
4. COUNT(playback_reaction_history rh JOIN playback p
        ON rh.playback_id = p.id
        WHERE p.user_id = botUserId
          AND p.partyroom_id = roomId
          AND rh.created_at > last_self_update_at) ≥ K   ⭐ INV-2 비용 게이트
```

- **비용 모델:** 게이트 통과 검사는 룸당 인덱스 COUNT 쿼리 1회/분. MANAGED 룸 수는 운영상 적다. **4 를
  통과한 룸에서만** §5 의 score 계산·LLM 호출로 진입한다.
- watermark 부재(첫 사이클, `last_self_update_at IS NULL`) → COUNT 는 전체 기간 기준. 첫 진입은 봇 투입 후
  반응이 K 이상 쌓였을 때.
- `K`, `cooldownSeconds` 는 `system_config` 키(런타임 튜닝).

---

## 5. 갱신 사이클 (원자적 증분 교체)

게이트 통과 룸에서만 실행. 목표 크기 T 를 유지하며 최저점 P 곡을 고반응 계열 신곡으로 교체한다.

```
입력: botUserId, roomId, 봇 playlistId, songPackId, roomPlaybackTimeLimit, 방 컨셉(RoomContextReader)

1. SCORE
   score(linkId) = w_react·(Σlike − Σdislike) + w_grab·Σgrab
   대상: 봇 자기 plays(user_id=botUserId, partyroom_id=roomId)를 link_id 로 group.
   집계 소스: playback_aggregation(like_count/dislike_count/grab_count) per linkId.
   한 번도 안 튼 곡 = score 없음(중립): prune 후보 아님, boost 입력도 아님.
   ※ 완주율 항 없음 — 데이터 부재(§3). grab 이 강한 양성 선호 신호를 대체한다.

2. PRUNE 후보 선정
   현재 playlist track 중 score 최저 P 곡. 단 제외:
     - 재생 중 / 임박(다음 커서) 트랙           (INV-3)
     - 최근 prune set(Redis, TTL) 에 든 linkId  (INV-6 — 재추가만 막음; 이미 있으니 무관)
   → pruneCandidates (최대 P개, score 오름차순)

3. LLM REFILL (best-effort)
   고반응 우승 곡(score 상위) + 방 컨셉(title/introduction) → SongRecommendationProvider
     → 곡명 후보 N개 (LlmChatProvider.complete, 빈 문자열이면 빈 리스트)
   각 곡명 → PytubeSearchService.searchByWord(query, rows)
     → duration 필터(PlaybackTimeLimit) + dedup(현 playlist linkId) + 최근prune set 차단
   → resolved (해소·검증 통과 곡)

4. SONGPACK 폴백 (resolved < |pruneCandidates| 일 때)
   송 팩(songPackId)에서 "아직 시도 안 한 곡"(현 playlist·최근prune set 에 없는 곡)을
   duration 필터 통과 순서대로 보충 → added = resolved + 폴백분

5. ATOMIC SWAP (INV-1)
   n = min(|added|, |pruneCandidates|)
   prune = pruneCandidates 의 최저점 n 곡만 삭제
   add   = added 의 상위 n 곡을 add-to-head 정책으로 삽입 (PR263 커서/grab tail 규약)
   ⇒ size 불변. added 가 0 이면 prune 0 = 그 사이클 변경 없음.

6. COMMIT
   last_self_update_at = now (watermark 전진)
   prune 된 linkId → Redis 최근prune set 에 TTL 로 추가 (INV-6)
```

- **add-to-head 결정 근거:** [[project_playlist_cursor_redesign_pr263]] 에서 order_number 이중용도 분리 +
  add-to-head 기획. 신곡을 머리에 넣어 빠르게 노출하되 grab tail·재생 커서는 보존한다. 정확한 삽입 위치는
  PR263 의 Track ordering 헬퍼를 재사용한다(구현 시 확정).
- **w_react / w_grab / N / T / P** 는 `system_config` 키(런타임 튜닝).

---

## 6. 컴포넌트 & 시임

| 컴포넌트 | 종류 | 책임 | ArchUnit |
|---|---|---|---|
| `VirtualDjReconcileScheduler` | 기존 확장 | 게이트 1·2·3 검사 → 통과 룸을 `PlaylistSelfUpdateService` 에 위임 | — |
| `PlaylistSelfUpdateService` | 신규 service | §5 사이클 오케스트레이션. score·refill·폴백·atomic swap 조립 | port 경유 |
| `ReactionScoreReader` | 신규 port + adapter | 게이트 COUNT(조건 4) + score 집계 쿼리(linkId별 Σlike/Σdislike/Σgrab). virtualdj-adapter cross-BC 쿼리(`ActiveDjSnapshot` 패턴, QueryDSL) | AggregatePort/MessagePublisher 직접의존 금지 |
| `PartyroomQueryService` | **기존 확장** | `getCurrentPlaybackLinkId(PartyroomId)` 신설(INV-3). `getCurrentPlaybackName` 미러 — `aggregatePort.findPlaybackState` → `getPlaybackById(currentPlaybackId).getLinkId()`, 비활성/없음=null. party BC 안에 AggregatePort 가둠 | (party BC 내부) |
| `SongRecommendationProvider` | 신규 port + adapter | 우승곡+컨셉 → 곡명 리스트. 내부에서 `LlmChatProvider` 재사용 + 프롬프트 조립/파싱. best-effort 빈 리스트 | — |
| `SongPackReservoir` | 신규(또는 `SongPackApplier` 추출) | songPackId 에서 "미시도 곡" 조회 (폴백 소스) | — |
| `RecentlyPrunedStore` | 신규(Redis) | per-bot prune된 linkId set + TTL (INV-6) | — |
| `RoomContextReader` | 기존(P3-A) | 방 컨셉(title/introduction) 재사용 | (기존) |
| `PytubeSearchService` | 기존 | 곡명 → SearchResultDto 해소 | (기존) |
| `SelfUpdateConfig` | 신규(또는 P3-A `VirtualDjChatConfig` 패턴) | `vdj.playlist.self_update.*` 키 read + fail-closed default | — |

**설계 원칙:** `PlaylistSelfUpdateService` 는 조립자(orchestrator)이고, score 쿼리·LLM·검색·폴백·진동방지는
각각 단일 책임 단위로 분리한다. 각 단위는 독립적으로 단위 테스트 가능해야 한다.

> **ArchUnit 적용 범위 (정확히):** 기존 `VirtualDjArchitectureTest` 는 패키지 전역으로 `*AggregatePort`/
> `*MessagePublisher` 직접의존만 막고, `*Repository` 직접의존은 **클래스명에 "Orchestrator" 포함 시에만**
> 막는다. 신규 `PlaylistSelfUpdateService`/`ReactionScoreReader` 는 "Orchestrator" 미포함이므로 — 봇 자기
> playlist 의 `Track`/`Playlist` repository 직접 접근은 **ArchUnit 상 허용**된다(§1 원칙대로, `SongPackApplier`
> 와 동일). 위 표의 "port 경유"는 **다른 BC(playback) 의 데이터에 한정**한 규율이다: `ReactionScoreReader`
> 의 adapter 는 playback BC query service 를 경유하고 그 BC 의 AggregatePort 를 직접 만지지 않는다. plan 은
> 신규 ArchUnit 단언을 추가하지 말 것(비-Orchestrator 서비스의 `*Repository` 차단을 기대하는 테스트 금지).

---

## 7. 설정 키 (V29 시드 + system_config)

P3-A 의 `vdj.playlist.self_update.enabled`(V28, 기본 false) 를 **활성 게이트로 승격**하고 튜닝 키를 추가한다.

| 키 | 기본값 | 용도 |
|---|---|---|
| `vdj.playlist.self_update.enabled` | `false` | 전역 게이트(V28 기존, INV-4) |
| `vdj.playlist.self_update.cooldown_seconds` | (예: 1800) | 쿨다운(조건 3) |
| `vdj.playlist.self_update.min_reactions` (K) | (예: 5) | 비용 게이트 임계(조건 4) |
| `vdj.playlist.self_update.target_size` (T) | (예: 20) | 목표 playlist 크기 |
| `vdj.playlist.self_update.replace_per_cycle` (P) | (예: 3) | 사이클당 최대 교체 수 |
| `vdj.playlist.self_update.recommend_count` (N) | (예: 6) | LLM 곡명 추천 수 |
| `vdj.playlist.self_update.weight.reaction` | (예: 1000‰=1.0) | w_react (순반응 가중치, 퍼밀) |
| `vdj.playlist.self_update.weight.grab` | (예: 2000‰=2.0) | w_grab (grab 가중치, 퍼밀) |
| `vdj.playlist.self_update.pruned_cooldown_seconds` | (예: 3600) | Redis 최근prune set TTL(INV-6) |

> weight 는 `SystemConfigCache` 에 readDouble 이 없어 **정수 퍼밀(‰)** 로 저장하고 1000 으로 나눠 double 로 쓴다.
> `weight.completion` 키는 **없다**(완주율 미채택, §3/§5.1).

기본값 괄호는 초기 제안. plan 단계에서 확정. **활성화 = 키 설정 + 어드민 패널 토글 또는
`UPDATE system_config`**(P3-A 패널 `selfUpdateEnabled` 가 이미 이 키를 read/write).

---

## 8. 안전 / 실패 경로

- **duration 필터:** 룸 `playbackTimeLimit` 초과 곡 제외(`PlaybackTimeLimit.exceedsDuration`, `SongPackApplier` 재사용).
- **dedup:** 현 playlist 의 linkId 중복 추가 금지.
- **진동 방지(INV-6):** prune 된 linkId 는 Redis set(TTL)에 두어 그 기간 재추가 차단.
- **Pytube 0건:** LLM 제안 곡명이 검색 0건이면 그 곡 스킵(예외 아님).
- **LLM best-effort:** `LlmChatProvider` 계약상 실패 시 빈 문자열 → 빈 리스트 → §5.4 송팩 폴백.
- **LLM·송팩 둘 다 빈손:** added=0 → prune 0 → 그 사이클 변경 없음(INV-1, size 불변).
- **룸 격리(INV-5):** 룸별 try/catch, 한 룸 실패가 sweep 중단 안 함(기존 cron 패턴 계승).
- **즉시 kill:** 어드민 패널 `selfUpdateEnabled` OFF → 다음 tick 부터 전역 no-op.

---

## 9. 테스트 전략

- **단위:**
  - score 산식: like/dislike/grab/완주율 조합 → 정렬 순서 검증.
  - 게이트 AND: 4 조건 각각 단독 실패 시 no-op, 모두 충족 시 진입.
  - atomic swap: added < pruneCandidates / added=0 / added > 0 각각 INV-1(크기 하한) 검증.
  - 진동 방지: 최근prune set 의 linkId 가 refill 후보에서 제외되는지.
- **통합(IT — deploy-blocker 포착):**
  - 빈 방(반응 0) → LLM 미호출(INV-2). Mock provider 호출 0 검증.
  - 반응 K 이상 쌓인 방 → 1회 갱신, watermark 전진, 쿨다운 내 재호출 안 됨.
  - LLM 실패(빈 문자열) → 송팩 폴백으로 size 불변(INV-1).
  - 재생 중 트랙 prune 제외(INV-3).
- **ArchUnit:** `VirtualDjArchitectureTest` 지속 — 신규 컴포넌트의 AggregatePort/MessagePublisher 직접의존 금지.
- **로컬 풀스택 e2e 게이트(dev 머지 전 필수):** docker-compose 풀스택 + V29 validate 부팅 + 실 갱신 1회
  관찰([[feedback_local_e2e_before_dev_merge]], [[reference_local_docker_compose]]). 실 Anthropic 키는
  P3-A 와 동일하게 미준비 가능 → SongRecommendationProvider 계약은 단위로, 폴백 경로는 IT 로 검증.

---

## 10. 범위 밖 (YAGNI)

- 봇↔봇 간 playlist 공유/학습.
- 전역(방 무관) 인기 차트 기반 선곡 — 방-로컬 적응만.
- 사용자별 개인화 — 방 단위 적응만.
- 실시간(트랙 종료 즉시) 갱신 — 60s cron + 쿨다운으로 충분.
- 어드민 신규 UI — P3-A 패널의 `selfUpdateEnabled` 토글 재사용, 튜닝 키는 SQL.

---

## 11. 빌드 / 브랜치 / 승격

- 빌드: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix ([[reference_pfplay_platform_jdk]]).
  Dockerfile 은 호스트 `app/build/libs/*.jar` 복사 → 이미지 빌드 전 `:app:bootJar` 필수.
- 브랜치: `feature/virtual-dj-p3-chat` tip 에서 분기(V28 + `RoomContextReader` 상속).
- dev 머지 = 사용자 게이트(자동 금지). prod 승격 = P3 전체 + P1 묶음 일괄 release/main.
