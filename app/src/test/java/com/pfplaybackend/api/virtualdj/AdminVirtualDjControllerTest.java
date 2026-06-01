package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.common.config.security.authorization.AdminAuthorizationSpEL;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.in.web.AdminVirtualDjController;
import com.pfplaybackend.api.virtualdj.application.service.VirtualDjAdminService;
import com.pfplaybackend.api.virtualdj.application.service.VirtualSongPackService;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import com.pfplaybackend.api.virtualdj.domain.exception.VirtualDjException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvcTest — {@link AdminVirtualDjController} 보안/검증/위임 검증.
 *
 * <p>ADMIN happy-path 위임 + non-admin(MEMBER) 403 + anonymous 401 + CSRF 누락 403 + 검증(400).
 * mirror {@code PartyroomReportCommandControllerTest}: 자체 {@link SecurityFilterChain} 으로 슬라이스 보안
 * 명시(기본 슬라이스 보안 대신) — {@code @adminAuth} 메서드 시큐리티는 {@link EnableMethodSecurity} 로 활성.
 */
@WebMvcTest(AdminVirtualDjController.class)
@Import({
        AdminVirtualDjControllerTest.TestSecurityConfig.class,
        AdminAuthorizationSpEL.class
})
class AdminVirtualDjControllerTest {

    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            http
                    .authorizeHttpRequests(request -> request
                            .requestMatchers("/api/**").authenticated())
                    .csrf(csrf -> {})
                    .exceptionHandling(eh -> eh
                            .authenticationEntryPoint((req, res, ex) ->
                                    res.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED)));
            return http.build();
        }
    }

    @Autowired private MockMvc mockMvc;
    @MockBean private VirtualDjAdminService adminService;
    @MockBean private VirtualSongPackService songPackService;

    // SecurityConfig autoconfig deps — @MockBean shims so the slice context loads.
    @MockBean private JwtDecoder jwtDecoder;
    @MockBean private JwtService jwtService;
    @MockBean private JwtProperties jwtProperties;
    @MockBean private SharedSessionCookieWriter sharedSessionCookieWriter;
    @MockBean private AdminCookieWriter adminCookieWriter;

    // ── pool ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void provisionPool_admin_returns201() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-dj/pool")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"count":5}
                                """))
                .andExpect(status().isCreated());
        verify(adminService).provisionPool(5);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void provisionPool_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-dj/pool")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"count":5}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void provisionPool_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-dj/pool")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"count":5}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void provisionPool_invalidCount_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-dj/pool")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"count":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ── song packs ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void createSongPack_admin_returns201() throws Exception {
        given(songPackService.createPack("Pack", "desc")).willReturn(10L);
        mockMvc.perform(post("/api/v1/admin/virtual-dj/song-packs")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Pack","description":"desc"}
                                """))
                .andExpect(status().isCreated());
        verify(songPackService).createPack("Pack", "desc");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createSongPack_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-dj/song-packs")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void renameSongPack_admin_returns204() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-dj/song-packs/3")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"New"}
                                """))
                .andExpect(status().isNoContent());
        verify(songPackService).renamePack(3L, "New");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteSongPack_admin_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/virtual-dj/song-packs/3").with(csrf()))
                .andExpect(status().isNoContent());
        verify(songPackService).deletePack(3L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addSongPackTrack_admin_returns201() throws Exception {
        given(songPackService.addTrack(eq(3L), any())).willReturn(99L);
        mockMvc.perform(post("/api/v1/admin/virtual-dj/song-packs/3/tracks")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Song","linkId":"vid1","duration":"3:00","thumbnailImage":null}
                                """))
                .andExpect(status().isCreated());
        verify(songPackService).addTrack(eq(3L), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void removeSongPackTrack_admin_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/virtual-dj/song-packs/3/tracks/9").with(csrf()))
                .andExpect(status().isNoContent());
        verify(songPackService).removeTrack(3L, 9L);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void createSongPack_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-dj/song-packs")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Pack"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ── per-room config ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void applyConfig_admin_returns204() throws Exception {
        mockMvc.perform(put("/api/v1/admin/partyrooms/7/virtual-dj")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"status":"MANAGED","targetCount":2,"companionFloor":1,"songPackId":5}
                                """))
                .andExpect(status().isNoContent());
        verify(adminService).applyConfig(new PartyroomId(7L), VirtualDjStatus.MANAGED, 2, 1, 5L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void applyConfig_invalidStatusEnum_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/partyrooms/7/virtual-dj")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"status":"NONSENSE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void applyConfig_member_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/admin/partyrooms/7/virtual-dj")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"status":"OFF"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void drain_admin_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-dj/drain").with(csrf()))
                .andExpect(status().isNoContent());
        verify(adminService).drain(new PartyroomId(7L));
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void drain_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-dj/drain").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void freeze_admin_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-dj/freeze").with(csrf()))
                .andExpect(status().isNoContent());
        verify(adminService).freeze(new PartyroomId(7L));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void liveStatus_admin_returns200() throws Exception {
        given(adminService.liveStatus(new PartyroomId(7L)))
                .willReturn(new VirtualDjAdminService.LiveStatus(VirtualDjStatus.MANAGED, 2, 1, 5L, 2));
        mockMvc.perform(get("/api/v1/admin/partyrooms/7/virtual-dj"))
                .andExpect(status().isOk());
        verify(adminService).liveStatus(new PartyroomId(7L));
    }

    // ── bulk ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void applyBulk_admin_returns204() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-dj/bulk")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"partyroomIds":[1,2,3],"status":"MANAGED","targetCount":2,"companionFloor":1,"songPackId":5}
                                """))
                .andExpect(status().isNoContent());
        verify(adminService).applyBulk(List.of(1L, 2L, 3L), VirtualDjStatus.MANAGED, 2, 1, 5L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void applyBulk_emptyIds_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-dj/bulk")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"partyroomIds":[],"status":"OFF","reason":"r"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void applyBulk_member_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-dj/bulk")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"partyroomIds":[1],"status":"OFF"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ── pool summary ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void poolSummary_admin_returns200WithBody() throws Exception {
        given(adminService.poolSummary())
                .willReturn(new VirtualDjAdminService.PoolSummary(
                        10L, 7L,
                        List.of(new VirtualDjAdminService.PoolSummary.Placement(3L, "Chill Room", 3L))));
        mockMvc.perform(get("/api/v1/admin/virtual-dj/pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(10))
                .andExpect(jsonPath("$.data.idle").value(7))
                .andExpect(jsonPath("$.data.placed[0].partyroomId").value(3))
                .andExpect(jsonPath("$.data.placed[0].partyroomTitle").value("Chill Room"))
                .andExpect(jsonPath("$.data.placed[0].botCount").value(3));
        verify(adminService).poolSummary();
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void poolSummary_member_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-dj/pool"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void poolSummary_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-dj/pool"))
                .andExpect(status().isUnauthorized());
    }

    // ── song pack list ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void listSongPacks_admin_returns200WithBody() throws Exception {
        given(songPackService.listPacks())
                .willReturn(List.of(
                        new VirtualSongPackService.PackListItem(1L, "Pack A", "desc A", 3L),
                        new VirtualSongPackService.PackListItem(2L, "Pack B", "desc B", 0L)));
        mockMvc.perform(get("/api/v1/admin/virtual-dj/song-packs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Pack A"))
                .andExpect(jsonPath("$.data[0].description").value("desc A"))
                .andExpect(jsonPath("$.data[0].trackCount").value(3))
                .andExpect(jsonPath("$.data[1].id").value(2))
                .andExpect(jsonPath("$.data[1].trackCount").value(0));
        verify(songPackService).listPacks();
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void listSongPacks_member_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-dj/song-packs"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void listSongPacks_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-dj/song-packs"))
                .andExpect(status().isUnauthorized());
    }

    // ── song pack detail ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSongPack_admin_existingPack_returns200WithBody() throws Exception {
        given(songPackService.getPack(5L))
                .willReturn(new VirtualSongPackService.PackDetail(
                        5L, "My Pack", "my desc",
                        List.of(new VirtualSongPackService.PackDetail.PackTrack(
                                11L, "Song One", "vid001", "3:45", "https://img/thumb.jpg"))));
        mockMvc.perform(get("/api/v1/admin/virtual-dj/song-packs/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.name").value("My Pack"))
                .andExpect(jsonPath("$.data.description").value("my desc"))
                .andExpect(jsonPath("$.data.tracks[0].trackId").value(11))
                .andExpect(jsonPath("$.data.tracks[0].name").value("Song One"))
                .andExpect(jsonPath("$.data.tracks[0].linkId").value("vid001"))
                .andExpect(jsonPath("$.data.tracks[0].duration").value("3:45"))
                .andExpect(jsonPath("$.data.tracks[0].thumbnailImage").value("https://img/thumb.jpg"));
        verify(songPackService).getPack(5L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSongPack_admin_notFound_returns404() throws Exception {
        given(songPackService.getPack(999L))
                .willThrow(ExceptionCreator.create(VirtualDjException.SONG_PACK_NOT_FOUND));
        mockMvc.perform(get("/api/v1/admin/virtual-dj/song-packs/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void getSongPack_member_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-dj/song-packs/5"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void getSongPack_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-dj/song-packs/5"))
                .andExpect(status().isUnauthorized());
    }

    // ── CSRF ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void drain_missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-dj/drain"))
                .andExpect(status().isForbidden());
    }
}
