package com.pfplaybackend.api.notification.adapter.in.web.payload.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Web Push 구독 해지 요청. 소유자(userId) 스코프 내에서만 revoke 처리.
 */
public record PushUnsubscribeRequest(
        @NotBlank String endpoint
) {}
