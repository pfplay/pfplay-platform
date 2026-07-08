package com.pfplaybackend.api.notification.adapter.in.web.payload.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Web Push 구독 등록/갱신 요청. endpoint UNIQUE, 동일 endpoint면 부활 upsert.
 */
public record PushSubscribeRequest(
        @NotBlank String endpoint,
        @NotBlank String p256dh,
        @NotBlank String auth,
        @NotBlank @Pattern(regexp = "(?i)KO|EN", message = "lang must be KO or EN") String lang
) {}
