package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminDjHistoryItemResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.PartyroomAnalyticsResponse;
import com.pfplaybackend.api.administration.application.dto.AttendanceAnalytics;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvcTest for the B-3 analytics / B-4 dj-history endpoints on
 * {@link AdminPartyroomQueryController}.
 *
 * <p>Locks the HTTP contract: admin gating (403 non-admin), 200 body schema,
 * days-reject → 400 (ADM-PR-002), missing room → 404, and dj-history size cap (200).
 */
class AdminPartyroomAnalyticsControllerTest extends AbstractAdminWebMvcTest {

    // ── B-3 analytics ──

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("analytics — 비어드민 → 403")
    void analytics_비어드민_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/partyrooms/1/analytics"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("analytics — 200 스키마 (attendance + silenceExit.approximate)")
    void analytics_200_스키마() throws Exception {
        PartyroomAnalyticsResponse response = new PartyroomAnalyticsResponse(
                20,
                new AttendanceAnalytics(4L, 3L, 3L, Collections.emptyList()),
                new PartyroomAnalyticsResponse.SilenceExit(true, 3L, 1L, 0.33, 12L));
        given(adminPartyroomAnalyticsQueryService.getAnalytics(eq(new PartyroomId(1L)), anyInt()))
                .willReturn(response);

        mockMvc.perform(get("/api/v1/admin/partyrooms/1/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attendance").exists())
                .andExpect(jsonPath("$.data.silenceExit.approximate").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("analytics — days 범위밖 → 400 (ADM-PR-002)")
    void analytics_days_범위밖_400() throws Exception {
        given(adminPartyroomAnalyticsQueryService.getAnalytics(any(PartyroomId.class), anyInt()))
                .willThrow(new BadRequestException("ADM-PR-002", "days out of range (1..90): 91"));

        mockMvc.perform(get("/api/v1/admin/partyrooms/1/analytics")
                        .param("days", "91"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("analytics — 없는 방 → 404")
    void analytics_없는방_404() throws Exception {
        given(adminPartyroomAnalyticsQueryService.getAnalytics(any(PartyroomId.class), anyInt()))
                .willThrow(ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));

        mockMvc.perform(get("/api/v1/admin/partyrooms/404/analytics"))
                .andExpect(status().isNotFound());
    }

    // ── B-4 dj-history ──

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("djHistory — size 200 초과 → 200으로 clamp")
    void djHistory_size_200_초과_clamp() throws Exception {
        Page<AdminDjHistoryItemResponse> emptyPage = new PageImpl<>(List.of());
        given(adminPartyroomAnalyticsQueryService.getDjHistory(any(PartyroomId.class), any(Pageable.class)))
                .willReturn(emptyPage);

        mockMvc.perform(get("/api/v1/admin/partyrooms/1/dj-history")
                        .param("size", "500"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(adminPartyroomAnalyticsQueryService)
                .getDjHistory(any(PartyroomId.class), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);
    }
}
