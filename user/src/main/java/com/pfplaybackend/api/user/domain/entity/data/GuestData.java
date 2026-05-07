package com.pfplaybackend.api.user.domain.entity.data;

import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.common.enums.AuthorityTier;
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

@Entity
@Table(name = "guest")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DynamicInsert
@DynamicUpdate
public class GuestData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "guest_id")
    private Long guestId;

    @Column(name = "user_account_id", nullable = false)
    private Long userAccountId;

    @Column(name = "agent", length = 255)
    private String agent;

    @Column(name = "authority_tier", nullable = false, length = 8)
    @Enumerated(EnumType.STRING)
    private AuthorityTier authorityTier;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id")
    private ProfileData profileData;

    @Column(name = "is_profile_updated", nullable = false)
    private boolean isProfileUpdated;

    @Builder(access = AccessLevel.PRIVATE)
    private GuestData(Long userAccountId, String agent, AuthorityTier authorityTier,
                      ProfileData profileData, boolean isProfileUpdated) {
        this.userAccountId = userAccountId;
        this.agent = agent;
        this.authorityTier = authorityTier;
        this.profileData = profileData;
        this.isProfileUpdated = isProfileUpdated;
    }

    public static GuestData createForUserAccount(Long userAccountId, String agent) {
        return GuestData.builder()
                .userAccountId(userAccountId)
                .agent(agent)
                .authorityTier(AuthorityTier.GT)
                .isProfileUpdated(false)
                .build();
    }

    public void initiateProfile(ProfileData profileData) {
        this.profileData = profileData;
        this.isProfileUpdated = true;
    }
}
