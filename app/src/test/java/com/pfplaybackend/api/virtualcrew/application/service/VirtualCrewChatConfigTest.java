package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VirtualCrewChatConfigTest {

    @Mock
    private SystemConfigCache cache;

    @InjectMocks
    private VirtualCrewChatConfig config;

    @Test
    @DisplayName("isEnabled — VCREW_CHAT_ENABLED 키+기본값 false(fail-closed) 로 readBoolean에 위임하고 캐시 값을 그대로 반환한다")
    void isEnabledDelegatesWithDefaultFalse() {
        when(cache.readBoolean(ConfigKey.VCREW_CHAT_ENABLED, false)).thenReturn(true);

        assertThat(config.isEnabled()).isTrue();
        verify(cache).readBoolean(ConfigKey.VCREW_CHAT_ENABLED, false);
    }

    @Test
    @DisplayName("isEnabled — 캐시가 false면 false를 반환한다 (kill switch, 기본 잠금)")
    void isEnabledReturnsCacheFalse() {
        when(cache.readBoolean(ConfigKey.VCREW_CHAT_ENABLED, false)).thenReturn(false);

        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("probabilityPercent — VCREW_CHAT_TRIGGER_PROBABILITY 키+기본값 12 로 readInt에 위임한다")
    void probabilityPercentDelegatesWithDefault12() {
        when(cache.readInt(ConfigKey.VCREW_CHAT_TRIGGER_PROBABILITY, 12)).thenReturn(12);

        assertThat(config.probabilityPercent()).isEqualTo(12);
        verify(cache).readInt(ConfigKey.VCREW_CHAT_TRIGGER_PROBABILITY, 12);
    }

    @Test
    @DisplayName("cooldownSeconds — VCREW_CHAT_ROOM_COOLDOWN_SECONDS 키+기본값 30 로 readInt에 위임한다")
    void cooldownSecondsDelegatesWithDefault30() {
        when(cache.readInt(ConfigKey.VCREW_CHAT_ROOM_COOLDOWN_SECONDS, 30)).thenReturn(30);

        assertThat(config.cooldownSeconds()).isEqualTo(30);
        verify(cache).readInt(ConfigKey.VCREW_CHAT_ROOM_COOLDOWN_SECONDS, 30);
    }

    @Test
    @DisplayName("contextSize — VCREW_CHAT_CONTEXT_SIZE 키+기본값 20 로 readInt에 위임한다")
    void contextSizeDelegatesWithDefault20() {
        when(cache.readInt(ConfigKey.VCREW_CHAT_CONTEXT_SIZE, 20)).thenReturn(20);

        assertThat(config.contextSize()).isEqualTo(20);
        verify(cache).readInt(ConfigKey.VCREW_CHAT_CONTEXT_SIZE, 20);
    }

    @Test
    @DisplayName("outputMaxTokens — VCREW_CHAT_OUTPUT_MAX_TOKENS 키+기본값 256 로 readInt에 위임한다")
    void outputMaxTokensDelegatesWithDefault256() {
        when(cache.readInt(ConfigKey.VCREW_CHAT_OUTPUT_MAX_TOKENS, 256)).thenReturn(256);

        assertThat(config.outputMaxTokens()).isEqualTo(256);
        verify(cache).readInt(ConfigKey.VCREW_CHAT_OUTPUT_MAX_TOKENS, 256);
    }

    @Test
    @DisplayName("readInt 위임은 캐시가 반환한 값을 그대로 통과시킨다 (override 시나리오)")
    void delegatesReturnCacheOverride() {
        when(cache.readInt(ConfigKey.VCREW_CHAT_TRIGGER_PROBABILITY, 12)).thenReturn(50);

        assertThat(config.probabilityPercent()).isEqualTo(50);
    }
}
