package com.pfplaybackend.api.virtualdj.adapter.in.web.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 페르소나 생성 요청.
 */
public record CreatePersonaRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 4000) String instruction
) {}
