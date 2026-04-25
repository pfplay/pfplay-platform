package com.pfplaybackend.api.admin.application.service;

import com.pfplaybackend.api.admin.application.port.out.AdminMemberPort;
import com.pfplaybackend.api.admin.application.port.out.AdminPlaylistPort;
import com.pfplaybackend.api.admin.domain.exception.AdminException;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.user.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.user.domain.value.WalletAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Service for managing virtual (admin-created) members.
 *
 * <p>Virtual members are admin-created accounts used for stage hosting and
 * demo content. Per spec §4.1.1 (6), they use {@link ProviderType#LOCAL} —
 * an unmistakable placeholder rather than a real social provider. Their
 * {@code password_hash} is {@code null} (DDL allows it; see spec §4.1.2).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final AdminMemberPort adminMemberPort;
    private final AdminProfileService adminProfileService;
    private final AdminPlaylistPort adminPlaylistPort;
    private final UserAccountRepository userAccountRepository;

    /**
     * Create virtual member with auto-generated profile and FM authority
     *
     * @return Created virtual member with FM authority tier
     */
    @Transactional
    public MemberData createVirtualMember() {
        return createVirtualMember(null, null, null);
    }

    /**
     * Create virtual member with optional nickname and avatar customization.
     * Final authority tier will be FM (Full Member).
     *
     * <p>Two-stage creation: first persist a {@link UserAccountData} with
     * {@link ProviderType#LOCAL} and a {@code null} password hash (virtual
     * users have no real login), then create a {@link MemberData} bound to
     * that account.
     *
     * @param nickname Optional nickname (auto-generated if null)
     * @param avatarBodyUri Optional avatar body URI (default if null)
     * @param avatarFaceUri Optional avatar face URI (default if null)
     * @return Created virtual member with FM authority tier
     */
    @Transactional
    public MemberData createVirtualMember(
            String nickname,
            AvatarBodyUri avatarBodyUri,
            AvatarFaceUri avatarFaceUri) {

        // 1. Generate unique email for virtual member
        String virtualEmail = generateVirtualEmail();

        // 2. Create UserAccount with LOCAL provider type and null password hash.
        //    Per spec §4.1.2, password_hash is nullable; virtual users have no login.
        UserAccountData userAccount = UserAccountData.createForLocal(new UserId(), virtualEmail, null);
        UserAccountData savedAccount = userAccountRepository.save(userAccount);

        // 3. Create Member bound to the UserAccount (initial tier: AM).
        Long userAccountId = savedAccount.getUserId().getUid();
        MemberData member = MemberData.createForUserAccount(userAccountId);

        // 4. Build profile (auto-generated nickname / default avatar when not provided).
        ProfileData profile = adminProfileService.createProfileForVirtualMember(
                savedAccount.getUserId(),
                nickname,
                avatarBodyUri,
                avatarFaceUri
        );
        member.initializeProfile(profile);

        // 5. Persist Member (cascades the new ProfileData).
        MemberData savedMember = adminMemberPort.saveMember(member);

        // TODO(Task 11): Activity-row initialization was here in the pre-V4 flow:
        //   var activityMap = adminMemberPort.createUserActivities(savedAccount.getUserId());
        //   savedMember.initializeActivityMap(activityMap);
        // MemberData.initializeActivityMap was removed in Task 5 (Member no longer
        // owns the activity collection). Re-add direct ActivityData persistence
        // here once ActivityRepository lands in Task 11. Mirrors the deferral in
        // MemberSignService.initializeNewMember.

        // 6. Create default GRABLIST playlist for the virtual member.
        adminPlaylistPort.createDefaultPlaylist(savedAccount.getUserId());

        // 7. Upgrade to FM by setting wallet address — automatically promotes the tier.
        savedMember.updateWalletAddress(new WalletAddress(""));

        // 8. Save upgraded member (now FM)
        MemberData finalData = adminMemberPort.saveMember(savedMember);

        log.info("Virtual member created with GRABLIST: userAccountId={}, email={}, nickname={}, authorityTier={}",
                userAccountId,
                virtualEmail,
                profile.getNicknameValue(),
                finalData.getAuthorityTier());

        return finalData;
    }

    /**
     * Update avatar for existing virtual member
     *
     * @param userId User ID of virtual member
     * @param avatarBodyUri New avatar body URI (optional)
     * @param avatarFaceUri New avatar face URI (optional)
     * @return Updated member
     */
    @Transactional
    public MemberData updateVirtualMemberAvatar(
            UserId userId,
            AvatarBodyUri avatarBodyUri,
            AvatarFaceUri avatarFaceUri) {

        // 1. Find member
        MemberData member = findMemberByUserId(userId);

        // 2. Verify it's a virtual member (LOCAL provider type on UserAccount)
        requireLocalProviderForVirtualMemberOp(member, AdminException.NON_VIRTUAL_MEMBER_AVATAR_UPDATE);

        // 3. Create new profile with updated avatar
        ProfileData updatedProfile = adminProfileService.createProfileForVirtualMember(
                userId,
                member.getProfileData().getNicknameValue(),  // Keep existing nickname
                avatarBodyUri,
                avatarFaceUri
        );

        // 4. Update member with new profile
        member.initializeProfile(updatedProfile);

        // 5. Save and return
        MemberData savedData = adminMemberPort.saveMember(member);

        log.info("Virtual member avatar updated: userId={}", userId.getUid());

        return savedData;
    }

    /**
     * Delete virtual member.
     * Only LOCAL provider type members (virtual members) can be deleted.
     *
     * @param userId User ID to delete
     */
    @Transactional
    public void deleteVirtualMember(UserId userId) {
        // 1. Find member
        MemberData member = findMemberByUserId(userId);

        // 2. Verify it's a virtual member
        requireLocalProviderForVirtualMemberOp(member, AdminException.NON_VIRTUAL_MEMBER_DELETE);

        // 3. Delete
        adminMemberPort.deleteMemberById(userId.getUid());

        log.info("Virtual member deleted: userId={}", userId.getUid());
    }

    /**
     * Get virtual member by user ID
     *
     * @param userId User ID
     * @return Virtual member
     */
    @Transactional(readOnly = true)
    public MemberData getVirtualMember(UserId userId) {
        MemberData member = findMemberByUserId(userId);

        requireLocalProviderForVirtualMemberOp(member, AdminException.NOT_VIRTUAL_MEMBER);

        return member;
    }

    /**
     * Find member by user ID
     *
     * @param userId User ID
     * @return MemberData entity
     */
    private MemberData findMemberByUserId(UserId userId) {
        return adminMemberPort.findMemberById(userId.getUid())
                .orElseThrow(() -> ExceptionCreator.create(AdminException.MEMBER_NOT_FOUND));
    }

    /**
     * Verify the given member's UserAccount uses {@link ProviderType#LOCAL}
     * (i.e. is a virtual member). Throws the supplied {@link AdminException}
     * otherwise.
     *
     * <p>Member no longer carries {@code providerType} (Task 5 moved IAM
     * identity to UserAccount); the check now resolves via
     * {@link UserAccountRepository}. The extra fetch is acceptable given
     * admin-only call frequency.
     */
    private void requireLocalProviderForVirtualMemberOp(MemberData member, AdminException onMismatch) {
        UserAccountData userAccount = userAccountRepository.findById(new UserId(member.getUserAccountId()))
                .orElseThrow(() -> new IllegalStateException(
                        "UserAccount missing for member " + member.getMemberId()));
        if (userAccount.getProviderType() != ProviderType.LOCAL) {
            throw ExceptionCreator.create(onMismatch);
        }
    }

    /**
     * Generate unique email for virtual member
     * Pattern: virtual_{uuid}@pfplay.system
     *
     * @return Generated email address
     */
    private String generateVirtualEmail() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String email = "virtual_" + uuid + "@pfplay.system";

        // Check if email already exists (should be extremely rare)
        if (adminMemberPort.findMemberByEmail(email).isPresent()) {
            // Recursive call to generate another email
            return generateVirtualEmail();
        }

        return email;
    }
}
