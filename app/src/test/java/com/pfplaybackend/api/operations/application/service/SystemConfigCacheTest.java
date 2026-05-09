package com.pfplaybackend.api.operations.application.service;

import com.pfplaybackend.api.operations.adapter.out.persistence.SystemConfigRepository;
import com.pfplaybackend.api.operations.domain.entity.data.SystemConfigData;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemConfigCacheTest {

    @Mock
    SystemConfigRepository repository;

    SystemConfigCache cache;
    MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-04-26T00:00:00Z"));
        cache = new SystemConfigCache(repository, clock);
    }

    @Test
    void isMaintenanceMode_reads_repo_on_first_call() {
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value()))
            .thenReturn(Optional.of(seed("maintenance.enabled", "true")));
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_MESSAGE.value()))
            .thenReturn(Optional.of(seed("maintenance.message", "down")));

        assertThat(cache.isMaintenanceMode()).isTrue();
        assertThat(cache.getMaintenanceMessage()).isEqualTo("down");
    }

    @Test
    void second_call_within_ttl_does_not_hit_repo() {
        when(repository.findByConfigKey(anyString()))
            .thenReturn(Optional.of(seed("maintenance.enabled", "false")))
            .thenReturn(Optional.of(seed("maintenance.message", "")))
            .thenReturn(Optional.of(seed("presence.dj_grace_seconds", "30")))
            .thenReturn(Optional.of(seed("presence.listener_grace_seconds", "10")));

        cache.isMaintenanceMode();
        clock.advanceSeconds(29);
        cache.isMaintenanceMode();
        cache.getMaintenanceMessage();
        cache.getDjGraceSeconds();
        cache.getListenerGraceSeconds();

        // One snapshot fetch loads all four keys; further reads within TTL hit no repo.
        verify(repository, times(4)).findByConfigKey(anyString());
    }

    @Test
    void readInt_returns_value_for_valid_int() {
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value()))
            .thenReturn(Optional.empty());
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_MESSAGE.value()))
            .thenReturn(Optional.empty());
        when(repository.findByConfigKey(ConfigKey.PRESENCE_DJ_GRACE_SECONDS.value()))
            .thenReturn(Optional.of(seed("presence.dj_grace_seconds", "45")));
        when(repository.findByConfigKey(ConfigKey.PRESENCE_LISTENER_GRACE_SECONDS.value()))
            .thenReturn(Optional.of(seed("presence.listener_grace_seconds", "  15  ")));

        assertThat(cache.getDjGraceSeconds()).isEqualTo(45);
        assertThat(cache.getListenerGraceSeconds()).isEqualTo(15);
    }

    @Test
    void readInt_falls_back_on_garbage() {
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value()))
            .thenReturn(Optional.empty());
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_MESSAGE.value()))
            .thenReturn(Optional.empty());
        when(repository.findByConfigKey(ConfigKey.PRESENCE_DJ_GRACE_SECONDS.value()))
            .thenReturn(Optional.of(seed("presence.dj_grace_seconds", "thirty")));
        when(repository.findByConfigKey(ConfigKey.PRESENCE_LISTENER_GRACE_SECONDS.value()))
            .thenReturn(Optional.of(seed("presence.listener_grace_seconds", "-5")));

        // Defaults: 30 / 10
        assertThat(cache.getDjGraceSeconds()).isEqualTo(30);
        assertThat(cache.getListenerGraceSeconds()).isEqualTo(10);
    }

    @Test
    void readInt_falls_back_on_missing_row() {
        when(repository.findByConfigKey(anyString())).thenReturn(Optional.empty());

        assertThat(cache.getDjGraceSeconds()).isEqualTo(30);
        assertThat(cache.getListenerGraceSeconds()).isEqualTo(10);
    }

    @Test
    void call_after_ttl_re_fetches() {
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value()))
            .thenReturn(Optional.of(seed("maintenance.enabled", "false")))
            .thenReturn(Optional.of(seed("maintenance.enabled", "true")));
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_MESSAGE.value()))
            .thenReturn(Optional.of(seed("maintenance.message", "ok")));

        assertThat(cache.isMaintenanceMode()).isFalse();
        clock.advanceSeconds(31);
        assertThat(cache.isMaintenanceMode()).isTrue();

        verify(repository, times(2)).findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value());
    }

    @Test
    void missing_rows_yield_safe_defaults() {
        when(repository.findByConfigKey(anyString())).thenReturn(Optional.empty());

        assertThat(cache.isMaintenanceMode()).isFalse();
        assertThat(cache.getMaintenanceMessage()).isNotBlank();
    }

    @Test
    void invalid_value_for_enabled_treated_as_false() {
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value()))
            .thenReturn(Optional.of(seed("maintenance.enabled", "yes-please")));
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_MESSAGE.value()))
            .thenReturn(Optional.of(seed("maintenance.message", "x")));

        assertThat(cache.isMaintenanceMode()).isFalse();
    }

    private SystemConfigData seed(String key, String value) {
        return SystemConfigData.create(key, value, null, null);
    }

    /** Test-only mutable clock. */
    static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
    }
}
