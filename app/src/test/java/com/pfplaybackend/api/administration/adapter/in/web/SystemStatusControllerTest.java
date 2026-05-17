package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AnnouncementSummaryResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.SystemStatusResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.SystemStatusResponse.MaintenanceInfo;
import com.pfplaybackend.api.administration.application.service.SystemAnnouncementQueryService;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc test for {@link SystemStatusController} (M5 V14 Task 7).
 *
 * <p>Coverage:
 * <ul>
 *     <li>{@code status_anonymous_returns200} — anonymous + payload (maintenance + active + planned)</li>
 *     <li>{@code status_emptyState_returns200} — null/empty payload</li>
 *     <li>{@code status_cacheControlHeaderSet} — Cache-Control: public, max-age=10</li>
 * </ul>
 *
 * <p>{@code addFilters = false} — admin authentication filter chain bypass (anonymous endpoint).
 * 패턴 mirror: {@code AuthControllerTest}.
 */
@WebMvcTest(SystemStatusController.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemStatusControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean SystemAnnouncementQueryService queryService;

    @Test
    @DisplayName("GET /api/v1/system/status 200 — anonymous, maintenance + active + planned 모두 반환")
    void status_anonymous_returns200() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 3, 12, 0);
        MaintenanceInfo current = new MaintenanceInfo(
                "ACTIVE", now.minusMinutes(5), now.plusMinutes(55), "점검 중", "Maintenance");
        AnnouncementSummaryResponse event = new AnnouncementSummaryResponse(
                10L, AnnouncementType.EVENT, AnnouncementSeverity.INFO,
                "행사", "Event", "본문", "body",
                null, null, null, now, 1L, null, null, null);
        AnnouncementSummaryResponse emergency = new AnnouncementSummaryResponse(
                11L, AnnouncementType.EMERGENCY, AnnouncementSeverity.CRITICAL,
                "긴급", "Emergency", "긴급 본문", "emergency body",
                null, null, null, now, 1L, null, null, null);
        MaintenanceInfo planned = new MaintenanceInfo(
                "PLANNED", now.plusHours(2), now.plusHours(3), "예정", "Planned");
        given(queryService.getSystemStatus()).willReturn(
                new SystemStatusResponse(current, List.of(event, emergency), List.of(planned)));

        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maintenance.phase").value("ACTIVE"))
                .andExpect(jsonPath("$.data.maintenance.messageKo").value("점검 중"))
                .andExpect(jsonPath("$.data.activeAnnouncements.length()").value(2))
                .andExpect(jsonPath("$.data.activeAnnouncements[0].type").value("EVENT"))
                .andExpect(jsonPath("$.data.activeAnnouncements[1].type").value("EMERGENCY"))
                .andExpect(jsonPath("$.data.plannedMaintenance.length()").value(1))
                .andExpect(jsonPath("$.data.plannedMaintenance[0].phase").value("PLANNED"));
    }

    @Test
    @DisplayName("GET 200 — empty state (maintenance=null, lists empty)")
    void status_emptyState_returns200() throws Exception {
        given(queryService.getSystemStatus()).willReturn(
                new SystemStatusResponse(null, List.of(), List.of()));

        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maintenance").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.activeAnnouncements.length()").value(0))
                .andExpect(jsonPath("$.data.plannedMaintenance.length()").value(0));
    }

    @Test
    @DisplayName("GET — Cache-Control: public, max-age=10 헤더")
    void status_cacheControlHeaderSet() throws Exception {
        given(queryService.getSystemStatus()).willReturn(
                new SystemStatusResponse(null, List.of(), List.of()));

        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=10, public"));
    }
}
