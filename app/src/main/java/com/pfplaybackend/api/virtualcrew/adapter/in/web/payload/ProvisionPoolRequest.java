package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 봇 풀 프로비저닝 요청 — {@code count} 명의 봇 계정을 생성한다.
 */
public record ProvisionPoolRequest(
        @NotNull @Min(1) @Max(500) Integer count
) {}
