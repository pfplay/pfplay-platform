package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 가상 DJ 봇 반응(좋아요) 런타임 설정. isEnabled 기본 false(dormant, fail-closed). */
@Component
@RequiredArgsConstructor
public class VirtualCrewReactionConfig {
    static final boolean DEFAULT_ENABLED = false;
    static final int DEFAULT_PROBABILITY_PERCENT = 15;

    private final SystemConfigCache cache;

    public boolean isEnabled() {
        return cache.readBoolean(ConfigKey.VCREW_REACTION_ENABLED, DEFAULT_ENABLED);
    }

    public int probabilityPercent() {
        return cache.readInt(ConfigKey.VCREW_REACTION_PROBABILITY_PERCENT, DEFAULT_PROBABILITY_PERCENT);
    }
}
