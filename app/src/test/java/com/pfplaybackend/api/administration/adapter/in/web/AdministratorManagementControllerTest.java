package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdministratorListResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdministratorView;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.CreateAdministratorResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.ResetPasswordResponse;
import com.pfplaybackend.api.administration.domain.value.AdminRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdministratorManagementControllerTest extends AbstractAdminWebMvcTest {

    // -------- list --------

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void list_superAdmin_returns200WithItems() throws Exception {
        given(administratorManagementService.list(null, false))
                .willReturn(AdministratorListResponse.builder()
                        .totalCount(1)
                        .items(List.of(AdministratorView.builder()
                                .administratorId(1L)
                                .role(AdminRole.SUPER_ADMIN)
                                .email("super@x")
                                .build()))
                        .build());

        mockMvc.perform(get("/api/v1/admin/system/administrators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].role").value("SUPER_ADMIN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/system/administrators"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void list_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/system/administrators"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void list_passesRoleAndIncludeRevokedQueryParams() throws Exception {
        given(administratorManagementService.list(AdminRole.ADMIN, true))
                .willReturn(AdministratorListResponse.builder().totalCount(0).items(List.of()).build());

        mockMvc.perform(get("/api/v1/admin/system/administrators")
                        .param("role", "ADMIN")
                        .param("includeRevoked", "true"))
                .andExpect(status().isOk());
    }

    // -------- create --------

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void create_superAdmin_returnsTempPassword() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(1L);
        given(administratorManagementService.create(any(), anyLong()))
                .willReturn(CreateAdministratorResponse.builder()
                        .administratorId(2L)
                        .userAccountId(20L)
                        .memberId(30L)
                        .tempPassword("Xk9@aB2zCdEf")
                        .message("임시 비번은 첫 로그인 후 반드시 변경하세요.")
                        .build());

        mockMvc.perform(post("/api/v1/admin/system/administrators")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"new@x.test","nickname":"newbie","includeMemberProfile":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.administratorId").value(2))
                .andExpect(jsonPath("$.data.tempPassword").value("Xk9@aB2zCdEf"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void create_missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/administrators")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"new@x.test","nickname":"n"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/administrators")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"new@x.test","nickname":"n"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void create_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/administrators")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","nickname":"n"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void create_blankNickname_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/administrators")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"a@x.test","nickname":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    // -------- patch --------

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void patch_superAdmin_returns204() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/system/administrators/7")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nickname":"renamed"}
                                """))
                .andExpect(status().isNoContent());
        verify(administratorManagementService).updateNickname(7L, "renamed");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void patch_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/system/administrators/7")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nickname":"renamed"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void patch_blankNickname_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/system/administrators/7")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nickname":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    // -------- attachMemberProfile --------

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void attachMemberProfile_returns200WithMemberId() throws Exception {
        given(administratorManagementService.attachMemberProfile(7L, "newbie")).willReturn(99L);
        mockMvc.perform(post("/api/v1/admin/system/administrators/7/member-profile")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nickname":"newbie"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(99));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void attachMemberProfile_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/administrators/7/member-profile")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nickname":"n"}
                                """))
                .andExpect(status().isForbidden());
    }

    // -------- revoke --------

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void revoke_superAdmin_returns204() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(1L);

        mockMvc.perform(post("/api/v1/admin/system/administrators/7/revoke")
                        .with(csrf()))
                .andExpect(status().isNoContent());
        verify(administratorManagementService).revoke(7L, 1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revoke_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/administrators/7/revoke")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // -------- resetPassword --------

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void resetPassword_superAdmin_returns200WithTempPassword() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(1L);
        given(administratorManagementService.resetPassword(7L, 1L))
                .willReturn(ResetPasswordResponse.builder()
                        .tempPassword("Yp4@xQ7zVwLm")
                        .message("임시 비번을 안전한 채널로 전달하세요. 첫 로그인 시 변경됩니다.")
                        .build());

        mockMvc.perform(post("/api/v1/admin/system/administrators/7/reset-password")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tempPassword").value("Yp4@xQ7zVwLm"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void resetPassword_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/administrators/7/reset-password")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN", "SUPER_ADMIN"})
    void resetPassword_missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/administrators/7/reset-password"))
                .andExpect(status().isForbidden());
    }
}
