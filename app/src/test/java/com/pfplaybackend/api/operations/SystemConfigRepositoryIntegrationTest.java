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
 * so V9 seed data is not automatically present. This test inserts the canonical seed rows
 * in @BeforeEach to simulate what V9 delivers in production, then verifies the repository
 * reads/writes correctly.
 *
 * Inherits Testcontainers MySQL + Redis wiring and @Tag("integration") from
 * {@link AbstractIntegrationTest}. Run via: ./gradlew :app:integrationTest
 *
 * Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.6
 */
@Transactional
class SystemConfigRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    SystemConfigRepository repository;

    @BeforeEach
    void seedV9Rows() {
        // Mirror the two rows inserted by V9__create_system_config.sql
        repository.save(SystemConfigData.create(
                ConfigKey.MAINTENANCE_ENABLED.value(),
                "false",
                "유지보수 모드 활성 여부 (true일 때 일반 API 503)",
                null));
        repository.save(SystemConfigData.create(
                ConfigKey.MAINTENANCE_MESSAGE.value(),
                "시스템 점검 중입니다. 잠시 후 다시 시도해주세요.",
                "유지보수 안내 메시지",
                null));
        flushAndClear();
    }

    @Test
    void v9_seeds_two_rows() {
        assertThat(repository.count()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void maintenance_enabled_seed_present_and_false() {
        Optional<SystemConfigData> row = repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value());
        assertThat(row).isPresent();
        assertThat(row.get().getConfigValue()).isEqualTo("false");
    }

    @Test
    void maintenance_message_seed_present_with_default() {
        Optional<SystemConfigData> row = repository.findByConfigKey(ConfigKey.MAINTENANCE_MESSAGE.value());
        assertThat(row).isPresent();
        assertThat(row.get().getConfigValue()).isNotBlank();
    }

    @Test
    void update_round_trips_via_repository() {
        SystemConfigData row = repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value()).orElseThrow();
        row.updateValue("true", 1L);
        repository.saveAndFlush(row);

        flushAndClear();

        SystemConfigData reloaded = repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value()).orElseThrow();
        assertThat(reloaded.getConfigValue()).isEqualTo("true");
        assertThat(reloaded.getUpdatedByAdministratorId()).isEqualTo(1L);

        // Restore so test ordering doesn't leak (though @Transactional rollback handles this)
        reloaded.updateValue("false", null);
        repository.saveAndFlush(reloaded);
    }
}
