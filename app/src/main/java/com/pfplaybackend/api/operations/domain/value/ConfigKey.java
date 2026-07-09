package com.pfplaybackend.api.operations.domain.value;

import java.util.regex.Pattern;

/**
 * Value object for SystemConfig PK.
 *
 * Validation: lowercase ASCII letters/digits, dots, and underscores. Max 64 chars.
 * Examples: presence.dj_grace_seconds, feature.avatar_v2.enabled
 *
 * Spec: docs/superpowers/specs/2026-04-19-admin-platform-design.md §3.3.4
 */
public record ConfigKey(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9_]+(\\.[a-z0-9_]+)*$");
    private static final int MAX_LENGTH = 64;

    public ConfigKey {
        if (value == null) {
            throw new IllegalArgumentException("config_key must not be null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("config_key must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("config_key must be <= " + MAX_LENGTH + " chars");
        }
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "config_key must be lowercase alnum/underscore segments separated by dots: " + value);
        }
    }

    public static ConfigKey of(String value) {
        return new ConfigKey(value);
    }

    // Well-known keys
    public static final ConfigKey PRESENCE_DJ_GRACE_SECONDS = new ConfigKey("presence.dj_grace_seconds");
    public static final ConfigKey PRESENCE_LISTENER_GRACE_SECONDS = new ConfigKey("presence.listener_grace_seconds");

    // Virtual-DJ anti-flap / 식별 토글 (Chunk 5)
    public static final ConfigKey VIRTUALCREW_BOT_YIELD_DEBOUNCE_MS = new ConfigKey("virtualcrew.bot_yield_debounce_ms");
    public static final ConfigKey VIRTUALCREW_BOT_MIN_DWELL_MS = new ConfigKey("virtualcrew.bot_min_dwell_ms");
    public static final ConfigKey VIRTUALCREW_DISTINGUISHABLE = new ConfigKey("virtualcrew.distinguishable");

    // 가상 DJ P3 채팅 (Chunk 3). max.inflight 키 없음 — 동시성은 후속 chunk 의 단일 게이트 키 TTL 로 처리.
    public static final ConfigKey VCREW_CHAT_ENABLED = new ConfigKey("vcrew.chat.enabled");

    // 가상 DJ 봇 반응(좋아요) — 방 생동감 (Chunk 6). 기본 off(dormant/fail-closed).
    public static final ConfigKey VCREW_REACTION_ENABLED = new ConfigKey("vcrew.reaction.enabled");
    public static final ConfigKey VCREW_REACTION_PROBABILITY_PERCENT = new ConfigKey("vcrew.reaction.probability_percent");

    public static final ConfigKey VCREW_CHAT_TRIGGER_PROBABILITY = new ConfigKey("vcrew.chat.trigger.probability");
    public static final ConfigKey VCREW_CHAT_ROOM_COOLDOWN_SECONDS = new ConfigKey("vcrew.chat.room.cooldown.seconds");
    public static final ConfigKey VCREW_CHAT_CONTEXT_SIZE = new ConfigKey("vcrew.chat.context.size");
    public static final ConfigKey VCREW_CHAT_OUTPUT_MAX_TOKENS = new ConfigKey("vcrew.chat.output.max.tokens");

    // 가상 DJ P3-B 플레이리스트 자가갱신 forward-gate (구현 전 기본 잠금)
    public static final ConfigKey VCREW_PLAYLIST_SELF_UPDATE_ENABLED = new ConfigKey("vcrew.playlist.self_update.enabled");

    // 가상 DJ P3-B 자가갱신 튜닝 키
    public static final ConfigKey VCREW_SELF_UPDATE_COOLDOWN_SECONDS = new ConfigKey("vcrew.playlist.self_update.cooldown_seconds");
    public static final ConfigKey VCREW_SELF_UPDATE_MIN_REACTIONS = new ConfigKey("vcrew.playlist.self_update.min_reactions");
    public static final ConfigKey VCREW_SELF_UPDATE_REPLACE_PER_CYCLE = new ConfigKey("vcrew.playlist.self_update.replace_per_cycle");
    public static final ConfigKey VCREW_SELF_UPDATE_RECOMMEND_COUNT = new ConfigKey("vcrew.playlist.self_update.recommend_count");
    public static final ConfigKey VCREW_SELF_UPDATE_WEIGHT_REACTION = new ConfigKey("vcrew.playlist.self_update.weight.reaction");
    public static final ConfigKey VCREW_SELF_UPDATE_WEIGHT_GRAB = new ConfigKey("vcrew.playlist.self_update.weight.grab");
    public static final ConfigKey VCREW_SELF_UPDATE_PRUNED_COOLDOWN_SECONDS = new ConfigKey("vcrew.playlist.self_update.pruned_cooldown_seconds");
}
