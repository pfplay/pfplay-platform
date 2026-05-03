package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.avatar.application.dto.AdminAvatarBodyView;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.avatar.domain.exception.AvatarException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAvatarQueryControllerTest extends AbstractAdminWebMvcTest {

    private AdminAvatarBodyView body(long id, LifecycleStatus s) {
        return new AdminAvatarBodyView(
                id, "name_" + id, "uri", "icon",
                ObtainmentType.BASIC, 0, true, false, 0, 0, s,
                LocalDateTime.now(), 1L, LocalDateTime.now(), 1L);
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void listBodies_noFilter_returns200() throws Exception {
        given(avatarAdminCatalogQueryUseCase.listBodies(isNull(), isNull()))
                .willReturn(List.of(body(1, LifecycleStatus.PUBLISHED), body(2, LifecycleStatus.DRAFT)));

        mockMvc.perform(get("/api/v1/admin/avatar/bodies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void listBodies_filter_passesParams() throws Exception {
        given(avatarAdminCatalogQueryUseCase.listBodies(
                eq(LifecycleStatus.DRAFT), eq(ObtainmentType.DJ_PNT)))
                .willReturn(List.of(body(7, LifecycleStatus.DRAFT)));

        mockMvc.perform(get("/api/v1/admin/avatar/bodies")
                        .param("status", "DRAFT")
                        .param("obtainableType", "DJ_PNT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(7));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listBodies_plainAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/avatar/bodies"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void listBodies_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/avatar/bodies"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void getBody_notFound_returns404() throws Exception {
        given(avatarAdminCatalogQueryUseCase.getBody(99L))
                .willThrow(ExceptionCreator.create(AvatarException.AVATAR_RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/admin/avatar/bodies/99"))
                .andExpect(status().isNotFound());
    }
}
