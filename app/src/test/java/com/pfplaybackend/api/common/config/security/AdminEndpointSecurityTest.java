package com.pfplaybackend.api.common.config.security;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test asserting that admin URL prefixes are gated correctly at the
 * SecurityFilterChain level (not just by method-level @PreAuthorize):
 * <ul>
 *   <li>{@code /api/v1/admin/**} → ROLE_ADMIN (covered by /partyrooms probe — PR 4)</li>
 *   <li>{@code /api/v1/admin/system/**} → ROLE_SUPER_ADMIN (covered by
 *       /system/administrators probe — PR 6, asserts that ROLE_ADMIN alone is rejected
 *       by the URL rule, not just by method-level annotations)</li>
 *   <li>{@code /api/v1/admin/password/change} → ROLE_ADMIN (covered by self-change
 *       probe — PR 6, asserts the moved path falls under the existing /admin/** rule)</li>
 * </ul>
 *
 * <p>URL-level security filters run before request mapping, so 401/403 fire regardless
 * of HTTP method or controller existence — these probes assert the gate, not the handler.
 */
@AutoConfigureMockMvc
class AdminEndpointSecurityTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // -------- /api/v1/admin/** (catch-all → ROLE_ADMIN) --------

    @Test
    @WithAnonymousUser
    void anonymousRequest_toAdminEndpoint_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"MEMBER"})
    void authenticatedMember_toAdminEndpoint_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void authenticatedAdmin_toAdminEndpoint_doesNotReturn401or403() throws Exception {
        // Endpoint may return 200/201/400/404/500 depending on internals,
        // but NOT 401/403 (URL gate is transparent to ADMIN).
        mockMvc.perform(post("/api/v1/admin/partyrooms")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(Matchers.not(Matchers.isOneOf(401, 403))));
    }

    // -------- /api/v1/admin/system/** (→ ROLE_SUPER_ADMIN, PR 6) --------

    @Test
    @WithAnonymousUser
    void anonymousRequest_toSystemAdminEndpoint_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/administrators")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void plainAdmin_toSystemAdminEndpoint_returns403() throws Exception {
        // ROLE_ADMIN alone (no SUPER_ADMIN) must be rejected by the URL rule
        // for /api/v1/admin/system/**, regardless of any method-level annotation.
        mockMvc.perform(post("/api/v1/admin/system/administrators")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"SUPER_ADMIN", "ADMIN"})
    void superAdmin_toSystemAdminEndpoint_doesNotReturn401or403() throws Exception {
        // PR 4 dual-issues both authorities for super admins; the URL rule passes
        // and the request reaches the controller (which may return 400 from
        // missing JSON fields — that's fine, we only assert NOT 401/403).
        mockMvc.perform(post("/api/v1/admin/system/administrators")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(Matchers.not(Matchers.isOneOf(401, 403))));
    }

    // -------- /api/v1/admin/avatar/** (→ ROLE_SUPER_ADMIN, PR 11) --------

    @Test
    @WithAnonymousUser
    void anonymousRequest_toAvatarAdminEndpoint_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/avatar/bodies/1/publish")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void plainAdmin_toAvatarAdminEndpoint_returns403() throws Exception {
        // ROLE_ADMIN alone은 avatar 경로 URL 규칙에서 거부 — SUPER_ADMIN 필요.
        mockMvc.perform(post("/api/v1/admin/avatar/bodies/1/publish")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"SUPER_ADMIN", "ADMIN"})
    void superAdmin_toAvatarAdminEndpoint_doesNotReturn401or403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/avatar/bodies/1/publish")
                        .with(csrf()))
                .andExpect(status().is(Matchers.not(Matchers.isOneOf(401, 403))));
    }

    // -------- /api/v1/admin/password/change (→ ROLE_ADMIN, PR 6 Decision 4) --------

    @Test
    @WithAnonymousUser
    void anonymousRequest_toAdminPasswordChange_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/password/change")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"MEMBER"})
    void member_toAdminPasswordChange_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/password/change")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void admin_toAdminPasswordChange_doesNotReturn401or403() throws Exception {
        // The moved self-change path falls under /api/v1/admin/** → ROLE_ADMIN.
        // Empty body will surface as 400 from @Valid, which is fine — we assert the
        // URL gate is transparent for ROLE_ADMIN, not the handler outcome.
        mockMvc.perform(post("/api/v1/admin/password/change")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(Matchers.not(Matchers.isOneOf(401, 403))));
    }
}
