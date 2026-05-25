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
}
