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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test asserting that {@code /api/v1/admin/**} is gated by ROLE_ADMIN
 * at the URL level (SecurityFilterChain), not just by method-level @PreAuthorize.
 *
 * <p>Uses POST against {@code /api/v1/admin/partyrooms} because that is the
 * registered admin endpoint on AdminPartyroomController. URL-level security
 * filters run before method matching, so 401/403 still apply regardless of HTTP method.
 */
@AutoConfigureMockMvc
class AdminEndpointSecurityTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithAnonymousUser
    void anonymousRequest_toAdminEndpoint_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"MEMBER"})
    void authenticatedMember_toAdminEndpoint_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms")
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(Matchers.not(Matchers.isOneOf(401, 403))));
    }
}
