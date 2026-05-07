package com.pfplaybackend.api.avatar.application.dto;

import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.ToString;

@Data
@Getter
@Builder(toBuilder = true)
@ToString
public class AvatarBodyDto {
    @Schema(example = "1") private Long id;
    @Schema(example = "default_body") private final String name;
    @Schema(example = "https://cdn.pfplay.xyz/avatar/body/default.png") private final String resourceUri;
    @Schema(example = "https://cdn.pfplay.xyz/avatar/icon/default.png") private final String iconUri;
    @Schema(example = "BASIC") private final ObtainmentType obtainableType;
    @Schema(example = "0") private final int obtainableScore;
    @Schema(example = "true") private final boolean combinable;
    @Schema(example = "true") private final boolean defaultSetting;
    @Schema(example = "true") private final boolean available;
    @Schema(example = "0") private final int combinePositionX;
    @Schema(example = "0") private final int combinePositionY;
    @Schema(example = "PUBLISHED") private final LifecycleStatus lifecycleStatus;

    public static AvatarBodyDto create(AvatarBodyResourceData avatarBodyResource) {
        return AvatarBodyDto.builder()
                .id(avatarBodyResource.getId())
                .name(avatarBodyResource.getName())
                .resourceUri(avatarBodyResource.getResourceUri())
                .iconUri(avatarBodyResource.getIconUri())
                .obtainableType(avatarBodyResource.getObtainableType())
                .obtainableScore(avatarBodyResource.getObtainableScore())
                .combinable(avatarBodyResource.isCombinable())
                .defaultSetting(avatarBodyResource.isDefaultSetting())
                // BASIC 타입인 경우 available 한 것으로 처리
                .available(avatarBodyResource.getObtainableType().equals(ObtainmentType.BASIC))
                .combinePositionX(avatarBodyResource.getCombinePositionX())
                .combinePositionY(avatarBodyResource.getCombinePositionY())
                .lifecycleStatus(avatarBodyResource.getLifecycleStatus())
                .build();
    }
}
