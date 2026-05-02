package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.service.UserProfileCommandService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces V5-seeded super-admin placeholder credentials with env-supplied
 * email + bcrypt-hashed password, and ensures the super-admin Member row has
 * a profile + default avatar attached. Both finalize methods run once at
 * ApplicationReadyEvent and are idempotent on re-boot.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SuperAdminSeedService {

    static final String PLACEHOLDER_EMAIL = "__SUPER_ADMIN_PLACEHOLDER_EMAIL__";
    static final String ADMIN_SEED_EMAIL_KEY = "ADMIN_SEED_EMAIL";
    static final String ADMIN_SEED_PASSWORD_KEY = "ADMIN_SEED_PASSWORD";
    private static final long SUPER_ADMIN_USER_ID = 1L;

    private final UserAccountRepository userAccountRepository;
    private final MemberRepository memberRepository;
    private final UserProfileCommandService userProfileCommandService;
    private final Environment environment;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void finalizeSuperAdminCredentials() {
        var placeholder = userAccountRepository.findByEmail(PLACEHOLDER_EMAIL);
        if (placeholder.isEmpty()) {
            log.info("Super-admin placeholder absent; no-op (already replaced).");
            return;
        }

        String seedEmail = environment.getProperty(ADMIN_SEED_EMAIL_KEY);
        String seedPassword = environment.getProperty(ADMIN_SEED_PASSWORD_KEY);

        if (seedEmail == null || seedEmail.isBlank()) {
            log.error("ADMIN_SEED_EMAIL is not set; cannot finalize super-admin credentials.");
            throw new IllegalStateException("ADMIN_SEED_EMAIL must be set in environment.");
        }
        if (seedPassword == null || seedPassword.isBlank()) {
            log.error("ADMIN_SEED_PASSWORD is not set; cannot finalize super-admin credentials.");
            throw new IllegalStateException("ADMIN_SEED_PASSWORD must be set in environment.");
        }

        String hash = passwordEncoder.encode(seedPassword);
        // Memory hygiene: drop the plaintext reference immediately after bcrypt.
        // (Java Strings are immutable; we cannot zero out the underlying chars.
        //  Spring's Environment returns String, so char[] is not an option here.
        //  Best-effort: reassign to null and let GC reclaim.)
        //noinspection UnusedAssignment
        seedPassword = null;

        UserAccountData admin = placeholder.get();
        admin.replacePlaceholderCredentials(seedEmail, hash);
        // JPA dirty-flush within @Transactional persists the change.

        log.info("Super-admin credentials finalized for user_id=1.");
    }

    /**
     * Attaches a default profile + avatar to the V5-seeded super-admin Member row
     * if not already attached. Without this, member.profile_id remains NULL and
     * any cross-BC query that path-projects {@code m.profileData.bio.nickname}
     * silently degrades to an INNER JOIN that drops admin-hosted partyrooms
     * (B-1 admin list, etc.).
     */
    @Transactional
    public void finalizeSuperAdminProfile() {
        MemberData admin = memberRepository.findByUserAccountId(SUPER_ADMIN_USER_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "Super-admin member row missing — V5 seed not run."));
        if (admin.getProfileData() != null) {
            log.info("Super-admin profile already initialized; no-op.");
            return;
        }
        ProfileData profile = userProfileCommandService.createProfileDataForSuperAdmin(
                new UserId(SUPER_ADMIN_USER_ID));
        admin.initializeProfile(profile);
        memberRepository.save(admin);
        log.info("Super-admin profile + default avatar initialized for member_id=1.");
    }
}
