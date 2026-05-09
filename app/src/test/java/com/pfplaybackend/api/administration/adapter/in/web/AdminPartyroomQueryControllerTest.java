package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomDetailResponse.PlaybackSummary;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomListItemResponse;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListFilter;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvcTest for {@link AdminPartyroomQueryController}.
 *
 * <p>Covers paging defaults, filter mapping, size cap (200), detail happy/404,
 * and auth gating (401 anonymous / 403 member).
 */
class AdminPartyroomQueryControllerTest extends AbstractAdminWebMvcTest {

    // -------- B-1 list --------

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_admin_defaultPaging_returns200() throws Exception {
        Page<AdminPartyroomListItemResponse> emptyPage = new PageImpl<>(Collections.emptyList());
        given(adminPartyroomQueryService.list(any(AdminPartyroomListFilter.class), any(Pageable.class)))
                .willReturn(emptyPage);

        mockMvc.perform(get("/api/v1/admin/partyrooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        ArgumentCaptor<AdminPartyroomListFilter> filterCaptor =
                ArgumentCaptor.forClass(AdminPartyroomListFilter.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(adminPartyroomQueryService).list(filterCaptor.capture(), pageableCaptor.capture());

        AdminPartyroomListFilter filter = filterCaptor.getValue();
        assertThat(filter.status()).isNull();
        assertThat(filter.stageType()).isNull();
        assertThat(filter.createdFrom()).isNull();
        assertThat(filter.createdTo()).isNull();
        assertThat(filter.hostQuery()).isNull();

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(50);
        Sort.Order createdAt = pageable.getSort().getOrderFor("createdAt");
        assertThat(createdAt).isNotNull();
        assertThat(createdAt.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_admin_withFilters_passesFilterAndPaging() throws Exception {
        Page<AdminPartyroomListItemResponse> emptyPage = new PageImpl<>(Collections.emptyList());
        given(adminPartyroomQueryService.list(any(AdminPartyroomListFilter.class), any(Pageable.class)))
                .willReturn(emptyPage);

        mockMvc.perform(get("/api/v1/admin/partyrooms")
                        .param("status", "ACTIVE")
                        .param("stageType", "MAIN")
                        .param("createdFrom", "2026-04-01T00:00:00")
                        .param("createdTo", "2026-04-27T23:59:59")
                        .param("host", "alice")
                        .param("page", "1")
                        .param("size", "20")
                        .param("sort", "lastActivityAt,asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<AdminPartyroomListFilter> filterCaptor =
                ArgumentCaptor.forClass(AdminPartyroomListFilter.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(adminPartyroomQueryService).list(filterCaptor.capture(), pageableCaptor.capture());

        AdminPartyroomListFilter filter = filterCaptor.getValue();
        assertThat(filter.status()).isEqualTo(PartyroomStatus.ACTIVE);
        assertThat(filter.stageType()).isEqualTo(StageType.MAIN);
        assertThat(filter.createdFrom()).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0, 0));
        assertThat(filter.createdTo()).isEqualTo(LocalDateTime.of(2026, 4, 27, 23, 59, 59));
        assertThat(filter.hostQuery()).isEqualTo("alice");

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        Sort.Order order = pageable.getSort().getOrderFor("lastActivityAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_admin_sizeOverCap_isBoundedTo200() throws Exception {
        Page<AdminPartyroomListItemResponse> emptyPage = new PageImpl<>(Collections.emptyList());
        given(adminPartyroomQueryService.list(any(AdminPartyroomListFilter.class), any(Pageable.class)))
                .willReturn(emptyPage);

        mockMvc.perform(get("/api/v1/admin/partyrooms")
                        .param("size", "500"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(adminPartyroomQueryService).list(any(AdminPartyroomListFilter.class), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("list — unknown sort field → 400")
    void list_unknownSort_returns400() throws Exception {
        given(adminPartyroomQueryService.list(any(AdminPartyroomListFilter.class), any(Pageable.class)))
                .willThrow(new IllegalArgumentException("Unsupported sort field: secretField"));

        mockMvc.perform(get("/api/v1/admin/partyrooms")
                        .param("sort", "secretField,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    void list_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/partyrooms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void list_member_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/partyrooms"))
                .andExpect(status().isForbidden());
    }

    // -------- B-2 detail --------

    @Test
    @WithMockUser(roles = "ADMIN")
    void detail_admin_returns200WithBody() throws Exception {
        AdminPartyroomDetailResponse response = new AdminPartyroomDetailResponse(
                7L,
                "room title",
                "intro text",
                PartyroomStatus.ACTIVE,
                DisplayFlag.NORMAL,
                42L,
                "host-nick",
                "host@example.com",
                3,
                LocalDateTime.of(2026, 4, 27, 12, 0, 0),
                StageType.GENERAL,
                30,
                new PlaybackSummary(false, null, null),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        given(adminPartyroomQueryService.detail(eq(new PartyroomId(7L)))).willReturn(response);

        mockMvc.perform(get("/api/v1/admin/partyrooms/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partyroomId").value(7))
                .andExpect(jsonPath("$.data.title").value("room title"))
                .andExpect(jsonPath("$.data.introduction").value("intro text"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.hostUserAccountId").value(42))
                .andExpect(jsonPath("$.data.playbackTimeLimit").value(30))
                .andExpect(jsonPath("$.data.playback.activated").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void detail_partyroomNotFound_returns404() throws Exception {
        willThrow(ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM))
                .given(adminPartyroomQueryService).detail(eq(new PartyroomId(404L)));

        mockMvc.perform(get("/api/v1/admin/partyrooms/404"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    void detail_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/partyrooms/7"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void detail_member_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/partyrooms/7"))
                .andExpect(status().isForbidden());
    }
}
