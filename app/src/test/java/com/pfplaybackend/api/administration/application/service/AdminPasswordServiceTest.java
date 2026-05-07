package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.application.util.AdminPasswordPolicy;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminPasswordServiceTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AdminPasswordPolicy policy;
    @InjectMocks AdminPasswordService service;

    @Test
    void changePassword_validCurrent_clearsMustChangeFlag() {
        UserId userId = new UserId(42L);
        UserAccountData ua = mock(UserAccountData.class);
        given(ua.getPasswordHash()).willReturn("OLD_HASH");
        given(userAccountRepository.findById(userId)).willReturn(Optional.of(ua));
        given(passwordEncoder.matches("oldPwd", "OLD_HASH")).willReturn(true);
        given(passwordEncoder.encode("NewP@ssw0rd1")).willReturn("BCRYPT-NEW");

        service.changePassword(userId, "oldPwd", "NewP@ssw0rd1");

        verify(policy).requireValid("NewP@ssw0rd1");
        verify(ua).completePasswordChange("BCRYPT-NEW");
    }

    @Test
    void changePassword_userNotFound_throwsNotFound() {
        UserId userId = new UserId(42L);
        given(userAccountRepository.findById(userId)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.changePassword(userId, "old", "New"))
                .satisfies(ex -> assertThat(ex.getClass().getSimpleName()).contains("NotFound"));
    }

    @Test
    void changePassword_wrongCurrentPassword_throwsUnauthorized() {
        UserId userId = new UserId(42L);
        UserAccountData ua = mock(UserAccountData.class);
        given(ua.getPasswordHash()).willReturn("OLD_HASH");
        given(userAccountRepository.findById(userId)).willReturn(Optional.of(ua));
        given(passwordEncoder.matches("wrong", "OLD_HASH")).willReturn(false);

        assertThatThrownBy(() -> service.changePassword(userId, "wrong", "NewP@ssw0rd1"))
                .satisfies(ex -> assertThat(ex.getClass().getSimpleName()).contains("Unauthorized"));
    }

    @Test
    void changePassword_weakNewPassword_throwsBadRequest() {
        UserId userId = new UserId(42L);
        UserAccountData ua = mock(UserAccountData.class);
        given(ua.getPasswordHash()).willReturn("OLD_HASH");
        given(userAccountRepository.findById(userId)).willReturn(Optional.of(ua));
        given(passwordEncoder.matches("oldPwd", "OLD_HASH")).willReturn(true);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("too weak"))
                .when(policy).requireValid("weak");

        assertThatThrownBy(() -> service.changePassword(userId, "oldPwd", "weak"))
                .satisfies(ex -> assertThat(ex.getClass().getSimpleName()).contains("BadRequest"));
    }
}
