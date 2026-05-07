package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminPasswordControllerTest extends AbstractAdminWebMvcTest {

    @Test
    @WithMockUser(roles = "ADMIN")
    void change_admin_returns204() throws Exception {
        given(adminContext.currentUserId()).willReturn(new UserId(42L));

        mockMvc.perform(post("/api/v1/admin/password/change")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"oldPwd","newPassword":"NewP@ssw0rd1"}
                                """))
                .andExpect(status().isNoContent());
        verify(adminPasswordService).changePassword(
                new UserId(42L), "oldPwd", "NewP@ssw0rd1");
    }

    @Test
    @WithAnonymousUser
    void change_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/password/change")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"o","newPassword":"n"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void change_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/password/change")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"o","newPassword":"n"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void change_missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/password/change")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"o","newPassword":"n"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void change_blankCurrentPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/password/change")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"","newPassword":"NewP@ssw0rd1"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void change_superAdmin_alsoAllowed() throws Exception {
        // SUPER_ADMIN carries ROLE_ADMIN too — @adminAuth.isAdmin() returns true.
        given(adminContext.currentUserId()).willReturn(new UserId(1L));
        mockMvc.perform(post("/api/v1/admin/password/change")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"oldPwd","newPassword":"NewP@ssw0rd1"}
                                """))
                .andExpect(status().isNoContent());
    }
}
