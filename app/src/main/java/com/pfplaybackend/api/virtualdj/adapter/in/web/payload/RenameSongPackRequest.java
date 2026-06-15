package com.pfplaybackend.api.virtualdj.adapter.in.web.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 송 팩 이름 변경 요청.
 */
public record RenameSongPackRequest(
        @NotBlank @Size(max = 100) String name
) {}
