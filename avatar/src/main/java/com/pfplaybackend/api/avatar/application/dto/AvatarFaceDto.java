package com.pfplaybackend.api.avatar.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AvatarFaceDto(
        @Schema(example = "1") long id,
        @Schema(example = "smile") String name,
        @Schema(example = "https://cdn.pfplay.xyz/avatar/face/smile.png") String resourceUri,
        @Schema(example = "true") boolean available
) {}
