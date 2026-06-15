package com.pfplaybackend.api.virtualdj.adapter.in.web.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 송 팩 생성 요청.
 */
public record CreateSongPackRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description
) {}
