package com.pfplaybackend.api.common.config.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for §5.6 must_change_password flow.
 * <p>Super-admin creates new admin → new admin logs in (flag=true) →
 * new admin self-changes password → new admin logs in again (flag=false).
 */
@AutoConfigureMockMvc
class AdminPasswordChangeIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired AdministratorRepository administratorRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtProperties jwtProperties;

    private static final String SUPER_EMAIL = "super@e2e.local";
    private static final String SUPER_PASSWORD = "SuperE2E#2026";
    private static final String NEW_ADMIN_EMAIL = "newadmin@e2e.local";
    private static final String NEW_ADMIN_NICKNAME = "newbie";
    private static final String NEW_PASSWORD = "FreshAdmin@2026";

    @BeforeEach
    void seedSuperAdmin() {
        // ddl-auto:create-drop wipes the DB between contexts, but @BeforeEach also
        // ensures cleanup if @DirtiesContext isn't configured. Use a transactional
        // truncate (manual delete) to be safe — both repositories support deleteAll.
        administratorRepository.deleteAll();
        userAccountRepository.deleteAll();

        UserAccountData ua = userAccountRepository.save(
                UserAccountData.createForLocal(
                        new UserId(), SUPER_EMAIL, passwordEncoder.encode(SUPER_PASSWORD)));
        administratorRepository.save(
                AdministratorData.createSuperAdmin(ua.getUserId().getUid()));
    }

    @Test
    void newAdmin_loginShowsMustChange_thenSelfChange_thenLoginCleared() throws Exception {
        // 1. Super admin logs in.
        MvcResult superLogin = mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(SUPER_EMAIL, SUPER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false))
                .andReturn();
        Cookie superAdminCookie = adminCookieFrom(superLogin);
        assertThat(superAdminCookie).isNotNull();

        // 2. Super admin creates a new admin. Captures the temp password.
        MvcResult createResp = mockMvc.perform(post("/api/v1/admin/system/administrators")
                        .cookie(superAdminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "nickname": "%s",
                                  "includeMemberProfile": true
                                }
                                """.formatted(NEW_ADMIN_EMAIL, NEW_ADMIN_NICKNAME)))
                .andExpect(status().isOk())
                .andReturn();
        String tempPassword = readJson(createResp).at("/data/tempPassword").asText();
        assertThat(tempPassword).isNotBlank();

        // 3. New admin logs in with temp password — assert mustChangePassword=true.
        MvcResult firstLogin = mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(NEW_ADMIN_EMAIL, tempPassword)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                .andReturn();
        Cookie newAdminCookie = adminCookieFrom(firstLogin);
        assertThat(newAdminCookie).isNotNull();

        // 4. New admin self-changes password.
        mockMvc.perform(post("/api/v1/admin/password/change")
                        .cookie(newAdminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "%s",
                                  "newPassword": "%s"
                                }
                                """.formatted(tempPassword, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        // 5. New admin logs in with the new password — assert mustChangePassword=false.
        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(NEW_ADMIN_EMAIL, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));

        // 6. Old (temp) password no longer works.
        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(NEW_ADMIN_EMAIL, tempPassword)))
                .andExpect(status().isUnauthorized());
    }

    private String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private Cookie adminCookieFrom(MvcResult result) {
        return result.getResponse().getCookie(jwtProperties.getCookie().getAdmin().getName());
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
