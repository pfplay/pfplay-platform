package com.pfplaybackend.api.admin.adapter.in.web;

import com.pfplaybackend.api.admin.application.dto.result.AdminPartyroomListResult;
import com.pfplaybackend.api.admin.application.dto.result.DemoEnvironmentResult;
import com.pfplaybackend.api.admin.application.dto.result.DemoStatusResult;
import com.pfplaybackend.api.admin.application.dto.result.SimulateReactionsResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminDemoControllerTest extends AbstractAdminWebMvcTest {

    @Test
    @DisplayName("getDemoEnvironmentStatus — 200 OK")
    void getDemoEnvironmentStatusReturns200() throws Exception {
        // given
        DemoStatusResult result = new DemoStatusResult(true, 400L, 12L);
        when(adminDemoService.getDemoEnvironmentStatus()).thenReturn(result);

        // when & then
        mockMvc.perform(get("/api/v1/admin/demo/status")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.virtualMemberCount").value(400))
                .andExpect(jsonPath("$.generalRoomCount").value(12));
    }

    @Test
    @DisplayName("getPartyrooms — FM 권한이면 200 OK")
    void getPartyroomsWithFmAuthorityReturns200() throws Exception {
        // given
        AdminPartyroomListResult result = new AdminPartyroomListResult(List.of());
        when(adminDemoService.getPartyrooms()).thenReturn(result);

        // when & then
        mockMvc.perform(get("/api/v1/admin/demo/partyrooms")
                        .with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyrooms").isArray());
    }

    @Test
    @DisplayName("getPartyrooms — 인증 없으면 401")
    void getPartyroomsUnauthenticatedReturns401() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/admin/demo/partyrooms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("initializeDemoEnvironment — FM 권한이면 201 Created")
    void initializeDemoEnvironmentWithFmAuthorityReturns201() throws Exception {
        // given
        String body = """
                {"playbackTimeLimit": 5, "titlePrefix": "Room", "introduction": "Hello", "registerDjs": true}""";

        DemoEnvironmentResult result = new DemoEnvironmentResult(400, 13, 13, 13, 1000L,
                new DemoEnvironmentResult.PartyroomDetail(1L, "MAIN", "Main Stage", "main-stage", "uid1", 40, "uid1", 1L),
                List.of());
        when(adminDemoService.initializeDemoEnvironment(any())).thenReturn(result);

        // when & then
        mockMvc.perform(post("/api/v1/admin/demo/init")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("simulateReactions — FM 권한이면 200 OK")
    void simulateReactionsWithFmAuthorityReturns200() throws Exception {
        // given
        String body = """
                {"reactionCount": 5}""";

        SimulateReactionsResult result = new SimulateReactionsResult(1L, 1L, List.of(),
                new SimulateReactionsResult.AggregationCounts(5, 0, 2));
        when(adminPartyroomService.simulateReactions(1L)).thenReturn(result);

        // when & then
        mockMvc.perform(post("/api/v1/admin/demo/partyrooms/1/reactions")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
