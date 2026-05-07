package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportSummaryResponse;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import com.pfplaybackend.api.administration.domain.exception.AdminReportException;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvcTest for {@link AdminReportQueryController}.
 *
 * <p>Covers C-2 list (200/400/401/403) + detail (200/200 orphan/401/403/404). Bean
 * Validation 표준 패턴(@Validated + @Min/@Max/@Pattern) — 위반 시
 * {@code GlobalExceptionHandler}가 400. cross-field {@code created_from > created_to}는
 * inline guard로 {@link AdminReportException#INVALID_LIST_QUERY} (RPT-004) 반환.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.2, §3.3
 */
class AdminReportQueryControllerTest extends AbstractAdminWebMvcTest {

    // ============================== list ==============================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/reports 200 — Page envelope + content row")
    void getList_200_pageEnvelope() throws Exception {
        AdminReportSummaryResponse row = new AdminReportSummaryResponse(
                11L, 100L, 200L,
                ReportCategory.SPAM, ReportStatus.PENDING,
                LocalDateTime.of(2026, 4, 28, 12, 0),
                null, null);
        Page<AdminReportSummaryResponse> page =
                new PageImpl<>(List.of(row), PageRequest.of(0, 50), 1L);
        given(adminReportQueryService.getList(any(), any(Pageable.class))).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reportId").value(11))
                .andExpect(jsonPath("$.data.content[0].partyroomId").value(100))
                .andExpect(jsonPath("$.data.content[0].reporterUserAccountId").value(200))
                .andExpect(jsonPath("$.data.content[0].category").value("SPAM"))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/reports 400 — size > 200 (@Max)")
    void getList_400_whenSizeExceedsMax() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports").param("size", "999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/reports 400 — page < 0 (@Min)")
    void getList_400_whenPageNegative() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/reports 400 — sort 허용 외 (@Pattern)")
    void getList_400_whenSortInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports").param("sort", "foo_bar"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/reports 400 — created_from > created_to (RPT-004)")
    void getList_400_whenDateRangeInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports")
                        .param("created_from", "2026-12-31")
                        .param("created_to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RPT-004"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /admin/reports 401 — anonymous")
    void getList_401_anonymous() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "u", roles = {"USER"})
    @DisplayName("GET /admin/reports 403 — non-admin role")
    void getList_403_nonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports"))
                .andExpect(status().isForbidden());
    }

    // ============================== detail ==============================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/reports/{id} 200 — nested DTO 직렬화")
    void getDetail_200_nestedDto() throws Exception {
        AdminReportDetailResponse response = new AdminReportDetailResponse(
                11L,
                ReportStatus.REVIEWING,
                ReportCategory.HARASSMENT,
                "Bad behavior",
                new AdminReportDetailResponse.Reporter(200L, "reporter@x.com", "Reporter Nick"),
                new AdminReportDetailResponse.Partyroom(100L, "Hot Room",
                        new AdminReportDetailResponse.Host(300L, "Host Nick")),
                new AdminReportDetailResponse.Review(999L, null, null),
                LocalDateTime.of(2026, 4, 28, 12, 0));
        given(adminReportQueryService.getDetail(11L)).willReturn(response);

        mockMvc.perform(get("/api/v1/admin/reports/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportId").value(11))
                .andExpect(jsonPath("$.data.status").value("REVIEWING"))
                .andExpect(jsonPath("$.data.category").value("HARASSMENT"))
                .andExpect(jsonPath("$.data.description").value("Bad behavior"))
                .andExpect(jsonPath("$.data.reporter.userAccountId").value(200))
                .andExpect(jsonPath("$.data.reporter.email").value("reporter@x.com"))
                .andExpect(jsonPath("$.data.reporter.nickname").value("Reporter Nick"))
                .andExpect(jsonPath("$.data.partyroom.partyroomId").value(100))
                .andExpect(jsonPath("$.data.partyroom.title").value("Hot Room"))
                .andExpect(jsonPath("$.data.partyroom.host.userAccountId").value(300))
                .andExpect(jsonPath("$.data.partyroom.host.nickname").value("Host Nick"))
                .andExpect(jsonPath("$.data.review.reviewedByAdministratorId").value(999));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/reports/{id} 200 — orphan tolerance: reporter.nickname=null, partyroom.title=null")
    void getDetail_200_orphanTolerance() throws Exception {
        AdminReportDetailResponse response = new AdminReportDetailResponse(
                12L,
                ReportStatus.PENDING,
                ReportCategory.OTHER,
                null,
                new AdminReportDetailResponse.Reporter(200L, "ghost@x.com", null),
                new AdminReportDetailResponse.Partyroom(100L, null, null),
                new AdminReportDetailResponse.Review(null, null, null),
                LocalDateTime.of(2026, 4, 28, 12, 0));
        given(adminReportQueryService.getDetail(12L)).willReturn(response);

        mockMvc.perform(get("/api/v1/admin/reports/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reporter.email").value("ghost@x.com"))
                .andExpect(jsonPath("$.data.reporter.nickname").doesNotExist())
                .andExpect(jsonPath("$.data.partyroom.partyroomId").value(100))
                .andExpect(jsonPath("$.data.partyroom.title").doesNotExist())
                .andExpect(jsonPath("$.data.partyroom.host").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/reports/{id} 404 — REPORT_NOT_FOUND (RPT-001)")
    void getDetail_404_reportNotFound() throws Exception {
        willThrow(ExceptionCreator.create(AdminReportException.REPORT_NOT_FOUND))
                .given(adminReportQueryService).getDetail(99L);

        mockMvc.perform(get("/api/v1/admin/reports/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RPT-001"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /admin/reports/{id} 401 — anonymous")
    void getDetail_401_anonymous() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports/11"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "u", roles = {"USER"})
    @DisplayName("GET /admin/reports/{id} 403 — non-admin role")
    void getDetail_403_nonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports/11"))
                .andExpect(status().isForbidden());
    }
}
