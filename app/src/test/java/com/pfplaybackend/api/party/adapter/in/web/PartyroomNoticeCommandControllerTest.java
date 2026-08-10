package com.pfplaybackend.api.party.adapter.in.web;

import com.pfplaybackend.api.party.application.dto.command.UpdatePartyroomNoticeCommand;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartyroomNoticeCommandControllerTest extends AbstractPartyCommandWebMvcTest {

    @Test
    @DisplayName("registerNotice — 204 No Content")
    void registerNoticeReturns204() throws Exception {
        mockMvc.perform(put("/api/v1/partyrooms/1/notice")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"content":"Welcome to the party!"}
                                """))
                .andExpect(status().isNoContent());

        verify(partyroomNoticeCommandService).updateNotice(
                new PartyroomId(1L), new UpdatePartyroomNoticeCommand("Welcome to the party!"));
    }

    @Test
    @DisplayName("registerNotice — 빈 문자열로 공지를 해제할 수 있다")
    void registerNoticeAllowsEmptyContent() throws Exception {
        mockMvc.perform(put("/api/v1/partyrooms/1/notice")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"content":""}
                                """))
                .andExpect(status().isNoContent());

        verify(partyroomNoticeCommandService).updateNotice(
                new PartyroomId(1L), new UpdatePartyroomNoticeCommand(""));
    }

    @Test
    @DisplayName("registerNotice — content 누락은 400")
    void registerNoticeMissingContentReturns400() throws Exception {
        mockMvc.perform(put("/api/v1/partyrooms/1/notice")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("registerNotice — 255자 초과는 400")
    void registerNoticeTooLongReturns400() throws Exception {
        String content = "x".repeat(256);

        mockMvc.perform(put("/api/v1/partyrooms/1/notice")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("registerNotice — 인증 없으면 401")
    void registerNoticeUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(put("/api/v1/partyrooms/1/notice")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"content":"Welcome"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
