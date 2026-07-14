package com.pfplaybackend.api.party.adapter.in.web.payload.request.dj;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Quick-DJ 등록 요청 — 검색 결과에서 선택한 곡 하나")
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class QuickDjRequest {
    @NotBlank(message = "name is required.")
    @Schema(description = "곡 이름", example = "BLACKPINK - 'Shut Down' M/V", requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
    private String name;

    @NotBlank(message = "linkId is required.")
    @Schema(description = "곡 링크 id", example = "POe9SOEKotk", requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
    private String linkId;

    @NotBlank(message = "duration is required.")
    @Pattern(regexp = "^\\d+:\\d{2}(:\\d{2})?$", message = "duration must be m:ss or h:mm:ss.")
    @Schema(description = "곡 재생 시간", example = "03:01", requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
    private String duration;

    @NotBlank(message = "thumbnailImage is required.")
    @Schema(description = "곡 썸네일 이미지", example = "https://i.ytimg.com/vi/POe9SOEKotk/mqdefault.jpg", requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
    private String thumbnailImage;
}
