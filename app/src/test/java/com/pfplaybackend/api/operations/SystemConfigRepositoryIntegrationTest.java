package com.pfplaybackend.api.operations;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.operations.adapter.out.persistence.SystemConfigRepository;
import com.pfplaybackend.api.operations.domain.entity.data.SystemConfigData;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying the SystemConfigRepository entity mapping and CRUD
 * work against a real MySQL instance (Testcontainers).
 *
 * The test profile disables Flyway (flyway.enabled=false) and uses ddl-auto=create-drop,
 * so this test inserts a row in @BeforeEach and verifies the repository reads/writes correctly.
 * (system_config 은 범용 key-value 저장소이며, maintenance 게이팅은 issue #267 로 분리되어
 * 더 이상 이 테이블의 키에 의존하지 않는다 — presence grace 키로 매핑/CRUD 만 검증.)
 *
 * Inherits Testcontainers MySQL + Redis wiring and @Tag("integration") from
 * {@link AbstractIntegrationTest}. Run via: ./gradlew :app:integrationTest
 *
 * Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.6
 */
@Transactional
class SystemConfigRepositoryIntegrationTest extends AbstractIntegrationTest {

    private static final ConfigKey KEY = ConfigKey.PRESENCE_DJ_GRACE_SECONDS;

    @Autowired
    SystemConfigRepository repository;

    @BeforeEach
    void seedRow() {
        repository.save(SystemConfigData.create(
                KEY.value(),
                "30",
                "DJ 이탈 grace 초",
                null));
        flushAndClear();
    }

    @Test
    void seeded_row_present_with_value() {
        Optional<SystemConfigData> row = repository.findByConfigKey(KEY.value());
        assertThat(row).isPresent();
        assertThat(row.get().getConfigValue()).isEqualTo("30");
    }

    @Test
    void update_round_trips_via_repository() {
        SystemConfigData row = repository.findByConfigKey(KEY.value()).orElseThrow();
        row.updateValue("45", 1L);
        repository.saveAndFlush(row);

        flushAndClear();

        SystemConfigData reloaded = repository.findByConfigKey(KEY.value()).orElseThrow();
        assertThat(reloaded.getConfigValue()).isEqualTo("45");
        assertThat(reloaded.getUpdatedByAdministratorId()).isEqualTo(1L);

        // Restore so test ordering doesn't leak (though @Transactional rollback handles this)
        reloaded.updateValue("30", null);
        repository.saveAndFlush(reloaded);
    }
}
