package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import com.pfplaybackend.api.virtualcrew.application.dto.command.AddPackTrackCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 송 팩 트랙 추가 요청 — 어드민 프론트의 music-search 로 이미 선택된 트랙을 그대로 받는다.
 */
public record AddPackTrackRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 100) String linkId,
        @NotBlank String duration,
        @Size(max = 1000) String thumbnailImage
) {
    public AddPackTrackCommand toCommand() {
        return new AddPackTrackCommand(name, linkId, duration, thumbnailImage);
    }
}
