package com.pfplaybackend.api.auth.application.service;

import com.pfplaybackend.api.auth.application.dto.command.OAuthLoginCommand;
import com.pfplaybackend.api.auth.application.dto.oauth.OAuthTokenDto;
import com.pfplaybackend.api.auth.application.dto.oauth.OAuthUserProfileDto;
import com.pfplaybackend.api.auth.application.dto.result.AuthResult;
import com.pfplaybackend.api.auth.application.port.out.StateStorePort;
import com.pfplaybackend.api.auth.domain.enums.OAuthProvider;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.AuthenticationException;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.service.MemberSignService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock OAuthClientService oAuthClientService;
    @Mock MemberSignService memberSignService;
    @Mock UserAccountRepository userAccountRepository;
    @Mock JwtService jwtService;
    @Mock StateStorePort stateStorePort;
    @Mock Clock clock;

    @InjectMocks AuthService authService;

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        lenient().when(clock.millis()).thenReturn(1735689600000L);
    }

    @Test
    @DisplayName("Google OAuth 로그인 성공 시 UserAccount의 이메일을 클레임에 담아 JWT를 발급한다")
    void processOAuthLoginGoogleSuccess() {
        // given
        OAuthLoginCommand command = new OAuthLoginCommand("google", "auth-code", "verifier");

        OAuthTokenDto tokenResponse = new OAuthTokenDto("access-token", "Bearer", 3600, null, "email");
        when(oAuthClientService.exchangeCodeForToken(OAuthProvider.GOOGLE, "auth-code", "verifier"))
                .thenReturn(tokenResponse);

        OAuthUserProfileDto userProfile = new OAuthUserProfileDto("google-id", "test@gmail.com", "Test User", null);
        when(oAuthClientService.getUserProfile(OAuthProvider.GOOGLE, "access-token"))
                .thenReturn(userProfile);

        MemberData member = mock(MemberData.class);
        when(member.getUserAccountId()).thenReturn(1L);
        when(member.getAuthorityTier()).thenReturn(AuthorityTier.FM);
        when(memberSignService.getMemberOrCreate("test@gmail.com", ProviderType.GOOGLE))
                .thenReturn(member);

        UserAccountData userAccount = mock(UserAccountData.class);
        when(userAccount.getEmail()).thenReturn("test@gmail.com");
        when(userAccountRepository.findByUserId(any(UserId.class)))
                .thenReturn(Optional.of(userAccount));

        when(jwtService.generateAccessToken(any())).thenReturn("jwt-token");
        when(jwtService.getAccessTokenExpiration()).thenReturn(3600L);

        // when
        AuthResult result = authService.processOAuthLogin(command);

        // then
        assertThat(result.accessToken()).isEqualTo("jwt-token");
        assertThat(result.tokenType()).isEqualTo("Cookie");
        assertThat(result.expiresIn()).isEqualTo(3600L);
        assertThat(result.issuedAt()).isNotNull();
    }

    @Test
    @DisplayName("UserAccount가 누락되면 IllegalStateException이 AuthenticationException으로 래핑된다")
    void processOAuthLoginMissingUserAccountThrowsAuthenticationException() {
        // given
        OAuthLoginCommand command = new OAuthLoginCommand("google", "auth-code", "verifier");

        OAuthTokenDto tokenResponse = new OAuthTokenDto("access-token", "Bearer", 3600, null, "email");
        when(oAuthClientService.exchangeCodeForToken(OAuthProvider.GOOGLE, "auth-code", "verifier"))
                .thenReturn(tokenResponse);

        OAuthUserProfileDto userProfile = new OAuthUserProfileDto("google-id", "test@gmail.com", "Test User", null);
        when(oAuthClientService.getUserProfile(OAuthProvider.GOOGLE, "access-token"))
                .thenReturn(userProfile);

        MemberData member = mock(MemberData.class);
        when(member.getUserAccountId()).thenReturn(99L);
        when(memberSignService.getMemberOrCreate("test@gmail.com", ProviderType.GOOGLE))
                .thenReturn(member);

        when(userAccountRepository.findByUserId(any(UserId.class)))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> authService.processOAuthLogin(command))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("Authentication failed");
    }

    @Test
    @DisplayName("OAuth 토큰 교환 실패 시 AuthenticationException이 발생한다")
    void processOAuthLoginTokenExchangeFailsThrowsException() {
        // given
        OAuthLoginCommand command = new OAuthLoginCommand("google", "bad-code", "verifier");
        when(oAuthClientService.exchangeCodeForToken(OAuthProvider.GOOGLE, "bad-code", "verifier"))
                .thenThrow(new RuntimeException("Token exchange failed"));

        // when & then
        assertThatThrownBy(() -> authService.processOAuthLogin(command))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("Authentication failed");
    }

    @Test
    @DisplayName("validateToken은 JwtService에 위임한다")
    void validateTokenDelegatesToJwtService() {
        // given
        when(jwtService.validateAccessToken("valid-token")).thenReturn(true);
        when(jwtService.validateAccessToken("invalid-token")).thenReturn(false);

        // when & then
        assertThat(authService.validateToken("valid-token")).isTrue();
        assertThat(authService.validateToken("invalid-token")).isFalse();
    }
}
