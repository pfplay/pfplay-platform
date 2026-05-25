package com.pfplaybackend.api.operations.domain.value;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigKeyTest {

    @Test
    void rejects_null() {
        assertThatThrownBy(() -> ConfigKey.of(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_blank() {
        assertThatThrownBy(() -> ConfigKey.of(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigKey.of("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_overlong_value() {
        String tooLong = "a".repeat(65);
        assertThatThrownBy(() -> ConfigKey.of(tooLong))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_uppercase() {
        assertThatThrownBy(() -> ConfigKey.of("Maintenance.Enabled"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_spaces() {
        assertThatThrownBy(() -> ConfigKey.of("maintenance enabled"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accepts_dotted_lowercase_alnum() {
        assertThat(ConfigKey.of("maintenance.enabled").value())
            .isEqualTo("maintenance.enabled");
        assertThat(ConfigKey.of("feature.avatar_v2.enabled").value())
            .isEqualTo("feature.avatar_v2.enabled");
    }

    @Test
    void provides_well_known_constants() {
        assertThat(ConfigKey.PRESENCE_DJ_GRACE_SECONDS.value()).isEqualTo("presence.dj_grace_seconds");
        assertThat(ConfigKey.PRESENCE_LISTENER_GRACE_SECONDS.value()).isEqualTo("presence.listener_grace_seconds");
    }
}
