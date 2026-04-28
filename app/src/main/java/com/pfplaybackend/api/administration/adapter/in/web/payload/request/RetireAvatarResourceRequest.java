package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Retire 사유. Spec §6.I-5 — 필수.
 */
public record RetireAvatarResourceRequest(
        @NotBlank @Size(max = 1000) String reason
) {}
