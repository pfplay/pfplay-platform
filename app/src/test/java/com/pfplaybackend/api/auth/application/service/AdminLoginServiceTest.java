package com.pfplaybackend.api.auth.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.value.AdminRole;
import com.pfplaybackend.api.auth.application.dto.command.AdminLoginCommand;
import com.pfplaybackend.api.auth.application.dto.result.AdminAuthResult;
import com.pfplaybackend.api.auth.application.ratelimit.AdminLoginRateLimiter;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.UnauthorizedException;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminLoginServiceTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock AdministratorRepository administratorRepository;
    @Mock MemberRepository memberRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock AdminLoginRateLimiter rateLimiter;
    @Mock JwtProperties jwtProperties;

    Clock clock = Clock.systemUTC();

    AdminLoginService sut;

    @BeforeEach
    void setup() {
        sut = new AdminLoginService(
                userAccountRepository, administratorRepository, memberRepository,
                passwordEncoder, jwtService, rateLimiter, jwtProperties, clock);
    }

    @Test
    void unknown_email_throws_invalid_credentials() {
        when(userAccountRepository.findByEmailAndProviderType("ghost@x.com", ProviderType.LOCAL))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.login(new AdminLoginCommand("ghost@x.com", "any", "1.1.1.1")))
                .isInstanceOf(UnauthorizedException.class);
        verify(rateLimiter).checkOrThrow("1.1.1.1", "ghost@x.com");
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void wrong_password_throws_invalid_credentials() {
        UserAccountData ua = stubLocalAccount(42L, "admin@x.com", "$2a$12$hashedhashedhashedhashed");
        when(userAccountRepository.findByEmailAndProviderType("admin@x.com", ProviderType.LOCAL))
                .thenReturn(Optional.of(ua));
        when(passwordEncoder.matches("wrong", ua.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> sut.login(new AdminLoginCommand("admin@x.com", "wrong", "1.1.1.1")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void revoked_admin_throws_account_revoked() {
        UserAccountData ua = stubLocalAccount(42L, "admin@x.com", "$2a$12$h");
        AdministratorData adm = stubRevokedAdmin();
        when(userAccountRepository.findByEmailAndProviderType("admin@x.com", ProviderType.LOCAL))
                .thenReturn(Optional.of(ua));
        when(passwordEncoder.matches("right", ua.getPasswordHash())).thenReturn(true);
        when(administratorRepository.findByUserAccountId(42L)).thenReturn(Optional.of(adm));

        assertThatThrownBy(() -> sut.login(new AdminLoginCommand("admin@x.com", "right", "1.1.1.1")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void successful_login_without_member_issues_admin_token_only() {
        UserAccountData ua = stubLocalAccount(42L, "admin@x.com", "$2a$12$h");
        AdministratorData adm = stubActiveAdmin(AdminRole.ADMIN);
        when(userAccountRepository.findByEmailAndProviderType("admin@x.com", ProviderType.LOCAL))
                .thenReturn(Optional.of(ua));
        when(passwordEncoder.matches("right", ua.getPasswordHash())).thenReturn(true);
        when(administratorRepository.findByUserAccountId(42L)).thenReturn(Optional.of(adm));
        when(memberRepository.findByUserAccountId(42L)).thenReturn(Optional.empty());
        when(jwtService.mintAdminAccessToken(any())).thenReturn("admin-jwt");
        when(jwtProperties.getAdminAccessTokenExpirationMs()).thenReturn(900_000L);

        AdminAuthResult res = sut.login(new AdminLoginCommand("admin@x.com", "right", "1.1.1.1"));

        assertThat(res.adminAccessToken()).isEqualTo("admin-jwt");
        assertThat(res.sharedSessionToken()).isNull();
        verify(rateLimiter).onLoginSuccess("admin@x.com");
        verify(jwtService, never()).mintSharedSessionToken(any());
    }

    @Test
    void successful_login_with_member_issues_both_tokens() {
        UserAccountData ua = stubLocalAccount(42L, "admin@x.com", "$2a$12$h");
        AdministratorData adm = stubActiveAdmin(AdminRole.SUPER_ADMIN);
        MemberData mem = stubMember(42L);
        when(userAccountRepository.findByEmailAndProviderType("admin@x.com", ProviderType.LOCAL))
                .thenReturn(Optional.of(ua));
        when(passwordEncoder.matches("right", ua.getPasswordHash())).thenReturn(true);
        when(administratorRepository.findByUserAccountId(42L)).thenReturn(Optional.of(adm));
        when(memberRepository.findByUserAccountId(42L)).thenReturn(Optional.of(mem));
        when(jwtService.mintAdminAccessToken(any())).thenReturn("admin-jwt");
        when(jwtService.mintSharedSessionToken(any())).thenReturn("shared-jwt");
        when(jwtProperties.getAdminAccessTokenExpirationMs()).thenReturn(900_000L);
        when(jwtProperties.getSharedSessionTokenExpirationMs()).thenReturn(86_400_000L);

        AdminAuthResult res = sut.login(new AdminLoginCommand("admin@x.com", "right", "1.1.1.1"));

        assertThat(res.adminAccessToken()).isEqualTo("admin-jwt");
        assertThat(res.sharedSessionToken()).isEqualTo("shared-jwt");
        assertThat(res.role()).isEqualTo(AdminRole.SUPER_ADMIN);
    }

    private UserAccountData stubLocalAccount(long id, String email, String passwordHash) {
        UserAccountData ua = mock(UserAccountData.class);
        lenient().when(ua.getUserId()).thenReturn(new UserId(id));
        lenient().when(ua.getEmail()).thenReturn(email);
        lenient().when(ua.getPasswordHash()).thenReturn(passwordHash);
        return ua;
    }

    private AdministratorData stubActiveAdmin(AdminRole role) {
        AdministratorData a = mock(AdministratorData.class);
        when(a.getRole()).thenReturn(role);
        when(a.isRevoked()).thenReturn(false);
        return a;
    }

    private AdministratorData stubRevokedAdmin() {
        AdministratorData a = mock(AdministratorData.class);
        when(a.isRevoked()).thenReturn(true);
        return a;
    }

    private MemberData stubMember(long userAccountId) {
        MemberData m = mock(MemberData.class);
        return m;
    }
}
