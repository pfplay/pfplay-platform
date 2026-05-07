package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * B-3 룸 강제 종료 요청. {@code reason}은 audit 로그에 기록되므로 필수.
 */
public record TerminatePartyroomRequest(
        @NotBlank @Size(max = 500) String reason
) {}
