package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * B-4 룸 일시 정지 요청. {@code reason}은 audit 로그에 기록되므로 필수.
 */
public record SuspendPartyroomRequest(
        @NotBlank @Size(max = 500) String reason
) {}
