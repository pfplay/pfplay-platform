package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminBugReportListResponse;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

class AdminBugReportQueryControllerTest extends AbstractAdminWebMvcTest {

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/voc/bug-reports — 200 + items")
    void getListReturns200() throws Exception {
        AdminBugReportListResponse response = new AdminBugReportListResponse(
                1L, 1L, 0, 20,
                List.of(new AdminBugReportSummaryDto(
                        1L, 100L, "user@x.com", null, "preview", 7L,
                        LocalDateTime.of(2026, 5, 21, 10, 0))));
        given(adminBugReportQueryService.getList(any())).willReturn(response);

        mockMvc.perform(get("/api/v1/admin/voc/bug-reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].bugReportId").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/voc/bug-reports — 잘못된 sortBy 400 BUG-002")
    void getListInvalidSortBy400() throws Exception {
        willThrow(ExceptionCreator.create(BugReportException.INVALID_LIST_QUERY))
                .given(adminBugReportQueryService).getList(any());

        mockMvc.perform(get("/api/v1/admin/voc/bug-reports").param("sortBy", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BUG-002"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /admin/voc/bug-reports — admin 미인증 401")
    void getListUnauthorized401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/voc/bug-reports"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/voc/bug-reports/{id} — 200")
    void getDetailReturns200() throws Exception {
        given(adminBugReportQueryService.getDetail(1L)).willReturn(
                new AdminBugReportDetailDto(1L, 100L, "user@x.com", null,
                        "content body", "https://pfplay.xyz/parties/7", "Mozilla/5.0",
                        7L, "테스트 룸", LocalDateTime.of(2026, 5, 21, 10, 0)));

        mockMvc.perform(get("/api/v1/admin/voc/bug-reports/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.content").value("content body"))
                .andExpect(jsonPath("$.data.detail.partyroomName").value("테스트 룸"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/voc/bug-reports/{id} — 미존재 404 BUG-003")
    void getDetailNotFound404() throws Exception {
        willThrow(ExceptionCreator.create(BugReportException.BUG_REPORT_NOT_FOUND))
                .given(adminBugReportQueryService).getDetail(999L);

        mockMvc.perform(get("/api/v1/admin/voc/bug-reports/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BUG-003"));
    }
}
