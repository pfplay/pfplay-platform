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

    @Test
    void selfUpdateTuningKeys_haveExpectedValues() {
        assertThat(ConfigKey.VDJ_SELF_UPDATE_COOLDOWN_SECONDS.value()).isEqualTo("vdj.playlist.self_update.cooldown_seconds");
        assertThat(ConfigKey.VDJ_SELF_UPDATE_MIN_REACTIONS.value()).isEqualTo("vdj.playlist.self_update.min_reactions");
        assertThat(ConfigKey.VDJ_SELF_UPDATE_REPLACE_PER_CYCLE.value()).isEqualTo("vdj.playlist.self_update.replace_per_cycle");
        assertThat(ConfigKey.VDJ_SELF_UPDATE_RECOMMEND_COUNT.value()).isEqualTo("vdj.playlist.self_update.recommend_count");
        assertThat(ConfigKey.VDJ_SELF_UPDATE_WEIGHT_REACTION.value()).isEqualTo("vdj.playlist.self_update.weight.reaction");
        assertThat(ConfigKey.VDJ_SELF_UPDATE_WEIGHT_GRAB.value()).isEqualTo("vdj.playlist.self_update.weight.grab");
        assertThat(ConfigKey.VDJ_SELF_UPDATE_PRUNED_COOLDOWN_SECONDS.value()).isEqualTo("vdj.playlist.self_update.pruned_cooldown_seconds");
    }
}
