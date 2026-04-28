package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.avatar.application.dto.AdminAvatarBodyView;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.avatar.domain.exception.AvatarException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAvatarCommandControllerTest extends AbstractAdminWebMvcTest {

    @BeforeEach
    void wireAdminContext() {
        given(adminContext.currentAdministratorId()).willReturn(1L);
    }

    private AdminAvatarBodyView sampleBodyView() {
        return new AdminAvatarBodyView(
                10L, "ava_body_x", "https://gcs/b.png", "https://gcs/i.png",
                ObtainmentType.BASIC, 0, true, false, 0, 0,
                LifecycleStatus.DRAFT,
                LocalDateTime.now(), 1L, LocalDateTime.now(), 1L);
    }

    // ---------------------------------------------------------- POST bodies

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void createBody_superAdmin_returns201() throws Exception {
        MockMultipartFile body = new MockMultipartFile("bodyImage", "b.png", "image/png", new byte[]{1, 2, 3});
        given(avatarCatalogCommandUseCase.createBody(any())).willReturn(sampleBodyView());

        mockMvc.perform(multipart("/api/v1/admin/avatar/bodies")
                        .file(body)
                        .with(csrf())
                        .param("name", "ava_body_x")
                        .param("obtainableType", "BASIC")
                        .param("obtainableScore", "0")
                        .param("combinable", "true")
                        .param("defaultSetting", "false")
                        .param("combinePositionX", "0")
                        .param("combinePositionY", "0"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lifecycleStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.id").value(10));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createBody_plainAdmin_returns403() throws Exception {
        MockMultipartFile body = new MockMultipartFile("bodyImage", "b.png", "image/png", new byte[]{1});

        mockMvc.perform(multipart("/api/v1/admin/avatar/bodies")
                        .file(body)
                        .with(csrf())
                        .param("name", "ava_body_x")
                        .param("obtainableType", "BASIC")
                        .param("obtainableScore", "0"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void createBody_anonymous_returns401() throws Exception {
        MockMultipartFile body = new MockMultipartFile("bodyImage", "b.png", "image/png", new byte[]{1});

        mockMvc.perform(multipart("/api/v1/admin/avatar/bodies")
                        .file(body)
                        .with(csrf())
                        .param("name", "ava_body_x")
                        .param("obtainableType", "BASIC")
                        .param("obtainableScore", "0"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void createBody_invalidName_returns400() throws Exception {
        MockMultipartFile body = new MockMultipartFile("bodyImage", "b.png", "image/png", new byte[]{1});

        mockMvc.perform(multipart("/api/v1/admin/avatar/bodies")
                        .file(body)
                        .with(csrf())
                        .param("name", "BAD-NAME")  // 패턴 위반
                        .param("obtainableType", "BASIC")
                        .param("obtainableScore", "0"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------- POST publish/retire

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void publishBody_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/admin/avatar/bodies/5/publish")
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void retireBody_missingReason_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/avatar/bodies/5/retire")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void retireBody_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/admin/avatar/bodies/5/retire")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"이미지 오타\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void publishBody_invalidLifecycle_returns409() throws Exception {
        doThrow(ExceptionCreator.create(AvatarException.AVATAR_INVALID_LIFECYCLE_TRANSITION))
                .when(avatarCatalogCommandUseCase).publishBody(any(), any());

        mockMvc.perform(post("/api/v1/admin/avatar/bodies/5/publish")
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void publishBody_plainAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/avatar/bodies/5/publish")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
