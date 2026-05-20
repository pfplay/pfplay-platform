package com.pfplaybackend.api.party.adapter.in.web.payload.request.dj;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;

@Getter
public class ChangePlaylistRequest {
    @NotNull(message = "playlistId is required.")
    @Positive(message = "playlistId must be positive.")
    private Long playlistId;
}
