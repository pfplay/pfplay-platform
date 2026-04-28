package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportStatusUpdateRequest;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import com.pfplaybackend.api.administration.domain.exception.AdminReportException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc test for {@link AdminReportCommandController} (PR 13 G4).
 *
 * <p>Covers:
 * <ul>
 *     <li>200 happy — service mock returns detail response</li>
 *     <li>400 — {@code status} null (Bean Validation @NotNull)</li>
 *     <li>400 — {@code status} enum 외 (Jackson binding)</li>
 *     <li>400 — {@code resolutionNote} >2000 chars (@Size)</li>
 *     <li>400 — {@code reportId} @Min(1) violation (path 0)</li>
 *     <li>400 — INVALID_STATE_TRANSITION (service throw RPT-002)</li>
 *     <li>400 — RESOLUTION_NOTE_REQUIRED (service throw RPT-003)</li>
 *     <li>404 — REPORT_NOT_FOUND (service throw RPT-001)</li>
 *     <li>401 anonymous, 403 non-admin role</li>
 * </ul>
 *
 * <p>Mirror of {@code AdminMemberTierCommandControllerTest} (PR 12b2 G2 패턴).
 */
class AdminReportCommandControllerTest extends AbstractAdminWebMvcTest {

    private static final long REPORT_ID = 11L;

    private static AdminReportDetailResponse detailStub(ReportStatus status) {
        return new AdminReportDetailResponse(
                REPORT_ID, status, ReportCategory.SPAM, "desc",
                new AdminReportDetailResponse.Reporter(200L, "r@g4it.local", "reporter"),
                new AdminReportDetailResponse.Partyroom(100L, "title",
                        new AdminReportDetailResponse.Host(300L, "host")),
                new AdminReportDetailResponse.Review(99L, "note", LocalDateTime.now()),
                LocalDateTime.now());
    }

    // ============================== happy ==============================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /admin/reports/{id} 200 — REVIEWING 진입 happy + detail response shape")
    void changeStatus_admin_returns200_reviewing() throws Exception {
        given(adminReportCommandService.changeStatus(eq(REPORT_ID), any(AdminReportStatusUpdateRequest.class)))
                .willReturn(detailStub(ReportStatus.REVIEWING));

        mockMvc.perform(patch("/api/v1/admin/reports/{id}", REPORT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportId").value(REPORT_ID))
                .andExpect(jsonPath("$.data.status").value("REVIEWING"))
                .andExpect(jsonPath("$.data.reporter.email").value("r@g4it.local"))
                .andExpect(jsonPath("$.data.partyroom.title").value("title"))
                .andExpect(jsonPath("$.data.partyroom.host.nickname").value("host"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH 200 — RESOLVED + resolutionNote happy")
    void changeStatus_admin_returns200_resolvedWithNote() throws Exception {
        given(adminReportCommandService.changeStatus(eq(REPORT_ID), any(AdminReportStatusUpdateRequest.class)))
                .willReturn(detailStub(ReportStatus.RESOLVED));

        mockMvc.perform(patch("/api/v1/admin/reports/{id}", REPORT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"resolutionNote\":\"확인 후 처리\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));
    }

    // ============================== 400 — Bean Validation / Jackson binding ==============================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH 400 — status null (@NotNull)")
    void changeStatus_nullStatus_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/reports/{id}", REPORT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH 400 — status enum 외 값 (HttpMessageNotReadable)")
    void changeStatus_invalidEnum_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/reports/{id}", REPORT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOT_A_STATUS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH 400 — resolutionNote >2000 chars (@Size)")
    void changeStatus_resolutionNoteTooLong_returns400() throws Exception {
        String tooLong = "a".repeat(2001);
        mockMvc.perform(patch("/api/v1/admin/reports/{id}", REPORT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"resolutionNote\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH 400 — reportId 0 (@Min(1) ConstraintViolation)")
    void changeStatus_reportIdZero_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/reports/{id}", 0L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWING\"}"))
                .andExpect(status().isBadRequest());
    }

    // ============================== 400 — service-thrown ==============================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH 400 — INVALID_STATE_TRANSITION RPT-002 (service throw)")
    void changeStatus_invalidStateTransition_returns400() throws Exception {
        willThrow(ExceptionCreator.create(AdminReportException.INVALID_STATE_TRANSITION))
                .given(adminReportCommandService).changeStatus(eq(REPORT_ID), any(AdminReportStatusUpdateRequest.class));

        mockMvc.perform(patch("/api/v1/admin/reports/{id}", REPORT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"resolutionNote\":\"note\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RPT-002"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH 400 — RESOLUTION_NOTE_REQUIRED RPT-003 (service throw)")
    void changeStatus_resolutionNoteRequired_returns400() throws Exception {
        willThrow(ExceptionCreator.create(AdminReportException.RESOLUTION_NOTE_REQUIRED))
                .given(adminReportCommandService).changeStatus(eq(REPORT_ID), any(AdminReportStatusUpdateRequest.class));

        mockMvc.perform(patch("/api/v1/admin/reports/{id}", REPORT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RPT-003"));
    }

    // ============================== 404 ==============================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH 404 — REPORT_NOT_FOUND RPT-001 (service throw)")
    void changeStatus_reportNotFound_returns404() throws Exception {
        willThrow(ExceptionCreator.create(AdminReportException.REPORT_NOT_FOUND))
                .given(adminReportCommandService).changeStatus(eq(999L), any(AdminReportStatusUpdateRequest.class));

        mockMvc.perform(patch("/api/v1/admin/reports/{id}", 999L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWING\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RPT-001"));
    }

    // ============================== auth ==============================

    @Test
    @WithAnonymousUser
    @DisplayName("PATCH 401 — anonymous")
    void changeStatus_anonymous_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/reports/{id}", REPORT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWING\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("PATCH 403 — 인증된 non-admin role")
    void changeStatus_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/reports/{id}", REPORT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWING\"}"))
                .andExpect(status().isForbidden());
    }
}
