package com.pfplaybackend.api.party.adapter.in.web;

import com.pfplaybackend.api.party.application.dto.playback.AddedTrackDto;
import com.pfplaybackend.api.party.application.dto.playback.ReactionHistoryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlaybackReactionCommandControllerTest extends AbstractPartyCommandWebMvcTest {

    @Test
    @DisplayName("reactToPlayback LIKE — 200 OK, addedTrack null")
    void reactToPlaybackReturns200() throws Exception {
        // given
        String body = """
                {
                    "reactionType": "LIKE"
                }
                """;
        when(playbackReactionCommandService.reactToCurrentPlayback(any(), any()))
                .thenReturn(new ReactionHistoryDto(true, false, false, null));

        // when & then
        mockMvc.perform(post("/api/v1/partyrooms/1/playbacks/reaction")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addedTrack").value(nullValue()));
    }

    @Test
    @DisplayName("reactToPlayback GRAB 성공 — 응답 body에 addedTrack {trackId, playlistId} 포함")
    void reactToPlaybackGrabIncludesAddedTrack() throws Exception {
        // given
        String body = """
                {
                    "reactionType": "GRAB"
                }
                """;
        when(playbackReactionCommandService.reactToCurrentPlayback(any(), any()))
                .thenReturn(new ReactionHistoryDto(true, false, true, new AddedTrackDto(12345L, 67L)));

        // when & then
        mockMvc.perform(post("/api/v1/partyrooms/1/playbacks/reaction")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addedTrack.trackId").value(12345))
                .andExpect(jsonPath("$.data.addedTrack.playlistId").value(67))
                .andExpect(jsonPath("$.data.isGrabbed").value(true));
    }

    @Test
    @DisplayName("reactToPlayback — 인증 없으면 401")
    void reactToPlaybackUnauthenticatedReturns401() throws Exception {
        String body = """
                {
                    "reactionType": "LIKE"
                }
                """;

        mockMvc.perform(post("/api/v1/partyrooms/1/playbacks/reaction")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }
}
