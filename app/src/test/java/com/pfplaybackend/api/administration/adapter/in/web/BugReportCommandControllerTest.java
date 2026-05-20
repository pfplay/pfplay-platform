package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BugReportCommandControllerTest extends AbstractVocCommandWebMvcTest {

    @Test
    @DisplayName("submit — 201 Created + bugReportId 반환")
    void submitReturns201() throws Exception {
        when(bugReportCommandService.submit(any(), any(), any(), any())).thenReturn(42L);

        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .header("Referer", "https://pfplay.xyz/parties/7")
                        .header("User-Agent", "Mozilla/5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"재생이 안 됩니다\",\"partyroomId\":7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bugReportId").value(42));
    }

    @Test
    @DisplayName("submit — content 너무 짧으면 400")
    void submitShortContentReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"abcd\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("submit — content blank 400")
    void submitBlankContentReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("submit — partyroomId 0/negative 400")
    void submitInvalidPartyroomIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"long enough content\",\"partyroomId\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("submit — 인증 없으면 401")
    void submitUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"valid content\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("submit — rate-limit 시 429 + BUG-001")
    void submitRateLimitReturns429() throws Exception {
        doThrow(ExceptionCreator.create(BugReportException.RATE_LIMIT_EXCEEDED))
                .when(bugReportCommandService).submit(any(), any(), any(), any());

        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"valid content\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("BUG-001"));
    }
}
