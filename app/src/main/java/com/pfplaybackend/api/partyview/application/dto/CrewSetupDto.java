package com.pfplaybackend.api.partyview.application.dto;

import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import io.swagger.v3.oas.annotations.media.Schema;

public record CrewSetupDto(
        @Schema(example = "1") long crewId,
        @Schema(example = "CLUBBER") GradeType gradeType,
        @Schema(example = "DJ_Master") String nickname,
        @Schema(example = "COMBINE") AvatarCompositionType avatarCompositionType,
        @Schema(example = "https://cdn.pfplay.xyz/avatar/body/default.png") String avatarBodyUri,
        @Schema(example = "https://cdn.pfplay.xyz/avatar/face/smile.png") String avatarFaceUri,
        @Schema(example = "https://cdn.pfplay.xyz/avatar/icon/1.png") String avatarIconUri,
        @Schema(example = "0") int combinePositionX,
        @Schema(example = "0") int combinePositionY,
        @Schema(example = "0.0") double offsetX,
        @Schema(example = "0.0") double offsetY,
        @Schema(example = "1.0") double scale,
        @Schema(example = "KR", nullable = true, description = "ISO 3166-1 alpha-2 country code captured at entry time")
        String countryCode
) {
    public static CrewSetupDto from(CrewData crew, ProfileSettingDto p) {
        CountryCode cc = crew.getCountryCode();
        return new CrewSetupDto(crew.getId(), crew.getGradeType(),
                p.nickname(), p.avatarCompositionType(), p.avatarBodyUri(), p.avatarFaceUri(), p.avatarIconUri(),
                p.combinePositionX(), p.combinePositionY(), p.offsetX(), p.offsetY(), p.scale(),
                cc == null ? null : cc.getValue());
    }
}
