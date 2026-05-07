package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberTierChangeRequest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberTierChangeResponse;
import com.pfplaybackend.api.administration.domain.exception.AdminMemberException;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc test for {@link AdminMemberTierCommandController} (PR 12b2 G2).
 *
 * <p>Covers 200 happy / 400 invalid body / 400 invalid enum / 400 TIER_UNCHANGED /
 * 404 MEMBER_NOT_FOUND / 401 anonymous / 403 non-admin role.
 */
class AdminMemberTierCommandControllerTest extends AbstractAdminWebMvcTest {

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /admin/members/{id}/tier 200 — admin happy")
    void changeTier_admin_returns200() throws Exception {
        given(adminMemberTierCommandService.changeTier(eq(1L), any(AdminMemberTierChangeRequest.class)))
                .willReturn(new AdminMemberTierChangeResponse(1L, AuthorityTier.AM, AuthorityTier.GT));

        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"GT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(1))
                .andExpect(jsonPath("$.data.oldTier").value("AM"))
                .andExpect(jsonPath("$.data.newTier").value("GT"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH 400 — targetTier null (Bean Validation)")
    void changeTier_nullTier_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH 400 — targetTier enum 외 값 (HttpMessageNotReadable)")
    void changeTier_invalidEnum_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH 400 — TIER_UNCHANGED MBR-003")
    void changeTier_unchanged_returns400() throws Exception {
        willThrow(ExceptionCreator.create(AdminMemberException.TIER_UNCHANGED))
                .given(adminMemberTierCommandService).changeTier(eq(1L), any(AdminMemberTierChangeRequest.class));

        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"AM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MBR-003"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH 404 — memberId 부재 MBR-001")
    void changeTier_notFound_returns404() throws Exception {
        willThrow(ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND))
                .given(adminMemberTierCommandService).changeTier(eq(999L), any(AdminMemberTierChangeRequest.class));

        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 999L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"AM\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MBR-001"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("PATCH 401 — anonymous")
    void changeTier_anonymous_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"AM\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("PATCH 403 — 인증된 non-admin")
    void changeTier_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/members/{id}/tier", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"AM\"}"))
                .andExpect(status().isForbidden());
    }
}
