package com.pfplaybackend.api.auth.adapter.in.web;

import com.pfplaybackend.api.auth.application.dto.command.OAuthLoginCommand;
import com.pfplaybackend.api.auth.application.dto.result.AuthResult;
import com.pfplaybackend.api.auth.application.dto.result.OAuthUrlResult;
import com.pfplaybackend.api.auth.application.service.AuthService;
import com.pfplaybackend.api.auth.application.service.LogoutService;
import com.pfplaybackend.api.auth.application.service.OAuthUrlService;
import com.pfplaybackend.api.auth.domain.enums.OAuthProvider;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.AdminTokenRenewalFilter;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.config.security.web.AdminOriginGuardFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean OAuthUrlService oAuthUrlService;
    @MockBean AuthService authService;
    @MockBean LogoutService logoutService;
    @MockBean SharedSessionCookieWriter sharedSessionCookieWriter;
    @MockBean AdminCookieWriter adminCookieWriter;
    @MockBean JwtService jwtService;
    @MockBean JwtProperties jwtProperties;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean AdminTokenRenewalFilter adminTokenRenewalFilter;
    @MockBean AdminOriginGuardFilter adminOriginGuardFilter;

    @Test
    @DisplayName("generateOAuthUrl — 200 OK + URL 반환")
    void generateOAuthUrlReturns200() throws Exception {
        // given
        String codeVerifier = "a".repeat(43);
        String body = """
                {
                    "provider": "google",
                    "codeVerifier": "%s"
                }
                """.formatted(codeVerifier);
        when(oAuthUrlService.generateAuthUrl(any(OAuthProvider.class), anyString()))
                .thenReturn(new OAuthUrlResult("https://accounts.google.com/auth", "state123", "google", 300L));

        // when & then
        mockMvc.perform(post("/api/v1/auth/oauth/url")
                        .with(jwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authUrl").value("https://accounts.google.com/auth"));
    }

    @Test
    @DisplayName("logout — 204 No Content")
    void logoutReturns204() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ── oauthCallback ──

    @Test
    @DisplayName("oauthCallback — state 검증 성공 시 200 OK + 쿠키 설정")
    void oauthCallbackValidStateReturns200() throws Exception {
        // given
        String codeVerifier = "a".repeat(43);
        String body = """
                {
                    "provider": "google",
                    "code": "auth-code-123",
                    "codeVerifier": "%s",
                    "state": "valid-state"
                }
                """.formatted(codeVerifier);

        when(oAuthUrlService.validateAndConsumeState(eq("valid-state"), any(OAuthProvider.class), anyString()))
                .thenReturn(true);
        when(authService.processOAuthLogin(any(OAuthLoginCommand.class)))
                .thenReturn(new AuthResult("access-token", "Cookie", 3600L, LocalDateTime.now(), true));

        // when & then
        mockMvc.perform(post("/api/v1/auth/oauth/callback")
                        .with(jwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Cookie"))
                .andExpect(jsonPath("$.data.isNewUser").value(true));

        verify(sharedSessionCookieWriter).write(any(), eq("access-token"));
    }

    @Test
    @DisplayName("oauthCallback — 기존 사용자 재로그인 시 isNewUser=false (Amplitude L4)")
    void oauthCallbackReturningUserExposesIsNewUserFalse() throws Exception {
        // given — same call shape but service signals returning user
        String codeVerifier = "a".repeat(43);
        String body = """
                {
                    "provider": "google",
                    "code": "auth-code-123",
                    "codeVerifier": "%s",
                    "state": "valid-state"
                }
                """.formatted(codeVerifier);

        when(oAuthUrlService.validateAndConsumeState(eq("valid-state"), any(OAuthProvider.class), anyString()))
                .thenReturn(true);
        when(authService.processOAuthLogin(any(OAuthLoginCommand.class)))
                .thenReturn(new AuthResult("access-token", "Cookie", 3600L, LocalDateTime.now(), false));

        // when & then
        mockMvc.perform(post("/api/v1/auth/oauth/callback")
                        .with(jwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNewUser").value(false));
    }

    @Test
    @DisplayName("oauthCallback — state 검증 실패 시 400 Bad Request")
    void oauthCallbackInvalidStateReturns400() throws Exception {
        // given
        String codeVerifier = "a".repeat(43);
        String body = """
                {
                    "provider": "google",
                    "code": "auth-code-123",
                    "codeVerifier": "%s",
                    "state": "invalid-state"
                }
                """.formatted(codeVerifier);

        when(oAuthUrlService.validateAndConsumeState(eq("invalid-state"), any(OAuthProvider.class), anyString()))
                .thenReturn(false);

        // when & then
        mockMvc.perform(post("/api/v1/auth/oauth/callback")
                        .with(jwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("oauthCallback — state가 null이면 검증을 건너뛰고 로그인 처리")
    void oauthCallbackNullStateSkipsValidation() throws Exception {
        // given
        String codeVerifier = "a".repeat(43);
        String body = """
                {
                    "provider": "google",
                    "code": "auth-code-123",
                    "codeVerifier": "%s"
                }
                """.formatted(codeVerifier);

        when(authService.processOAuthLogin(any(OAuthLoginCommand.class)))
                .thenReturn(new AuthResult("access-token", "Cookie", 3600L, LocalDateTime.now(), false));

        // when & then
        mockMvc.perform(post("/api/v1/auth/oauth/callback")
                        .with(jwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Cookie"));

        verify(oAuthUrlService, never()).validateAndConsumeState(anyString(), any(), anyString());
    }
}
