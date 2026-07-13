package com.pfplaybackend.api.virtualcrew.application.dto;

/**
 * 가상 DJ 채팅/자가갱신 런타임 설정 read 뷰 (P3 어드민 패널).
 *
 * <p>{@code system_config} 의 {@code vcrew.chat.*} 5키 + {@code vcrew.playlist.self_update.enabled}
 * forward-gate 를 하나로 묶은 어드민 read 결과. 누락/오타 행은 코드 DEFAULT 로 폴백된 값이다.
 */
public record ChatConfigView(
        boolean chatEnabled,
        boolean selfUpdateEnabled,
        int probabilityPercent,
        int cooldownSeconds,
        int contextSize,
        int outputMaxTokens) {}
