# DatabaseCleaner 더티-테이블 최적화 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `DatabaseCleaner.clean()`이 매 테스트 메서드 전 ~70개 테이블을 전부 TRUNCATE하던 것을, pristine(0행 AND AUTO_INCREMENT=1)이 아닌 "더티" 테이블만 TRUNCATE하도록 바꿔 로컬(Windows Docker) 통합 테스트 속도를 회복한다. 동작(사후 상태)은 현행과 동일.

**Architecture:** `clean()`이 truncate 전에 더티 테이블 집합 = (행 존재: 라이브 `EXISTS`) ∪ (`AUTO_INCREMENT>1`: MySQL 8.0 통계캐시를 `stats_expiry=0`으로 우회한 `information_schema` 조회)을 계산하고, 그 집합만 truncate한다. 감지는 fsync 없는 읽기라 TRUNCATE의 fsync보다 훨씬 싸다. 별도 autocommit 커넥션·PRESERVE_SET·FK_CHECKS 로직은 현행 유지.

**Tech Stack:** Java 21, Spring Boot, Testcontainers MySQL 8.0.30, JUnit5, AssertJ. 스펙: `docs/superpowers/specs/2026-07-10-database-cleaner-dirty-table-optimization-design.md`.

**빌드/실행 주의(이 레포·이 머신):**
- 모든 gradle 명령은 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix 필수.
- 로컬 전량 IT 단일호출은 Windows Docker fsync로 매우 느리다(바로 이 플랜이 고치려는 문제). **검증은 소배치로**, 각 배치 전 잔존 gradle Worker JVM kill + `output.bin` 락 해제 + docker prune 필요(reference: killed run이 Worker 좀비를 남겨 `output.bin`을 점유). 단일 클래스 실행은 빠르다.

---

## 파일 구조 (생성/수정 맵)

- Modify: `app/src/test/java/com/pfplaybackend/api/common/DatabaseCleaner.java`
  - `clean()`을 "더티만 truncate"로 변경. `detectDirtyTables()`·`selectDirtyTables()`·`nonPreserveBaseTables()` 추가. 별도 커넥션·PRESERVE·FK_CHECKS 뼈대 유지.
- Modify: `app/src/test/java/com/pfplaybackend/api/common/DatabaseCleanerIsolationIT.java`
  - 신규 테스트 2개 추가: 선택적 truncate 화이트박스(T5), INSERT-롤백 후 AI 리셋(T2b). 기존 isolationA/B/C(T1/T3)는 그대로 유지(선택적 truncate 후에도 격리 보존 = 회귀 가드).

책임 경계: `DatabaseCleaner`는 "테스트 간 DB를 pristine으로 복원"이라는 단일 책임 유지. 감지 로직은 같은 클래스의 private/package-private 헬퍼로 캡슐화(외부 인터페이스는 `clean()` 불변, 화이트박스 검증용 `selectDirtyTables()`만 package-private 노출).

---

## Chunk 1: 더티-테이블 선택적 truncate

### Task 1: 실패하는 테스트 먼저 (T5 화이트박스 + T2b AI 리셋)

**Files:**
- Modify/Test: `app/src/test/java/com/pfplaybackend/api/common/DatabaseCleanerIsolationIT.java`

먼저 `BugReportData`의 생성 id 게터 이름을 확인한다(플랜은 `getId()`를 가정):

- [ ] **Step 1: 엔티티 id 게터 확인**

Run: `grep -nE "getId|BugReportId|@Id|Long id" app/src/main/**/BugReportData.java` (또는 파일 찾기: `find . -name BugReportData.java -not -path '*/build/*'`)
확인: 생성 PK 게터 이름(예 `getId()` 또는 `getBugReportId()`). 이후 Step에서 그 이름을 사용.

- [ ] **Step 2: IsolationIT에 신규 테스트 2개 추가(실패 예정)**

기존 import/필드에 더해 `DatabaseCleaner`·`TransactionTemplate`·`Set`·`assertThat` 사용. 아래를 클래스에 추가(기존 isolationA/B/C·`assertCleanThenWriteOne`은 유지):

```java
// --- 추가 import ---
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Set;

// --- 클래스 내부 필드 추가 ---
@Autowired
DatabaseCleaner databaseCleaner;
@Autowired
TransactionTemplate transactionTemplate;

private BugReportData newProbe(String desc) {
    return BugReportData.create(
            REPORTER_UID, desc, "https://pfplay.xyz/parties/lobby",
            "Mozilla/5.0", null, LocalDateTime.of(2026, 7, 9, 12, 0));
}

/** T5: pristine 테이블은 truncate 대상에서 제외되고 더티만 감지됨(선택적 truncate 증명). */
@Test
void selectDirtyTables_detectsOnlyDirtyTables() {
    // pre-test clean() 이 이미 모든 비-PRESERVE 테이블을 pristine 으로 만든 상태.
    bugReportRepository.save(newProbe("dirty probe"));   // 커밋 → bug_report 만 더티

    Set<String> dirty = databaseCleaner.selectDirtyTables();

    assertThat(dirty).contains("bug_report");
    // 이 IT 가 건드리지 않는 비-PRESERVE 테이블 = pristine → 제외되어야 함.
    assertThat(dirty).doesNotContain("system_announcement");
}

/** T2b: INSERT 후 롤백(행 0, engine AI 전진)도 더티로 감지되고 clean 후 AI=1 복원.
 *  8.0 통계캐시 우회(stats_expiry=0)가 없으면 stale AI=1 로 미감지되어 실패한다. */
@Test
void autoIncrementReset_afterInsertRollback() {
    // pre-test clean() → bug_report pristine(AI=1).
    // INSERT 후 롤백: 행은 사라지지만 InnoDB 는 소비한 AUTO_INCREMENT 를 되돌리지 않는다.
    transactionTemplate.executeWithoutResult(status -> {
        bugReportRepository.saveAndFlush(newProbe("rollback probe"));
        status.setRollbackOnly();
    });
    assertThat(bugReportRepository.count()).isZero();                 // 롤백 확인
    assertThat(databaseCleaner.selectDirtyTables()).contains("bug_report");  // AI 전진 → 더티

    databaseCleaner.clean();                                         // 더티(bug_report) truncate → AI=1

    BugReportData saved = bugReportRepository.save(newProbe("after clean"));
    assertThat(saved.getId()).isEqualTo(1L);                        // AI 리셋됨 (게터명 Step1 확인값으로 교체)
}
```

> 주의: `saved.getId()` 는 Step 1에서 확인한 실제 게터명으로 교체. `system_announcement` 가 이 컨테이너 스키마에 실제 존재하는 비-PRESERVE base table 인지 확인(그렇다). 아니면 다른 미사용 비-PRESERVE 테이블명으로 교체.

- [ ] **Step 3: 컴파일/실행해서 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava`
Expected: **컴파일 실패** — `DatabaseCleaner` 에 `selectDirtyTables()` 없음(cannot find symbol).

- [ ] **Step 4: 커밋(실패 테스트)**

```bash
git add app/src/test/java/com/pfplaybackend/api/common/DatabaseCleanerIsolationIT.java
git commit -m "test: DatabaseCleaner 선택적 truncate·AI리셋(롤백) 실패 테스트 추가"
```

### Task 2: DatabaseCleaner 더티 감지 구현

**Files:**
- Modify: `app/src/test/java/com/pfplaybackend/api/common/DatabaseCleaner.java`

- [ ] **Step 1: clean() 을 더티-선택형으로 교체 + 헬퍼 추가**

전체 파일을 아래로 교체(PRESERVE·생성자·`queryBaseTables` 는 동일 유지, `clean()` 변경 + 헬퍼 3개 추가):

```java
package com.pfplaybackend.api.common;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 공유 Testcontainers MySQL 스키마를 각 테스트 시작 전 "깨끗한 슬레이트"로 복원한다 (스펙 §3.2).
 *
 * <h2>더티 테이블만 truncate (2026-07-10 최적화)</h2>
 * 현재 (0행 AND AUTO_INCREMENT=1) 인 테이블은 truncate 가 no-op 이므로 스킵한다. InnoDB TRUNCATE 는
 * DDL fsync 를 유발하는데 Docker Desktop for Windows 에선 테이블당 1~2초로 매우 느리다. "더티" =
 * (행 존재: 라이브 EXISTS) ∪ (AUTO_INCREMENT>1: MySQL 8.0 통계캐시 우회 후 information_schema).
 * 사후 상태는 전 테이블 truncate 와 동일(스펙 §3.4).
 *
 * <h2>별도 autocommit 커넥션 (C1)</h2>
 * MySQL TRUNCATE 는 implicit COMMIT 이라 test 트랜잭션과 무관한 별도 커넥션에서 실행한다.
 *
 * <h2>PRESERVE_SET (레퍼런스 시드 보존)</h2>
 * Flyway 시드가 채우는 참조 테이블은 truncate 하지 않는다.
 */
@Component
public class DatabaseCleaner {

    private static final Set<String> PRESERVE = Set.of(
            "flyway_schema_history",
            "avatar_body_resource",
            "avatar_face_resource",
            "avatar_icon_resource",
            "system_config");

    private final DataSource dataSource;

    public DatabaseCleaner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void clean() {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            List<String> targets = nonPreserveBaseTables(c);
            Set<String> dirty = detectDirtyTables(c, targets);
            try (Statement s = c.createStatement()) {
                s.execute("SET FOREIGN_KEY_CHECKS=0");
                try {
                    for (String t : targets) {
                        if (dirty.contains(t)) {
                            s.execute("TRUNCATE TABLE `" + t + "`");
                        }
                    }
                } finally {
                    // 세션 변수(FOREIGN_KEY_CHECKS)는 HikariCP 가 리셋하지 않으므로 예외 경로에서도 복원.
                    s.execute("SET FOREIGN_KEY_CHECKS=1");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("DB cleanup 실패", e);
        }
    }

    /** 화이트박스 검증용: truncate 없이 더티 테이블 집합만 계산해 반환. */
    Set<String> selectDirtyTables() {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            return detectDirtyTables(c, nonPreserveBaseTables(c));
        } catch (SQLException e) {
            throw new IllegalStateException("더티 테이블 감지 실패", e);
        }
    }

    private List<String> nonPreserveBaseTables(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        for (String t : queryBaseTables(c)) {
            if (!PRESERVE.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * pristine(0행 AND AUTO_INCREMENT=1) 이 아닌 테이블 집합.
     * 조건① 행 존재(라이브 EXISTS 배치) ∪ 조건② AUTO_INCREMENT>1.
     * ⚠️ MySQL 8.0 은 information_schema 의 AUTO_INCREMENT/TABLE_ROWS 를 캐시하며 DML 로 무효화되지
     * 않는다. 조회 전 SET SESSION information_schema_stats_expiry=0 으로 live 값을 강제해야
     * INSERT-롤백으로 "비었지만 AI 전진" 한 테이블을 놓치지 않는다(스펙 §3.2, §6). 조건①(EXISTS)는
     * 라이브 데이터 쿼리라 캐시와 무관.
     */
    private Set<String> detectDirtyTables(Connection c, List<String> targets) throws SQLException {
        Set<String> dirty = new HashSet<>();
        if (targets.isEmpty()) {
            return dirty;
        }

        // 조건① 행 존재 — 라이브 EXISTS 배치(fsync 없음).
        StringBuilder sb = new StringBuilder();
        for (String t : targets) {
            if (sb.length() > 0) {
                sb.append(" UNION ALL ");
            }
            sb.append("SELECT '").append(t).append("' AS t FROM DUAL WHERE EXISTS (SELECT 1 FROM `")
              .append(t).append("`)");
        }
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sb.toString())) {
            while (rs.next()) {
                dirty.add(rs.getString(1));
            }
        }

        // 조건② AUTO_INCREMENT>1 — 8.0 통계캐시 우회(라이브).
        try (Statement s = c.createStatement()) {
            s.execute("SET SESSION information_schema_stats_expiry = 0");
        }
        Set<String> targetSet = new HashSet<>(targets);
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE() AND auto_increment > 1")) {
            while (rs.next()) {
                String t = rs.getString(1);
                if (targetSet.contains(t)) {
                    dirty.add(t);
                }
            }
        }
        return dirty;
    }

    private List<String> queryBaseTables(Connection c) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'";
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileTestJava`
Expected: **BUILD SUCCESSFUL**.

- [ ] **Step 3: IsolationIT 실행(신규+기존 전부 그린)**

먼저 잔존 gradle Worker/락 정리(이 머신 필수):
```bash
for p in $(wmic process where "name='java.exe'" get ProcessId 2>/dev/null | grep -oE '^[0-9]+'); do cl=$(wmic process where "ProcessId=$p" get CommandLine 2>/dev/null | tr -d '\r'); echo "$cl" | grep -qiE 'gradle|worker.tmpdir' && taskkill //F //PID "$p" >/dev/null 2>&1; done
docker rm -f $(docker ps -q --filter "name=testcontainers-ryuk") 2>/dev/null; docker container prune -f >/dev/null 2>&1
rm -rf app/build/test-results/integrationTest
```
Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --no-daemon --tests "com.pfplaybackend.api.common.DatabaseCleanerIsolationIT"`
Expected: **BUILD SUCCESSFUL**, 5 tests(isolationA/B/C + selectDirtyTables_detectsOnlyDirtyTables + autoIncrementReset_afterInsertRollback) 전부 pass.

- [ ] **Step 4: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/common/DatabaseCleaner.java
git commit -m "perf(test): DatabaseCleaner 더티 테이블만 truncate (no-op fsync 제거, 8.0 캐시우회)"
```

## Chunk 2: 전량 회귀 검증 + 속도 측정

### Task 3: 대표 클래스로 정확성 회귀 확인(소배치)

동작 불변이므로 기존에 그린이던 클래스가 그대로 그린이어야 한다. 이전에 FK/DUP/DATETIME 이슈가 있던 클래스들을 대표로 소배치 실행(각 배치 전 위 정리 스니펫 재실행).

- [ ] **Step 1: 격리 민감 + AFTER_COMMIT 클래스 배치 실행**

Run(정리 스니펫 후):
```
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --no-daemon --continue \
  --tests "com.pfplaybackend.api.common.DatabaseCleanerIsolationIT" \
  --tests "com.pfplaybackend.api.administration.application.service.AdminReportCommandServiceIT" \
  --tests "com.pfplaybackend.api.virtualdj.VirtualDjEventListenerIT" \
  --tests "com.pfplaybackend.api.party.adapter.in.listener.PartyroomCounterListenerIT" \
  --tests "com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepositoryIntegrationTest"
```
Expected: **BUILD SUCCESSFUL**, 0 fail/0 error. (더티-선택이 격리를 깨지 않고 AI 리셋도 보존함을 확인.)

- [ ] **Step 2: 결과 XML 확인**

Run: `ls app/build/test-results/integrationTest/*.xml | wc -l` 및 실패 수 집계(0 이어야 함).

### Task 4: 전량 2회 그린 + 유닛 무변 + 속도 측정

- [ ] **Step 1: 전량 IT 를 소배치로 2회 초록**

전량 단일호출은 이 머신에서 비현실적으로 느리다. 대신 72개 클래스를 ~6개씩 소배치로 나눠(각 배치 전 정리 스니펫) 실행, 완료 XML 을 별도 디렉터리에 누적해 총 0 fail/0 error 를 2회 확인한다(직전 IT 하네스 검증과 동일 절차). 배치별 `--no-daemon`.

- [ ] **Step 2: 유닛 회귀 무변**

Run(정리 후): `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --no-daemon`
Expected: **BUILD SUCCESSFUL**(하네스 변경이 유닛에 영향 없음).

- [ ] **Step 3: 속도 측정(전/후 비교) 기록**

동일한 대표 배치(Task 3 Step 1 세트)를 최적화 전 커밋과 후 커밋에서 각각 실행해 벽시계 시간을 기록. 개선 폭을 커밋 메시지 또는 짧은 노트로 남긴다(선택). 최적화가 유의미한지 확인.

- [ ] **Step 4: (필요 시) 정리 커밋**

```bash
git commit -m "docs(test): DatabaseCleaner 최적화 속도 측정 결과 기록" --allow-empty
```

### Task 5: 원격 검증(CI)

- [ ] **Step 1: 브랜치 push → CI 그린 확인**

이 변경은 `test/it-harness-flyway-validate` 브랜치(PR #319)에 얹힌다. push 후 CI 의 `Run integration tests` 스텝(`./gradlew :app:integrationTest`, clean ubuntu)이 초록인지 확인 — clean Linux 는 단일호출도 빠르므로 전량 그린이 여기서 최종 확인된다.

Run: `git push` 후 `gh pr checks 319 --repo pfplay/pfplay-platform` 로 상태 확인.
Expected: test 잡 pass.

---

## 실행 후 체크리스트
- [ ] `DatabaseCleanerIsolationIT` 5개 그린(선택적 truncate + INSERT-롤백 AI 리셋 포함).
- [ ] 대표 소배치 + 전량 소배치 2회 0 fail/0 error(동작 불변 확인).
- [ ] `:app:test` 유닛 회귀 무변.
- [ ] CI(리눅스) `:app:integrationTest` 그린.
- [ ] 로컬 배치 속도 개선 확인(전/후 벽시계).
