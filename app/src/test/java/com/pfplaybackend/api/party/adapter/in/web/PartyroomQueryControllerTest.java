package com.pfplaybackend.api.party.adapter.in.web;

import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.dto.result.DjQueueInfoResult;
import com.pfplaybackend.api.party.domain.enums.QueueStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartyroomQueryControllerTest extends AbstractPartyQueryWebMvcTest {

    @Test
    @DisplayName("getPartyrooms — 200 OK + 파티룸 목록 반환")
    void getPartyroomsReturns200() throws Exception {
        // given
        when(partyroomQueryService.getAllPartyrooms()).thenReturn(List.of());
        when(partyroomQueryService.getPrimariesAvatarSettings(any())).thenReturn(Map.of());

        // when & then
        mockMvc.perform(get("/api/v1/partyrooms")
                        .with(jwt().authorities(() -> "ROLE_MEMBER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("getPartyroomSummaryInfo — 200 OK")
    void getPartyroomSummaryInfoReturns200() throws Exception {
        // given
        when(partyroomQueryService.getSummaryInfo(any())).thenReturn(null);

        // when & then
        mockMvc.perform(get("/api/v1/partyrooms/1/summary")
                        .with(jwt().authorities(() -> "ROLE_MEMBER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("getDjQueueInfo — 200 OK")
    void getDjQueueInfoReturns200() throws Exception {
        // given
        when(partyroomQueryService.getDjQueueInfo(any())).thenReturn(
                new DjQueueInfoResult(false, QueueStatus.OPEN, false, null, List.of()));

        // when & then
        mockMvc.perform(get("/api/v1/partyrooms/1/dj-queue")
                        .with(jwt().authorities(() -> "ROLE_MEMBER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("getMyActivePartyroom — 활성 방 있음: 200 + 스냅샷(partyroomId, crewId) 반환")
    void getMyActivePartyroomReturnsSnapshot() throws Exception {
        // given — 재연결 resync가 되훔침(tryEnter) 대신 조회할 '내 활성 방' 스냅샷
        when(partyroomQueryService.getMyActivePartyroom()).thenReturn(
                Optional.of(new ActivePartyroomDto(42L, false, 7L, false, null, null)));

        // when & then
        mockMvc.perform(get("/api/v1/partyrooms/me/active")
                        .with(jwt().authorities(() -> "ROLE_MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partyroomId").value(42))
                .andExpect(jsonPath("$.data.crewId").value(7));
    }

    @Test
    @DisplayName("getMyActivePartyroom — 활성 방 없음: 204 No Content (로비로 분기)")
    void getMyActivePartyroomReturnsNoContentWhenNoActiveRoom() throws Exception {
        // given
        when(partyroomQueryService.getMyActivePartyroom()).thenReturn(Optional.empty());

        // when & then — 200 + {data:null} 은 web 인터셉터(response.data?.data ?? response.data)에서
        // 래퍼 객체로 오역되므로, "활성 방 없음" 은 본문 없는 204 로 명확히 표현한다.
        mockMvc.perform(get("/api/v1/partyrooms/me/active")
                        .with(jwt().authorities(() -> "ROLE_MEMBER")))
                .andExpect(status().isNoContent());
    }
}
