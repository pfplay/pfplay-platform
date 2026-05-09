package com.pfplaybackend.api.partyview.adapter.in.web;

import com.pfplaybackend.api.party.adapter.in.web.AbstractPartyQueryWebMvcTest;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.partyview.application.dto.DisplayDto;
import com.pfplaybackend.api.partyview.application.dto.result.PartyroomSetupResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartyroomSetupControllerTest extends AbstractPartyQueryWebMvcTest {

    @Test
    @DisplayName("getSetupInfo — 200 OK")
    void getSetupInfoReturns200() throws Exception {
        // given
        PartyroomSetupResult result = new PartyroomSetupResult(StageType.MAIN, List.of(), mock(DisplayDto.class));
        when(partyroomSetupQueryService.getSetupInfo(any())).thenReturn(result);

        // when & then
        mockMvc.perform(get("/api/v1/partyrooms/1/setup")
                        .with(jwt().authorities(() -> "ROLE_MEMBER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("getSetupInfo — 응답 본문에 stageType 필드가 포함된다 (Amplitude L1)")
    void getSetupInfoIncludesStageType() throws Exception {
        // given — service가 GENERAL 스테이지 룸을 반환
        PartyroomSetupResult result = new PartyroomSetupResult(StageType.GENERAL, List.of(), mock(DisplayDto.class));
        when(partyroomSetupQueryService.getSetupInfo(any())).thenReturn(result);

        // when & then — stageType이 응답 최상위에 enum-name 문자열로 노출되어야 한다
        mockMvc.perform(get("/api/v1/partyrooms/1/setup")
                        .with(jwt().authorities(() -> "ROLE_MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stageType", equalTo("GENERAL")));
    }

    @Test
    @DisplayName("getSetupInfo — 인증 없으면 401")
    void getSetupInfoUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/partyrooms/1/setup"))
                .andExpect(status().isUnauthorized());
    }
}
