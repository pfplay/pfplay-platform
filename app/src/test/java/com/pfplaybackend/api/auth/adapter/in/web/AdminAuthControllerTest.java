package com.pfplaybackend.api.auth.adapter.in.web;

import com.pfplaybackend.api.administration.domain.value.AdminRole;
import com.pfplaybackend.api.auth.application.dto.result.AdminAuthResult;
import com.pfplaybackend.api.auth.application.service.AdminLoginService;
import com.pfplaybackend.api.auth.domain.exception.AdminAuthException;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import jakarta.servlet.http.HttpServletResponse;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminAuthControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean AdminLoginService adminLoginService;
    @MockBean AdminCookieWriter adminCookieWriter;
    @MockBean SharedSessionCookieWriter sharedSessionCookieWriter;
    @MockBean JwtDecoder jwtDecoder;

    private static final String VALID_BODY = """
            {"email":"super@pfplay.local","password":"DevSeed123!"}
            """;

    @Test
    @DisplayName("login — 200 + admin cookie when no Member linked")
    void login_success_admin_only() throws Exception {
        when(adminLoginService.login(any())).thenReturn(new AdminAuthResult(
                "admin-jwt", null, AdminRole.SUPER_ADMIN,
                900_000L, 0L, LocalDateTime.now()));

        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.expiresIn").value(900));

        verify(adminCookieWriter).write(any(HttpServletResponse.class), eq("admin-jwt"));
        verifyNoInteractions(sharedSessionCookieWriter);
    }

    @Test
    @DisplayName("login — 200 + both cookies when Member linked")
    void login_success_with_member_writes_both_cookies() throws Exception {
        when(adminLoginService.login(any())).thenReturn(new AdminAuthResult(
                "admin-jwt", "shared-jwt", AdminRole.ADMIN,
                900_000L, 86_400_000L, LocalDateTime.now()));

        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());

        verify(adminCookieWriter).write(any(HttpServletResponse.class), eq("admin-jwt"));
        verify(sharedSessionCookieWriter).write(any(HttpServletResponse.class), eq("shared-jwt"));
    }

    @Test
    @DisplayName("login — 401 on invalid credentials")
    void login_invalid_credentials_returns_401() throws Exception {
        when(adminLoginService.login(any()))
                .thenThrow(ExceptionCreator.create(AdminAuthException.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ghost@x.com","password":"wrongpass"}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminCookieWriter, sharedSessionCookieWriter);
    }

    @Test
    @DisplayName("login — 429 when rate-limited")
    void login_rate_limited_returns_429() throws Exception {
        when(adminLoginService.login(any()))
                .thenThrow(ExceptionCreator.create(AdminAuthException.RATE_LIMITED));

        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("login — 400 when @Valid rejects empty password")
    void login_validation_rejects_blank_password() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"super@pfplay.local","password":""}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adminLoginService);
    }

    @Test
    @DisplayName("logout — 204 + clears both cookies")
    void logout_clears_both_cookies() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/logout"))
                .andExpect(status().isNoContent());

        verify(adminCookieWriter).clear(any(HttpServletResponse.class));
        verify(sharedSessionCookieWriter).clear(any(HttpServletResponse.class));
    }
}
