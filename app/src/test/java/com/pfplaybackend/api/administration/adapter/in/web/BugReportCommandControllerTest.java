package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BugReportCommandControllerTest extends AbstractVocCommandWebMvcTest {

    @Test
    @DisplayName("submit — 201 Created + bugReportId 반환")
    void submitReturns201() throws Exception {
        when(bugReportCommandService.submit(any(), any(), any(), any(), any())).thenReturn(42L);

        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(authedUser(100L))
                        .with(csrf())
                        .header("Referer", "https://pfplay.xyz/parties/7")
                        .header("User-Agent", "Mozilla/5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"재생이 안 됩니다\",\"partyroomId\":7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bugReportId").value(42));
    }

    @Test
    @DisplayName("submit — 인증 주체 userId 가 service 로 전달된다 (AuthContext aspect 부재 회귀 가드)")
    void submitPassesAuthenticatedUserId() throws Exception {
        when(bugReportCommandService.submit(eq(100L), any(), any(), any(), any())).thenReturn(42L);

        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(authedUser(100L))
                        .with(csrf())
                        .header("Referer", "https://pfplay.xyz/parties/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"재생이 안 됩니다\",\"partyroomId\":7}"))
                .andExpect(status().isCreated());

        verify(bugReportCommandService).submit(eq(100L), eq("재생이 안 됩니다"),
                eq("https://pfplay.xyz/parties/7"), any(), eq(7L));
    }

    @Test
    @DisplayName("submit — content 너무 짧으면 400")
    void submitShortContentReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(authedUser(100L))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"abcd\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("submit — content blank 400")
    void submitBlankContentReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(authedUser(100L))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("submit — partyroomId 0/negative 400")
    void submitInvalidPartyroomIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(authedUser(100L))
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
    @DisplayName("submit — 게스트(ROLE_GUEST)도 201 (게스트 허용 회귀 잠금)")
    void submitAsGuestReturns201() throws Exception {
        // VOC 버그 신고는 게스트+AM+FM 모두 허용이 의도된 동작. 누군가 실수로 'MEMBER 만'
        // 가드를 넣으면 게스트가 막히는데, 기존 통과 케이스가 전부 ROLE_MEMBER 라 CI 가 못 잡았다.
        // 이 케이스가 게스트 허용 계약을 잠근다 (회귀 시 403 으로 실패).
        when(bugReportCommandService.submit(any(), any(), any(), any(), any())).thenReturn(42L);

        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(authedGuest(100L))
                        .with(csrf())
                        .header("Referer", "https://pfplay.xyz/parties/7")
                        .header("User-Agent", "Mozilla/5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"재생이 안 됩니다\",\"partyroomId\":7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bugReportId").value(42));
    }

    @Test
    @DisplayName("submit — rate-limit 시 429 + BUG-001")
    void submitRateLimitReturns429() throws Exception {
        doThrow(ExceptionCreator.create(BugReportException.RATE_LIMIT_EXCEEDED))
                .when(bugReportCommandService).submit(any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(authedUser(100L))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"valid content\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("BUG-001"));
    }
}
