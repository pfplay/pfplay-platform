package com.pfplaybackend.api.user.application.service.initialize;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.dto.shared.AvatarBodyDto;
import com.pfplaybackend.api.user.application.service.AvatarResourceQueryService;
import com.pfplaybackend.api.user.application.service.UserAvatarCommandService;
import com.pfplaybackend.api.user.application.service.UserProfileCommandService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.user.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.user.domain.value.AvatarIconUri;
import com.pfplaybackend.api.user.domain.value.WalletAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserInitializeService {

    private static final long ADMIN_FIXED_ID = 1000000000000000L;

    private final UserAccountRepository userAccountRepository;
    private final MemberRepository memberRepository;
    private final UserProfileCommandService userProfileCommandService;
    private final AvatarResourceQueryService avatarResourceQueryService;
    private final UserAvatarCommandService userAvatarCommandService;

    @Transactional
    public UserId addAdminUser() {
        UserId adminId = new UserId(ADMIN_FIXED_ID);
        if (userAccountRepository.findByUserId(adminId).isPresent()) {
            return adminId; // idempotent
        }
        MemberData member = addAssociateMember(adminId, "N/A");
        MemberData updatedMember = updateAvatarBody(member, new AvatarBodyUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_003.png?alt=media"));
        upgradeMember(updatedMember);
        return adminId;
    }

    private MemberData addAssociateMember(UserId userId, String email) {
        // 1) Persist UserAccount (admin keeps GOOGLE per spec — PR 2 will replace
        //    this bootstrap path with a DDL-level SUPER_ADMIN seed using LOCAL).
        UserAccountData userAccount = UserAccountData.createForSocial(userId, email, ProviderType.GOOGLE);
        userAccountRepository.save(userAccount);

        // 2) Persist Member with userAccountId == userId.uid
        MemberData member = MemberData.createForUserAccount(userId.getUid());

        // 3) Initialize profile (matches pre-V4 behavior)
        ProfileData profile = userProfileCommandService.createProfileDataForMember(userId);
        member.initializeProfile(profile);

        // TODO(Task 11): Activity rows initialization. Pre-V4:
        //   Map<ActivityType, ActivityData> activityMap =
        //       userActivityCommandService.createUserActivities(userId);
        //   member.initializeActivityMap(activityMap);
        // Post-V4 with ActivityRepository (Task 11): persist ActivityData rows
        // directly, keyed by userId (legacy UserId VO == userAccountId by value).

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
