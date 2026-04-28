package com.pfplaybackend.api.user.domain.entity.data;

import com.pfplaybackend.api.common.domain.annotation.AggregateRoot;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
import com.pfplaybackend.api.user.domain.event.MemberTierChangedEvent;
import com.pfplaybackend.api.user.domain.value.ActivitySummary;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import com.pfplaybackend.api.user.domain.value.ProfileSummary;
import com.pfplaybackend.api.user.domain.value.WalletAddress;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.util.List;

@AggregateRoot
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DynamicInsert
@DynamicUpdate
public class MemberData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "user_account_id", nullable = false)
    private Long userAccountId;

    @Column(name = "authority_tier", nullable = false, length = 8)
    @Enumerated(EnumType.STRING)
    private AuthorityTier authorityTier;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id")
    private ProfileData profileData;

    @Column(name = "is_profile_updated", nullable = false)
    private boolean isProfileUpdated;

    // ActivityData is no longer owned by Member as a JPA association.
    // Application services query it directly via ActivityRepository.findByUserAccountId(...).
    // Rationale: Member's PK is now memberId (AUTO_INCREMENT), but user_activity.user_id
    // joins on the legacy UserId value (== user_account.user_id == member.user_account_id).
    // Sharper aggregate boundary; eliminates the cross-PK reconciliation footgun.
    // See plan §"activityDataMap mapping decision" for full rationale.

    @Builder(access = AccessLevel.PRIVATE)
    private MemberData(Long userAccountId, AuthorityTier authorityTier,
                       ProfileData profileData, boolean isProfileUpdated) {
        this.userAccountId = userAccountId;
        this.authorityTier = authorityTier;
        this.profileData = profileData;
        this.isProfileUpdated = isProfileUpdated;
    }

    public static MemberData createForUserAccount(Long userAccountId) {
        return MemberData.builder()
                .userAccountId(userAccountId)
                .authorityTier(AuthorityTier.AM)
                .isProfileUpdated(false)
                .build();
    }

    public void initializeProfile(ProfileData profileData) {
        this.profileData = profileData;
    }

    public void updateProfileBio(String nickName, String introduction) {
        this.profileData.updateBio(nickName, introduction);
        this.isProfileUpdated = true;
    }

    public void updateAvatarBody(AvatarBodyUri bodyUri, int positionX, int positionY) {
        this.profileData.updateAvatarBody(bodyUri, positionX, positionY);
    }

    public void updateAvatarFace(AvatarFaceUri uri) {
        this.profileData.updateAvatarFaceSingleBody(uri);
    }

    public void updateAvatarFace(AvatarFaceUri uri, FaceSourceType src,
                                 double offsetX, double offsetY, double scale) {
        this.profileData.updateAvatarFaceWithTransform(uri, src, offsetX, offsetY, scale);
    }

    public void updateAvatarIcon(AvatarIconUri uri) {
        this.profileData.updateAvatarIcon(uri);
    }

    public void updateWalletAddress(WalletAddress walletAddress) {
        this.profileData.updateWalletAddress(walletAddress);
        this.authorityTier = AuthorityTier.FM;
    }

    /**
     * Admin-driven tier change: pure mutation + domain event registration.
     * Service layer guards TIER_UNCHANGED before invoking. {@code byAdministratorId}
     * is captured into the event for downstream audit trail (UAL row 2건 — TIER_CHANGED + ADMIN_ACTED_ON).
     */
    public void changeTier(AuthorityTier newTier, Long byAdministratorId) {
        AuthorityTier oldTier = this.authorityTier;
        this.authorityTier = newTier;
        registerEvent(new MemberTierChangedEvent(
                this.userAccountId, this.memberId, oldTier, newTier, byAdministratorId));
    }

    /**
     * Build a profile summary view. Activity scores are passed in by the
     * application service (which queries ActivityRepository directly) — this
     * entity does not own the activity collection.
     */
    public ProfileSummary getProfileSummary(List<ActivitySummary> activitySummaries) {
        var bio = this.profileData.getBio();
        var avatar = this.profileData.getAvatarSetting();
        return new ProfileSummary(
                bio != null ? bio.getNicknameValue() : null,
                bio != null ? bio.getIntroduction() : null,
                avatar.getAvatarBodyUri().getValue(),
                avatar.getAvatarCompositionType(),
                avatar.getCombinePositionX(),
                avatar.getCombinePositionY(),
                avatar.getOffsetX(),
                avatar.getOffsetY(),
                avatar.getScale(),
                avatar.getAvatarFaceUri().getValue(),
                avatar.getAvatarIconUri().getValue(),
                this.profileData.getWalletAddress().getValue(),
                activitySummaries
        );
    }
}
