package com.pfplaybackend.api.admin.application.service;

import com.pfplaybackend.api.admin.application.port.out.AdminMemberPort;
import com.pfplaybackend.api.admin.application.port.out.AdminPlaylistPort;
import com.pfplaybackend.api.admin.domain.exception.AdminException;
import com.pfplaybackend.api.administration.application.service.AdminMemberWithdrawCommandService;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
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
    private final AdminMemberWithdrawCommandService adminMemberWithdrawCommandService;

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

        // 5a. Initialize activity rows. Member no longer owns the activity
        //     collection — AdminMemberPort.createUserActivities now persists
        //     ActivityData rows directly via ActivityRepository (Task 11).
        adminMemberPort.createUserActivities(savedAccount.getUserId());

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

        // 3. MUTATE the existing profile row (same id) — never build+swap a new ProfileData.
        //    Replacing the @OneToOne(cascade=ALL) reference would cascade a SECOND transient
        //    user_profile INSERT (same user_id/nickname) → V15 uk_user_profile_nickname 위반(HTTP 500).
        //    create-drop 테스트 스키마엔 그 제약이 없어 silent 였다(reference_ddl_auto_create_drop_hides_migration_drift).
        //    nickname 은 mutate 하지 않으므로 그대로 보존된다.
        adminProfileService.applyAvatarToExistingMember(member, avatarBodyUri, avatarFaceUri);

        // 4. Save and return (UPDATE on the existing row).
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

        // 3. Clean up activity rows. Member no longer owns the activity
        //    collection as a JPA association (Task 5/11), so they don't
        //    cascade — clean up explicitly to avoid orphans.
        adminMemberPort.deleteUserActivities(userId);

        // 4. Delete
        adminMemberPort.deleteMemberById(userId.getUid());

        log.info("Virtual member deleted: userId={}", userId.getUid());
    }

    /**
     * 가상 회원을 탈퇴(soft-delete)한다 — 물리 삭제 대신 {@code withdrawn_at} 세팅 + 이메일 PII 비식별화.
     *
     * <p>가상 크루 봇 제거의 진입점. 봇 계정은 crew/dj/playlist/activity 등 여러 테이블이 참조하므로
     * 물리 삭제는 orphan/FK 위험이 크다. 대신 실회원과 동일한 검증된 탈퇴 경로
     * ({@link AdminMemberWithdrawCommandService#withdraw})를 재사용한다 — idempotent(재호출 무해),
     * adminId 기록, {@code UserAccountWithdrawnEvent} 발행을 모두 승계한다. 모든 봇 풀 조회가
     * {@code withdrawn_at IS NULL} 로 필터하므로 탈퇴 즉시 풀·로스터·배치 대상에서 사라진다.
     *
     * @param userId 가상 회원(봇)의 user_account_id
     */
    @Transactional
    public void withdrawVirtualMember(UserId userId) {
        // 1. 회원 조회(user_account_id) — 없으면 MEMBER_NOT_FOUND.
        MemberData member = findMemberByUserId(userId);

        // 2. LOCAL provider(가상 회원)인지 검증 — 실 소셜 회원 오탈퇴 방지.
        requireLocalProviderForVirtualMemberOp(member, AdminException.NON_VIRTUAL_MEMBER_DELETE);

        // 3. 검증된 탈퇴 명령에 위임(member_id 기준). idempotent — 이미 탈퇴면 no-op.
        adminMemberWithdrawCommandService.withdraw(member.getMemberId());

        log.info("Virtual member withdrawn (soft-delete): userId={}", userId.getUid());
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
        // 가상 멤버는 user_account_id 로 주소지정된다(어드민 가상멤버 도구·봇 로스터 모두 user_account_id 노출).
        // member_id(IDENTITY) ≠ user_account_id 이므로 user_account_id 로 조회해야 한다(코드베이스 관례 동일).
        return adminMemberPort.findMemberByUserAccountId(userId.getUid())
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
