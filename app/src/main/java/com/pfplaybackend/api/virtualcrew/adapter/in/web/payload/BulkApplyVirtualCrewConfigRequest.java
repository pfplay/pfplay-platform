package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 여러 룸에 같은 가상 DJ config 를 일괄 적용(체크박스).
 */
public record BulkApplyVirtualCrewConfigRequest(
        @NotEmpty @Size(min = 1, max = 100) List<Long> partyroomIds,
        @NotNull VirtualCrewStatus status,
        @Min(1) Integer targetCount,
        @Min(0) Integer djBotCount,
        Long songPackId
) {}
