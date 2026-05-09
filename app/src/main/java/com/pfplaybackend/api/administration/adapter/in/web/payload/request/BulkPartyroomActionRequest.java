package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.pfplaybackend.api.administration.domain.enums.BulkActionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * B-8 일괄 액션 요청.
 *
 * <p>{@code skipErrors} 기본값은 true: 한 항목이 실패해도 나머지 진행. 명시적 false면 첫 실패 시 break.
 */
public record BulkPartyroomActionRequest(
        @NotEmpty @Size(min = 1, max = 100) List<Long> partyroomIds,
        @NotNull BulkActionType action,
        @NotBlank @Size(max = 500) String reason,
        Boolean skipErrors
) {
    /** null-safe accessor — default true (skip and continue on errors). */
    public boolean skipErrorsOrDefault() {
        return skipErrors == null || skipErrors;
    }
}
