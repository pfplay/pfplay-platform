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
 * MySQL {@code TRUNCATE} 는 DDL 이라 implicit COMMIT 을 유발한다. 스위트의 다수 IT 가 class-level
 * {@code @Transactional}(롤백형)이므로, cleaner 를 test 커넥션에서 돌리면 test 트랜잭션이 강제 커밋되어
 * 롤백 격리가 붕괴한다. 따라서 truncate 는 <b>test 트랜잭션과 무관한 별도 커넥션(autocommit=true)</b>에서
 * 실행한다 — implicit commit 이 자기 커넥션에만 적용되므로 test tx 를 절대 깨지 않는다.
 *
 * <h2>PRESERVE_SET (레퍼런스 시드 보존)</h2>
 * Flyway 시드가 채우는 참조 테이블은 truncate 하지 않는다. 전부 outgoing FK 가 없어 FK-closure 를
 * 만족한다. {@code avatar_icon_resource} 는 V12 에서 DROP 되어 base table 로 나타나지 않지만
 * 방어적으로 포함한다.
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
                    // 세션 변수(FOREIGN_KEY_CHECKS)는 HikariCP 가 리셋하지 않으므로,
                    // 예외 경로에서도 반드시 복원해 FK 검사 꺼진 커넥션이 풀로 반환되는 오염을 막는다.
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
     * 않는다. 조회 전 {@code SET SESSION information_schema_stats_expiry=0} 으로 live 값을 강제해야
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
