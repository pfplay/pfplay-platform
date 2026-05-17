package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AnnouncementSummaryResponse;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.exception.AnnouncementException;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc test for {@link AdminAnnouncementController} (M5 V14 Task 6).
 *
 * <p>Coverage:
 * <ul>
 *     <li>publish 201 — happy</li>
 *     <li>publish 400 — titleKo blank (Bean Validation @NotBlank)</li>
 *     <li>publish 400 — titleKo >200 chars (@Size)</li>
 *     <li>list 200 — sentAt DESC paged</li>
 *     <li>delete 200 — happy</li>
 *     <li>delete 404 — service throws ANN-001</li>
 * </ul>
 *
 * <p>패턴 mirror: {@code AdminAvatarCommandControllerTest} (BeforeEach AdminContext wiring).
 */
class AdminAnnouncementControllerTest extends AbstractAdminWebMvcTest {

    private static final long ANNOUNCEMENT_ID = 42L;

    @BeforeEach
    void wireAdminContext() {
        given(adminContext.currentAdministratorId()).willReturn(1L);
    }

    // ============================== publish ==============================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /admin/announcements 201 — EVENT happy")
    void publish_validRequest_returns201() throws Exception {
        given(systemAnnouncementCommandService.publish(
                any(AnnouncementType.class), any(AnnouncementSeverity.class),
                any(), any(), any(), any(),
                any(), any(), any(),
                anyLong()))
                .willReturn(ANNOUNCEMENT_ID);

        mockMvc.perform(post("/api/v1/admin/announcements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"EVENT","severity":"INFO",
                                  "titleKo":"행사","titleEn":"Event",
                                  "messageKo":"본문","messageEn":"body"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.announcementId").value(ANNOUNCEMENT_ID));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 400 — titleKo blank (@NotBlank)")
    void publish_blankTitle_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/announcements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"EVENT","severity":"INFO",
                                  "titleKo":"","titleEn":"Event",
                                  "messageKo":"본문","messageEn":"body"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 400 — titleKo >200 chars (@Size)")
    void publish_titleTooLong_returns400() throws Exception {
        String longTitle = "a".repeat(201);
        String body = """
                {
                  "type":"EVENT","severity":"INFO",
                  "titleKo":"%s","titleEn":"Event",
                  "messageKo":"본문","messageEn":"body"
                }
                """.formatted(longTitle);

        mockMvc.perform(post("/api/v1/admin/announcements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ============================== list ==============================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/announcements 200 — paged response")
    void list_returnsPagedResponse_returns200() throws Exception {
        SystemAnnouncementData entity = SystemAnnouncementData.create(
                AnnouncementType.EVENT, AnnouncementSeverity.INFO,
                "제목", "title", "본문", "body",
                null, null, null,
                LocalDateTime.now(), 1L);

        Page<SystemAnnouncementData> page = new PageImpl<>(List.of(entity), Pageable.ofSize(20), 1);
        given(systemAnnouncementRepository.findAll(any(Pageable.class))).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/announcements")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].titleKo").value("제목"))
                .andExpect(jsonPath("$.data.content[0].type").value("EVENT"))
                .andExpect(jsonPath("$.data.content[0].severity").value("INFO"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    // ============================== delete ==============================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /admin/announcements/{id} 200 — happy")
    void delete_validId_returns200() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/announcements/{id}", ANNOUNCEMENT_ID)
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE 404 — ANN-001 ANNOUNCEMENT_NOT_FOUND (service throw)")
    void delete_notFound_returns404() throws Exception {
        willThrow(ExceptionCreator.create(AnnouncementException.ANNOUNCEMENT_NOT_FOUND))
                .given(systemAnnouncementCommandService).cancel(eq(999L), anyLong());

        mockMvc.perform(delete("/api/v1/admin/announcements/{id}", 999L)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ANN-001"));
    }

    // ============================== adjustSchedule ==============================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /{id}/schedule 200 — happy")
    void adjustSchedule_returns200() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/announcements/{id}/schedule", ANNOUNCEMENT_ID)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledEndAt\":\"2026-05-04T05:00:00\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /{id}/schedule 409 — ANN-007 비-ACTIVE")
    void adjustSchedule_notActive_returns409() throws Exception {
        willThrow(ExceptionCreator.create(AnnouncementException.NOT_ACTIVE_MAINTENANCE))
                .given(systemAnnouncementCommandService).adjustEndTime(eq(ANNOUNCEMENT_ID), any(), anyLong());
        mockMvc.perform(patch("/api/v1/admin/announcements/{id}/schedule", ANNOUNCEMENT_ID)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledEndAt\":\"2026-05-04T05:00:00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ANN-007"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /{id}/schedule 400 — scheduledEndAt 누락(@NotNull)")
    void adjustSchedule_missingBody_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/announcements/{id}/schedule", ANNOUNCEMENT_ID)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ============================== complete ==============================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /{id}/complete 200 — happy")
    void complete_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/admin/announcements/{id}/complete", ANNOUNCEMENT_ID)
                        .with(csrf())).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /{id}/complete 409 — ANN-008 이미 완료")
    void complete_alreadyCompleted_returns409() throws Exception {
        willThrow(ExceptionCreator.create(AnnouncementException.ALREADY_COMPLETED))
                .given(systemAnnouncementCommandService).complete(eq(ANNOUNCEMENT_ID), anyLong());
        mockMvc.perform(post("/api/v1/admin/announcements/{id}/complete", ANNOUNCEMENT_ID)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ANN-008"));
    }
}
