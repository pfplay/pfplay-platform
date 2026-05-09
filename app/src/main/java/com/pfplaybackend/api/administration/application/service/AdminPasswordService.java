package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.application.util.AdminPasswordPolicy;
import com.pfplaybackend.api.administration.domain.exception.AdministratorManagementException;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminPasswordService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminPasswordPolicy policy;

    @Transactional
    public void changePassword(UserId userId, String currentPassword, String newPassword) {
        UserAccountData ua = userAccountRepository.findById(userId)
                .orElseThrow(() -> ExceptionCreator.create(
                        AdministratorManagementException.NOT_FOUND));

        if (!passwordEncoder.matches(currentPassword, ua.getPasswordHash())) {
            log.info("admin_password.self_change.invalid_current user_id={}", userId.getUid());
            throw ExceptionCreator.create(
                    AdministratorManagementException.INVALID_CURRENT_PASSWORD);
        }

        try {
            policy.requireValid(newPassword);
        } catch (IllegalArgumentException e) {
            throw ExceptionCreator.create(
                    AdministratorManagementException.INVALID_NEW_PASSWORD);
        }

        ua.completePasswordChange(passwordEncoder.encode(newPassword));
        log.info("admin_password.self_change user_id={}", userId.getUid());
    }
}
