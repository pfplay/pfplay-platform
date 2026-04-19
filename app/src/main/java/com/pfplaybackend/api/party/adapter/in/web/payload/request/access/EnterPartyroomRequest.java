package com.pfplaybackend.api.party.adapter.in.web.payload.request.access;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

public record EnterPartyroomRequest(
        @Schema(description = "입장 시점에 프론트엔드가 추출한 ISO 3166-1 alpha-2 국가 코드. 추출 불가 또는 미제공이면 null.",
                example = "KR",
                nullable = true)
        @Pattern(regexp = "^[A-Z]{2}$", message = "countryCode는 대문자 2글자여야 합니다 (ISO 3166-1 alpha-2).")
        String countryCode
) {
}
