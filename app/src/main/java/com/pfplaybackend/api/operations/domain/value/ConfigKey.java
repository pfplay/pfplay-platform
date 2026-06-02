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
    public static final ConfigKey VIRTUALDJ_BOT_YIELD_DEBOUNCE_MS = new ConfigKey("virtualdj.bot_yield_debounce_ms");
    public static final ConfigKey VIRTUALDJ_BOT_MIN_DWELL_MS = new ConfigKey("virtualdj.bot_min_dwell_ms");
    public static final ConfigKey VIRTUALDJ_DISTINGUISHABLE = new ConfigKey("virtualdj.distinguishable");

    // 가상 DJ P3 채팅 (Chunk 3). max.inflight 키 없음 — 동시성은 후속 chunk 의 단일 게이트 키 TTL 로 처리.
    public static final ConfigKey VDJ_CHAT_ENABLED = new ConfigKey("vdj.chat.enabled");
    public static final ConfigKey VDJ_CHAT_TRIGGER_PROBABILITY = new ConfigKey("vdj.chat.trigger.probability");
    public static final ConfigKey VDJ_CHAT_ROOM_COOLDOWN_SECONDS = new ConfigKey("vdj.chat.room.cooldown.seconds");
    public static final ConfigKey VDJ_CHAT_CONTEXT_SIZE = new ConfigKey("vdj.chat.context.size");
    public static final ConfigKey VDJ_CHAT_OUTPUT_MAX_TOKENS = new ConfigKey("vdj.chat.output.max.tokens");
}
