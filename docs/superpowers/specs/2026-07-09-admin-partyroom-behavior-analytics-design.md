# 어드민 파티룸 행동분석 (Behavior Analytics) — 백엔드 설계

- 작성일: 2026-07-09
- 범위: **플랫폼 백엔드 only** (어드민 SPA UI는 후속 슬라이스)
- BC: `administration` (Party 데이터는 `PartyroomAggregatePort` 경유)
- 상태: Draft → 스펙 리뷰 대기

## 1. 배경 & 목표

어드민 콘솔에서 **특정 파티룸을 골라 상세로 들어가** 고객 행동을 진단하는 기능. 이미 프론트→Amplitude로 집계·퍼널 분석이 있으나, 그것은 **서비스 전체 aggregate**다. 본 기능은 성격이 다르다:

- **운영/포렌식 드릴다운**: "이 방 하나가 어떻게 굴러갔나"를 파고든다.
- Amplitude가 답하지 못하는 것: 서버 영속 데이터 기반의 방별 사실(fact) 조회.

핵심 가설(사용자 제기): **"사람들이 파티룸을 나가는 이유가 노래가 활성화되지 않아서(무음)일 것"** — 이를 서버 데이터로 검증한다.

## 2. 스코프

| # | 기능 | 상태 |
|---|------|------|
| ① | 파티룸+호스트 목록, 최근 개설순 | **이미 구현됨** — `GET /api/v1/admin/partyrooms`가 `sort=createdAt DESC` 기본 + host 필터/닉네임 노출. **본 스펙 제외** |
| ② | 최근 N일 입장/퇴장 집계 + 일자별 추이 | 신규 |
| ③ | "무음 이탈" 가설 지표 (근사) | 신규 |
| ④ | 디제잉 이력 페이지네이션 | 신규 |

Non-goals (명시적 제외):
- 개별 입장/퇴장 이벤트 raw 나열(드릴다운) — 데이터 폭발 회피. 필요 시 후속.
- 리액션/채팅 밀도 — **서버 미영속**(Amplitude 전용). 본 기능으로 불가.
- playback 활성화 구간의 **정확한** FSM 이력 — 전용 로깅 없음. ③은 근사만.
- 어드민 SPA 화면.

## 3. 데이터 소스 (기존 스키마, 신규 저장 없음)

| 소스 | 용도 | 근거 |
|------|------|------|
| `user_activity_log` (V10, append-only) | ② 입퇴장, ③ 퇴장 시각 | `event_type IN (PARTYROOM_ENTERED, PARTYROOM_EXITED)`, `partyroom_id`, `occurred_at`. administration BC 소유 |
| `playback` (V1) | ③ 활성구간, ④ DJ 이력 | `user_id`(DJ), `name`(트랙명), `created_at`(시작), `end_time`(예정종료 epoch ms), `thumbnail_image`, `partyroom_id`. Party BC |
| `member`/`profile` | ④ DJ 닉네임/아바타 | 기존 `detail()`의 `memberRepository.findAllByUserAccountIdIn` 재사용 |

> **주의 — 인덱스 현황(§7)**: `user_activity_log`는 `partyroom_id` 인덱스가 **없다**. `playback`은 단일컬럼 `playback_partyroom_id_IDX (partyroom_id)`가 **이미 존재**(V1)하나 `created_at` 정렬을 커버하지 못해 복합으로 대체한다.

## 4. API 설계

기존 `AdminPartyroomQueryController` 계열에 추가. 모든 엔드포인트 `@PreAuthorize("@adminAuth.isAdmin()")`, `ApiCommonResponse<T>` 래핑.

### 4.1 `GET /api/v1/admin/partyrooms/{partyroomId}/analytics` — ②+③

Query param: `days` (기본 20, **1~90 범위 밖은 400** `ADM-PR-002` reject — 파티션 스캔 방어).

윈도우: `[now - days, now)`. 모든 시각 계산은 `Asia/Seoul` 고정 zone(§5.0). `daily` 버킷의 날짜는 **KST 달력일**(`date(occurred_at)`은 벽시계 그대로라 KST일과 일치; UTC 변환 삽입 금지).

```jsonc
{
  "windowDays": 20,
  "attendance": {                       // ② — user_activity_log
    "totalEntered": 128,
    "totalExited": 121,
    "uniqueVisitors": 47,               // 윈도우 내 PARTYROOM_ENTERED 의 distinct user_account_id
    "daily": [                          // 발생한 날만, KST 달력일 asc (윈도우는 자정 미정렬 → days=90이면 최대 91일 걸침)
      { "date": "2026-06-20", "entered": 12, "exited": 9 }
    ]
  },
  "silenceExit": {                      // ③ — playback 근사 (§5)
    "approximate": true,               // 항상 true — 근사 지표임을 명시(오독 방지)
    "totalExits": 121,                 // == attendance.totalExited (같은 윈도우·같은 이벤트원). 항상 동일
    "exitsDuringSilence": 51,
    "silenceExitRatio": 0.42,          // 소수 2자리 HALF_UP. totalExits=0 → null
    "totalSilenceMinutes": 137
  }
}
```

- **404(S2)**: 집계 전 서비스가 `PartyroomAggregatePort.findPartyroomById`로 존재를 먼저 검증. 없으면 `NOT_FOUND_ROOM` → `GlobalExceptionHandler` 404. (검증 없으면 없는 방도 빈 200이 나오므로 필수.)
- `uniqueVisitors`: 윈도우 내 **ENTERED 이벤트 기준** distinct. 윈도우 이전 입장해 윈도우 안에서 EXITED만 있는 유저는 **제외**.
- `daily`는 이벤트 0인 날을 채우지 않음(발생한 날만) — 프론트에서 축 렌더(§9 확정).

### 4.2 `GET /api/v1/admin/partyrooms/{partyroomId}/dj-history` — ④

Query: 표준 `Pageable` (`page`, `size`; `size`는 기존 컨트롤러처럼 **200 초과 시 200으로 clamp**, reject 아님). 정렬 **`created_at DESC` 고정**(클라 sort param 무시 — §9 확정). 404 동작은 §4.1과 동일(집계 전 파티룸 존재 검증).

```jsonc
// Page<AdminDjHistoryItemResponse>
{
  "content": [
    {
      "playbackId": 90123,
      "trackName": "...",
      "djUserAccountId": 55,
      "djNickname": "...",         // null-tolerant (탈퇴/프로필 미완)
      "avatarIconUri": "...",
      "thumbnailImage": "...",
      "playedAt": "2026-07-09T14:02:11"   // created_at
    }
  ],
  "pageable": { ... }, "totalElements": 340, ...
}
```

## 5. ③ 무음 이탈 알고리즘 (근사)

### 5.0 시간축 통일 (C1 — 반드시 선결)
세 시각의 타입이 다르다: `occurred_at`·`created_at`은 오프셋 없는 **벽시계**(`LocalDateTime`/`DATETIME`), `end_time`은 **절대 epoch millis**(`bigint`), `now`도 벽시계. 유일하게 올바른 다리는 고정 zone `Asia/Seoul`(한국은 DST 없음, +09:00 고정)이다. **모든 시각을 epoch millis(`long`)로 통일**해 구간 연산을 수행한다:

```
static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");   // JVM 기본 TZ에 의존하지 않음(CI/테스트 TZ 무관)
long ms(LocalDateTime t) = t.atZone(SEOUL).toInstant().toEpochMilli();
// end_time 은 이미 epoch millis 이므로 그대로 사용.
```
③의 순수함수 단위테스트 입력도 이 결정에 맞춰 `long`(epoch millis)로 고정한다.

### 5.1 활성구간(음악 재생 중) 집합 생성
입력: partyroom의 playback 행 — **정렬 `created_at ASC`**, 아래 두 부분을 합친 것.
- (a) 윈도우 내 트랙: `created_at ∈ [from, now)`.
- (b) **straddle 트랙(S1)**: `created_at < from` 중 **가장 최신 1건**. 이 트랙이 `from` 이후까지 재생 중이었을 수 있으므로 반드시 포함한다(누락 시 윈도우 초반이 무음으로 오분류되어 silence 비율이 부풀려짐).

각 트랙 → 반열림 구간 `[startMs, endMs)`:
- `startMs = max(ms(created_at), fromMs)` — straddle 트랙 시작을 `from`으로 클램프.
- `rawEnd  = min(end_time, next.created_at_ms)` — 스킵으로 실제 종료가 앞당겨진 경우 다음 트랙 시작으로 클램프(예정종료 과대추정 완화). 다음 트랙이 없으면 `end_time`.
- `endMs   = min(rawEnd, nowMs)` — **윈도우 끝(now) 클램프(C2)**. 진행 중 트랙의 예정 `end_time`이 미래여도 union이 윈도우를 넘지 않게 한다.
- `startMs >= endMs`면 버림(빈 구간).

겹치거나 인접한 구간은 병합 → 정규화된 disjoint 구간 집합 `intervals`(각 `[start, end)`).

### 5.2 퇴장 분류
각 `PARTYROOM_EXITED`의 `exitMs = ms(occurred_at)`가 **어떤 `[start, end)`에도 포함되지 않으면 무음 중 이탈**.
- 경계 규약(S3): **half-open `[start, end)`** — `exitMs == start`는 음악 중, `exitMs == end`는 무음. (병합 규약과 일관.)
- 정렬된 `intervals`에 대해 이진탐색 O(log n).

### 5.3 집계
- `totalExits` = 윈도우 내 EXITED 수.
- `exitsDuringSilence` = 위 무음 분류 수.
- `silenceExitRatio` = `exitsDuringSilence / totalExits`, **소수 2자리 반올림(HALF_UP)**. `totalExits == 0` → `null`.
- `totalSilenceMinutes` = `(nowMs − fromMs) − Σ(intervals 길이)`, 분 단위 반올림. 5.1의 now/from 클램프로 union ⊆ 윈도우가 보장되어 **음수 불가**.

### 5.4 명시적 한계 (응답 문서/코드 주석에 남김)
- `end_time`은 **예정** 종료 → 스킵 시 과대추정 가능(연속 트랙이면 §5.1 `rawEnd` 클램프로 상쇄, 마지막 트랙만 잔여 오차).
- 리액션/채팅으로 인한 "체감 활성"은 못 봄 — 오직 **트랙 재생 여부** 기준.
- EXIT은 presence grace(pending_exit) 이후 시각이라 실제 이탈과 수 초~수십 초 오차 가능.
- **재입장/중복**: 같은 유저가 여러 번 입퇴장하면 각 EXIT을 독립 카운트(uniqueVisitors와 별개).
- 결론: **정밀 지표가 아니라 가설의 방향성 신호**. 응답에 `approximate: true` 플래그를 노출한다(§9 확정).

## 6. 구현 배치 (헥사고날, 기존 패턴 미러링)

```
administration/adapter/in/web/
  AdminPartyroomQueryController            (기존 확장 — analytics, dj-history 엔드포인트 추가)
administration/application/service/
  AdminPartyroomAnalyticsQueryService      (신규; read-only. 조회부하를 detail 서비스와 분리)
administration/application/dto/
  AttendanceAnalytics, SilenceExitAnalytics, DailyAttendanceBucket   (신규)
administration/adapter/out/persistence/
  UserActivityLogAnalyticsRepository(Custom/Impl)  (신규; QueryDSL, BC 내부)
party/domain/port/PartyroomAggregatePort   (메서드 추가)
  + findPlaybackForInterval(PartyroomId, LocalDateTime from, LocalDateTime now): List<PlaybackData>
      // ③ — created_at ∈ [from, now) 전부 + created_at < from 중 최신 1건(straddle, S1). created_at ASC.
  + findPlaybackHistory(PartyroomId, Pageable): Page<PlaybackData>          // ④
party/adapter/out/persistence/...          (위 포트 구현; QueryDSL)
```

- **파티룸 존재 검증 먼저(S2)**: 두 엔드포인트 모두 서비스 진입 시 `aggregatePort.findPartyroomById`로 404 판정 후 집계 진행.
- **BC 경계**: ②는 administration이 자기 소유 `user_activity_log`를 직접 질의. ③④의 `playback`은 반드시 `PartyroomAggregatePort` 경유(ArchUnit Task 18 규칙 유지). ④의 닉네임/아바타 enrichment는 administration 측 `memberRepository`로 수행(기존 `detail()`과 동일 전략).
- 집계/구간연산은 **서비스 계층**에서(순수 함수로 분리해 단위테스트 용이 — §5는 `long` 입력 순수함수). 리포지토리는 raw row만 반환.
- ②의 집계(GROUP BY date)는 DB에서 수행(QueryDSL `date()` 그룹핑, KST 벽시계) vs 앱에서 수행 — **DB 그룹핑 권장**(전송량 축소). uniqueVisitors는 ENTERED 필터 후 `countDistinct(user_account_id)`.

## 7. 성능 & 마이그레이션 (신규 인덱스)

20일 윈도우는 `occurred_at`/`created_at` 파티션·범위로 좁혀지나, 바쁜 방/시기엔 전체 입퇴장·재생 스캔 부담. 신규 마이그레이션으로 범위/정렬 최적화 인덱스 추가:

```sql
-- V<next>: admin behavior-analytics range/sort indexes
-- user_activity_log: partyroom_id 인덱스 신규(기존 부재).
ALTER TABLE user_activity_log
  ADD INDEX idx_ual_partyroom_event_time (partyroom_id, event_type, occurred_at DESC);
-- playback: 기존 단일컬럼 playback_partyroom_id_IDX(V1)를 복합으로 대체(신규가 완전 상위집합 → 중복 제거).
ALTER TABLE playback
  ADD INDEX idx_playback_partyroom_time (partyroom_id, created_at DESC);
DROP INDEX playback_partyroom_id_IDX ON playback;
```

> 엔티티 `PlaybackData`의 `@Index(name="playback_partyroom_id_IDX", ...)`도 신규 복합 인덱스로 **동시 교체**한다. (주의: Hibernate `validate`는 테이블/컬럼/타입만 검증하고 secondary index는 검증하지 않으므로 미교체가 부팅을 깨진 않는다 — 부팅 게이트에 의존하지 말 것. 다만 `create-drop` 테스트 컨텍스트의 생성 DDL 정합을 위해 애노테이션 동기화는 위생 요건.) 마이그레이션 DROP과 엔티티 애노테이션을 한 커밋에 묶는다.

- **컬럼 순서(S4)**: `user_activity_log`는 `(partyroom_id, event_type, occurred_at)`. 등가 필터(`partyroom_id`, `event_type IN/=`)를 앞에, 범위/정렬(`occurred_at`)을 뒤에 둔다. ②는 `event_type IN (ENTERED,EXITED)` + GROUP BY date, ③은 `event_type = EXITED` — **둘 다 이 하나의 인덱스로 커버**. (단순 `(partyroom_id, occurred_at)`은 event_type을 잔여 필터로 남겨 ②의 선택도가 떨어짐.)
- **쓰기 비용 저울질(S4)**: `user_activity_log`는 append-only **핫 라이트 경로**라 인덱스 1개 추가는 매 INSERT에 소폭 비용. 그러나 (a) 어드민 조회의 스캔 폭발 방어 이득이 크고, (b) 인덱스 선두가 write 분산되는 `partyroom_id`라 hot-spot 삽입은 아님 → **추가 채택**. 향후 write 병목 관측 시 재검토.
- `user_activity_log`는 파티셔닝 테이블 → 인덱스는 각 파티션에 로컬 생성(정상).
- **Flyway 슬롯 확정 규칙**: V32/V33은 **미머지** `feature/vdj-bot-model-overhaul`가 점유. 본 슬라이스 머지 직전에 `origin/develop` HEAD와 인플라이트 마이그레이션 PR을 대조해 **충돌 없는 다음 슬롯**을 확정(`uniq -d` 사전 스캔). 문서상 잠정 `V34`로 표기하되 확정 아님.
- `playback` 인덱스는 인덱스 포함 승인 결정의 자연스러운 확장 — ③④가 같은 `partyroom_id` 접근이므로 동반 추가.

## 8. 테스트 전략

기존 IT 패턴(`UserActivityLogListener*IT`, `AdminPartyroomQueryServiceTest`, `*RepositoryImplIT`) 미러링.

- **② 집계**: activity_log 시드(입장 N·퇴장 M, 여러 날짜·유저) → totals·uniqueVisitors·일자버킷 검증. 윈도우 경계(`now-days` 포함/`now` 제외) 케이스.
- **③ 구간분류 (순수함수 단위테스트 우선; 입력 `long` epoch millis)**:
  - 음악 중 퇴장 / 무음 중 퇴장 / 트랙 없음(전부 무음) / 스킵 클램프(`rawEnd`).
  - **경계(S3)**: `exitMs == start` → 음악 중, `exitMs == end` → 무음(half-open).
  - **now 클램프(C2)**: 진행 중 트랙의 `end_time`이 윈도우 끝(now)보다 미래 → `endMs=now`, `totalSilenceMinutes` 음수 아님.
  - **straddle(S1)**: `from` 이전 시작해 `from` 이후까지 재생 중인 트랙 → 윈도우 초반 EXIT이 음악 중으로 분류.
  - **TZ(C1)**: `Asia/Seoul` 변환이 CI TZ와 무관하게 동일 결과(테스트 JVM TZ를 UTC로 강제해도 통과).
  - `totalExits=0` → ratio null.
- **④ 페이지네이션**: `created_at DESC` 순서, size 캡, 탈퇴 유저 닉네임 null-tolerant, 빈 페이지.
- **인증**: 비-admin 403(기존 `@adminAuth` 테스트 패턴).
- **부팅 게이트**: 신규 QueryDSL `@Query`/마이그레이션은 로컬 docker 풀부팅(fresh DB)로 검증(JPQL/Flyway 오류는 부팅에서만 잡힘).

## 9. 결정 사항 (스펙 리뷰에서 확정, 2026-07-09)

1. `daily` 0인 날 **안 채움**(발생한 날만) — YAGNI, 프론트 축 렌더.
2. `silenceExit.approximate: true` **노출** — 근사 지표 오독 방지, 저비용 정직성.
3. ④ 정렬 **`created_at DESC` 고정** — 화이트리스트 정렬은 후속 YAGNI.
4. 컨트롤러는 **기존 `AdminPartyroomQueryController` 확장**(같은 리소스 하위 경로라 응집적). `@adminAuth`·`ApiCommonResponse` 일관 유지. (서비스는 조회부하 분리 위해 `AdminPartyroomAnalyticsQueryService` 신규.)
5. `playback` 인덱스 **동반 추가**(§7, ③④ 동일 접근 경로).

## 10. 후속 슬라이스 (본 스펙 밖)
- 어드민 SPA 화면(카드·일자별 차트·이력 테이블·무음이탈 배지).
- ③ 정밀화 옵션: `PLAYBACK_ACTIVATED/DEACTIVATED` 전용 로깅(전용 테이블 or user_activity_log 핸들러 추가) — 소급 데이터 없음, 오늘부터 축적. 파킹된 *재생 세션 명시 FSM*·*아웃박스* 설계와 연결.
- 개별 입퇴장 raw 타임라인 드릴다운.
