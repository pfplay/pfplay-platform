package com.pfplaybackend.api.common;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 공유 Testcontainers MySQL 스키마를 각 테스트 시작 전 "깨끗한 슬레이트"로 복원한다 (스펙 §3.2).
 *
 * <h2>별도 autocommit 커넥션 (C1)</h2>
 * MySQL {@code TRUNCATE} 는 DDL 이라 implicit COMMIT 을 유발한다. 스위트의 다수 IT 가 class-level
 * {@code @Transactional}(롤백형)이므로, cleaner 를 test 커넥션에서 돌리면 test 트랜잭션이 강제 커밋되어
 * 롤백 격리가 붕괴한다. 따라서 truncate 는 <b>test 트랜잭션과 무관한 별도 커넥션(autocommit=true)</b>에서
 * 실행한다 — implicit commit 이 자기 커넥션에만 적용되므로 test tx 를 절대 깨지 않는다.
 *
 * <h2>PRESERVE_SET (레퍼런스 시드 보존)</h2>
 * Flyway 시드가 채우는 참조 테이블은 truncate 하지 않는다. 전부 outgoing FK 가 없어 FK-closure 를
 * 만족한다(FK_CHECKS=0 로 대상을 비워도 dangling 참조가 생기지 않음). {@code avatar_icon_resource}
 * 는 V12 에서 DROP 되어 base table 로 나타나지 않지만 방어적으로 포함한다.
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
            List<String> tables = queryBaseTables(c);
            try (Statement s = c.createStatement()) {
                s.execute("SET FOREIGN_KEY_CHECKS=0");
                try {
                    for (String t : tables) {
                        if (!PRESERVE.contains(t)) {
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
