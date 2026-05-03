package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.PartyroomReportCreateRequest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.PartyroomReportCreateResponse;
import com.pfplaybackend.api.administration.application.service.PartyroomReportCommandService;
import com.pfplaybackend.api.administration.domain.exception.AdminReportException;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.CustomJwtAuthenticationToken;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc test for {@link PartyroomReportCommandController} (PR 13 G2 — C-1).
 *
 * <p>{@code AbstractAdminWebMvcTest}는 admin SecurityConfig를 강제하여 부적합 — user-facing endpoint은 별 standalone
 * {@code @WebMvcTest} + 최소 SecurityConfig를 사용한다 (mirror {@link PartyroomAccessQueryControllerTest}).
 *
 * <p>Cases (~11): 201 happy / 400 (category null/invalid, description 길이, partyroomId @Min) / 401 anonymous /
 * 403 Guest tier (service-layer guard) / 404 PARTYROOM_NOT_FOUND / 400 도메인 예외 3종.
 */
@WebMvcTest(PartyroomReportCommandController.class)
@Import(PartyroomReportCommandControllerTest.TestSecurityConfig.class)
class PartyroomReportCommandControllerTest {

    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(request -> request
                            .requestMatchers("/api/**").authenticated()
                    )
                    .exceptionHandling(eh -> eh
                            .authenticationEntryPoint((req, res, ex) ->
                                    res.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED)));
            return http.build();
        }
    }

    @Autowired MockMvc mockMvc;
    @MockBean PartyroomReportCommandService reportCommandService;

    // SecurityConfig deps consumed via Spring Boot autoconfig — provide @MockBean shims so context loads.
    @MockBean JwtDecoder jwtDecoder;
    @MockBean JwtService jwtService;
    @MockBean JwtProperties jwtProperties;
    @MockBean SharedSessionCookieWriter sharedSessionCookieWriter;
    @MockBean AdminCookieWriter adminCookieWriter;

    /**
     * Build a {@link CustomJwtAuthenticationToken} so the controller's
     * {@code (CustomJwtAuthenticationToken) auth} cast succeeds.
     */
    private static RequestPostProcessor memberAuth(long userId, AuthorityTier tier) {
        return SecurityMockMvcRequestPostProcessors.authentication(buildToken(userId, tier));
    }

    private static CustomJwtAuthenticationToken buildToken(long userId, AuthorityTier tier) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("authority_tier", tier.name())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return new CustomJwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")),
                new UserId(userId),
                "member@test.local",
                tier);
    }

    private static final String PATH = "/api/v1/partyrooms/{id}/reports";

    // ============================== Happy path ==============================

    @Test
    @DisplayName("POST 201 — happy + $.data.reportId")
    void create_happy_returns201() throws Exception {
        given(reportCommandService.create(eq(1L), any(PartyroomReportCreateRequest.class),
                any(AuthorityTier.class), anyLong()))
                .willReturn(new PartyroomReportCreateResponse(42L));

        mockMvc.perform(post(PATH, 1L)
                        .with(csrf())
                        .with(memberAuth(100L, AuthorityTier.FM))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"SPAM\",\"description\":\"광고\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reportId").value(42));
    }

    // ============================== 400 Bean Validation ==============================

    @Test
    @DisplayName("POST 400 — category null (Bean Validation)")
    void create_categoryNull_returns400() throws Exception {
        mockMvc.perform(post(PATH, 1L)
                        .with(csrf())
                        .with(memberAuth(100L, AuthorityTier.FM))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"text\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST 400 — category enum 외 값 (HttpMessageNotReadable)")
    void create_invalidEnum_returns400() throws Exception {
        mockMvc.perform(post(PATH, 1L)
                        .with(csrf())
                        .with(memberAuth(100L, AuthorityTier.FM))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"INVALID\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST 400 — description 2000자 초과")
    void create_descriptionTooLong_returns400() throws Exception {
        String tooLong = "a".repeat(2001);
        String body = "{\"category\":\"SPAM\",\"description\":\"" + tooLong + "\"}";

        mockMvc.perform(post(PATH, 1L)
                        .with(csrf())
                        .with(memberAuth(100L, AuthorityTier.FM))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST 400 — partyroomId @Min(1) 위반 (path=0)")
    void create_partyroomIdMinViolation_returns400() throws Exception {
        mockMvc.perform(post(PATH, 0L)
                        .with(csrf())
                        .with(memberAuth(100L, AuthorityTier.FM))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"SPAM\"}"))
                .andExpect(status().isBadRequest());
    }

    // ============================== Auth ==============================

    @Test
    @WithAnonymousUser
    @DisplayName("POST 401 — anonymous")
    void create_anonymous_returns401() throws Exception {
        mockMvc.perform(post(PATH, 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"SPAM\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST 403 — Guest tier (service-layer Member guard, PTR-005)")
    void create_guestTier_returns403() throws Exception {
        // Service throws RESTRICTED_AUTHORITY (PTR-005, FORBIDDEN) for Guest.
        willThrow(ExceptionCreator.create(PartyroomException.RESTRICTED_AUTHORITY))
                .given(reportCommandService).create(anyLong(), any(PartyroomReportCreateRequest.class),
                        eq(AuthorityTier.GT), anyLong());

        mockMvc.perform(post(PATH, 1L)
                        .with(csrf())
                        .with(memberAuth(100L, AuthorityTier.GT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"SPAM\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PTR-005"));
    }

    // ============================== Domain errors ==============================

    @Test
    @DisplayName("POST 404 — PARTYROOM_NOT_FOUND (PTR-001)")
    void create_partyroomNotFound_returns404() throws Exception {
        willThrow(ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM))
                .given(reportCommandService).create(eq(999L), any(PartyroomReportCreateRequest.class),
                        any(AuthorityTier.class), anyLong());

        mockMvc.perform(post(PATH, 999L)
                        .with(csrf())
                        .with(memberAuth(100L, AuthorityTier.FM))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"SPAM\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PTR-001"));
    }

    @Test
    @DisplayName("POST 400 — PARTYROOM_NOT_REPORTABLE (RPT-005)")
    void create_partyroomNotReportable_returns400() throws Exception {
        willThrow(ExceptionCreator.create(AdminReportException.PARTYROOM_NOT_REPORTABLE))
                .given(reportCommandService).create(anyLong(), any(PartyroomReportCreateRequest.class),
                        any(AuthorityTier.class), anyLong());

        mockMvc.perform(post(PATH, 1L)
                        .with(csrf())
                        .with(memberAuth(100L, AuthorityTier.FM))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"SPAM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RPT-005"));
    }

    @Test
    @DisplayName("POST 400 — SELF_REPORT_FORBIDDEN (RPT-006)")
    void create_selfReport_returns400() throws Exception {
        willThrow(ExceptionCreator.create(AdminReportException.SELF_REPORT_FORBIDDEN))
                .given(reportCommandService).create(anyLong(), any(PartyroomReportCreateRequest.class),
                        any(AuthorityTier.class), anyLong());

        mockMvc.perform(post(PATH, 1L)
                        .with(csrf())
                        .with(memberAuth(100L, AuthorityTier.FM))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"SPAM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RPT-006"));
    }

    @Test
    @DisplayName("POST 400 — DUPLICATE_REPORT (RPT-007)")
    void create_duplicate_returns400() throws Exception {
        willThrow(ExceptionCreator.create(AdminReportException.DUPLICATE_REPORT))
                .given(reportCommandService).create(anyLong(), any(PartyroomReportCreateRequest.class),
                        any(AuthorityTier.class), anyLong());

        mockMvc.perform(post(PATH, 1L)
                        .with(csrf())
                        .with(memberAuth(100L, AuthorityTier.FM))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"SPAM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RPT-007"));
    }
}
