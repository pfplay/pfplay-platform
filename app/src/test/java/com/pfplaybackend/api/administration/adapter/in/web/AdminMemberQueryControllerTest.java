package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberSummaryResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.MemberProfileSummary;
import com.pfplaybackend.api.administration.adapter.in.web.dto.UserAccountSummary;
import com.pfplaybackend.api.administration.domain.exception.AdminMemberException;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.enums.AuthorityTier;
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

/**
 * WebMvcTest for {@link AdminMemberQueryController}.
 *
 * <p>Covers A-2 detail happy path (200), MEMBER_NOT_FOUND (404), and auth gating
 * (401 anonymous / 403 member). Mirrors PR 8 {@code AdminPartyroomQueryControllerTest}
 * structure — uses {@link WithMockUser}/{@link WithAnonymousUser} role fixtures.
 */
class AdminMemberQueryControllerTest extends AbstractAdminWebMvcTest {

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/members/{id} 200 — detail 응답")
    void getDetail_admin_returns200WithBody() throws Exception {
        AdminMemberDetailResponse response = new AdminMemberDetailResponse(
                50L,
                new UserAccountSummary(100L, "u@x", ProviderType.GOOGLE,
                        LocalDateTime.of(2026, 4, 28, 10, 0), null),
                new MemberProfileSummary("Nick", "intro"),
                AuthorityTier.FM,
                LocalDateTime.of(2025, 12, 1, 0, 0),
                false,
                null,
                List.of());
        given(adminMemberQueryService.getDetail(50L)).willReturn(response);

        mockMvc.perform(get("/api/v1/admin/members/50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(50))
                .andExpect(jsonPath("$.data.userAccount.userAccountId").value(100))
                .andExpect(jsonPath("$.data.userAccount.email").value("u@x"))
                .andExpect(jsonPath("$.data.profile.nickname").value("Nick"))
                .andExpect(jsonPath("$.data.authorityTier").value("FM"))
                .andExpect(jsonPath("$.data.withdrawn").value(false))
                .andExpect(jsonPath("$.data.recentActivityLog").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/members/{id} 404 — MEMBER_NOT_FOUND")
    void getDetail_memberNotFound_returns404() throws Exception {
        willThrow(ExceptionCreator.create(AdminMemberException.MEMBER_NOT_FOUND))
                .given(adminMemberQueryService).getDetail(99L);

        mockMvc.perform(get("/api/v1/admin/members/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /admin/members/{id} 401 — 미인증")
    void getDetail_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/members/50"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("GET /admin/members/{id} 403 — 인증된 non-admin")
    void getDetail_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/members/50"))
                .andExpect(status().isForbidden());
    }

    // -------- A-1 list --------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/members 200 — 빈 결과")
    void getList_returns_200_empty_content() throws Exception {
        Page<AdminMemberSummaryResponse> emptyPage =
                new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 50), 0L);
        given(adminMemberQueryService.getList(any(), any(Pageable.class))).willReturn(emptyPage);

        mockMvc.perform(get("/api/v1/admin/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/members 200 — content row + withdrawn flag 직렬화")
    void getList_returns_200_with_content_row() throws Exception {
        AdminMemberSummaryResponse row = new AdminMemberSummaryResponse(
                50L, 100L, "u@x", ProviderType.GOOGLE,
                "Nick", AuthorityTier.FM,
                LocalDateTime.of(2026, 4, 28, 10, 0),
                LocalDateTime.of(2025, 12, 1, 0, 0),
                false, null);
        Page<AdminMemberSummaryResponse> page =
                new PageImpl<>(List.of(row), PageRequest.of(0, 50), 1L);
        given(adminMemberQueryService.getList(any(), any(Pageable.class))).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].memberId").value(50))
                .andExpect(jsonPath("$.data.content[0].nickname").value("Nick"))
                .andExpect(jsonPath("$.data.content[0].withdrawn").value(false))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/members 400 — size > 200")
    void getList_returns_400_when_size_exceeds_cap() throws Exception {
        mockMvc.perform(get("/api/v1/admin/members").param("size", "10000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/members 400 — joined_from > joined_to")
    void getList_returns_400_when_date_range_invalid() throws Exception {
        mockMvc.perform(get("/api/v1/admin/members")
                        .param("joined_from", "2026-12-31")
                        .param("joined_to", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/members 400 — sort 허용 외 값")
    void getList_returns_400_when_sort_invalid() throws Exception {
        mockMvc.perform(get("/api/v1/admin/members").param("sort", "random_xyz"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /admin/members 401 — 미인증")
    void getList_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/members"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("GET /admin/members 403 — 인증된 non-admin")
    void getList_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/members"))
                .andExpect(status().isForbidden());
    }
}
