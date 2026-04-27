package com.pfplaybackend.api.user.domain.value;

import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Builder;
import lombok.Getter;

@Embeddable
@Getter
public class AvatarSetting {

    @Embedded
    private AvatarBodyUri avatarBodyUri;

    @Embedded
    private AvatarFaceUri avatarFaceUri;

    @Embedded
    private AvatarIconUri avatarIconUri;

    @Enumerated(EnumType.STRING)
    private AvatarCompositionType avatarCompositionType;

    @Enumerated(EnumType.STRING)
    private FaceSourceType faceSourceType;

    private int combinePositionX;
    private int combinePositionY;
    private double offsetX;
    private double offsetY;
    private double scale;

    protected AvatarSetting() {}

    @Builder
    public AvatarSetting(AvatarBodyUri avatarBodyUri, AvatarFaceUri avatarFaceUri, AvatarIconUri avatarIconUri,
                         AvatarCompositionType avatarCompositionType, FaceSourceType faceSourceType,
                         int combinePositionX, int combinePositionY,
                         double offsetX, double offsetY, double scale) {
        this.avatarBodyUri = avatarBodyUri;
        this.avatarFaceUri = avatarFaceUri;
        this.avatarIconUri = avatarIconUri;
        this.avatarCompositionType = avatarCompositionType;
        this.faceSourceType = faceSourceType;
        this.combinePositionX = combinePositionX;
        this.combinePositionY = combinePositionY;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.scale = scale;
    }

    public void updateBody(AvatarBodyUri avatarBodyUri, int combinePositionX, int combinePositionY) {
        this.avatarBodyUri = avatarBodyUri;
        this.combinePositionX = combinePositionX;
        this.combinePositionY = combinePositionY;
    }

    public void updateFaceSingleBody(AvatarFaceUri avatarFaceUri) {
        this.avatarCompositionType = AvatarCompositionType.SINGLE_BODY;
        this.avatarFaceUri = avatarFaceUri;
    }

    public void updateFaceWithTransform(AvatarFaceUri avatarFaceUri, FaceSourceType faceSourceType,
                                        double offsetX, double offsetY, double scale) {
        this.avatarCompositionType = AvatarCompositionType.BODY_WITH_FACE;
        this.faceSourceType = faceSourceType;
        this.avatarFaceUri = avatarFaceUri;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.scale = scale;
    }

    public void updateIcon(AvatarIconUri avatarIconUri) {
        this.avatarIconUri = avatarIconUri;
    }

    public void applyDefaults() {
        if (this.avatarBodyUri == null) this.avatarBodyUri = new AvatarBodyUri("");
        if (this.avatarFaceUri == null) this.avatarFaceUri = new AvatarFaceUri("");
        if (this.avatarIconUri == null) this.avatarIconUri = new AvatarIconUri("");
    }
}
