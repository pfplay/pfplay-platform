package com.pfplaybackend.api.party.adapter.in.web;

import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartyroomAccessCommandControllerTest extends AbstractPartyCommandWebMvcTest {

    @Test
    @DisplayName("enterPartyroom — countryCode 포함 요청 시 201 Created")
    void enterPartyroomWithCountryCodeReturns201() throws Exception {
        // given
        CrewData crew = mock(CrewData.class);
        when(crew.getId()).thenReturn(1L);
        when(crew.getGradeType()).thenReturn(GradeType.CLUBBER);
        when(partyroomAccessCommandService.tryEnter(any(), any())).thenReturn(crew);

        // when & then
        mockMvc.perform(post("/api/v1/partyrooms/1/crews")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryCode\":\"KR\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("enterPartyroom — countryCode null 요청도 201 Created")
    void enterPartyroomWithNullCountryCodeReturns201() throws Exception {
        // given
        CrewData crew = mock(CrewData.class);
        when(crew.getId()).thenReturn(1L);
        when(crew.getGradeType()).thenReturn(GradeType.CLUBBER);
        when(partyroomAccessCommandService.tryEnter(any(), any())).thenReturn(crew);

        // when & then
        mockMvc.perform(post("/api/v1/partyrooms/1/crews")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryCode\":null}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("enterPartyroom — body 자체 생략 시에도 201 Created (frontend가 국기 기능 비활성화 케이스)")
    void enterPartyroomWithoutBodyReturns201() throws Exception {
        // given
        CrewData crew = mock(CrewData.class);
        when(crew.getId()).thenReturn(1L);
        when(crew.getGradeType()).thenReturn(GradeType.CLUBBER);
        when(partyroomAccessCommandService.tryEnter(any(), any())).thenReturn(crew);

        // when & then — no .content(), no .contentType()
        mockMvc.perform(post("/api/v1/partyrooms/1/crews")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("enterPartyroom — Content-Type만 있고 body가 빈 경우에도 201 Created")
    void enterPartyroomWithEmptyBodyReturns201() throws Exception {
        // given
        CrewData crew = mock(CrewData.class);
        when(crew.getId()).thenReturn(1L);
        when(crew.getGradeType()).thenReturn(GradeType.CLUBBER);
        when(partyroomAccessCommandService.tryEnter(any(), any())).thenReturn(crew);

        // when & then — empty JSON object
        mockMvc.perform(post("/api/v1/partyrooms/1/crews")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("enterPartyroom — 잘못된 countryCode 형식은 400")
    void enterPartyroomWithInvalidCountryCodeReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/partyrooms/1/crews")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryCode\":\"kr\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("exitPartyroom — 204 No Content")
    void exitPartyroomReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/partyrooms/1/crews/me")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("enterPartyroom — 인증 없으면 401")
    void enterPartyroomUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/partyrooms/1/crews")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryCode\":\"KR\"}"))
                .andExpect(status().isUnauthorized());
    }
}
