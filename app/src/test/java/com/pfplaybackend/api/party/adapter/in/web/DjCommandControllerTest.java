package com.pfplaybackend.api.party.adapter.in.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.exception.DjException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DjCommandControllerTest extends AbstractPartyCommandWebMvcTest {

    @Test
    @DisplayName("enqueueDj — 201 Created + djId 반환")
    void enqueueDjReturns201WithDjId() throws Exception {
        // given
        String body = """
                {
                    "playlistId": 1
                }
                """;
        when(djCommandService.enqueueDj(any(), any())).thenReturn(42L);

        // when & then
        mockMvc.perform(post("/api/v1/partyrooms/1/dj-queue")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.djId").value(42));
    }

    @Test
    @DisplayName("dequeueDj — 204 No Content")
    void dequeueDjReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/partyrooms/1/dj-queue/me")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("enqueueDj — 인증 없으면 401")
    void enqueueDjUnauthenticatedReturns401() throws Exception {
        String body = """
                {
                    "playlistId": 1
                }
                """;

        mockMvc.perform(post("/api/v1/partyrooms/1/dj-queue")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("changePlaylist — 204 No Content")
    void changePlaylistReturns204() throws Exception {
        mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playlistId\": 99}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("changePlaylist — playlistId null 이면 400")
    void changePlaylistNullPlaylistIdReturns400() throws Exception {
        mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("changePlaylist — playlistId 0 이면 400")
    void changePlaylistZeroPlaylistIdReturns400() throws Exception {
        mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playlistId\": 0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("changePlaylist — 인증 없으면 401")
    void changePlaylistUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playlistId\": 99}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("changePlaylist — DJ-004 NOT_FOUND_DJ → 404")
    void changePlaylistNotFoundReturns404() throws Exception {
        doThrow(ExceptionCreator.create(DjException.NOT_FOUND_DJ))
                .when(djCommandService).changePlaylist(any(), any());

        mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playlistId\": 99}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("DJ-004"));
    }

    @Test
    @DisplayName("changePlaylist — DJ-005 NOT_OWNED_PLAYLIST → 403 (미존재 playlistId 포함)")
    void changePlaylistNotOwnedReturns403() throws Exception {
        doThrow(ExceptionCreator.create(DjException.NOT_OWNED_PLAYLIST))
                .when(djCommandService).changePlaylist(any(), any());

        mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playlistId\": 99}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("DJ-005"));
    }

    @Test
    @DisplayName("changePlaylist — DJ-003 EMPTY_PLAYLIST → 403")
    void changePlaylistEmptyReturns403() throws Exception {
        doThrow(ExceptionCreator.create(DjException.EMPTY_PLAYLIST))
                .when(djCommandService).changePlaylist(any(), any());

        mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playlistId\": 99}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("DJ-003"));
    }

    @Test
    @DisplayName("changePlaylist — DJ-006 CURRENT_DJ_CANNOT_CHANGE_PLAYLIST → 409")
    void changePlaylistCurrentDjReturns409() throws Exception {
        doThrow(ExceptionCreator.create(DjException.CURRENT_DJ_CANNOT_CHANGE_PLAYLIST))
                .when(djCommandService).changePlaylist(any(), any());

        mockMvc.perform(patch("/api/v1/partyrooms/1/dj-queue/me")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playlistId\": 99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DJ-006"));
    }
}
