package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestSummaryResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.GuestProfileSummary;
import com.pfplaybackend.api.administration.adapter.in.web.dto.UserAccountSummary;
import com.pfplaybackend.api.administration.domain.exception.AdminGuestException;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminGuestQueryControllerTest extends AbstractAdminWebMvcTest {

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests/{id} 200 — detail 응답 + agent/isProfileUpdated 직렬화")
    void getDetail_admin_returns200WithBody() throws Exception {
        AdminGuestDetailResponse response = new AdminGuestDetailResponse(
                50L,
                new UserAccountSummary(100L, "g@x", ProviderType.GOOGLE,
                        LocalDateTime.of(2026, 5, 1, 10, 0), null),
                new GuestProfileSummary("Nick", "intro"),
                "ua-string",
                true,
                LocalDateTime.of(2026, 4, 1, 0, 0),
                false, null,
                List.of());
        given(adminGuestQueryService.getDetail(50L)).willReturn(response);

        mockMvc.perform(get("/api/v1/admin/guests/50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.guestId").value(50))
                .andExpect(jsonPath("$.data.userAccount.userAccountId").value(100))
                .andExpect(jsonPath("$.data.profile.nickname").value("Nick"))
                .andExpect(jsonPath("$.data.agent").value("ua-string"))
                .andExpect(jsonPath("$.data.isProfileUpdated").value(true))
                .andExpect(jsonPath("$.data.withdrawn").value(false))
                .andExpect(jsonPath("$.data.recentActivityLog").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests/{id} 404 — GUEST_NOT_FOUND")
    void getDetail_guestNotFound_returns404() throws Exception {
        willThrow(ExceptionCreator.create(AdminGuestException.GUEST_NOT_FOUND))
                .given(adminGuestQueryService).getDetail(99L);

        mockMvc.perform(get("/api/v1/admin/guests/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /admin/guests/{id} 401 — 미인증")
    void getDetail_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests/50"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("GET /admin/guests/{id} 403 — 인증된 non-admin")
    void getDetail_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests/50"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests 200 — 빈 결과")
    void getList_returns_200_empty_content() throws Exception {
        Page<AdminGuestSummaryResponse> emptyPage =
                new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 50), 0L);
        given(adminGuestQueryService.getList(any(), any(Pageable.class))).willReturn(emptyPage);

        mockMvc.perform(get("/api/v1/admin/guests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests 200 — content row + agent/isProfileUpdated 직렬화")
    void getList_returns_200_with_content_row() throws Exception {
        AdminGuestSummaryResponse row = new AdminGuestSummaryResponse(
                50L, 100L, "g@x", ProviderType.GOOGLE,
                "Nick", "ua-string", true,
                LocalDateTime.of(2026, 5, 1, 10, 0),
                LocalDateTime.of(2026, 4, 1, 0, 0),
                false, null);
        Page<AdminGuestSummaryResponse> page =
                new PageImpl<>(List.of(row), PageRequest.of(0, 50), 1L);
        given(adminGuestQueryService.getList(any(), any(Pageable.class))).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/guests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].guestId").value(50))
                .andExpect(jsonPath("$.data.content[0].nickname").value("Nick"))
                .andExpect(jsonPath("$.data.content[0].agent").value("ua-string"))
                .andExpect(jsonPath("$.data.content[0].isProfileUpdated").value(true))
                .andExpect(jsonPath("$.data.content[0].withdrawn").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests 400 — size > 200")
    void getList_returns_400_when_size_exceeds_cap() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests").param("size", "10000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests 400 — joined_from > joined_to")
    void getList_returns_400_when_date_range_invalid() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests")
                        .param("joined_from", "2026-12-31")
                        .param("joined_to", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests 400 — sort 허용 외 값")
    void getList_returns_400_when_sort_invalid() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests").param("sort", "random_xyz"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /admin/guests 401 — 미인증")
    void getList_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("GET /admin/guests 403 — 인증된 non-admin")
    void getList_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests"))
                .andExpect(status().isForbidden());
    }
}
