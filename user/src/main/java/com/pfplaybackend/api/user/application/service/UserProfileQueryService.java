package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.adapter.out.persistence.ActivityRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserProfileRepository;
import com.pfplaybackend.api.user.application.dto.shared.ActivitySummaryDto;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSummaryDto;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.value.ActivitySummary;
import com.pfplaybackend.api.user.domain.value.AvatarSetting;
import com.pfplaybackend.api.user.domain.value.ProfileSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserProfileQueryService {
    private final UserProfileRepository userProfileRepository;
    private final GuestRepository guestRepository;
    private final MemberRepository memberRepository;
    private final ActivityRepository activityRepository;

    public ProfileSummaryDto getMyProfileSummary() {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        ProfileSummary summary = buildProfileSummary(authContext.getUserId(), authContext.getAuthorityTier());
        return toProfileSummaryDto(summary);
    }

    public ProfileSummaryDto getOtherProfileSummary(UserId otherUserId, AuthorityTier authorityTier) {
        ProfileSummary summary = buildProfileSummary(otherUserId, authorityTier);
        return toProfileSummaryDto(summary);
    }

    public AuthorityTier getAuthorityTier(UserId userId) {
        return memberRepository.findByUserAccountId(userId.getUid())
                .map(MemberData::getAuthorityTier)
                .orElseGet(() -> guestRepository.findByUserAccountId(userId.getUid())
                        .map(GuestData::getAuthorityTier)
                        .orElseThrow());
    }

    /**
     * Builds the profile summary view. For members, activity rows are loaded
     * externally (Member no longer owns the activity collection — see Task 11
     * notes on {@link MemberData}). Guests have no activity rows.
     */
    private ProfileSummary buildProfileSummary(UserId userId, AuthorityTier tier) {
        if (tier == AuthorityTier.GT) {
            GuestData guest = guestRepository.findByUserAccountId(userId.getUid()).orElseThrow();
            return buildSummaryFromProfile(guest.getProfileData(), List.of());
        }
        MemberData member = memberRepository.findByUserAccountId(userId.getUid()).orElseThrow();
        List<ActivitySummary> activities = activityRepository.findAllByUserId(userId).stream()
                .map(a -> new ActivitySummary(a.getActivityType(), a.getScore().getValue()))
                .toList();
        return member.getProfileSummary(activities);
    }

    private ProfileSummary buildSummaryFromProfile(ProfileData profileData, List<ActivitySummary> activities) {
        AvatarSetting avatar = profileData.getAvatarSetting();
        return new ProfileSummary(
                profileData.getNicknameValue(),
                profileData.getIntroduction(),
                avatar.getAvatarBodyUriValue(),
                avatar.getAvatarCompositionType(),
                avatar.getCombinePositionX(),
                avatar.getCombinePositionY(),
                avatar.getOffsetX(),
                avatar.getOffsetY(),
                avatar.getScale(),
                avatar.getAvatarFaceUriValue(),
                avatar.getAvatarIconUriValue(),
                profileData.getWalletAddress() != null ? profileData.getWalletAddress().getValue() : null,
                activities
        );
    }

    /**
     * 프로필이 없는 참조 유저(가상 DJ 봇 아바타 생성 실패/더미 점유/이상 데이터)에 대한 방어용 placeholder.
     *
     * <p>{@link #getUsersProfileSetting}이 요청된 모든 userId에 대해 non-null을 보장하도록,
     * 프로필 행이 없는 userId는 본 값으로 채운다. 소비처(로비 메인스테이지/크루 아이콘 등 7+)가
     * {@code map.get(userId).avatarIconUri()}를 null 가드 없이 역참조하던 NPE→500(로비 전체 다운)을
     * 원천 차단한다(defense-in-depth, #291). 아바타 URI는 null로 두어 프런트의 기본 아바타로 graceful 표시.
     */
    private static final ProfileSettingDto PLACEHOLDER_PROFILE = new ProfileSettingDto(
            "", AvatarCompositionType.SINGLE_BODY, null, null, null, 0, 0, 0.0, 0.0, 1.0);

    // 다수 사용자에 대한 프로필 설정 정보 조회
    // 프로필 없는 userId도 placeholder로 채워 silent drop(소비처 NPE 원인)을 방지한다. (#291)
    @Transactional(readOnly = true)
    public Map<UserId, ProfileSettingDto> getUsersProfileSetting(List<UserId> userIds) {
        Map<UserId, ProfileSettingDto> profileMap = new HashMap<>(
                userProfileRepository.findAllByUserIdIn(userIds).stream()
                        .collect(Collectors.toMap(ProfileData::getUserId, this::toProfileSettingDto)));
        for (UserId id : userIds) {
            profileMap.putIfAbsent(id, PLACEHOLDER_PROFILE);
        }
        return profileMap;
    }

    // 특정 사용자에 대한 프로필 설정 정보 조회
    @Transactional(readOnly = true)
    public ProfileSettingDto getUserProfileSetting(UserId userId) {
        ProfileData profileData = userProfileRepository.findByUserId(userId);
        return toProfileSettingDto(profileData);
    }

    private ProfileSummaryDto toProfileSummaryDto(ProfileSummary summary) {
        return new ProfileSummaryDto(
                summary.nickname(),
                summary.introduction(),
                summary.avatarBodyUri(),
                summary.avatarCompositionType(),
                summary.combinePositionX(),
                summary.combinePositionY(),
                summary.offsetX(),
                summary.offsetY(),
                summary.scale(),
                summary.avatarFaceUri(),
                summary.avatarIconUri(),
                summary.walletAddress(),
                summary.activitySummaries() != null
                        ? summary.activitySummaries().stream()
                            .map(a -> new ActivitySummaryDto(a.activityType(), a.score()))
                            .toList()
                        : List.of()
        );
    }

    private ProfileSettingDto toProfileSettingDto(ProfileData profileData) {
        AvatarSetting avatar = profileData.getAvatarSetting();
        return new ProfileSettingDto(
                profileData.getNicknameValue(),
                avatar.getAvatarCompositionType(),
                avatar.getAvatarBodyUriValue(),
                avatar.getAvatarFaceUriValue(),
                avatar.getAvatarIconUriValue(),
                avatar.getCombinePositionX(),
                avatar.getCombinePositionY(),
                avatar.getOffsetX(),
                avatar.getOffsetY(),
                avatar.getScale()
        );
    }
}
