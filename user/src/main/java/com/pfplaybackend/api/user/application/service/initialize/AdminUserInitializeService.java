package com.pfplaybackend.api.user.application.service.initialize;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.application.dto.shared.AvatarBodyDto;
import com.pfplaybackend.api.user.application.service.AvatarResourceQueryService;
import com.pfplaybackend.api.user.application.service.UserActivityCommandService;
import com.pfplaybackend.api.user.application.service.UserAvatarCommandService;
import com.pfplaybackend.api.user.application.service.UserProfileCommandService;
import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import com.pfplaybackend.api.user.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.user.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.user.domain.value.AvatarIconUri;
import com.pfplaybackend.api.user.domain.value.WalletAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminUserInitializeService {

    private static final long ADMIN_FIXED_ID = 1000000000000000L;

    private final MemberRepository memberRepository;
    private final UserProfileCommandService userProfileCommandService;
    private final UserActivityCommandService userActivityCommandService;
    private final AvatarResourceQueryService avatarResourceQueryService;
    private final UserAvatarCommandService userAvatarCommandService;

    @Transactional
    public UserId addAdminUser() {
        UserId adminId = new UserId(ADMIN_FIXED_ID);
        if (memberRepository.findByUserId(adminId).isPresent()) {
            return adminId;
        }
        MemberData member = addAssociateMember(adminId);
        MemberData updatedMember = updateAvatarBody(member, new AvatarBodyUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_003.png?alt=media"));
        upgradeMember(updatedMember);
        return adminId;
    }

    private MemberData addAssociateMember(UserId userId) {
        MemberData member = MemberData.createWithFixedUserId(userId, "N/A", ProviderType.GOOGLE);
        ProfileData profile = userProfileCommandService.createProfileDataForMember(member.getUserId());
        Map<ActivityType, ActivityData> activityMap = userActivityCommandService.createUserActivities(member.getUserId());
        member.initializeProfile(profile);
        member.initializeActivityMap(activityMap);
        return memberRepository.save(member);
    }

    private void upgradeMember(MemberData member) {
        // 1. Profile Update
        member.updateProfileBio("운영자", "");
        memberRepository.save(member);
        // 2. Wallet Update
        member.updateWalletAddress(new WalletAddress(""));
        memberRepository.save(member);
    }

    private MemberData updateAvatarBody(MemberData member, AvatarBodyUri avatarBodyUri) {
        AvatarBodyDto avatarBodyDto = avatarResourceQueryService.findAvatarBodyByUri(avatarBodyUri);
        AvatarFaceUri avatarFaceUri = new AvatarFaceUri();
        AvatarIconUri avatarIconUri = userAvatarCommandService.findAvatarIconPairWithSingleBody(avatarBodyDto);

        member.updateAvatarBody(
                new AvatarBodyUri(avatarBodyDto.getResourceUri()),
                avatarBodyDto.getCombinePositionX(),
                avatarBodyDto.getCombinePositionY());
        member.updateAvatarFace(avatarFaceUri);
        member.updateAvatarIcon(avatarIconUri);
        return memberRepository.save(member);
    }
}
