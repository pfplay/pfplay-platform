package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces V5-seeded super-admin placeholder credentials with env-supplied
 * email + bcrypt-hashed password. Runs once at ApplicationReadyEvent.
 * Idempotent: subsequent boots find no placeholder and no-op.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SuperAdminSeedService {

    static final String PLACEHOLDER_EMAIL = "__SUPER_ADMIN_PLACEHOLDER_EMAIL__";
    static final String ADMIN_SEED_EMAIL_KEY = "ADMIN_SEED_EMAIL";
    static final String ADMIN_SEED_PASSWORD_KEY = "ADMIN_SEED_PASSWORD";

    private final UserAccountRepository userAccountRepository;
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
}
