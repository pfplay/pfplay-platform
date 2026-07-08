package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VirtualDjReactionConfigTest {

    @Mock SystemConfigCache cache;
    @InjectMocks VirtualDjReactionConfig config;

    @Test
    @DisplayName("isEnabled — 기본 false(dormant)")
    void enabled_defaultsFalse() {
        when(cache.readBoolean(ConfigKey.VDJ_REACTION_ENABLED, false)).thenReturn(false);
        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("probabilityPercent — 설정값 반환")
    void probability_returnsConfigured() {
        when(cache.readInt(eq(ConfigKey.VDJ_REACTION_PROBABILITY_PERCENT), anyInt())).thenReturn(25);
        assertThat(config.probabilityPercent()).isEqualTo(25);
    }
}
