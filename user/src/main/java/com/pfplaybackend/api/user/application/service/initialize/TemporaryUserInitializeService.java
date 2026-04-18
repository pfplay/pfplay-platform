package com.pfplaybackend.api.user.application.service.initialize;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.application.port.out.PlaylistSetupPort;
import com.pfplaybackend.api.user.application.service.UserActivityCommandService;
import com.pfplaybackend.api.user.application.service.UserProfileCommandService;
import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import com.pfplaybackend.api.user.domain.value.WalletAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class TemporaryUserInitializeService {

    private final GuestRepository guestRepository;
    private final MemberRepository memberRepository;
    private final UserProfileCommandService userProfileCommandService;
    private final UserActivityCommandService userActivityCommandService;
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
        MemberData fullMember = memberRepository.findByUserId(fullMemberId)
                .orElseGet(() -> addAssociateMember(fullMemberId, "FM@google.com"));
        upgradeMember(fullMember);
    }

    public void addGuest(UserId userId) {
        if (guestRepository.findByUserId(userId).isPresent()) {
            return;
        }
        GuestData guest = GuestData.createWithFixedUserId(userId, "Firefox/MacOS");
        ProfileData profile = userProfileCommandService.createProfileDataForGuest(guest.getUserId());
        guest.initiateProfile(profile);
        guestRepository.save(guest);
    }

    public MemberData addAssociateMember(UserId userId, String email) {
        return memberRepository.findByUserId(userId)
                .orElseGet(() -> {
                    MemberData member = MemberData.createWithFixedUserId(userId, email, ProviderType.GOOGLE);
                    ProfileData profile = userProfileCommandService.createProfileDataForMember(member.getUserId());
                    Map<ActivityType, ActivityData> activityMap = userActivityCommandService.createUserActivities(member.getUserId());
                    member.initializeProfile(profile);
                    member.initializeActivityMap(activityMap);

                    MemberData memberData = memberRepository.save(member);
                    playlistSetupPort.createDefaultPlaylist(member.getUserId());
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
