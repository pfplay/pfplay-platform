package com.pfplaybackend.api.user.application.service.initialize;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserProfileRepository;
import com.pfplaybackend.api.user.application.port.out.PlaylistSetupPort;
import com.pfplaybackend.api.user.application.service.UserActivityCommandService;
import com.pfplaybackend.api.user.application.service.UserAvatarQueryService;
import com.pfplaybackend.api.user.application.service.UserProfileCommandService;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
import com.pfplaybackend.api.user.domain.value.Nickname;
import com.pfplaybackend.api.user.domain.value.WalletAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TemporaryUserInitializeService {

    private final UserAccountRepository userAccountRepository;
    private final GuestRepository guestRepository;
    private final MemberRepository memberRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserProfileCommandService userProfileCommandService;
    private final UserActivityCommandService userActivityCommandService;
    private final UserAvatarQueryService userAvatarQueryService;
    private final PlaylistSetupPort playlistSetupPort;

    private static final long GUEST_FIXED_ID = 1000000000000001L;
    private static final long ASSOCIATE_MEMBER_FIXED_ID = 1000000000000002L;
    private static final long FULL_MEMBER_FIXED_ID = 1000000000000003L;
    private static final int TEMP_NICKNAME_MAX_ATTEMPTS = 5;

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

        // Note: Guests do NOT get ActivityData rows. Pre-V4 the
        // initializeActivityMap call was member-scoped; guests have no DJ /
        // referral / room-activity score by design. Promotion to Member runs
        // through addAssociateMember which will seed activity rows then.

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

                    MemberData memberData = memberRepository.save(member);

                    // 4) Persist activity rows directly via the repository.
                    //    Member no longer owns the activity collection (Task 11).
                    userActivityCommandService.createUserActivities(userId);

                    playlistSetupPort.createDefaultPlaylist(userId);
                    return memberData;
                });
    }

    public MemberData upgradeMember(MemberData member) {
        // 1. Profile Update — V15 unique 제약 대응으로 nickname 마다 UUID 접미사
        member.updateProfileBio(generateUniqueTemporaryNickname(), "introduction");
        memberRepository.save(member);
        // 2. Wallet Update
        member.updateWalletAddress(new WalletAddress("wallet-address"));
        memberRepository.save(member);
        // 3. Avatar Default Attach — broadcaster/응답 빌더가 avatar를 dereferencing할 때
        //    NPE를 일으키지 않도록 BASIC seed (V3) 리소스를 attach한다.
        AvatarBodyDto bodyResource = userAvatarQueryService.getDefaultAvatarBody();
        member.updateAvatarBody(
                userAvatarQueryService.getDefaultAvatarBodyUri(),
                bodyResource.getCombinePositionX(),
                bodyResource.getCombinePositionY());
        member.updateAvatarFace(
                userAvatarQueryService.getDefaultAvatarFaceUri(),
                FaceSourceType.INTERNAL_IMAGE,
                0.0, 0.0, 100.0);
        member.updateAvatarIcon(userAvatarQueryService.getDefaultAvatarIconUri());
        memberRepository.save(member);
        return member;
    }

    private String generateUniqueTemporaryNickname() {
        for (int attempt = 0; attempt < TEMP_NICKNAME_MAX_ATTEMPTS; attempt++) {
            String candidate = "nickname-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            if (!userProfileRepository.existsByNickname(new Nickname(candidate))) {
                return candidate;
            }
        }
        return "nickname-" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }
}
