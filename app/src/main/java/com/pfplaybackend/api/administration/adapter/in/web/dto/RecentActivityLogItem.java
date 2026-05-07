package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.administration.domain.value.JsonMetadata;

import java.time.LocalDateTime;

/**
 * A-2 detail response — recentActivityLog list item (mapped from {@code user_activity_log}).
 *
 * <p>{@code eventType}는 DB에 VARCHAR(64)로 저장된 enum.name() 그대로 노출된다 — listener에서
 * 검증된 값이라 Type-safe 변환은 불필요. {@code partyroomId}/{@code metadata}는 nullable.
 */
public record RecentActivityLogItem(
        String eventType,
        Long partyroomId,
        JsonMetadata metadata,
        LocalDateTime occurredAt
) {}
