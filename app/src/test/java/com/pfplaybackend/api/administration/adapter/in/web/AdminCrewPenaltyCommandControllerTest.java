package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.application.dto.command.AdminApplyPenaltyCommand;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.exception.PenaltyException;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvcTest for {@link AdminCrewPenaltyCommandController}.
 *
 * <p>POST/DELETE × {happy / auth / validation / domain-exception} 패턴.
 * 도메인 예외 매핑(GlobalExceptionHandler):
 * NOT_FOUND_ROOM → 404, ALREADY_TERMINATED → 403 (NOT 409 — spec drift),
 * PENALTY_HISTORY_NOT_FOUND → 404, CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE → 403.
 */
class AdminCrewPenaltyCommandControllerTest extends AbstractAdminWebMvcTest {

    // -------- POST /api/v1/admin/partyrooms/{partyroomId}/penalties --------

    @Test
    @WithMockUser(roles = "ADMIN")
    void apply_admin_returns201() throws Exception {
        given(adminCrewPenaltyCommandService.apply(eq(1L), any(AdminApplyPenaltyCommand.class)))
                .willReturn(999L);

        mockMvc.perform(post("/api/v1/admin/partyrooms/1/penalties")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crewId":42,"penaltyType":"PERMANENT_EXPULSION","reason":"abuse"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.penaltyId").value(999));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void apply_crewIdNull_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/1/penalties")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crewId":null,"penaltyType":"PERMANENT_EXPULSION","reason":"abuse"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void apply_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/1/penalties")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crewId":42,"penaltyType":"PERMANENT_EXPULSION","reason":"abuse"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void apply_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/1/penalties")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crewId":42,"penaltyType":"PERMANENT_EXPULSION","reason":"abuse"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void apply_partyroomNotFound_returns404() throws Exception {
        doThrow(ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM))
                .when(adminCrewPenaltyCommandService)
                .apply(eq(404L), any(AdminApplyPenaltyCommand.class));

        mockMvc.perform(post("/api/v1/admin/partyrooms/404/penalties")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crewId":42,"penaltyType":"PERMANENT_EXPULSION","reason":"abuse"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void apply_partyroomTerminated_returns403() throws Exception {
        // Spec drift: ALREADY_TERMINATED maps to ErrorType.FORBIDDEN (403), NOT 409.
        doThrow(ExceptionCreator.create(PartyroomException.ALREADY_TERMINATED))
                .when(adminCrewPenaltyCommandService)
                .apply(eq(1L), any(AdminApplyPenaltyCommand.class));

        mockMvc.perform(post("/api/v1/admin/partyrooms/1/penalties")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crewId":42,"penaltyType":"PERMANENT_EXPULSION","reason":"abuse"}
                                """))
                .andExpect(status().isForbidden());
    }

    // -------- DELETE /api/v1/admin/partyrooms/{partyroomId}/penalties/{penaltyId} --------

    @Test
    @WithMockUser(roles = "ADMIN")
    void release_admin_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/partyrooms/1/penalties/999")
                        .with(csrf()))
                .andExpect(status().isNoContent());
        verify(adminCrewPenaltyCommandService).release(1L, 999L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void release_crewApplied_returns403() throws Exception {
        doThrow(ExceptionCreator.create(PenaltyException.CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE))
                .when(adminCrewPenaltyCommandService)
                .release(eq(1L), eq(999L));

        mockMvc.perform(delete("/api/v1/admin/partyrooms/1/penalties/999")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void release_historyNotFound_returns404() throws Exception {
        doThrow(ExceptionCreator.create(PenaltyException.PENALTY_HISTORY_NOT_FOUND))
                .when(adminCrewPenaltyCommandService)
                .release(eq(1L), eq(999L));

        mockMvc.perform(delete("/api/v1/admin/partyrooms/1/penalties/999")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }
}
