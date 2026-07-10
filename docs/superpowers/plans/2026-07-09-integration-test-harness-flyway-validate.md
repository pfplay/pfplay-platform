# 통합 테스트 하네스 Flyway+validate 전환 — 구현 플랜

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** pfplay-platform 통합 테스트(74개 IT)를 `create-drop`에서 프로덕션과 동일한 **Flyway+`validate`**로 전환하고, 공유 스키마 격리(`DatabaseCleaner`)를 도입해 전량 초록화한 뒤, `integrationTest`를 CI에 배선한다.

**Architecture:** 설정 전환(`application-test.yml`) + pre-transaction `TestExecutionListener`에서 **별도 autocommit 커넥션**으로 동적 truncate(레퍼런스 시드 보존) + async executor 3종 quiescence. 제품 코드 무변경, 테스트/설정/CI만.

**Tech Stack:** Spring Boot Test, Testcontainers(MySQL 8.0.30 싱글턴), Flyway, JUnit5, Gradle, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-07-09-integration-test-harness-flyway-validate-design.md` (**구현 전 정독 필수** — 이 플랜은 스펙의 C1/C2/FK-closure 결정을 전제로 함)

**빌드/실행 노트:**
- JDK 21: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix.
- 유닛/웹: `:app:test` / 통합: `:app:integrationTest`(tag-gated). Docker 필요.
- ⚠️ **직렬 실행 불변식**: `maxParallelForks=1` 유지(병렬화 금지 — 공유 스키마 truncate가 fork 간 상호파괴).
- Testcontainers 동시 2런 금지(Windows 파일락). 실행 전 `./gradlew --stop` + java 프로세스 정리.

---

## 파일 구조 (생성/수정 맵)
**생성:**
- `app/src/test/java/com/pfplaybackend/api/common/DatabaseCleaner.java` — 동적 truncate + PRESERVE_SET (별도 커넥션)
- `app/src/test/java/com/pfplaybackend/api/common/DatabaseCleanupTestExecutionListener.java` — `beforeTestMethod`: async quiescence → cleaner
- `app/src/test/java/com/pfplaybackend/api/common/DatabaseCleanerIsolationIT.java` — 결정론 격리 검증(§5.0a)

**수정:**
- `app/src/test/resources/application-test.yml` — `ddl-auto: validate`, `flyway.enabled: true`
- `app/src/test/java/com/pfplaybackend/api/common/AbstractIntegrationTest.java` — `@TestExecutionListeners`로 cleaner 리스너 등록
- 잔여 실패 IT들 — §Phase 3(재측정으로 확정)
- `.github/workflows/ci-test.yml` — integrationTest 스텝
- (필요 시) `build.gradle` — integrationTest 태스크 주석/가드(직렬 불변식)

---

## Chunk 1: 설정 전환 + 격리 메커니즘

### Task 1: `application-test.yml` 전환 + 부팅 확인
**Files:** Modify `app/src/test/resources/application-test.yml`

- [ ] **Step 1: 설정 변경**
`ddl-auto: create-drop` → `validate`, `flyway.enabled: false` → `true`. `sql.init.mode: never` 유지.

- [ ] **Step 2: 컨텍스트 부팅만 확인(격리 전)**
기존 IT 아무거나 1개 실행해 **Flyway가 V1~ 적용 + validate 통과 + 컨텍스트 부팅**을 확인(격리 없이도 부팅은 됨 — 디스커버리에서 0 errors로 입증).
Run: `JAVA_HOME=... ./gradlew :app:integrationTest --tests '*AdminBugReportQueryRepositoryImplIT'`
Expected: 부팅 성공(테스트 자체는 통과 예상 — 이 클래스는 격리 무관). Flyway 로그에 `Successfully applied N migrations ... now at version vNN` 확인.
> ⚠️ **S1**: 이 브랜치(origin/develop 기준)의 소스 최대 마이그레이션은 **V31**이다(V32/V33은 미머지 vdj 소유, V34는 analytics 브랜치 것 — 둘 다 이 브랜치엔 없음). 로그의 최종 버전은 **V31 근방**을 기대할 것(V34 아님). `./gradlew clean` 후 실행해 스테일 `build/resources`의 V34 산출물이 섞이지 않게 한다.
> 이 시점 커밋하지 않는다(격리 없이는 다수 red). Task 3까지 한 논리단위.

### Task 2: `DatabaseCleaner` — 결정론 격리 검증 먼저(TDD)
**Files:**
- Create: `DatabaseCleanerIsolationIT.java` (검증)
- Create: `DatabaseCleaner.java`, `DatabaseCleanupTestExecutionListener.java` (구현)
- Modify: `AbstractIntegrationTest.java`

- [ ] **Step 1: 실패하는 격리 검증 IT 작성(§5.0a 핵심단언)**
`DatabaseCleanerIsolationIT extends AbstractIntegrationTest` — **비-`@Transactional`(커밋형)** 클래스. **대칭 단언(S4 — 순서의존 제거)**: 여러 메서드가 각자 "시작 시 clean → 커밋 삽입 → 1개 관측"을 수행 → 임의의 두 메서드가 서로의 잔재를 안 보는 것을 순서 무관하게 결정론 증명:
```java
// 대상 테이블은 required-parent FK 없는 것 선택(N2) — 커밋형이라 저장 즉시 실행됨.
// 예: partyroom(host_id nullable) 또는 FK 자유 테이블. system_announcement가 필수 FK 없으면 그것도 가능.
private void assertCleanThenWriteOne() {
    assertThat(repo.count()).isZero();         // cleaner가 pre-transaction truncate → 매 메서드 시작 0
    repo.save(<row>);                          // 커밋
    assertThat(repo.count()).isEqualTo(1);
}
@Test void isolationA() { assertCleanThenWriteOne(); }
@Test void isolationB() { assertCleanThenWriteOne(); }
@Test void isolationC() { assertCleanThenWriteOne(); }
```
+ 롤백형 방증(부수, 별 `@Transactional` 검증 메서드/클래스): "write→같은 메서드 롤백 소멸"이 cleaner와 충돌 없음(격리가 tx를 안 깸) 단언.

- [ ] **Step 2: 실패 확인** — cleaner 미구현 → `nextMethodSeesCleanSlate`가 count=1로 FAIL(또는 리스너 미등록으로 truncate 안 됨).
Run: `... :app:integrationTest --tests '*DatabaseCleanerIsolationIT'` → FAIL.

- [ ] **Step 3: `DatabaseCleaner` 구현(별도 autocommit 커넥션)**
```java
@Component
public class DatabaseCleaner {
    private final DataSource dataSource;
    // PRESERVE_SET: 시드 마이그레이션이 채우는 레퍼런스 테이블 (FK-closure 만족 — 전부 outgoing FK 없음).
    // ⚠️ V27/V28/V29는 별도 테이블이 아니라 system_config에 INSERT할 뿐이고,
    //    partyroom_virtual_dj_config(V29)는 컬럼 ALTER만(시드 행 없음) → truncate 대상. "vdj 설정 테이블" 없음.
    private static final Set<String> PRESERVE = Set.of(
        "flyway_schema_history",
        "avatar_body_resource","avatar_face_resource","avatar_icon_resource",
        "system_config");

    public void clean() {
        try (Connection c = dataSource.getConnection()) {   // test tx와 무관한 별도 커넥션
            c.setAutoCommit(true);
            List<String> tables = queryBaseTables(c);        // information_schema, 현 스키마
            try (Statement s = c.createStatement()) {
                s.execute("SET FOREIGN_KEY_CHECKS=0");
                try {
                    for (String t : tables) if (!PRESERVE.contains(t)) s.execute("TRUNCATE TABLE `"+t+"`");
                } finally {
                    s.execute("SET FOREIGN_KEY_CHECKS=1");   // S3: 예외경로에서도 반드시 복원(세션 스코프 오염 방지)
                }
            }
        } catch (SQLException e) { throw new IllegalStateException("DB cleanup 실패", e); }
    }
}
```
> **S3 주의**: `FOREIGN_KEY_CHECKS`는 세션 변수이고 HikariCP는 리셋 안 함 → truncate 루프 예외 시 finally로 복원 안 하면 FK 검사 꺼진 커넥션이 풀로 반환돼 후속 test 오염. finally 필수.

- [ ] **Step 3a (Task 2a): PRESERVE_SET FK-closure 확인**
실행 전, 위 5개 보존 테이블의 **outgoing FK**를 `information_schema.key_column_usage`로 확인해 truncate 대상으로 나가는 FK가 **없음**을 검증(레퍼런스/KV 테이블이라 자명히 없을 것 — system_config는 standalone). 있으면 그 대상도 보존하거나 후보를 truncate 대상으로 강등(스펙 §8-1). **별도 vdj 테이블을 찾지 말 것(S2 — 존재하지 않음).**

- [ ] **Step 4: `DatabaseCleanupTestExecutionListener` 구현(pre-transaction + quiescence)**
```java
public class DatabaseCleanupTestExecutionListener extends AbstractTestExecutionListener {
    @Override public int getOrder() { return 2900; } // DI(2000) 이후·Transactional(4000) 이전. 2500은 Micrometer TEL과 충돌하므로 2900.
    @Override public void beforeTestMethod(TestContext ctx) {
        var appCtx = ctx.getApplicationContext();
        quiesceAsyncExecutors(appCtx);   // 모든 ThreadPoolTaskExecutor 빈 idle 대기(연속 N회, fail-loud)
        appCtx.getBean(DatabaseCleaner.class).clean();
    }
    // quiesce: appCtx.getBeansOfType(ThreadPoolTaskExecutor.class) 전부에 대해
    //   activeCount==0 && queueSize==0 를 연속 3회(짧은 간격) 관측될 때까지 폴링,
    //   상한(예 5s) 초과 시 IllegalStateException(fail-loud).
}
```

- [ ] **Step 5: `AbstractIntegrationTest`에 리스너 등록**
```java
@TestExecutionListeners(
    value = DatabaseCleanupTestExecutionListener.class,
    mergeMode = MergeMode.MERGE_WITH_DEFAULTS)
public abstract class AbstractIntegrationTest { ... }
```

- [ ] **Step 6: 격리 검증 통과 확인**
Run: `... :app:integrationTest --tests '*DatabaseCleanerIsolationIT'` → PASS.

### Task 3: 롤백 격리 결정론 스트레스(C1 검증)
- [ ] **Step 1: 롤백형 `@Transactional` IT 표본 반복 실행**
cleaner가 `@Transactional` IT의 롤백 격리를 **안 깬다**를 확인. 대표 롤백형 IT 3~5개를 **연속 3회** 실행해 flake 없음 확인(1회 초록≠증명).
Run 예: `... :app:integrationTest --tests '*SystemConfigRepositoryIntegrationTest' --tests '*IamRepositoryIntegrationTest'` ×3.
Expected: 매회 동일 초록.

- [ ] **Step 2: 커밋(격리 메커니즘 한 단위)**
```bash
git add app/src/test/resources/application-test.yml \
        app/src/test/java/com/pfplaybackend/api/common/DatabaseCleaner.java \
        app/src/test/java/com/pfplaybackend/api/common/DatabaseCleanupTestExecutionListener.java \
        app/src/test/java/com/pfplaybackend/api/common/DatabaseCleanerIsolationIT.java \
        app/src/test/java/com/pfplaybackend/api/common/AbstractIntegrationTest.java
git commit -m "test: IT 하네스 Flyway+validate 전환 + DatabaseCleaner 격리(pre-transaction)"
```

---

## Chunk 2: 재측정 → 잔여 실패 개별 수정

### Task 4: 전량 재측정 (§5.0b — 진실원천)
- [ ] **Step 1: cleaner 적용 상태로 전량 실행 + 벽시계 기록(N3)**
Run: `... :app:integrationTest --continue` (uncontended, 사전 `--stop`+프로세스 정리).
전체 소요시간(BUILD 시간)을 기록 → Task 7의 CI 스텝 구성(단일 `test integrationTest` vs 분리, 스펙 Open Decision #3) 근거로 사용. `@MockBean` 컨텍스트 파편화가 시간 지배.
- [ ] **Step 2: 실패 재분류**
결과 XML에서 실패 클래스·메시지 수집 → 카테고리 분류표 작성:
  - **자동해소 확인**: §2 디스커버리의 41 중 cleaner로 사라진 것.
  - **잔여**: FK("Cannot add child row")=부모행 시드 누락 / Duplicate=시드 충돌 or 잔여 오염 / Assertion=시드-인지 / async=quiescence 후에도 남는 것.
> 이 분류표가 Task 5의 작업목록. 재측정 없이 Task 5 착수 금지.

### Task 5: 잔여 실패 클래스별 수정 (재측정 결과 기반, 클래스당 1커밋 권장)
각 잔여 실패 클래스에 대해 원인별 플레이북 적용:
- [ ] **FK "Cannot add child row"**: 테스트가 자식 삽입 전 **부모행을 먼저 시드**하도록 보강(참조 무결성 충족). truncate로는 안 풀림.
- [ ] **"Duplicate entry"**: 참조 시드와 충돌하는 고정 키 → **테스트 전용 키 범위(990000+)**로 이동. 잔여 오염이면 해당 테스트의 async await 누락 보강(§3.2.2 목록 대조).
- [ ] **AssertionError(시드-인지)**: 빈-테이블 전제 기대값을 시드 존재에 맞게 조정(단, 이중목적 시드 테이블은 truncate되므로 대부분 자동해소 — 재측정에서 확인).
- [ ] **PRESERVE_SET 커밋 오염(§3.3)**: 보존 테이블에 커밋하는 비-`@Transactional` IT 열거 → `@AfterEach` 자체정리 or 클래스스코프 재시드.
- [ ] **각 수정 후**: 해당 클래스 **연속 2회** 초록 확인 후 커밋.
```bash
git commit -m "test: <클래스> Flyway 하네스 대응 (<원인 요약>)"
```

### Task 6: 전량 초록 게이트 (§5.1)
- [ ] **Step 1: 전량 2회 연속 초록**
Run: `... :app:integrationTest` **연속 2회** → 매회 0 fail/0 error.
- [ ] **Step 2: 유닛/웹 회귀 무변**
Run: `... :app:test` → 초록(하네스 변경이 유닛에 영향 없음 확인).
- [ ] **Step 3: 커밋(있으면 정리 커밋)**

---

## Chunk 3: CI 배선

### Task 7: `ci-test.yml`에 integrationTest 배선
**Files:** Modify `.github/workflows/ci-test.yml`
- [ ] **Step 1: integrationTest 스텝 추가**
기존 `./gradlew test` 스텝 뒤에 `./gradlew :app:integrationTest` 스텝 추가(또는 `./gradlew test integrationTest`). GitHub-hosted `ubuntu-latest`는 Docker 지원(Testcontainers 동작).
- [ ] **Step 2: 로컬로 CI 명령 재현 검증**
CI가 돌릴 명령을 로컬에서 그대로 실행해 초록 확인.
- [ ] **Step 3: (선택) build.gradle 직렬 불변식 가드**
`integrationTest` 태스크에 "병렬화 금지(스키마-per-fork 없인 불가)" 주석 추가.
- [ ] **Step 4: 커밋**
```bash
git commit -m "ci: integrationTest를 ci-test.yml에 배선 (통합 게이트 활성화)"
```
> N2: CI 배선을 별 커밋으로 두어, first-run flake가 dev 자동배포 게이트를 막지 않게 함(사용자 규칙 정합).

---

## 실행 후 체크리스트
- [ ] `:app:integrationTest` 전량 2회 연속 초록 (0 fail/0 error).
- [ ] `@Transactional` IT 롤백 격리 flake 없음(C1), async 레이스 없음(C2).
- [ ] `:app:test` 회귀 무변.
- [ ] CI에서 integrationTest 실제 실행·초록.
- [ ] validate 상시 활성 → 이후 마이그레이션 drift IT 부팅에서 검출.
- [ ] 직렬 실행 불변식 유지(`maxParallelForks=1`).
- [ ] 제품 코드 무변경(테스트/설정/CI만).
