# 어드민 파티룸 행동분석 백엔드 구현 플랜

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어드민이 특정 파티룸 상세에서 최근 N일 입장/퇴장 집계(②), "무음 이탈" 가설 지표(③), 디제잉 이력 페이지네이션(④)을 조회하는 플랫폼 백엔드 API를 추가한다.

**Architecture:** 기존 헥사고날 구조 확장. `administration` BC의 `AdminPartyroomQueryController`에 엔드포인트 2개(`/analytics`, `/dj-history`) 추가. ②는 administration 소유 `user_activity_log`를 QueryDSL로 집계, ③④의 `playback`은 `PartyroomAggregatePort` 경유(ArchUnit 경계 유지). ③의 무음구간 연산은 `Asia/Seoul` epoch-millis 기반 순수함수로 분리해 단위테스트.

**Tech Stack:** Spring Boot, JPA/Hibernate, QueryDSL, MySQL, Flyway, JUnit5 + AssertJ, Spring Security(`@PreAuthorize`).

**Spec:** `docs/superpowers/specs/2026-07-09-admin-partyroom-behavior-analytics-design.md`

**빌드/실행 노트(reference):**
- JDK 21 빌드: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix 필수.
- 단위/IT: `./gradlew :app:test --tests '<FQCN>'`
- 마이그레이션/JPQL 오류는 **로컬 docker 풀부팅(fresh DB)** 에서만 잡히므로, Task 1·9 후 부팅 게이트 수행.

**Flyway 슬롯:** 본 플랜은 잠정 `V34`로 표기한다. **머지 직전** `origin/develop` HEAD 및 인플라이트 마이그레이션 PR과 대조해 충돌 없는 다음 슬롯으로 재번호(V32/V33은 미머지 virtualcrew 브랜치 점유). `ls db/migration | sort | uniq -d` 사전 스캔.

---

## 파일 구조 (생성/수정 맵)

**생성:**
- `app/src/main/resources/db/migration/V34__admin_analytics_indexes.sql` — 인덱스 마이그레이션
- `.../administration/application/service/AdminPartyroomAnalyticsQueryService.java` — ②③④ 조합 read-only 서비스
- `.../administration/application/analytics/SilenceExitCalculator.java` — ③ 순수함수(구간병합·분류)
- `.../administration/application/analytics/PlaybackInterval.java` — ③ 내부 값(레코드)
- `.../administration/adapter/out/persistence/UserActivityLogAnalyticsRepository.java` — ② QueryDSL 집계 리포지토리
- `.../administration/application/dto/AttendanceAnalytics.java` — ② 집계 DTO(레코드)
- `.../administration/application/dto/DailyAttendanceBucket.java` — 일자 버킷(레코드)
- `.../administration/adapter/in/web/payload/response/PartyroomAnalyticsResponse.java` — `/analytics` 응답(레코드)
- `.../administration/adapter/in/web/payload/response/AdminDjHistoryItemResponse.java` — `/dj-history` 아이템(레코드)
- 테스트: `SilenceExitCalculatorTest`, `UserActivityLogAnalyticsRepositoryImplIT`, `AdminPartyroomAnalyticsQueryServiceIT`, `AdminPartyroomAnalyticsControllerTest`

**수정:**
- `.../party/domain/entity/data/PlaybackData.java` — `@Index`를 복합으로 교체
- `.../party/domain/port/PartyroomAggregatePort.java` — 메서드 2개 추가
- `.../party/adapter/out/persistence/PartyroomAggregateAdapter.java` — 위 구현 위임
- `.../party/adapter/out/persistence/custom/PartyroomRepositoryCustom.java` — 메서드 2개 추가
- `.../party/adapter/out/persistence/impl/PartyroomRepositoryImpl.java` — QueryDSL 구현
- `.../administration/adapter/in/web/AdminPartyroomQueryController.java` — 엔드포인트 2개 추가

---

## Chunk 1: 인덱스 마이그레이션 + 엔티티 정합

### Task 1: Flyway 인덱스 마이그레이션 + PlaybackData @Index 교체

**Files:**
- Create: `app/src/main/resources/db/migration/V34__admin_analytics_indexes.sql`
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/PlaybackData.java` (@Table indexes)

- [ ] **Step 1: 마이그레이션 작성**

`V34__admin_analytics_indexes.sql`:
```sql
-- =====================================================
-- V34: 어드민 파티룸 행동분석 — 범위/정렬 인덱스
-- Spec: docs/superpowers/specs/2026-07-09-admin-partyroom-behavior-analytics-design.md §7
--
-- user_activity_log: partyroom_id 인덱스 신규(기존 부재).
--   ②는 event_type IN (ENTERED,EXITED)+GROUP BY date, ③은 event_type=EXITED.
--   등가 컬럼(partyroom_id, event_type)을 앞, 범위/정렬(occurred_at)을 뒤에 둔다.
-- playback: 기존 단일컬럼 playback_partyroom_id_IDX(V1)를 복합으로 대체
--   (신규가 완전 상위집합 → 중복 제거).
-- =====================================================

ALTER TABLE user_activity_log
    ADD INDEX idx_ual_partyroom_event_time (partyroom_id, event_type, occurred_at DESC);

ALTER TABLE playback
    ADD INDEX idx_playback_partyroom_time (partyroom_id, created_at DESC);

DROP INDEX playback_partyroom_id_IDX ON playback;
```

- [ ] **Step 2: 엔티티 @Index 교체**

`PlaybackData.java`의 `@Table` 애노테이션 인덱스를 신규 복합으로 교체. (주의: Hibernate `validate`는 테이블/컬럼/타입만 검증하고 **secondary index는 검증하지 않으므로** 미교체가 부팅을 깨진 않는다. 그러나 `create-drop` 테스트 컨텍스트의 생성 DDL 정합을 위해 애노테이션을 마이그레이션과 동기화하는 것이 위생 — 반드시 함께 교체한다.):
```java
@Table(
        name = "PLAYBACK",
        indexes = {
                @Index(name = "idx_playback_partyroom_time", columnList = "partyroom_id, created_at")
        }
)
```

- [ ] **Step 3: 로컬 docker 풀부팅(fresh DB)으로 마이그레이션·validate 검증**

Run(reference `reference_local_docker_compose`): fresh volume로 컨테이너 기동 → Flyway V34 적용 + Hibernate `validate` 통과 확인.
Expected: 부팅 성공, `Successfully applied ... V34`, validate 에러 없음.
> 부팅 게이트 전 `./gradlew :app:bootJar` 필수(Dockerfile은 호스트 빌드 jar COPY — stale jar 방지, `reference_local_docker_boot_stale_host_jar`).

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/resources/db/migration/V34__admin_analytics_indexes.sql \
        app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/PlaybackData.java
git commit -m "feat: 어드민 행동분석용 인덱스 추가 (user_activity_log 신규 + playback 복합 대체)"
```

---

## Chunk 2: Playback 포트 확장 (③④ 데이터 접근)

### Task 2: `findPlaybackForInterval` — ③용 구간 재구성 소스

윈도우 내 트랙 전부 + `created_at < from` 최신 1건(straddle). 정렬 `created_at ASC`.

**Files:**
- Modify: `.../party/adapter/out/persistence/custom/PartyroomRepositoryCustom.java`
- Modify: `.../party/adapter/out/persistence/impl/PartyroomRepositoryImpl.java`
- Modify: `.../party/domain/port/PartyroomAggregatePort.java`
- Modify: `.../party/adapter/out/persistence/PartyroomAggregateAdapter.java`
- Test: `.../party/adapter/out/persistence/impl/PartyroomRepositoryPlaybackIntervalIT.java` (신규)

- [ ] **Step 1: 실패 테스트 작성**

`PartyroomRepositoryPlaybackIntervalIT`(기존 `@DataJpaTest`/`*IT` 패턴 따름):
```java
// 시나리오: 룸에 트랙 3개 — t0(from 이전 시작, straddle), t1·t2(윈도우 내), 다른 룸 t_other
// from = base, now = base+2h
@Test
void 윈도우_내_트랙과_straddle_1건을_created_at_asc로_반환() {
    // given: playback rows 저장 (partyroom=1: created_at base-10m, base+10m, base+30m; partyroom=2: base+5m)
    // when
    List<PlaybackData> result = repository.findPlaybackForInterval(
            new PartyroomId(1L), base, base.plusHours(2));
    // then: 3건(straddle 1 + in-window 2), created_at asc, 다른 룸 제외
    assertThat(result).extracting(p -> p.getCreatedAt())
            .containsExactly(base.minusMinutes(10), base.plusMinutes(10), base.plusMinutes(30));
}

@Test
void straddle_후보가_여러개면_from_직전_최신_1건만() {
    // given: partyroom=1 created_at base-30m, base-10m (둘 다 from 이전)
    // when/then: base-10m 만 포함(base-30m 제외)
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :app:test --tests '*PartyroomRepositoryPlaybackIntervalIT'`
Expected: FAIL — `findPlaybackForInterval` 메서드 없음(컴파일 에러).

- [ ] **Step 3: 인터페이스 시그니처 추가**

`PartyroomRepositoryCustom.java`:
```java
List<PlaybackData> findPlaybackForInterval(PartyroomId partyroomId, LocalDateTime from, LocalDateTime now);
```
`PartyroomAggregatePort.java` 동일 시그니처 추가.

- [ ] **Step 4: QueryDSL 구현**

`PartyroomRepositoryImpl.java` — 윈도우 내 조회 + straddle 1건 조회 후 병합:
```java
@Override
public List<PlaybackData> findPlaybackForInterval(PartyroomId partyroomId, LocalDateTime from, LocalDateTime now) {
    QPlaybackData q = QPlaybackData.playbackData;

    // 윈도우 내: created_at ∈ [from, now)
    List<PlaybackData> inWindow = queryFactory
            .select(q).from(q)
            .where(q.partyroomId.id.eq(partyroomId.getId())
                    .and(q.createdAt.goe(from))
                    .and(q.createdAt.lt(now)))
            .orderBy(q.createdAt.asc())
            .fetch();

    // straddle: created_at < from 중 최신 1건 (윈도우 시작 시점에 재생 중이었을 수 있음)
    PlaybackData straddle = queryFactory
            .select(q).from(q)
            .where(q.partyroomId.id.eq(partyroomId.getId())
                    .and(q.createdAt.lt(from)))
            .orderBy(q.createdAt.desc())
            .fetchFirst();

    if (straddle == null) {
        return inWindow;
    }
    List<PlaybackData> result = new ArrayList<>(inWindow.size() + 1);
    result.add(straddle);          // 가장 이른 시작 → asc 정렬 선두
    result.addAll(inWindow);
    return result;
}
```
`PartyroomAggregateAdapter.java`: `return partyroomRepository.findPlaybackForInterval(partyroomId, from, now);` 위임.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :app:test --tests '*PartyroomRepositoryPlaybackIntervalIT'`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/party/...
git commit -m "feat: playback 구간 재구성 조회(findPlaybackForInterval) 추가"
```

### Task 3: `findPlaybackHistory` — ④용 페이지네이션

**Files:** Task 2와 동일 4개 파일 + Test: `PartyroomRepositoryPlaybackHistoryIT`

- [ ] **Step 1: 실패 테스트**

```java
@Test
void created_at_desc_페이지네이션_반환() {
    // given: partyroom=1 트랙 5건(서로 다른 created_at)
    Page<PlaybackData> page = repository.findPlaybackHistory(new PartyroomId(1L), PageRequest.of(0, 2));
    assertThat(page.getTotalElements()).isEqualTo(5);
    assertThat(page.getContent()).hasSize(2);
    // created_at 내림차순 검증
    assertThat(page.getContent().get(0).getCreatedAt())
            .isAfterOrEqualTo(page.getContent().get(1).getCreatedAt());
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests '*PartyroomRepositoryPlaybackHistoryIT'` → FAIL(메서드 없음).

- [ ] **Step 3: 시그니처 추가**

`PartyroomRepositoryCustom`/`PartyroomAggregatePort`:
```java
Page<PlaybackData> findPlaybackHistory(PartyroomId partyroomId, Pageable pageable);
```

- [ ] **Step 4: QueryDSL 구현**

`PartyroomRepositoryImpl`:
```java
@Override
public Page<PlaybackData> findPlaybackHistory(PartyroomId partyroomId, Pageable pageable) {
    QPlaybackData q = QPlaybackData.playbackData;

    List<PlaybackData> content = queryFactory
            .select(q).from(q)
            .where(q.partyroomId.id.eq(partyroomId.getId()))
            .orderBy(q.createdAt.desc())            // 정렬 고정(§9)
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

    Long total = queryFactory
            .select(q.count()).from(q)
            .where(q.partyroomId.id.eq(partyroomId.getId()))
            .fetchOne();

    return new PageImpl<>(content, pageable, total == null ? 0L : total);
}
```
Adapter 위임 추가.

- [ ] **Step 5: 통과 확인** — Run 위 테스트 → PASS.

- [ ] **Step 6: 커밋**

```bash
git commit -am "feat: playback 이력 페이지네이션 조회(findPlaybackHistory) 추가"
```

---

## Chunk 3: ② 입퇴장 집계 리포지토리

### Task 4: UserActivityLogAnalyticsRepository (QueryDSL)

administration BC 소유 `user_activity_log`를 파티룸+윈도우로 집계. 세 가지 산출: 일자 버킷(entered/exited), uniqueVisitors(ENTERED distinct), EXIT occurred_at 리스트(③ 입력).

**Files:**
- Create: `.../administration/adapter/out/persistence/UserActivityLogAnalyticsRepository.java`
- Test: `.../administration/adapter/out/persistence/impl/UserActivityLogAnalyticsRepositoryImplIT.java`

> 구현은 QueryDSL이므로 클래스명 관례상 `...Repository`(인터페이스)+`...RepositoryImpl` 또는 단일 `@Repository` 클래스. 기존 `AdminPartyroomQueryRepositoryImpl`이 단일 `@Repository` 클래스 패턴이므로 **단일 `@Repository` 클래스**로 작성(인터페이스 불필요).

- [ ] **Step 1: 실패 테스트 작성**

`UserActivityLogAnalyticsRepositoryImplIT`:
```java
// given: partyroom=1 에 대해
//   day D-1: ENTERED x3 (user 10,11,10), EXITED x2
//   day D-0: ENTERED x1 (user 12), EXITED x1
//   다른 룸/다른 event_type row 다수(격리 검증용)
// window = [D-1 00:00, now)
@Test
void 일자버킷_totals_uniqueVisitors() {
    List<DailyAttendanceBucket> daily = repo.findDailyAttendance(1L, from, now);
    assertThat(daily).extracting(DailyAttendanceBucket::date)
            .containsExactly(dMinus1, dZero);            // KST 달력일 asc
    assertThat(daily.get(0).entered()).isEqualTo(3);
    assertThat(daily.get(0).exited()).isEqualTo(2);

    long unique = repo.countUniqueVisitors(1L, from, now); // ENTERED distinct user
    assertThat(unique).isEqualTo(3);                        // user 10,11,12
}

@Test
void EXIT_occurredAt_리스트_윈도우_내_only() {
    List<LocalDateTime> exits = repo.findExitOccurredAt(1L, from, now);
    assertThat(exits).hasSize(3);   // D-1 2건 + D-0 1건
}
```

- [ ] **Step 2: 실패 확인** — Run → FAIL(클래스 없음).

- [ ] **Step 3: 리포지토리 구현**

```java
@Repository
@RequiredArgsConstructor
public class UserActivityLogAnalyticsRepository {

    private static final String ENTERED = UserActivityEventType.PARTYROOM_ENTERED.name();
    private static final String EXITED  = UserActivityEventType.PARTYROOM_EXITED.name();

    private final JPAQueryFactory queryFactory;

    /** 일자별 entered/exited 카운트. date = KST 달력일(occurred_at 벽시계). 발생한 날만, asc. */
    public List<DailyAttendanceBucket> findDailyAttendance(Long partyroomId, LocalDateTime from, LocalDateTime now) {
        QUserActivityLogData q = QUserActivityLogData.userActivityLogData;
        DateTemplate<java.sql.Date> day = Expressions.dateTemplate(
                java.sql.Date.class, "DATE({0})", q.occurredAt);

        // entered/exited 를 조건부 합으로 한 번에 집계
        NumberExpression<Integer> enteredCase =
                new CaseBuilder().when(q.eventType.eq(ENTERED)).then(1).otherwise(0).sum();
        NumberExpression<Integer> exitedCase =
                new CaseBuilder().when(q.eventType.eq(EXITED)).then(1).otherwise(0).sum();

        return queryFactory
                .select(day, enteredCase, exitedCase)
                .from(q)
                .where(q.partyroomId.eq(partyroomId)
                        .and(q.eventType.in(ENTERED, EXITED))
                        .and(q.occurredAt.goe(from))
                        .and(q.occurredAt.lt(now)))
                .groupBy(day)
                .orderBy(day.asc())
                .fetch()
                .stream()
                .map(t -> new DailyAttendanceBucket(
                        t.get(day).toLocalDate(),
                        nz(t.get(enteredCase)),
                        nz(t.get(exitedCase))))
                .toList();
    }

    public long countUniqueVisitors(Long partyroomId, LocalDateTime from, LocalDateTime now) {
        QUserActivityLogData q = QUserActivityLogData.userActivityLogData;
        Long c = queryFactory
                .select(q.userAccountId.countDistinct())
                .from(q)
                .where(q.partyroomId.eq(partyroomId)
                        .and(q.eventType.eq(ENTERED))
                        .and(q.occurredAt.goe(from))
                        .and(q.occurredAt.lt(now)))
                .fetchOne();
        return c == null ? 0L : c;
    }

    public List<LocalDateTime> findExitOccurredAt(Long partyroomId, LocalDateTime from, LocalDateTime now) {
        QUserActivityLogData q = QUserActivityLogData.userActivityLogData;
        return queryFactory
                .select(q.occurredAt)
                .from(q)
                .where(q.partyroomId.eq(partyroomId)
                        .and(q.eventType.eq(EXITED))
                        .and(q.occurredAt.goe(from))
                        .and(q.occurredAt.lt(now)))
                .fetch();
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }
}
```
> `DateTemplate`/`CaseBuilder`/`Expressions`는 `com.querydsl.core.types.dsl.*`. `DATE()`는 MySQL 함수, occurred_at 벽시계 그대로 → KST 달력일.

- [ ] **Step 4: 통과 확인** — Run → PASS.

- [ ] **Step 5: 커밋**

```bash
git commit -am "feat: user_activity_log 파티룸 입퇴장 집계 리포지토리"
```

---

## Chunk 4: ③ 무음 이탈 순수함수

### Task 5: SilenceExitCalculator (구간병합 + 분류)

`Asia/Seoul` epoch-millis 기반. 입력이 전부 `long`이라 DB 없이 단위테스트.

**Files:**
- Create: `.../administration/application/analytics/PlaybackInterval.java`
- Create: `.../administration/application/analytics/SilenceExitCalculator.java`
- Test: `.../administration/application/analytics/SilenceExitCalculatorTest.java`

- [ ] **Step 1: 값 타입 + 실패 테스트**

`PlaybackInterval.java`:
```java
/** playback 트랙의 활성 구간 재구성용 입력. 모든 시각 epoch millis(KST 변환 완료). */
public record PlaybackInterval(long createdAtMs, long endTimeMs) {}
```

`SilenceExitCalculatorTest.java` — 순수 로직 테스트(JVM TZ 무관 검증 위해 epoch 직접):
```java
class SilenceExitCalculatorTest {
    // 편의: 분 단위 → ms
    static long m(long min) { return min * 60_000L; }

    @Test
    void 음악_중_퇴장은_무음카운트_제외() {
        // 활성구간 [0, 10m). 퇴장 @5m → 음악 중.
        var r = SilenceExitCalculator.compute(
                List.of(new PlaybackInterval(0, m(10))),
                List.of(m(5)), 0, m(20));
        assertThat(r.exitsDuringSilence()).isEqualTo(0);
        assertThat(r.totalExits()).isEqualTo(1);
    }

    @Test
    void 무음_중_퇴장은_카운트() {
        // 활성구간 [0,10m). 퇴장 @15m → 무음.
        var r = SilenceExitCalculator.compute(
                List.of(new PlaybackInterval(0, m(10))),
                List.of(m(15)), 0, m(20));
        assertThat(r.exitsDuringSilence()).isEqualTo(1);
    }

    @Test
    void 경계_half_open_start는_음악_end는_무음() {
        var intervals = List.of(new PlaybackInterval(m(5), m(10)));
        var r = SilenceExitCalculator.compute(intervals, List.of(m(5), m(10)), 0, m(20));
        // @5m(start)=음악, @10m(end)=무음  → 무음 1
        assertThat(r.exitsDuringSilence()).isEqualTo(1);
    }

    @Test
    void 스킵_클램프_다음트랙_시작으로_종료() {
        // t0 [0, endTime=30m] 이지만 t1 이 10m 에 시작 → t0 은 [0,10m) 로 클램프
        var intervals = List.of(
                new PlaybackInterval(0, m(30)),
                new PlaybackInterval(m(10), m(40)));
        // 퇴장 @35m: t0 클램프로 [0,10m), t1 [10,35m)(now=35 클램프)... now=35m
        var r = SilenceExitCalculator.compute(intervals, List.of(m(35)), 0, m(35));
        // @35m == now == t1 end(클램프) → half-open 무음
        assertThat(r.exitsDuringSilence()).isEqualTo(1);
    }

    @Test
    void now_클램프로_totalSilence_음수불가() {
        // 진행중 트랙 endTime 이 now(20m) 보다 미래(30m)
        var r = SilenceExitCalculator.compute(
                List.of(new PlaybackInterval(0, m(30))), List.of(), 0, m(20));
        assertThat(r.totalSilenceMinutes()).isEqualTo(0);   // [0,20m) 전부 음악
    }

    @Test
    void straddle_start_클램프_from으로() {
        // from=10m, 트랙 [0, 40m] → [10m,40m→now=30m 클램프)=[10,30)
        var r = SilenceExitCalculator.compute(
                List.of(new PlaybackInterval(0, m(40))), List.of(m(12)), m(10), m(30));
        assertThat(r.exitsDuringSilence()).isEqualTo(0);    // @12m 음악 중
        assertThat(r.totalSilenceMinutes()).isEqualTo(0);
    }

    @Test
    void 퇴장_없으면_ratio_null() {
        var r = SilenceExitCalculator.compute(List.of(), List.of(), 0, m(20));
        assertThat(r.totalExits()).isEqualTo(0);
        assertThat(r.silenceExitRatio()).isNull();
        assertThat(r.totalSilenceMinutes()).isEqualTo(20);  // 전부 무음
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :app:test --tests '*SilenceExitCalculatorTest'` → FAIL(클래스 없음).

- [ ] **Step 3: 계산기 구현**

`SilenceExitCalculator.java`:
```java
public final class SilenceExitCalculator {

    private SilenceExitCalculator() {}

    /** 결과. ratio 는 totalExits==0 이면 null. */
    public record Result(long totalExits, long exitsDuringSilence,
                         Double silenceExitRatio, long totalSilenceMinutes) {}

    /**
     * @param rawIntervals playback 트랙 구간(createdAtMs asc 가정). endTime 은 예정 종료(미래 가능).
     * @param exitMsList   EXIT occurred_at(ms) 리스트.
     * @param fromMs/nowMs 윈도우 경계(ms).
     */
    public static Result compute(List<PlaybackInterval> rawIntervals,
                                 List<Long> exitMsList, long fromMs, long nowMs) {
        // 1) 클램프하여 [start,end) 정규화
        List<long[]> clamped = new ArrayList<>();
        for (int i = 0; i < rawIntervals.size(); i++) {
            PlaybackInterval it = rawIntervals.get(i);
            long start = Math.max(it.createdAtMs(), fromMs);          // straddle → from 클램프
            long rawEnd = it.endTimeMs();
            if (i + 1 < rawIntervals.size()) {                        // 스킵 클램프: 다음 트랙 시작
                rawEnd = Math.min(rawEnd, rawIntervals.get(i + 1).createdAtMs());
            }
            long end = Math.min(rawEnd, nowMs);                       // now 클램프(C2)
            if (start < end) {
                clamped.add(new long[]{start, end});
            }
        }
        // 2) 병합(정렬 후 인접/겹침)
        clamped.sort(Comparator.comparingLong(a -> a[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] iv : clamped) {
            if (!merged.isEmpty() && iv[0] <= merged.get(merged.size() - 1)[1]) {
                long[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], iv[1]);
            } else {
                merged.add(new long[]{iv[0], iv[1]});
            }
        }
        // 3) 퇴장 분류 — half-open [start,end)
        long silence = 0;
        for (long exit : exitMsList) {
            if (!coveredByMusic(merged, exit)) {
                silence++;
            }
        }
        long totalExits = exitMsList.size();
        Double ratio = totalExits == 0 ? null
                : BigDecimal.valueOf((double) silence / totalExits)
                        .setScale(2, RoundingMode.HALF_UP).doubleValue();
        // 4) 무음 시간 = 윈도우 − 음악합집합 (union ⊆ 윈도우 보장 → 음수 불가)
        long musicMs = 0;
        for (long[] iv : merged) musicMs += (iv[1] - iv[0]);
        long silenceMs = (nowMs - fromMs) - musicMs;
        long silenceMinutes = Math.round(silenceMs / 60_000.0);
        return new Result(totalExits, silence, ratio, silenceMinutes);
    }

    /** merged(정렬 disjoint)에서 exit 이 [start,end) 에 포함되는지 이진탐색. */
    private static boolean coveredByMusic(List<long[]> merged, long exit) {
        int lo = 0, hi = merged.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long[] iv = merged.get(mid);
            if (exit < iv[0]) hi = mid - 1;
            else if (exit >= iv[1]) lo = mid + 1;   // half-open: end 는 미포함
            else return true;                        // start <= exit < end
        }
        return false;
    }
}
```
> import: `java.util.*`, `java.math.BigDecimal`, `java.math.RoundingMode`.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :app:test --tests '*SilenceExitCalculatorTest'`
Expected: PASS(7 케이스). 필요 시 테스트 JVM에 `-Duser.timezone=UTC` 부여해도 동일 통과(순수 epoch 로직이라 TZ 무관).

- [ ] **Step 5: 커밋**

```bash
git commit -am "feat: 무음 이탈 계산기(SilenceExitCalculator) + 단위테스트"
```

---

## Chunk 5: DTO + 조합 서비스

### Task 6: 응답/집계 DTO 레코드

**Files (모두 Create):**
- `.../administration/application/dto/AttendanceAnalytics.java`
- `.../administration/application/dto/DailyAttendanceBucket.java`
- `.../administration/adapter/in/web/payload/response/PartyroomAnalyticsResponse.java`
- `.../administration/adapter/in/web/payload/response/AdminDjHistoryItemResponse.java`

- [ ] **Step 1: 레코드 작성**(테스트 불필요 — 순수 데이터 홀더, 서비스 IT에서 커버)

```java
// DailyAttendanceBucket.java
public record DailyAttendanceBucket(LocalDate date, int entered, int exited) {}

// AttendanceAnalytics.java
public record AttendanceAnalytics(long totalEntered, long totalExited,
                                  long uniqueVisitors, List<DailyAttendanceBucket> daily) {}

// PartyroomAnalyticsResponse.java  (/analytics 응답)
public record PartyroomAnalyticsResponse(int windowDays,
                                         AttendanceAnalytics attendance,
                                         SilenceExit silenceExit) {
    public record SilenceExit(boolean approximate, long totalExits,
                              long exitsDuringSilence, Double silenceExitRatio,
                              long totalSilenceMinutes) {}
}

// AdminDjHistoryItemResponse.java  (/dj-history 아이템)
public record AdminDjHistoryItemResponse(Long playbackId, String trackName,
                                         Long djUserAccountId, String djNickname,
                                         String avatarIconUri, String thumbnailImage,
                                         LocalDateTime playedAt) {}
```
> 스펙 §6은 `SilenceExitAnalytics`를 별도 DTO로 명명했으나, 본 플랜은 응답 응집을 위해 `PartyroomAnalyticsResponse.SilenceExit` 중첩 레코드로 둔다(기능 동일, 의도된 divergence). `avatarIconUri`는 미설정 DJ의 경우 `""`(빈 문자열).

- [ ] **Step 2: 컴파일 확인** — Run: `./gradlew :app:compileJava` → 성공.

- [ ] **Step 3: 커밋**

```bash
git commit -am "feat: 행동분석 응답/집계 DTO 레코드"
```

### Task 7: AdminPartyroomAnalyticsQueryService

②③④ 조합. 진입 시 파티룸 존재검증(404). ④는 playback → 닉네임/아바타 enrichment(기존 detail 전략 재사용).

**Files:**
- Create: `.../administration/application/service/AdminPartyroomAnalyticsQueryService.java`
- Test: `.../administration/application/service/AdminPartyroomAnalyticsQueryServiceIT.java`

- [ ] **Step 1: 실패 테스트(IT — 풀 컨텍스트, 시드 후 검증)**

```java
@Test
void analytics_없는_방이면_NOT_FOUND_ROOM() {
    assertThatThrownBy(() -> service.getAnalytics(new PartyroomId(999L), 20))
            .isInstanceOf(PartyroomException.class); // NOT_FOUND_ROOM
}

@Test
void analytics_days_범위밖이면_예외() {
    assertThatThrownBy(() -> service.getAnalytics(existingRoomId, 0))
            .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.getAnalytics(existingRoomId, 91))
            .isInstanceOf(BadRequestException.class);
}

@Test
void analytics_집계와_무음지표_조합() {
    // given: 방에 ENTERED/EXITED 로그 + playback 트랙 시드
    PartyroomAnalyticsResponse r = service.getAnalytics(existingRoomId, 20);
    assertThat(r.attendance().totalEntered()).isEqualTo(...);
    assertThat(r.silenceExit().approximate()).isTrue();
    assertThat(r.silenceExit().totalExits())
            .isEqualTo(r.attendance().totalExited());   // 불변식(§4.1)
}

@Test
void djHistory_created_at_desc_페이지() {
    Page<AdminDjHistoryItemResponse> page = service.getDjHistory(existingRoomId, PageRequest.of(0, 2));
    assertThat(page.getContent()).isSortedAccordingTo(
            Comparator.comparing(AdminDjHistoryItemResponse::playedAt).reversed());
}
```

- [ ] **Step 2: 실패 확인** — Run → FAIL(서비스 없음).

- [ ] **Step 3: 서비스 구현**

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPartyroomAnalyticsQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;

    private final PartyroomAggregatePort aggregatePort;
    private final UserActivityLogAnalyticsRepository activityRepo;
    private final MemberRepository memberRepository;

    public PartyroomAnalyticsResponse getAnalytics(PartyroomId partyroomId, int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new BadRequestException("ADM-PR-002", "days out of range (1..90): " + days);
        }
        requireRoom(partyroomId);                                   // 404 게이트(S2)

        LocalDateTime now = LocalDateTime.now(SEOUL);
        LocalDateTime from = now.minusDays(days);
        long pid = partyroomId.getId();

        // ② attendance
        List<DailyAttendanceBucket> daily = activityRepo.findDailyAttendance(pid, from, now);
        long totalEntered = daily.stream().mapToLong(DailyAttendanceBucket::entered).sum();
        long totalExited  = daily.stream().mapToLong(DailyAttendanceBucket::exited).sum();
        long unique = activityRepo.countUniqueVisitors(pid, from, now);
        AttendanceAnalytics attendance =
                new AttendanceAnalytics(totalEntered, totalExited, unique, daily);

        // ③ silence-exit
        long fromMs = ms(from), nowMs = ms(now);
        List<PlaybackInterval> intervals = aggregatePort
                .findPlaybackForInterval(partyroomId, from, now).stream()
                .filter(p -> p.getEndTime() != null)   // endTime 은 Long(nullable) — 언박싱 NPE 방어(레거시/엣지 row)
                .map(p -> new PlaybackInterval(ms(p.getCreatedAt()), p.getEndTime()))
                .toList();
        List<Long> exits = activityRepo.findExitOccurredAt(pid, from, now).stream()
                .map(this::ms).toList();
        SilenceExitCalculator.Result s =
                SilenceExitCalculator.compute(intervals, exits, fromMs, nowMs);
        var silence = new PartyroomAnalyticsResponse.SilenceExit(
                true, s.totalExits(), s.exitsDuringSilence(),
                s.silenceExitRatio(), s.totalSilenceMinutes());

        return new PartyroomAnalyticsResponse(days, attendance, silence);
    }

    public Page<AdminDjHistoryItemResponse> getDjHistory(PartyroomId partyroomId, Pageable pageable) {
        requireRoom(partyroomId);
        Page<PlaybackData> page = aggregatePort.findPlaybackHistory(partyroomId, pageable);
        // 닉네임/아바타 enrichment (detail() 전략 재사용). 빈 페이지면 findAllByUserAccountIdIn([]) 도 안전 → 별도 분기 불필요.
        List<Long> userIds = page.getContent().stream()
                .map(p -> p.getUserId().getUid()).distinct().toList();
        Map<Long, MemberData> byId = userIds.isEmpty() ? Map.of()
                : memberRepository.findAllByUserAccountIdIn(userIds).stream()
                        .collect(Collectors.toMap(MemberData::getUserAccountId, m -> m, (a, b) -> a));
        return page.map(p -> {
            MemberData m = byId.get(p.getUserId().getUid());
            return new AdminDjHistoryItemResponse(
                    p.getId(), p.getName(), p.getUserId().getUid(),
                    nickname(m), avatarUri(m), p.getThumbnailImage(), p.getCreatedAt());
        });
    }

    private void requireRoom(PartyroomId id) {
        aggregatePort.findPartyroomById(id.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
    }
    private long ms(LocalDateTime t) { return t.atZone(SEOUL).toInstant().toEpochMilli(); }
    private static String nickname(MemberData m) {
        return (m == null || m.getProfileData() == null) ? null : m.getProfileData().getNicknameValue();
    }
    private static String avatarUri(MemberData m) {
        if (m == null || m.getProfileData() == null || m.getProfileData().getAvatarSetting() == null) {
            return null;
        }
        return m.getProfileData().getAvatarSetting().getAvatarIconUriValue();  // 미설정 시 "" 반환(null 아님)
    }
}
```
> 시그니처 확인됨: 아바타 URI = `profileData.getAvatarSetting().getAvatarIconUriValue()` (`AvatarSetting.java` — 미설정 시 빈 문자열 반환). `PlaybackData.getUserId()`는 `UserId` VO → `.getUid()`. `getCreatedAt()`은 `BaseEntity` 제공.
> `BadRequestException`은 `com.pfplaybackend.api.common.exception.http.BadRequestException`(컨트롤러에서 이미 사용). `ExceptionCreator`/`PartyroomException.NOT_FOUND_ROOM`은 detail()과 동일.

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :app:test --tests '*AdminPartyroomAnalyticsQueryServiceIT'` → PASS.

- [ ] **Step 5: 커밋**

```bash
git commit -am "feat: 파티룸 행동분석 조합 서비스(analytics/dj-history)"
```

---

## Chunk 6: 컨트롤러 엔드포인트 + 회귀 게이트

### Task 8: AdminPartyroomQueryController 엔드포인트 2개

**Files:**
- Modify: `.../administration/adapter/in/web/AdminPartyroomQueryController.java`
- Test: `.../administration/adapter/in/web/AdminPartyroomAnalyticsControllerTest.java`

- [ ] **Step 1: 실패 테스트(MockMvc — 기존 컨트롤러 테스트 패턴)**

```java
@Test
void analytics_비어드민_403() { /* @adminAuth false → 403 */ }

@Test
void analytics_200_스키마() {
    // service mock → JSON 경로 검증: $.data.attendance, $.data.silenceExit.approximate=true
}

@Test
void djHistory_size_200_초과_clamp() {
    // size=500 요청 → service 에 size=200 으로 전달됐는지(Pageable captor)
}

@Test
void analytics_없는방_404() { /* service 가 NOT_FOUND_ROOM → GlobalExceptionHandler 404 */ }

@Test
void analytics_days_범위밖_400() {
    // service mock 이 BadRequestException("ADM-PR-002") 던지도록 stub → HTTP 400 계약 고정(스펙 §4.1)
    // GET .../analytics?days=0 → status().isBadRequest()
    // GET .../analytics?days=91 → status().isBadRequest()
}
```
> days 범위 400은 서비스가 던지므로 컨트롤러 테스트는 service mock 이 `BadRequestException` 던질 때 **HTTP 400 상태코드**가 나오는지만 검증(경계값 실제 판정은 Task 7 서비스 IT가 담당). 이로써 스펙 §4.1 "400 reject" 계약이 상태코드 레벨에서 잠긴다.

- [ ] **Step 2: 실패 확인** — Run → FAIL(엔드포인트 없음).

- [ ] **Step 3: 엔드포인트 추가**

`AdminPartyroomQueryController`에 필드 주입 추가(`private final AdminPartyroomAnalyticsQueryService analyticsService;`) 후:
```java
@Operation(summary = "B-3 룸 행동분석 (입퇴장 집계 + 무음이탈 근사)")
@PreAuthorize("@adminAuth.isAdmin()")
@GetMapping("/{partyroomId}/analytics")
public ResponseEntity<ApiCommonResponse<PartyroomAnalyticsResponse>> analytics(
        @PathVariable Long partyroomId,
        @RequestParam(defaultValue = "20") int days) {
    return ResponseEntity.ok(ApiCommonResponse.success(
            analyticsService.getAnalytics(new PartyroomId(partyroomId), days)));
}

@Operation(summary = "B-4 룸 디제잉 이력 (페이지네이션)")
@PreAuthorize("@adminAuth.isAdmin()")
@GetMapping("/{partyroomId}/dj-history")
public ResponseEntity<ApiCommonResponse<Page<AdminDjHistoryItemResponse>>> djHistory(
        @PathVariable Long partyroomId,
        @PageableDefault(size = 20) Pageable pageable) {
    // size 만 200 캡(기존 list 패턴). 정렬은 리포지토리가 created_at DESC 로 고정(§9)하므로 Sort 는 전달 무의미.
    Pageable bounded = pageable.getPageSize() > MAX_PAGE_SIZE
            ? PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE)
            : pageable;
    return ResponseEntity.ok(ApiCommonResponse.success(
            analyticsService.getDjHistory(new PartyroomId(partyroomId), bounded)));
}
```
> `MAX_PAGE_SIZE=200` 상수는 컨트롤러에 이미 존재(재사용). days 범위검증은 서비스가 `BadRequestException`(400) 던짐 → 기존 매핑.

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :app:test --tests '*AdminPartyroomAnalyticsControllerTest'` → PASS.

- [ ] **Step 5: 커밋**

```bash
git commit -am "feat: 어드민 파티룸 analytics/dj-history 엔드포인트"
```

### Task 9: 전체 회귀 + 부팅 게이트 + ArchUnit

- [ ] **Step 1: 모듈 전체 테스트**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test`
Expected: 신규 포함 전체 GREEN. 특히 `CrossContextDependencyTest`/ArchUnit(administration↔party 경계) 통과 — playback 접근이 `PartyroomAggregatePort` 경유인지 확인.

- [ ] **Step 2: 로컬 docker 풀부팅(fresh DB) 게이트**

`./gradlew :app:bootJar` 후 `-p pfplay-local --env-file .env.local` 컨테이너 기동 → V34 적용 + validate 통과 + 앱 부팅 성공(`reference_local_boot_gate_jpql_startup_bugs`).

- [ ] **Step 3: 라이브 스모크(어드민 인증)**

로컬 부팅 상태에서 admin 세션으로 `GET /api/v1/admin/partyrooms/{id}/analytics?days=20` 및 `/dj-history` 200 확인. (어드민은 별도 브라우저/쿠키 — `reference_admin_login_shared_cookie_half_identity`.)

- [ ] **Step 4: 최종 커밋(있으면)**

```bash
git commit -am "test: 행동분석 회귀/부팅 게이트 반영"
```

---

## 실행 후 확인 체크리스트
- [ ] `days` 범위 400, 없는 방 404, size 200 clamp 동작.
- [ ] `silenceExit.approximate=true` 상시, `totalExits==attendance.totalExited` 불변식.
- [ ] daily 버킷 KST 달력일 asc, 발생일만.
- [ ] Flyway 슬롯 머지 직전 재확정(`uniq -d`).
- [ ] 어드민 SPA UI는 **후속 슬라이스**(본 플랜 밖).
