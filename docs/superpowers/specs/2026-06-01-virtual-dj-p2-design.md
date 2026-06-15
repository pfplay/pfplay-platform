# 가상 사용자 능동 디제잉 (P2) — 설계 문서

- 작성일: 2026-06-01
- 상태: 설계 합의 완료 (구현 전)
- 범위: 전체 비전 3단계 중 **P2 (가상 사용자 능동 디제잉)**
- 대상 레포: `pfplay-platform`(백엔드, 주), `pfplay-admin`(어드민 UI)

---

## 0. 배경과 동기

파티룸 점유가 낮아 신규 유입자가 빈 방을 보고 즉시 이탈하는 문제가 빈번하다. 해법으로 여러 파티룸에
컨셉(K-POP, 애니 OST 등)을 부여하고, 그 컨셉을 따르는 **가상 사용자**를 상주시켜 "살아있는 방"을 만든다.

전체 비전은 3단계로 누적된다:

| 단계 | 서브프로젝트 | 비고 |
|---|---|---|
| P1 | 가상 사용자 아바타 일괄/개별 셋팅 (콘솔) | 본 문서 범위 아님 |
| **P2** | **가상 사용자 능동 디제잉 (콘솔)** | **본 문서** |
| P3 | AI 에이전트화 (채팅 응답 + 플레이리스트 자가갱신 + 컨셉) | 본 문서 범위 아님 |

P2를 먼저 설계하는 이유: 프로젝트 성패와 아키텍처 위험이 P2의 "수동 더미 → 능동 참여자" 피벗에 집중되어
있으므로, 이를 먼저 못 박아 전체 go/no-go 확신을 확보한다.

기존 더미 점유 설계(pfplay-platform 이슈 #264)는 더미를 **수동 머릿수**(채팅·디제잉·리액션 없음, presence
비간섭)로 잠갔으나, P2는 가상 사용자를 **실제 도메인 참여자(DJ)**로 전환한다. 이는 #264의 *확장*이 아니라
아키텍처 피벗이다.

---

## 1. 핵심 아키텍처 원칙 — 정직한 길 (path A)

가상 사용자를 능동 참여자로 만드는 두 갈래 중 하나만 채택한다:

- **(A) 정직한 길 [채택]** — 가상 유저 = **실제 계정**(진짜 userId·crew row·dj row). 실유저가 쓰는 그대로의
  **application command service 메서드**(`tryEnter`, `enqueueDj`, `dequeueDj`, `exit`)로만 행동. → 1-room
  불변식, crew 라이프사이클, DJ 큐, playback 스케줄러 등 **모든 기존 가드·도메인 캐스케이드가 그대로 적용**되어
  불변식 divergence가 발생할 수 없다.
- **(B) 게으른 길 [금지]** — `aggregatePort` / repository / `messagePublisher`를 **직접 만져 도메인
  캐스케이드(예: `handleDjQueueOnLeave`, 이벤트 발행, atomic crew 토글)를 건너뛰는 것**. 손쉽지만 불변식 보장
  책임이 호출자에게 전가되어 시간이 지나면 골격이 썩는다.

> **주의 — "internal 메서드 = 백도어"가 아니다.** `exitInternal(partyroomId, userId)`는 *"callers MUST
> already have authority"* 주석이 붙어 있으나, **전체 도메인 캐스케이드를 실행**하며 이미 presence grace
> 리스너·reconcile cron이 시스템 액션으로 사용 중이다. 그 주석은 *ThreadLocal 신원 검사(본인인가)*만 호출자
> 책임으로 위임한다는 뜻이지 불변식을 건너뛴다는 뜻이 아니다. (B)의 경계는 "public/internal"이 아니라
> **"캐스케이드를 통과하는가"**이다.

### 1.1 핵심 메커니즘 — ThreadLocal 봇 정체성 임퍼소네이션

`tryEnter`·`enqueueDj`·`dequeueDj`·`exit`는 모두 행위자(userId/tier)를 `ThreadLocalContext.getAuthContext()`
에서 읽는다(HTTP 필터가 세팅하는 것과 동일). 따라서 오케스트레이터는:

```
withBotIdentity(botUserId) {        // try: ThreadLocalContext.setContext(bot AuthContext)
    partyroomAccessCommandService.tryEnter(partyroomId, null);
    djCommandService.enqueueDj(partyroomId, botPlaylistId);
}                                   // finally: ThreadLocalContext.clear()
```

- **새 guarded 진입점 추가 불필요** — 실유저가 쓰는 public 메서드를 그대로 재사용. 가장 충실한 "진짜 유저" 경로.
- 오케스트레이터는 자기 스레드(스케줄러/이벤트 리스너 스레드)에서 실행되므로 ThreadLocal은 그 실행 범위 안에서만
  설정·해제된다. **try/finally 정리 필수**(다른 요청으로 신원 누출 방지).
- 봇 `AuthContext` 구성(필요 필드: userId, authorityTier 등)은 plan Chunk에서 실제 타입 확인 후 빌더 제공.

> **골격 붕괴 리스크는 (B)를 택할 때만 발생한다. (A)를 규율로 못 박으면 구조적 위험은 경계가 명확하고 관리
> 가능한 수준으로 떨어진다. 이 규율은 §8의 아키텍처 테스트(ArchUnit)로 강제한다 — 오케스트레이터 패키지는
> application command service + 임퍼소네이션 유틸에만 의존하고, persistence 포트/repository/`messagePublisher`
> 에는 의존하지 못한다.**

### 검증된 사실 (탐색 결과)

- 파티룸 멤버/DJ/아바타 목록은 **100% DB 소스**(`crew` 테이블 + `dj` 테이블 + `user_profile`),
  presence/Redis/세션 필터 **없음**. `crew.is_active=true`이면 **WS 세션이 한 번도 없어도** 다른 유저
  화면에 멤버·DJ·아바타로 정상 노출된다. (`findByPartyroomIdAndIsActiveTrue` → `is_active=true AND !is_banned`)
- 입장 broadcast(`CREW_ENTERED`)는 **REST `tryEnter` AFTER_COMMIT 시점**에 발행 (WS connect 아님).
- playback "다음 곡" 진행은 **서버 스케줄러**(`ExpirationTaskPort` → 트랙 duration 만료 → `complete()`)가
  구동. 클라이언트 하트비트 아님 → **연결 안 된 가상 DJ라도 곡은 정상 진행**.
- 트랙 메타데이터(videoId/title/**duration**/thumbnail)는 기존 검색 포트 `YoutubeSearchService`(구현
  `PytubeSearchService`, 우리 `pfplay-streaming` 마이크로서비스)에서 취득. **새 YouTube Data API 불필요**.
- 트랙 회전: PR #263에서 트랙의 위치값(`order_number`)을 고정(물리적 재정렬 제거)하고 **커서가 순환**하도록
  변경 → **플레이리스트 소진 개념 없음, 영속 재생**.

**결론: 헤드리스 가상 유저에게 WS 세션은 불필요하며, 없는 편이 낫다**(세션이 있으면 프로세스 재시작 시
presence 상태머신이 PENDING_EXIT→OFFLINE으로 방을 비워버린다). 세션이 없으면 `pending_exit_at`이 NULL로
남아 sweep 비대상 → 영속.

---

## 2. 결정 요약

| # | 주제 | 결정 |
|---|---|---|
| D1 | 구별 가능성 | 시각적 **구별 불가(기본)** + 내부 `is_dummy` 플래그 + 리더보드/점수 제외 + Amplitude opt-out. **"구별 가능 모드"는 런타임 토글**(`system_config` 키, 기본 OFF) — 여론 악화 시 코드 배포 없이 봇 뱃지/AI 라벨을 켤 수 있게 표시 경로를 미리 심어둔다. |
| D2 | 제어 모델 | **수동(어드민 지시형)**. 크로스룸 자동 점유 reconcile(빈 방 자동 탐지·머릿수 유지)은 #264와 합쳐 **별도 단계로 미룸**. |
| D3 | 플레이리스트 콘텐츠 | **재사용 송 팩(템플릿)**. 어드민이 이름 붙은 팩을 빌드(기존 검색 인프라 재사용, duration 사전검증)하여 N명/방에 **일괄 적용**. |
| D4 | 공존 정책 | **b′: 목표 DJ 수(T) + 사람 우선 + 동반자 하한(floor)**. 기본 T=2·floor=1, 방별 config. |
| D5 | 플레이리스트 영속 | **소진 없음**(커서 순환). 추가 루프 로직 불필요. |
| D6 | 오케스트레이터 위치 | **A안: in-process** Spring 모듈, **포트 뒤 배치**(P3에서 LLM 워커만 분리 가능하도록). |

### D4 공존 정책 상세 (T=2, floor=1 예시)

| 상황 | 봇 행동 | 결과 |
|---|---|---|
| 사람 DJ 0명 | 봇이 T까지 채움 | 봇 2명이 음악 유지 |
| 사람 DJ 1명 | 봇 floor(1)만 남기고 나머지 빠짐 | 사람 1 + 봇 1 → 혼자 아님(부담 회피), 차례 넉넉 |
| 사람 DJ 2명+ | 봇 전부 물러남 | 사람들끼리 활기 |
| 사람들이 DJ 멈춤/퇴장 | 봇이 다시 T까지 복귀 | 음악 끊김 없이 유지 |

설계 의도: 신규 유저가 **밀려나지도(자리 충분), 혼자 남겨지지도(곁에 봇 동반자)** 않게 한다.

---

## 3. 범위

### 넣음 (P2)

- 실제 계정 기반 가상 유저 **풀** 관리 (`is_dummy`)
- 재사용 **송 팩** 빌드(기존 검색 인프라 재사용) + 일괄 적용
- 방별 **가상 DJ 활성화**: T / floor / 송팩 / 상태 설정
- in-process **오케스트레이터**: 방에 봇 배치(`tryEnter`) → DJ 등록(`enqueueDj`), 도메인 이벤트 반응으로
  T 유지 · 사람에게 양보 · 복귀
- **drain / FROZEN** 라이프사이클 제어
- **`PartyroomTerminatedEvent` 리스너**: 방이 terminate되면 bulk-deactivate가 봇 crew도 비우므로, 해당 방
  config를 OFF로 정리하고 오케스트레이터 관리에서 제외.

> §9-2 검증 결과 **public `exit(PartyroomId)`가 이미 존재**하고 §1.1 임퍼소네이션으로 호출 가능하므로,
> 새 guarded EXIT 진입점 추가는 **불필요**(범위에서 제외). path A는 기존 public 메서드의 닫힌 집합으로 성립.

### 뺌 (다른 단계)

- 봇 **채팅 / 리액션 / AI** → **P3** (따라서 채팅-쓰기 세션 의존 코어 추출도 P2 아님)
- 크로스룸 **자동 점유 reconcile** → #264와 합쳐 나중
- **풍부한 아바타 일괄 커스터마이징** → **P1** (P2는 최소 정체성: 현실적 닉네임 + 기본 아바타)

---

## 4. 시스템 아키텍처

```
[Admin REST] ─→ VirtualDjAdminController ─→ VirtualDjOrchestrator (port)
                                                    │ (in-process, guarded 호출만)
[Domain events]                                     ▼
 CrewAccessedEvent ──listen──→            ApplicationServices (기존, 가드 내장)
 DjQueueChangedEvent ─listen─→            tryEnter / enqueueDj / dequeue
                                          ※ exitInternal 등 internal 메서드 직접호출 금지
```

- `VirtualDjOrchestrator`는 **새 inbound adapter**일 뿐, 새 도메인 코어가 아니다. 모든 변경은 컨트롤러가 쓰는
  *guarded* application service를 통과한다 → 불변식 단일 강제, 우회 0.
- **반응형 컨트롤러**: 방별 목표 상태(`target=T`, `floor`, `song_pack`, `status`)를 두고, 사람 DJ 입·퇴장
  이벤트에 반응해 봇 DJ 수를 조정(`enqueueDj` / dequeue). 폴링 reconcile은 **저빈도 안전망**으로만 두고
  주 동작은 이벤트 반응으로 한다.
- 봇은 **WS 세션 없음** → 영속, presence sweep 비대상.
- 오케스트레이터를 포트(interface) 뒤에 두어, P3에서 LLM 의사결정 워커를 out-of-process로 떼어낼 수 있게 한다.

### 4.1 반응형 컨트롤러 의미론 (트리거 · 산식 · anti-flap)

**구독 이벤트 → 트리거.** 컨트롤러는 다음 두 이벤트를 구독한다. 어느 쪽이 오든 **해당 방의 desired 봇 수를
재계산**하는 단일 진입(`reconcileRoom(partyroomId)`)을 호출한다.

| 이벤트 | 의미 | 반영되는 D4 상황 |
|---|---|---|
| `DjQueueChangedEvent` (ENQUEUE/DEQUEUE/ROTATE/DEACTIVATE) | DJ 큐 구성 변화 | 사람 DJ 등록 / 사람 DJ 멈춤(DEQUEUE) / silent deactivate |
| `CrewAccessedEvent` (ENTER/EXIT) | 입·퇴장 (퇴장은 DJ dequeue 캐스케이드 동반) | 사람 DJ 퇴장 |

**산식 (현재 *커밋된* 활성 DJ 집합 기준).** 트리거의 *행위*가 아니라 reconcile 시점의 **실제 커밋된 활성 DJ
집합**을 읽어 계산한다. 이렇게 하면 "첫 DJ silent deactivate"(사람이 enqueue 했으나 첫 트랙이
`playbackTimeLimit` 초과로 즉시 비활성)된 경우, 그 사람은 활성 DJ로 안 잡히므로 봇이 잘못 빠지지 않는다.

```
human = 활성 DJ 중 is_dummy=false 인 수
bot   = 활성 DJ 중 is_dummy=true  인 수
desired_bot =
    if human == 0 : T
    elif human == 1: max(floor, T - 1)     // 외톨이 사람에게 동반자 보장
    else          : max(0,    T - human)   // 사람끼리 동반자가 됨
```
(T=2, floor=1 → 0명:2 / 1명:1 / 2명:0 / 3명:0 — D4 표와 일치)

reconcile은 `bot`을 `desired_bot`에 맞춘다: 부족하면 idle 풀에서 봇을 `tryEnter`→`enqueueDj`(송팩 복사
플레이리스트), 초과하면 가장 최근 합류 봇부터 guarded dequeue/exit.

**anti-flap (2026-05-25 플래핑 인시던트 교훈).** 단순 이벤트 구동은 thrash(사람 입장→봇 제거→수초 후 사람
퇴장→봇 재투입→DJ 재등록→플레이리스트 재바인딩) 위험이 있다. 따라서:

- **방별 직렬화 락**: `reconcileRoom`은 룸 단위 분산락으로 직렬 실행(동시 이벤트 레이스 차단). 기존 룸락 패턴 재사용.
- **봇 제거 디바운스(`bot_yield_debounce`, 기본 5s)**: 사람 DJ 등장으로 봇을 빼야 할 때, 그 상태가 디바운스
  창을 넘겨 지속될 때만 실제 제거. 창 안에 사람이 사라지면 제거 취소.
- **봇 투입 최소 체류(`bot_min_dwell`, 기본 10s)**: 투입한 봇은 최소 체류 시간 전엔 제거하지 않음.
- 두 값은 `system_config` 키. 산식의 T/floor는 방별 config(`partyroom_virtual_dj_config`).
- **우선순위 규칙**: 디바운스·최소체류는 **산식의 *제거* 결정을 게이팅**한다(산식이 "빼라"고 해도 dwell/debounce가
  유지를 강제하면 유지). *투입* 결정은 게이팅하지 않는다(음악 유지가 목적이므로 부족분은 즉시 채움).

**안전망 reconcile**: 위 이벤트 반응이 주 동작이고, 저빈도(예: 60s) 스케줄러가 모든 MANAGED 방에 대해
`reconcileRoom`을 호출해 누락된 이벤트·고아 상태를 보정한다(§7 R3와 공유).

---

## 5. 데이터 모델

- `user_account.is_dummy BOOLEAN NOT NULL DEFAULT false` — 가상 유저 식별. **`user_type` enum이 아닌
  boolean으로 확정**(#264 잠금 설계 계승, 기존 temp 유저는 고정 ID로 식별하므로 type enum 도입은 과함, 가산적
  최소 변경). 리더보드/점수/Amplitude 분기에 사용.
- **`virtual_song_pack`** — 이름 붙은 재사용 팩 (id, name, 설명, 생성/수정 시각).
- **`virtual_song_pack_track`** — 팩 내 트랙 (videoId/`link_id`, name, duration, thumbnail). duration은 빌드
  시점에 `playbackTimeLimit` 이하로 사전검증.
- **`partyroom_virtual_dj_config`** — `partyroom_id`, `target_count(T)`, `companion_floor`, `song_pack_id`,
  `status(OFF / MANAGED / FROZEN)`. #264의 2축(target + enabled) + FROZEN 계승.
- 봇의 `crew` / `dj` / `playlist` / `track` row는 **전부 기존 테이블의 진짜 row**(가상 전용 테이블 아님) →
  조인·FK·NPE 안전(#264 PA-7 우려 해소).

### 5.1 송 팩 → 봇 플레이리스트 연결 (복사 의미론)

- 각 봇은 **자기 소유의 playlist + track row**를 가진다(playlist는 유저당, 커서도 playlist/DJ당 독립 →
  봇마다 재생 위치가 따로 진행). 즉 N개 봇이 한 팩을 쓰면 **track row가 N배 복제**된다. track row는 작아
  허용 가능.
- **배치 시 1회 복사**: 봇이 방에 DJ로 투입될 때, 그 방 config의 `song_pack_id` 트랙을 봇 playlist로 1회
  복사한다. 추적용으로 봇 playlist에 `source_song_pack_id`를 기록.
- **팩 편집 비전파(기본)**: 이미 배치된 봇의 playlist는 팩 편집에 자동 갱신되지 않는다. 반영하려면 어드민이
  **재적용**(drain 후 재배치, 또는 명시적 "refresh" 액션)한다. 라이브 상태 비교는
  `bot.playlist.source_song_pack_id` vs `config.song_pack_id`로 판정.
- **팩 삭제**: config 또는 배치된 봇이 참조 중인 팩은 **삭제 차단**(soft-delete 아님 — 단순·명시적). 어드민이
  먼저 detach(config에서 분리) + drain 후 삭제.

- "구별 가능 모드" 토글 = `system_config` 키 1개.

마이그레이션은 Flyway 슬롯 규칙(out-of-order=false)을 따른다.

---

## 6. 어드민 UX (pfplay-admin)

기존 어드민 인증 패턴(AdminAccessToken + XSRF double-submit + Origin allowlist) 위에 mutation 엔드포인트를 얹는다.
(pfplay-admin은 GitHub Actions 없음 — Cloudflare/Vercel native integration.)

- **가상 유저 풀 관리**: 풀 조회(idle / 배치된 방 표시), N명 생성, 최소 정체성(현실적 닉네임 자동 배정).
  아바타는 기본값(풍부한 커스터마이징은 P1).
- **송 팩 관리**: 목록 · 생성 · 편집 · 삭제. 생성 시 기존 `music-search` 컴포넌트 재사용(검색 또는 URL) +
  duration 사전검증 표시.
- **방별 가상 DJ 설정**: 파티룸 목록/상세에서 활성화 → T · floor · 송팩 · 상태 지정.
  **체크박스 다중선택 → 일괄 적용**(#264 패턴 계승).
- **라이브 상태 컬럼**: 방별 "봇 DJ x/T", 상태(OFF/MANAGED/FROZEN), 현재 봇.
- **액션**: 적용 / FROZEN / **drain(방에서 봇 전부 비우기)**.

---

## 7. 라이프사이클 + R3 완화

- **OFF**: 봇 없음, config 휴면.
- **MANAGED**: 오케스트레이터가 T/floor를 이벤트 반응으로 능동 유지.
- **FROZEN**: 봇 현 상태 고정, 오케스트레이터가 손대지 않음(인시던트/디버깅용).
- **Drain**: guarded 경로로 봇 dequeue + 방 퇴장.
- **R3 (영속 row 자가치유 X)**: 봇은 presence sweep 비대상(영속)이라 명시적 정리가 필요하다.
  - ① 어드민 drain
  - ② **안전망 reconcile**(config 없는 방의 봇 crew, TERMINATED 방의 봇 DJ 같은 고아 row 탐지·정리)
  - 2026-05-25 좀비/플래핑 인시던트 교훈 반영(crew zombie flap / maintenance gate). 배포·점검 전 SQL
    cleanup 패턴(stale crew cleanup)을 준비한다.
- **1-room 불변식**: 봇은 정확히 한 방에만. 재배치 시 guarded autoExit 경유.

---

## 8. 테스트 전략 (TDD)

핵심 회귀 잠금(골격 보호의 키스톤):

- **🔑 "오케스트레이터는 캐스케이드만 통과"** — 오케스트레이터 패키지가 **application command service +
  임퍼소네이션 유틸에만 의존**하고 `aggregatePort`/repository/`messagePublisher`에는 의존하지 못하도록
  **ArchUnit(존재 확인됨: archunit-junit5)으로 강제**. (B) 백도어(캐스케이드 우회) 추락을 구조적으로 차단.
  (`*Internal` 직접호출은 금지 대상이 아님 — §1 주의 참조. 임퍼소네이션으로 public `exit` 사용이 기본.)
- presence 비간섭: 봇 crew(WS 세션 X)가 sweep 비대상 + **봇만 있는 방의 자동종료 상호작용** 확인(#264 PA).
- 1-room 불변식(봇 포함).
- **D4 공존 산식**(§4.1): human 0/1/2/3명 각각에 desired_bot = T / max(floor,T-1) / 0 / 0 (이벤트 구동
  컨트롤러 테스트).
- **silent-deactivate 내성**(§4.1): 사람이 enqueue 했으나 첫 트랙 초과로 즉시 비활성 → 활성 DJ로 안 잡혀
  봇이 잘못 빠지지 않음(방이 비지 않음).
- **anti-flap**(§4.1): 디바운스 창 내 사람 입·퇴장 반복 시 봇 add/remove thrash가 발생하지 않음 + 봇
  최소 체류 보장.
- **duration 필터**: 팩이 `playbackTimeLimit` 초과 트랙 거부(silent deactivate 회피).
- 리더보드/점수 `is_dummy` 제외.
- 반응형 컨트롤러가 실제 도메인 이벤트에 반응하는 통합 테스트.

JDK 21 빌드 환경(`JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix), 로컬 docker compose 풀스택으로 검증.

---

## 9. 상세 설계 단계에서 코드로 확인할 항목 (현재 단정하지 않음)

1. 커서 끝→앞 **wrap** 한 줄 (영속 재생 보장). [부분 확인] `doStart`가 `rotateDjQueue` + `peekTracksFromCursor`
   /`advancePlaybackCursor`로 진행 — 플레이리스트 끝에서 커서가 맨 앞으로 wrap 하는지 `peekTracksFromCursor`
   구현부만 plan Chunk 1에서 눈으로 확정.
2. ~~public guarded EXIT 존재 여부~~ → **해소.** `exit(PartyroomId)` public 존재 + §1.1 임퍼소네이션으로 호출.
   새 메서드 불필요.
3. ~~봇만 있는 방 자동종료~~ → **해소.** 방은 crew_count==0이어도 자동 종료 안 됨(호스트 close / admin
   terminate만). 봇만 있는 방 유지됨. terminate 시 `PartyroomTerminatedEvent`로 config 정리(§3·§7).
4. `enqueueDj`는 explicit `PlaylistId`를 받고 **active crew 선행 + ownership(`isOwnedBy`) + non-empty** 검증.
   순서: 임퍼소네이션 → `tryEnter` → 봇 playlist를 팩으로 채움 → `enqueueDj(partyroomId, botPlaylistId)`.
5. **R4 부하**: `ExpirationTaskPort` 스케줄러가 N개 동시 방에서 견디는지. **수용 기준: N=20 / N=50 동시
   MANAGED 방에서 스케줄러 지연·누락 없음**을 로컬 풀스택에서 측정(rough target, 실측 후 조정).
   **"누락" 관측 신호 = 트랙 완료(`complete`)가 duration+ε 보다 늦게 발화**되는 것(구체 pass/fail probe).
6. 리더보드/점수(`user_activity` DJ_PNT) 쿼리에 `is_dummy` 제외를 넣을 지점.
7. Amplitude opt-out 재사용 지점.

---

## 10. 리스크 자세

| | 상태 |
|---|---|
| R1 presence 충돌 | **소멸** (세션 불필요 검증 완료) |
| R2 채팅-쓰기 세션 의존 | **P2에서 제외**(P3로 이관) → P2 경량화 |
| R3 영속 row 자가치유 X | §7 drain + 안전망 reconcile로 관리 |
| R4 N개 상시 방 스케줄러 부하 | §9-5 plan 단계 측정 항목 (불변식 무관, 부하 이슈) |

**최종 판단:** path A 규율(§1) + 아키텍처 테스트(§8) + #264 drain/FROZEN(§7)을 함께 가져가면, P2의 구조적
위험은 "크다"가 아니라 "경계가 명확하고 관리 가능"이다. 진행 가능한 설계.

---

## 11. 후속 단계 (본 문서 범위 밖)

- **P1**: 아바타 일괄/개별 커스터마이징 (P2가 만든 가상 계정 모델 위에).
- **P3**: AI 에이전트화 — 채팅 응답(채팅-쓰기 세션 코어 추출 + 상용 LLM API), 디제잉 히스토리 반영
  플레이리스트 자가갱신, 방 컨셉(K-POP/애니 OST) 추종. 오케스트레이터 포트 뒤에서 LLM 워커 분리(C안).
- 크로스룸 **자동 점유 reconcile**: #264와 통합.
