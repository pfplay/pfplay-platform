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

    /**
     * 아바타 URI(body/face/icon) 의 문자열 값을 null-safe 하게 반환하는 접근자들.
     *
     * <p>임베디드 URI 값 객체는 셋이 모두 NULL 일 수 있다({@code applyDefaults()} 가 셋을 대칭으로
     * null→"" 보정하는 것과 동일한 전제). 특히 최소 정체성 계정(봇)은 아이콘이 NULL 이고, 향후 body/face
     * 도 미설정될 수 있다. 컬럼이 NULL 이면 임베디드 객체 자체가 null 이라 {@code getXxxUri().getValue()}
     * 를 직접 호출하면 NPE 가 난다. 프로필/DJ 조회·이벤트 직렬화 경로는 반드시 이 접근자들을 써야 한다.
     */
    public String getAvatarBodyUriValue() {
        return this.avatarBodyUri != null ? this.avatarBodyUri.getValue() : "";
    }

    public String getAvatarFaceUriValue() {
        return this.avatarFaceUri != null ? this.avatarFaceUri.getValue() : "";
    }

    public String getAvatarIconUriValue() {
        return this.avatarIconUri != null ? this.avatarIconUri.getValue() : "";
    }
}
