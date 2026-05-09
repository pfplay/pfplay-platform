package com.pfplaybackend.api.common.config.security;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §5.7.1 — 권한 회귀 parameterized matrix.
 *
 * <p>Matrix: (role) × (probe path) → expected status.
 *
 * <p>Probe paths are deliberately non-existent — the URL-level filter chain runs
 * before request mapping, so the gate fires regardless of whether a controller exists.
 * "Allow-through" responses look like 404 (no controller) which the test asserts is
 * NOT 401 / NOT 403.
 *
 * <p>Expected matrix:
 * <pre>
 *                  /admin/probe    /admin/system/probe   /admin/avatar/probe
 *   anonymous          401              401                  401
 *   MEMBER             403              403                  403
 *   ADMIN              not(401|403)     403                  403
 *   SUPER_ADMIN        not(401|403)     not(401|403)         not(401|403)
 * </pre>
 */
@AutoConfigureMockMvc
class AdminAuthorizationMatrixTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private static final String GENERIC_ADMIN = "/api/v1/admin/__test_probe__";
    private static final String SYSTEM_ADMIN = "/api/v1/admin/system/__test_probe__";
    private static final String AVATAR_ADMIN = "/api/v1/admin/avatar/__test_probe__";

    // ----- Anonymous: always 401 -----
    @ParameterizedTest
    @MethodSource("allAdminPaths")
    @WithAnonymousUser
    void anonymousRequest_returns401(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ----- MEMBER: always 403 -----
    @ParameterizedTest
    @MethodSource("allAdminPaths")
    @WithMockUser(roles = "MEMBER")
    void memberRequest_returns403(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    // ----- ADMIN: 403 on /system/**, 403 on /avatar/**, allow on catch-all -----
    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRequest_genericAdminPath_isAllowedThroughGate() throws Exception {
        mockMvc.perform(post(GENERIC_ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(Matchers.not(Matchers.isOneOf(401, 403))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRequest_systemAdminPath_returns403() throws Exception {
        mockMvc.perform(post(SYSTEM_ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRequest_avatarAdminPath_returns403() throws Exception {
        mockMvc.perform(post(AVATAR_ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    // ----- SUPER_ADMIN: allowed everywhere (carries both ROLE_ADMIN + ROLE_SUPER_ADMIN) -----
    @ParameterizedTest
    @MethodSource("allAdminPaths")
    @WithMockUser(roles = {"SUPER_ADMIN", "ADMIN"})
    void superAdminRequest_allowedThroughGate(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(Matchers.not(Matchers.isOneOf(401, 403))));
    }

    private static Stream<Arguments> allAdminPaths() {
        return Stream.of(
                Arguments.of(GENERIC_ADMIN),
                Arguments.of(SYSTEM_ADMIN),
                Arguments.of(AVATAR_ADMIN)
        );
    }
}
