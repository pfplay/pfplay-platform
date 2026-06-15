package com.pfplaybackend.api.virtualdj.adapter.in.web.payload;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 가상 DJ 채팅/자가갱신 설정 변경 요청.
 *
 * <p>boolean 은 primitive 라 JSON 누락 시 false 로 바인딩된다(의미상 OFF). 정수 범위는 bean validation
 * 으로 1차 단언하고, 서비스가 동일 경계를 재단언한다(직접 호출/curl 우회 방어).
 */
public record UpdateChatConfigRequest(
        boolean chatEnabled,
        boolean selfUpdateEnabled,
        @Min(0) @Max(100) int probabilityPercent,
        @Min(1) int cooldownSeconds,
        @Min(1) int contextSize,
        @Min(1) int outputMaxTokens) {}
