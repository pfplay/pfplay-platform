package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SelfUpdateConfigTest {

    private final SystemConfigCache cache = mock(SystemConfigCache.class);
    private final SelfUpdateConfig config = new SelfUpdateConfig(cache);

    @Test
    void isEnabled_defaultsFalse_failClosed() {
        when(cache.readBoolean(eq(ConfigKey.VCREW_PLAYLIST_SELF_UPDATE_ENABLED), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1));
        assertThat(config.isEnabled()).isFalse();
        verify(cache).readBoolean(ConfigKey.VCREW_PLAYLIST_SELF_UPDATE_ENABLED, false);
    }

    @Test
    void tuningGetters_delegateWithDefaults() {
        when(cache.readInt(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        assertThat(config.cooldownSeconds()).isEqualTo(1800);
        assertThat(config.minReactions()).isEqualTo(5);
        assertThat(config.replacePerCycle()).isEqualTo(3);
        assertThat(config.recommendCount()).isEqualTo(6);
        assertThat(config.prunedCooldownSeconds()).isEqualTo(3600);
        assertThat(config.weightReaction()).isEqualTo(1.0);   // 1000‰
        assertThat(config.weightGrab()).isEqualTo(2.0);       // 2000‰
    }
}
