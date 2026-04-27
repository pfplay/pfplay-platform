package com.pfplaybackend.api.user.domain.entity.data;

import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
import com.pfplaybackend.api.user.domain.value.*;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

@Getter
@DynamicInsert
@DynamicUpdate
@Table(name = "USER_PROFILE",
        indexes = {
                @Index(name = "user_profile_user_id_IDX", columnList = "user_id")
        })
@Entity
public class ProfileData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "bigint unsigned")
    private Long id;

    @Embedded
    @AttributeOverride(name = "uid", column = @Column(name = "user_id"))
    private UserId userId;

    @Embedded
    private Bio bio;

    @Embedded
    private WalletAddress walletAddress;

    @Embedded
    private AvatarSetting avatarSetting;

    protected ProfileData() {}

    @Builder
    public ProfileData(Long id, UserId userId, Nickname nickname, String introduction,
                       AvatarBodyUri avatarBodyUri,
                       AvatarFaceUri avatarFaceUri,
                       AvatarIconUri avatarIconUri,
                       WalletAddress walletAddress,
                       AvatarCompositionType avatarCompositionType,
                       FaceSourceType faceSourceType,
                       int combinePositionX, int combinePositionY,
                       double offsetX, double offsetY, double scale) {
        this.id = id;
        this.userId = userId;
        this.bio = new Bio(nickname, introduction);
        this.walletAddress = walletAddress;
        this.avatarSetting = AvatarSetting.builder()
                .avatarBodyUri(avatarBodyUri)
                .avatarFaceUri(avatarFaceUri)
                .avatarIconUri(avatarIconUri)
                .avatarCompositionType(avatarCompositionType)
                .faceSourceType(faceSourceType)
                .combinePositionX(combinePositionX)
                .combinePositionY(combinePositionY)
                .offsetX(offsetX)
                .offsetY(offsetY)
                .scale(scale)
                .build();
    }

    @PrePersist
    @PreUpdate
    private void setDefaultValues() {
        if (this.avatarSetting != null) {
            this.avatarSetting.applyDefaults();
        }
        if (this.walletAddress == null) {
            this.walletAddress = new WalletAddress("");
        }
    }

    // --- Bio delegates ---

    public String getNicknameValue() {
        return bio == null ? null : bio.getNicknameValue();
    }

    public String getIntroduction() {
        return bio == null ? null : bio.getIntroduction();
    }

    public void updateBio(String nickname, String introduction) {
        if (this.bio == null) {
            this.bio = new Bio(new Nickname(nickname), introduction);
        } else {
            this.bio.update(nickname, introduction);
        }
    }

    public void updateNickname(String nickname) {
        if (this.bio == null) {
            this.bio = new Bio(new Nickname(nickname), null);
        } else {
            this.bio.updateNickname(nickname);
        }
    }

    // --- AvatarSetting delegates ---

    public void updateAvatarBody(AvatarBodyUri avatarBodyUri, int combinePositionX, int combinePositionY) {
        this.avatarSetting.updateBody(avatarBodyUri, combinePositionX, combinePositionY);
    }

    public void updateAvatarFaceSingleBody(AvatarFaceUri avatarFaceUri) {
        this.avatarSetting.updateFaceSingleBody(avatarFaceUri);
    }

    public void updateAvatarFaceWithTransform(AvatarFaceUri avatarFaceUri, FaceSourceType faceSourceType,
                                              double offsetX, double offsetY, double scale) {
        this.avatarSetting.updateFaceWithTransform(avatarFaceUri, faceSourceType, offsetX, offsetY, scale);
    }

    public void updateAvatarIcon(AvatarIconUri avatarIconUri) {
        this.avatarSetting.updateIcon(avatarIconUri);
    }

    // --- WalletAddress delegate ---

    public void updateWalletAddress(WalletAddress walletAddress) {
        this.walletAddress = walletAddress;
    }
}
