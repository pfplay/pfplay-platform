package com.pfplaybackend.api.party.adapter.in.web;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartyroomCommandControllerTest extends AbstractPartyCommandWebMvcTest {

    @Test
    @DisplayName("createPartyroom — 201 Created + 서비스 호출")
    void createPartyroomReturns201() throws Exception {
        // given
        String body = """
                {
                    "title": "Test Room",
                    "introduction": "Welcome",
                    "linkDomain": "test-link",
                    "playbackTimeLimit": 10
                }
                """;
        PartyroomData partyroom = PartyroomData.builder()
                .id(1L).partyroomId(new PartyroomId(1L)).hostId(new UserId(1L)).stageType(StageType.GENERAL)
                .title("Test Room").introduction("Welcome")
                .linkDomain(LinkDomain.of("test-link"))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(10))
                .status(PartyroomStatus.ACTIVE).build();
        when(partyroomCommandService.createGeneralPartyRoom(any())).thenReturn(partyroom);

        // when & then
        mockMvc.perform(post("/api/v1/partyrooms")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("deletePartyroom — 204 No Content")
    void deletePartyroomReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/partyrooms/1")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("createPartyroom — 인증 없으면 401")
    void createPartyroomUnauthenticatedReturns401() throws Exception {
        String body = """
                {
                    "title": "Test Room",
                    "introduction": "Welcome",
                    "linkDomain": "test-link",
                    "playbackTimeLimit": 10
                }
                """;

        mockMvc.perform(post("/api/v1/partyrooms")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }
}
