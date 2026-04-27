package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import lombok.Builder;

@Builder
public record ResetPasswordResponse(
        String tempPassword,
        String message
) {}
