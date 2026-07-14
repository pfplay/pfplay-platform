package com.pfplaybackend.api.party.adapter.in.web.payload.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Quick-DJ 등록 응답")
public record QuickDjResponse(
        @Schema(description = "생성된 DJ ID") Long djId,
        @Schema(description = "내 대기열 순번(1-base)") int orderNumber
) {}
