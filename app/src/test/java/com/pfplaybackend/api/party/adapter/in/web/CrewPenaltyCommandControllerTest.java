package com.pfplaybackend.api.party.adapter.in.web;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.exception.PenaltyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CrewPenaltyCommandControllerTest extends AbstractPartyCommandWebMvcTest {

    @Test
    @DisplayName("imposeCrewPenalty — 201 Created + penaltyId 반환")
    void imposeCrewPenaltyReturns201WithPenaltyId() throws Exception {
        // given
        String body = """
                {"crewId": 1, "penaltyType": "ONE_TIME_EXPULSION", "detail": "Disruptive behavior"}
                """;
        when(crewPenaltyCommandService.addPenalty(any(), any())).thenReturn(77L);

        // when & then
        mockMvc.perform(post("/api/v1/partyrooms/1/penalties")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.penaltyId").value(77));
    }

    @Test
    @DisplayName("releaseCrewPenalty — 204 No Content")
    void releaseCrewPenaltyReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/partyrooms/1/penalties/100")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("imposeCrewPenalty — 인증 없으면 401")
    void imposeCrewPenaltyUnauthenticatedReturns401() throws Exception {
        String body = """
                {"crewId": 1, "penaltyType": "ONE_TIME_EXPULSION", "detail": "Disruptive behavior"}
                """;

        mockMvc.perform(post("/api/v1/partyrooms/1/penalties")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("releaseCrewPenalty — admin이 부과한 페널티는 403 (PNT-003)")
    void releaseCrewPenaltyAdminAppliedReturns403() throws Exception {
        // given
        doThrow(ExceptionCreator.create(PenaltyException.ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE))
                .when(crewPenaltyCommandService).releaseCrewPenalty(any(), eq(999L));

        // when & then
        mockMvc.perform(delete("/api/v1/partyrooms/1/penalties/999")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
