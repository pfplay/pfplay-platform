package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberWithdrawResponse;
import com.pfplaybackend.api.administration.domain.exception.AdminMemberException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc test for {@link AdminMemberWithdrawCommandController} (PR 12b2 G3).
 *
 * <p>Covers 200 happy / 200 idempotent / 404 / 401 / 403.
 */
class AdminMemberWithdrawCommandControllerTest extends AbstractAdminWebMvcTest {

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /admin/members/{id}/withdraw 200 — admin happy")
    void withdraw_admin_returns200() throws Exception {
        given(adminMemberWithdrawCommandService.withdraw(eq(1L)))
                .willReturn(new AdminMemberWithdrawResponse(
                        1L, 7L, LocalDateTime.of(2026, 4, 28, 12, 0), false));

        mockMvc.perform(post("/api/v1/admin/members/{id}/withdraw", 1L)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(1))
                .andExpect(jsonPath("$.data.userAccountId").value(7))
                .andExpect(jsonPath("$.data.alreadyWithdrawn").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 200 — idempotent 재호출 → alreadyWithdrawn=true")
    void withdraw_idempotent_returnsAlreadyWithdrawnTrue() throws Exception {
        given(adminMemberWithdrawCommandService.withdraw(eq(1L)))
                .willReturn(new AdminMemberWithdrawResponse(
                        1L, 7L, LocalDateTime.of(2026, 4, 28, 12, 0), true));

        mockMvc.perform(post("/api/v1/admin/members/{id}/withdraw", 1L)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyWithdrawn").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 404 — memberId 부재 MBR-001")
    void withdraw_notFound_returns404() throws Exception {
        willThrow(ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND))
                .given(adminMemberWithdrawCommandService).withdraw(eq(999L));

        mockMvc.perform(post("/api/v1/admin/members/{id}/withdraw", 999L)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MBR-001"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("POST 401 — anonymous")
    void withdraw_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/members/{id}/withdraw", 1L)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("POST 403 — 인증된 non-admin")
    void withdraw_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/members/{id}/withdraw", 1L)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
