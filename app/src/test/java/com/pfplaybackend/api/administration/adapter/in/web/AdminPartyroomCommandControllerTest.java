package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvcTest for {@link AdminPartyroomCommandController}.
 *
 * <p>5 endpoints × {happy / auth / validation / domain-exception} 패턴.
 * 도메인 예외 매핑은 {@link com.pfplaybackend.api.common.exception.GlobalExceptionHandler}가 담당:
 * NOT_FOUND_ROOM → 404, ALREADY_TERMINATED → 403, ILLEGAL_STATE_TRANSITION → 409.
 */
class AdminPartyroomCommandControllerTest extends AbstractAdminWebMvcTest {

    // -------- B-3 terminate --------

    @Test
    @WithMockUser(roles = "ADMIN")
    void terminate_admin_returns204() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(1L);

        mockMvc.perform(post("/api/v1/admin/partyrooms/7/terminate")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"reason":"abuse report"}
                                """))
                .andExpect(status().isNoContent());
        verify(adminPartyroomCommandService).terminate(new PartyroomId(7L), "abuse report", 1L);
    }

    @Test
    @WithAnonymousUser
    void terminate_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/terminate")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"reason":"r"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void terminate_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/terminate")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"reason":"r"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void terminate_blankReason_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/terminate")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"reason":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void terminate_partyroomNotFound_returns404() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(1L);
        willThrow(ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM))
                .given(adminPartyroomCommandService)
                .terminate(eq(new PartyroomId(404L)), eq("r"), eq(1L));

        mockMvc.perform(post("/api/v1/admin/partyrooms/404/terminate")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"reason":"r"}
                                """))
                .andExpect(status().isNotFound());
    }

    // -------- B-4 suspend --------

    @Test
    @WithMockUser(roles = "ADMIN")
    void suspend_admin_returns204() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(2L);

        mockMvc.perform(post("/api/v1/admin/partyrooms/7/suspend")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"reason":"temporary hold"}
                                """))
                .andExpect(status().isNoContent());
        verify(adminPartyroomCommandService).suspend(new PartyroomId(7L), "temporary hold", 2L);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void suspend_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/suspend")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"reason":"r"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void suspend_blankReason_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/suspend")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"reason":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void suspend_illegalStateTransition_returns409() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(1L);
        willThrow(ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION))
                .given(adminPartyroomCommandService)
                .suspend(eq(new PartyroomId(7L)), eq("r"), eq(1L));

        mockMvc.perform(post("/api/v1/admin/partyrooms/7/suspend")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"reason":"r"}
                                """))
                .andExpect(status().isConflict());
    }

    // -------- B-4 restore --------

    @Test
    @WithMockUser(roles = "ADMIN")
    void restore_admin_returns204() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(3L);

        mockMvc.perform(post("/api/v1/admin/partyrooms/7/restore")
                        .with(csrf()))
                .andExpect(status().isNoContent());
        verify(adminPartyroomCommandService).restore(new PartyroomId(7L), 3L);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void restore_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/restore")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void restore_illegalStateTransition_returns409() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(1L);
        willThrow(ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION))
                .given(adminPartyroomCommandService)
                .restore(eq(new PartyroomId(7L)), eq(1L));

        mockMvc.perform(post("/api/v1/admin/partyrooms/7/restore")
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    // -------- B-5 updateMeta --------

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateMeta_admin_returns204() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(4L);

        mockMvc.perform(patch("/api/v1/admin/partyrooms/7")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"new title","introduction":"new intro","playbackTimeLimit":10}
                                """))
                .andExpect(status().isNoContent());
        verify(adminPartyroomCommandService).updateMeta(
                new PartyroomId(7L), "new title", "new intro", 10, 4L);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void updateMeta_member_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/partyrooms/7")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"t"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateMeta_allFieldsNull_returns400() throws Exception {
        // @AssertTrue isAtLeastOnePresent fails
        mockMvc.perform(patch("/api/v1/admin/partyrooms/7")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateMeta_playbackTimeLimitOutOfRange_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/partyrooms/7")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"playbackTimeLimit":61}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateMeta_alreadyTerminated_returns403() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(1L);
        willThrow(ExceptionCreator.create(PartyroomException.ALREADY_TERMINATED))
                .given(adminPartyroomCommandService)
                .updateMeta(eq(new PartyroomId(7L)), eq("t"), eq(null), eq(null), eq(1L));

        mockMvc.perform(patch("/api/v1/admin/partyrooms/7")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"t"}
                                """))
                .andExpect(status().isForbidden());
    }

    // -------- B-6 setDisplayFlag --------

    @Test
    @WithMockUser(roles = "ADMIN")
    void setDisplayFlag_admin_returns204() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(5L);

        mockMvc.perform(patch("/api/v1/admin/partyrooms/7/display-flag")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"flag":"FEATURED"}
                                """))
                .andExpect(status().isNoContent());
        verify(adminPartyroomCommandService).setDisplayFlag(
                new PartyroomId(7L), DisplayFlag.FEATURED, 5L);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void setDisplayFlag_member_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/partyrooms/7/display-flag")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"flag":"FEATURED"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void setDisplayFlag_nullFlag_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/partyrooms/7/display-flag")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"flag":null}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("setDisplayFlag — invalid enum value (Jackson InvalidFormatException) → 400")
    void setDisplayFlag_invalidEnumValue_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/partyrooms/{id}/display-flag", 1L)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"flag":"INVALID_VALUE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void setDisplayFlag_partyroomNotFound_returns404() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(1L);
        willThrow(ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM))
                .given(adminPartyroomCommandService)
                .setDisplayFlag(eq(new PartyroomId(404L)), eq(DisplayFlag.HIDDEN), eq(1L));

        mockMvc.perform(patch("/api/v1/admin/partyrooms/404/display-flag")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"flag":"HIDDEN"}
                                """))
                .andExpect(status().isNotFound());
    }

    // -------- CSRF --------

    @Test
    @WithMockUser(roles = "ADMIN")
    void terminate_missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/terminate")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"reason":"r"}
                                """))
                .andExpect(status().isForbidden());
    }
}
