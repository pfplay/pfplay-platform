package com.pfplaybackend.api.user.application.service.initialize;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.port.out.PlaylistSetupPort;
import com.pfplaybackend.api.user.application.service.UserProfileCommandService;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.value.WalletAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TemporaryUserInitializeService {

    private final UserAccountRepository userAccountRepository;
    private final GuestRepository guestRepository;
    private final MemberRepository memberRepository;
    private final UserProfileCommandService userProfileCommandService;
    private final PlaylistSetupPort playlistSetupPort;

    private static final long GUEST_FIXED_ID = 1000000000000001L;
    private static final long ASSOCIATE_MEMBER_FIXED_ID = 1000000000000002L;
    private static final long FULL_MEMBER_FIXED_ID = 1000000000000003L;

    @Transactional
    public void addTemporaryUsers() {
        UserId guestId = new UserId(GUEST_FIXED_ID);
        UserId associateMemberId = new UserId(ASSOCIATE_MEMBER_FIXED_ID);
        UserId fullMemberId = new UserId(FULL_MEMBER_FIXED_ID);
        // Add Users
        addGuest(guestId);
        addAssociateMember(associateMemberId, "AM@google.com");
        // UpgradeToFullMember
        MemberData fullMember = memberRepository.findByUserAccountId(fullMemberId.getUid())
                .orElseGet(() -> addAssociateMember(fullMemberId, "FM@google.com"));
        upgradeMember(fullMember);
    }

    public void addGuest(UserId userId) {
        if (userAccountRepository.findByUserId(userId).isPresent()) {
            return; // idempotent
        }
        // 1) Persist UserAccount with synthetic placeholder email to satisfy
        //    the email NOT NULL UNIQUE constraint. Synthetic emails are
        //    uniquely keyed by userId.getUid() so no collision risk.
        //    Guests use GOOGLE placeholder per pre-V4 behavior (Task 10
        //    migrates virtual users to LOCAL — guests included? not in
        //    this PR; this site keeps GOOGLE).
        //    Note: guests do NOT call recordLogin() — they don't "login"
        //    in the social sense.
        UserAccountData userAccount = UserAccountData.createForSocial(
                userId,
                "guest-" + userId.getUid() + "@guest.local",
                ProviderType.GOOGLE);
        userAccountRepository.save(userAccount);

        // 2) Persist Guest with userAccountId == userId.uid
        GuestData guest = GuestData.createForUserAccount(userId.getUid(), "Firefox/MacOS");

        // 3) Initialize profile (matches pre-V4 behavior)
        ProfileData profile = userProfileCommandService.createProfileDataForGuest(userId);
        guest.initiateProfile(profile);

        // TODO(Task 11): ActivityData init for guest (was previously implicit
        //   via member.initializeActivityMap; guest activity init, if any,
        //   will be wired in Task 11 once ActivityRepository is available).

        guestRepository.save(guest);
    }

    public MemberData addAssociateMember(UserId userId, String email) {
        return memberRepository.findByUserAccountId(userId.getUid())
                .orElseGet(() -> {
                    // 1) Persist UserAccount (GOOGLE per pre-V4 behavior)
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
                    // Post-V4 with ActivityRepository (Task 11): persist
                    // ActivityData rows directly, keyed by userId.

                    MemberData memberData = memberRepository.save(member);
                    playlistSetupPort.createDefaultPlaylist(userId);
                    return memberData;
                });
    }

    public MemberData upgradeMember(MemberData member) {
        // 1. Profile Update
        member.updateProfileBio("nickname", "introduction");
        memberRepository.save(member);
        // 2. Wallet Update
        member.updateWalletAddress(new WalletAddress("wallet-address"));
        memberRepository.save(member);
        return member;
    }
}
