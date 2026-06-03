package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 가상 DJ P3-B 플레이리스트 자가갱신 런타임 설정 읽기 래퍼.
 *
 * <p>{@link SystemConfigCache} fail-open readInt(양수만)/readBoolean 에 위임. {@link #isEnabled()} 는
 * 전역 kill switch 이며 <b>기본 잠금(false)</b>(fail-closed) — 행 부재/오타/캐시 실패 시 자가갱신은 켜지지
 * 않는다(V28 시드 참조). weight 는 readDouble 부재로 정수 퍼밀(‰)로 저장하고 1000 으로 나눠 double 로 쓴다.
 */
@Component
@RequiredArgsConstructor
public class SelfUpdateConfig {

    static final boolean DEFAULT_ENABLED = false;          // fail-closed
    static final int DEFAULT_COOLDOWN_SECONDS = 1800;      // 30분
    static final int DEFAULT_MIN_REACTIONS = 5;            // K
    static final int DEFAULT_TARGET_SIZE = 20;             // T
    static final int DEFAULT_REPLACE_PER_CYCLE = 3;        // P
    static final int DEFAULT_RECOMMEND_COUNT = 6;          // N
    static final int DEFAULT_PRUNED_COOLDOWN_SECONDS = 3600;
    static final int DEFAULT_WEIGHT_REACTION_PERMILLE = 1000;   // 1.0
    static final int DEFAULT_WEIGHT_GRAB_PERMILLE = 2000;       // 2.0

    private final SystemConfigCache cache;

    public boolean isEnabled() {
        return cache.readBoolean(ConfigKey.VDJ_PLAYLIST_SELF_UPDATE_ENABLED, DEFAULT_ENABLED);
    }

    public int cooldownSeconds() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_COOLDOWN_SECONDS, DEFAULT_COOLDOWN_SECONDS);
    }

    public int minReactions() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_MIN_REACTIONS, DEFAULT_MIN_REACTIONS);
    }

    public int targetSize() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_TARGET_SIZE, DEFAULT_TARGET_SIZE);
    }

    public int replacePerCycle() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_REPLACE_PER_CYCLE, DEFAULT_REPLACE_PER_CYCLE);
    }

    public int recommendCount() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_RECOMMEND_COUNT, DEFAULT_RECOMMEND_COUNT);
    }

    public int prunedCooldownSeconds() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_PRUNED_COOLDOWN_SECONDS, DEFAULT_PRUNED_COOLDOWN_SECONDS);
    }

    public double weightReaction() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_WEIGHT_REACTION, DEFAULT_WEIGHT_REACTION_PERMILLE) / 1000.0;
    }

    public double weightGrab() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_WEIGHT_GRAB, DEFAULT_WEIGHT_GRAB_PERMILLE) / 1000.0;
    }
}
