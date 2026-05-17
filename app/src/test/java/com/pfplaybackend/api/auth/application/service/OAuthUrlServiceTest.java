package com.pfplaybackend.api.auth.application.service;

import com.pfplaybackend.api.auth.adapter.out.external.config.OAuth2Properties;
import com.pfplaybackend.api.auth.application.dto.result.OAuthUrlResult;
import com.pfplaybackend.api.auth.application.port.out.StateStorePort;
import com.pfplaybackend.api.auth.domain.enums.OAuthProvider;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OAuthUrlServiceTest {

    private static final String GOOGLE = "google";
    private static final String TEST_STATE = "test-state";
    private static final String TEST_VERIFIER = "test-verifier";
    private static final String TWITTER = "twitter";
    private static final String VERIFIER = "verifier";

    @Mock private OAuth2Properties oAuth2Properties;
    @Mock private StateStorePort stateStorePort;

    @InjectMocks
    private OAuthUrlService oAuthUrlService;

    private OAuth2Properties.Provider googleConfig;

    @BeforeEach
    void setUp() {
        googleConfig = new OAuth2Properties.Provider();
        googleConfig.setClientId("test-client-id");
        googleConfig.setRedirectUri("http://localhost/callback");
        googleConfig.setAuthorizationUri("https://accounts.google.com/o/oauth2/auth");
        googleConfig.setScopes(java.util.List.of("openid", "email", "profile"));
    }

    @Test
    @DisplayName("generateAuthUrl - StateStore를 통해 state를 생성해야 한다")
    void generateAuthUrlShouldUseStateStore() {
        // given
        when(oAuth2Properties.getProviders()).thenReturn(Map.of(GOOGLE, googleConfig));
        when(stateStorePort.generateAndStoreState(GOOGLE)).thenReturn(TEST_STATE);

        // when
        OAuthUrlResult result = oAuthUrlService.generateAuthUrl(OAuthProvider.GOOGLE, TEST_VERIFIER);

        // then
        verify(stateStorePort, times(1)).generateAndStoreState(GOOGLE);
        assertThat(result.state()).isEqualTo(TEST_STATE);
        assertThat(result.authUrl()).contains("state=test-state");
    }

    @Test
    @DisplayName("validateAndConsumeState - StateStore를 통해 검증해야 한다")
    void validateAndConsumeStateShouldDelegateToStateStore() {
        // given
        when(stateStorePort.validateAndConsumeState(TEST_STATE, GOOGLE)).thenReturn(true);

        // when
        boolean result = oAuthUrlService.validateAndConsumeState(TEST_STATE, OAuthProvider.GOOGLE, VERIFIER);

        // then
        verify(stateStorePort, times(1)).validateAndConsumeState(TEST_STATE, GOOGLE);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("validateAndConsumeState - 잘못된 state는 false를 반환해야 한다")
    void validateAndConsumeStateShouldReturnFalseWhenInvalid() {
        // given
        when(stateStorePort.validateAndConsumeState("invalid-state", GOOGLE)).thenReturn(false);

        // when
        boolean result = oAuthUrlService.validateAndConsumeState("invalid-state", OAuthProvider.GOOGLE, VERIFIER);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("generateAuthUrl — Twitter URL이 올바르게 생성된다")
    void generateAuthUrlTwitterBuildsCorrectUrl() {
        // given
        OAuth2Properties.Provider twitterConfig = new OAuth2Properties.Provider();
        twitterConfig.setClientId("twitter-client-id");
        twitterConfig.setRedirectUri("http://localhost/twitter/callback");
        twitterConfig.setAuthorizationUri("https://twitter.com/i/oauth2/authorize");
        twitterConfig.setScopes(java.util.List.of("tweet.read", "users.read"));

        when(oAuth2Properties.getProviders()).thenReturn(Map.of(TWITTER, twitterConfig));
        when(stateStorePort.generateAndStoreState(TWITTER)).thenReturn("twitter-state");

        // when
        OAuthUrlResult result = oAuthUrlService.generateAuthUrl(OAuthProvider.TWITTER, TEST_VERIFIER);

        // then
        assertThat(result.authUrl())
                .contains("https://twitter.com/i/oauth2/authorize")
                .contains("client_id=twitter-client-id")
                .contains("state=twitter-state")
                .contains("code_challenge=")
                .contains("code_challenge_method=S256");
        assertThat(result.provider()).isEqualTo(TWITTER);
    }

    @Test
    @DisplayName("generateAuthUrl — Google에 access_type과 prompt가 포함된다")
    void generateAuthUrlGoogleWithAccessTypeAndPrompt() {
        // given
        googleConfig.setAccessType("offline");
        googleConfig.setPrompt("consent");

        when(oAuth2Properties.getProviders()).thenReturn(Map.of(GOOGLE, googleConfig));
        when(stateStorePort.generateAndStoreState(GOOGLE)).thenReturn("google-state");

        // when
        OAuthUrlResult result = oAuthUrlService.generateAuthUrl(OAuthProvider.GOOGLE, TEST_VERIFIER);

        // then
        assertThat(result.authUrl())
                .contains("access_type=offline")
                .contains("prompt=consent");
    }

    @Test
    @DisplayName("generateAuthUrl — Google에 access_type/prompt가 null이면 포함되지 않는다")
    void generateAuthUrlGoogleNullAccessTypePromptExcludedFromUrl() {
        // given
        googleConfig.setAccessType(null);
        googleConfig.setPrompt(null);

        when(oAuth2Properties.getProviders()).thenReturn(Map.of(GOOGLE, googleConfig));
        when(stateStorePort.generateAndStoreState(GOOGLE)).thenReturn("google-state");

        // when
        OAuthUrlResult result = oAuthUrlService.generateAuthUrl(OAuthProvider.GOOGLE, TEST_VERIFIER);

        // then
        assertThat(result.authUrl())
                .doesNotContain("access_type=")
                .doesNotContain("prompt=");
    }

    @Test
    @DisplayName("generateAuthUrl — Twitter scope는 공백을 %20으로 인코딩한다 (리터럴 + 금지: X가 +를 리터럴 처리해 무효 스코프가 되어 /2/users/me 403)")
    void generateAuthUrlTwitterScopeIsSpaceEncodedNotPlus() {
        // given
        OAuth2Properties.Provider twitterConfig = new OAuth2Properties.Provider();
        twitterConfig.setClientId("twitter-client-id");
        twitterConfig.setRedirectUri("http://localhost/twitter/callback");
        twitterConfig.setAuthorizationUri("https://twitter.com/i/oauth2/authorize");
        twitterConfig.setScopes(java.util.List.of("users.read", "tweet.read"));

        when(oAuth2Properties.getProviders()).thenReturn(Map.of(TWITTER, twitterConfig));
        when(stateStorePort.generateAndStoreState(TWITTER)).thenReturn("twitter-state");

        // when
        OAuthUrlResult result = oAuthUrlService.generateAuthUrl(OAuthProvider.TWITTER, TEST_VERIFIER);

        // then
        assertThat(result.authUrl())
                .contains("scope=users.read%20tweet.read")
                .doesNotContain("scope=users.read+tweet.read");
    }

    @Test
    @DisplayName("generateAuthUrl — 설정되지 않은 provider이면 예외가 발생한다")
    void generateAuthUrlUnconfiguredProviderThrows() {
        // given
        when(oAuth2Properties.getProviders()).thenReturn(Map.of());

        // when & then
        assertThatThrownBy(() -> oAuthUrlService.generateAuthUrl(OAuthProvider.GOOGLE, VERIFIER))
                .isInstanceOf(BadRequestException.class);
    }
}
