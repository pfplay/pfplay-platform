package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import jakarta.validation.constraints.NotNull;

/**
 * B-6 Display flag 변경 요청. NORMAL / FEATURED / HIDDEN 중 하나.
 */
public record UpdateDisplayFlagRequest(
        @NotNull DisplayFlag flag
) {}
