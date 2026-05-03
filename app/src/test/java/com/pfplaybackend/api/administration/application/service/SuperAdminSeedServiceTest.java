package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.service.UserProfileCommandService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.value.Nickname;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminSeedServiceTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock MemberRepository memberRepository;
    @Mock UserProfileCommandService userProfileCommandService;
    @Mock Environment environment;
    @Mock PasswordEncoder passwordEncoder;

    @InjectMocks SuperAdminSeedService service;

    @Test
    void finalizeSuperAdminCredentials_replacesPlaceholderWithEnvValues() {
        var placeholder = UserAccountData.createForLocal(
            new UserId(1L),
            "__SUPER_ADMIN_PLACEHOLDER_EMAIL__",
            "__SUPER_ADMIN_PLACEHOLDER_HASH__");
        when(userAccountRepository.findByEmail("__SUPER_ADMIN_PLACEHOLDER_EMAIL__"))
            .thenReturn(Optional.of(placeholder));
        when(environment.getProperty("ADMIN_SEED_EMAIL")).thenReturn("admin@pfplay.com");
        when(environment.getProperty("ADMIN_SEED_PASSWORD")).thenReturn("plain-password");
        when(passwordEncoder.encode("plain-password")).thenReturn("$2a$12$encoded...");

        service.finalizeSuperAdminCredentials();

        assertThat(placeholder.getEmail()).isEqualTo("admin@pfplay.com");
        assertThat(placeholder.getPasswordHash()).isEqualTo("$2a$12$encoded...");
    }

    @Test
    void finalizeSuperAdminCredentials_isNoOpWhenPlaceholderAbsent() {
        when(userAccountRepository.findByEmail("__SUPER_ADMIN_PLACEHOLDER_EMAIL__"))
            .thenReturn(Optional.empty());

        service.finalizeSuperAdminCredentials();

        verifyNoInteractions(environment, passwordEncoder);
    }

    @Test
    void finalizeSuperAdminCredentials_throwsWhenAdminSeedEmailMissing() {
        var placeholder = UserAccountData.createForLocal(
            new UserId(1L),
            "__SUPER_ADMIN_PLACEHOLDER_EMAIL__",
            "__SUPER_ADMIN_PLACEHOLDER_HASH__");
        when(userAccountRepository.findByEmail("__SUPER_ADMIN_PLACEHOLDER_EMAIL__"))
            .thenReturn(Optional.of(placeholder));
        when(environment.getProperty("ADMIN_SEED_EMAIL")).thenReturn(null);

        assertThatThrownBy(() -> service.finalizeSuperAdminCredentials())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ADMIN_SEED_EMAIL");
    }

    @Test
    void finalizeSuperAdminCredentials_throwsWhenAdminSeedPasswordMissing() {
        var placeholder = UserAccountData.createForLocal(
            new UserId(1L),
            "__SUPER_ADMIN_PLACEHOLDER_EMAIL__",
            "__SUPER_ADMIN_PLACEHOLDER_HASH__");
        when(userAccountRepository.findByEmail("__SUPER_ADMIN_PLACEHOLDER_EMAIL__"))
            .thenReturn(Optional.of(placeholder));
        when(environment.getProperty("ADMIN_SEED_EMAIL")).thenReturn("admin@pfplay.com");
        when(environment.getProperty("ADMIN_SEED_PASSWORD")).thenReturn(null);

        assertThatThrownBy(() -> service.finalizeSuperAdminCredentials())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ADMIN_SEED_PASSWORD");
    }

    @Test
    void finalizeSuperAdminProfile_attachesProfileWhenAbsent() {
        MemberData admin = MemberData.createForUserAccount(1L);
        ProfileData profile = ProfileData.builder()
                .userId(new UserId(1L))
                .nickname(new Nickname("Super Admin"))
                .build();
        when(memberRepository.findByUserAccountId(1L)).thenReturn(Optional.of(admin));
        when(userProfileCommandService.createProfileDataForSuperAdmin(any(UserId.class)))
                .thenReturn(profile);

        service.finalizeSuperAdminProfile();

        ArgumentCaptor<MemberData> savedCaptor = ArgumentCaptor.forClass(MemberData.class);
        verify(memberRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getProfileData()).isSameAs(profile);
        assertThat(savedCaptor.getValue().getProfileData().getNicknameValue()).isEqualTo("Super Admin");
    }

    @Test
    void finalizeSuperAdminProfile_isNoOpWhenProfileAlreadyAttached() {
        MemberData admin = MemberData.createForUserAccount(1L);
        ProfileData existing = ProfileData.builder()
                .userId(new UserId(1L))
                .nickname(new Nickname("Existing"))
                .build();
        admin.initializeProfile(existing);
        when(memberRepository.findByUserAccountId(1L)).thenReturn(Optional.of(admin));

        service.finalizeSuperAdminProfile();

        verify(userProfileCommandService, never()).createProfileDataForSuperAdmin(any());
        verify(memberRepository, never()).save(any());
    }

    @Test
    void finalizeSuperAdminProfile_throwsWhenAdminMemberRowMissing() {
        when(memberRepository.findByUserAccountId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finalizeSuperAdminProfile())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V5 seed");
    }
}
