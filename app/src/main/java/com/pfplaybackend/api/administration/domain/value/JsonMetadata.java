package com.pfplaybackend.api.administration.domain.value;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * partyroom_admin_action.metadata JSON 컬럼의 typed wrapper.
 *
 * 빈 map / null 모두 안전:
 *  - {@code JsonMetadata.empty()} → DB NULL
 *  - {@code JsonMetadata.of(map)} → JSON 직렬화 (빈 map은 NULL로 저장 — converter가 처리)
 *
 * Immutable: 내부 map은 unmodifiable.
 */
public final class JsonMetadata {

    private static final JsonMetadata EMPTY = new JsonMetadata(Map.of());

    private final Map<String, Object> data;

    private JsonMetadata(Map<String, Object> data) {
        this.data = (data == null || data.isEmpty())
                ? Map.of()
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    }

    public static JsonMetadata empty() {
        return EMPTY;
    }

    public static JsonMetadata of(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return EMPTY;
        return new JsonMetadata(data);
    }

    /**
     * Underlying map exposed as the wrapper's serialized form.
     *
     * <p>{@code @JsonValue} tells Jackson to render the {@code JsonMetadata} instance as
     * the returned map (its keys/values appear directly in the response object) rather
     * than introspecting bean-style getters. Without this, the record-style accessor
     * {@code data()} is invisible to Jackson's default property discovery — only
     * {@code isEmpty()} is picked up via the {@code is*} pattern, leaking
     * {@code {"empty": false}} into responses while the actual metadata stays hidden.
     * Spec: {@code admin-backend-asks.md} A7.
     */
    @JsonValue
    public Map<String, Object> data() {
        return data;
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JsonMetadata that)) return false;
        return Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(data);
    }

    @Override
    public String toString() {
        return "JsonMetadata" + data;
    }
}
