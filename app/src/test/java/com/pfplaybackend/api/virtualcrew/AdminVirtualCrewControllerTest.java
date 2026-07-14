package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.common.config.security.authorization.AdminAuthorizationSpEL;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.playlist.application.dto.search.SearchResultDto;
import com.pfplaybackend.api.playlist.application.dto.search.SearchResultRawDto;
import com.pfplaybackend.api.playlist.application.service.search.MusicSearchService;
import com.pfplaybackend.api.virtualcrew.adapter.in.web.AdminVirtualCrewController;
import com.pfplaybackend.api.virtualcrew.application.dto.BotRosterRow;
import com.pfplaybackend.api.virtualcrew.application.service.BotAvatarAdminService;
import com.pfplaybackend.api.virtualcrew.application.service.BotAvatarAssigner;
import com.pfplaybackend.api.virtualcrew.application.service.BotPersonaAssignmentService;
import com.pfplaybackend.api.virtualcrew.application.dto.ChatConfigView;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewAdminService;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewChatConfigAdminService;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualPersonaService;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualSongPackService;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import com.pfplaybackend.api.virtualcrew.domain.exception.VirtualCrewException;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
 * WebMvcTest — {@link AdminVirtualCrewController} 보안/검증/위임 검증.
 *
 * <p>ADMIN happy-path 위임 + non-admin(MEMBER) 403 + anonymous 401 + CSRF 누락 403 + 검증(400).
 * mirror {@code PartyroomReportCommandControllerTest}: 자체 {@link SecurityFilterChain} 으로 슬라이스 보안
 * 명시(기본 슬라이스 보안 대신) — {@code @adminAuth} 메서드 시큐리티는 {@link EnableMethodSecurity} 로 활성.
 */
@WebMvcTest(AdminVirtualCrewController.class)
@Import({
        AdminVirtualCrewControllerTest.TestSecurityConfig.class,
        AdminAuthorizationSpEL.class
})
class AdminVirtualCrewControllerTest {

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
    @MockBean private VirtualCrewAdminService adminService;
    @MockBean private VirtualSongPackService songPackService;
    @MockBean private VirtualPersonaService personaService;
    @MockBean private MusicSearchService musicSearchService;
    @MockBean private BotAvatarAdminService botAvatarAdminService;
    @MockBean private BotPersonaAssignmentService botPersonaAssignmentService;
    @MockBean private VirtualCrewChatConfigAdminService chatConfigAdminService;

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
        mockMvc.perform(post("/api/v1/admin/virtual-crew/pool")
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
        mockMvc.perform(post("/api/v1/admin/virtual-crew/pool")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"count":5}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void provisionPool_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/pool")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"count":5}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void provisionPool_invalidCount_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/pool")
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
        mockMvc.perform(post("/api/v1/admin/virtual-crew/song-packs")
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
        mockMvc.perform(post("/api/v1/admin/virtual-crew/song-packs")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void renameSongPack_admin_returns204() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/song-packs/3")
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
        mockMvc.perform(delete("/api/v1/admin/virtual-crew/song-packs/3").with(csrf()))
                .andExpect(status().isNoContent());
        verify(songPackService).deletePack(3L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addSongPackTrack_admin_returns201() throws Exception {
        given(songPackService.addTrack(eq(3L), any())).willReturn(99L);
        mockMvc.perform(post("/api/v1/admin/virtual-crew/song-packs/3/tracks")
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
        mockMvc.perform(delete("/api/v1/admin/virtual-crew/song-packs/3/tracks/9").with(csrf()))
                .andExpect(status().isNoContent());
        verify(songPackService).removeTrack(3L, 9L);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void createSongPack_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/song-packs")
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
        mockMvc.perform(put("/api/v1/admin/partyrooms/7/virtual-crew")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"status":"MANAGED","targetCount":3,"djBotCount":2,"songPackId":5}
                                """))
                .andExpect(status().isNoContent());
        verify(adminService).applyConfig(new PartyroomId(7L), VirtualCrewStatus.MANAGED, 3, 2, 5L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void applyConfig_invalidStatusEnum_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/partyrooms/7/virtual-crew")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"status":"NONSENSE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void applyConfig_member_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/admin/partyrooms/7/virtual-crew")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"status":"OFF"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void drain_admin_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-crew/drain").with(csrf()))
                .andExpect(status().isNoContent());
        verify(adminService).drain(new PartyroomId(7L));
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void drain_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-crew/drain").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void drainResources_admin_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-crew/drain-resources").with(csrf()))
                .andExpect(status().isNoContent());
        verify(adminService).drainResources(new PartyroomId(7L));
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void drainResources_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-crew/drain-resources").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void drainResources_missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-crew/drain-resources"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revive_admin_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-crew/revive").with(csrf()))
                .andExpect(status().isNoContent());
        verify(adminService).revive(new PartyroomId(7L));
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void revive_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-crew/revive").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revive_missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-crew/revive"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void replace_admin_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-crew/replace").with(csrf()))
                .andExpect(status().isNoContent());
        verify(adminService).replace(new PartyroomId(7L));
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void replace_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-crew/replace").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void replace_missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-crew/replace"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void liveStatus_admin_returns200() throws Exception {
        given(adminService.liveStatus(new PartyroomId(7L)))
                .willReturn(new VirtualCrewAdminService.LiveStatus(VirtualCrewStatus.MANAGED, 2, 1, 5L, 2));
        mockMvc.perform(get("/api/v1/admin/partyrooms/7/virtual-crew"))
                .andExpect(status().isOk());
        verify(adminService).liveStatus(new PartyroomId(7L));
    }

    // ── bulk ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void applyBulk_admin_returns204() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/bulk")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"partyroomIds":[1,2,3],"status":"MANAGED","targetCount":3,"djBotCount":2,"songPackId":5}
                                """))
                .andExpect(status().isNoContent());
        verify(adminService).applyBulk(List.of(1L, 2L, 3L), VirtualCrewStatus.MANAGED, 3, 2, 5L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void applyBulk_emptyIds_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/bulk")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"partyroomIds":[],"status":"OFF","reason":"r"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void applyBulk_member_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/bulk")
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
                .willReturn(new VirtualCrewAdminService.PoolSummary(
                        10L, 7L,
                        List.of(new VirtualCrewAdminService.PoolSummary.Placement(3L, "Chill Room", 3L))));
        mockMvc.perform(get("/api/v1/admin/virtual-crew/pool"))
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
        mockMvc.perform(get("/api/v1/admin/virtual-crew/pool"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void poolSummary_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/pool"))
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
        mockMvc.perform(get("/api/v1/admin/virtual-crew/song-packs"))
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
        mockMvc.perform(get("/api/v1/admin/virtual-crew/song-packs"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void listSongPacks_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/song-packs"))
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
        mockMvc.perform(get("/api/v1/admin/virtual-crew/song-packs/5"))
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
                .willThrow(ExceptionCreator.create(VirtualCrewException.SONG_PACK_NOT_FOUND));
        mockMvc.perform(get("/api/v1/admin/virtual-crew/song-packs/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void getSongPack_member_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/song-packs/5"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void getSongPack_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/song-packs/5"))
                .andExpect(status().isUnauthorized());
    }

    // ── music-search 프록시 ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void searchMusic_admin_returns200WithBody() throws Exception {
        SearchResultDto stub = new SearchResultDto("ok", List.of(
                new SearchResultRawDto("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up",
                        "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "3:33",
                        "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg")));
        given(musicSearchService.getSearchList("foo")).willReturn(stub);

        mockMvc.perform(get("/api/v1/admin/virtual-crew/music-search").param("q", "foo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.musicList[0].videoId").value("dQw4w9WgXcQ"))
                .andExpect(jsonPath("$.data.musicList[0].videoTitle").value("Rick Astley - Never Gonna Give You Up"))
                .andExpect(jsonPath("$.data.musicList[0].runningTime").value("3:33"))
                .andExpect(jsonPath("$.data.musicList[0].thumbnailUrl").value("https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg"));
        verify(musicSearchService).getSearchList("foo");
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void searchMusic_member_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/music-search").param("q", "foo"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void searchMusic_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/music-search").param("q", "foo"))
                .andExpect(status().isUnauthorized());
    }

    // ── CSRF ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void drain_missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partyrooms/7/virtual-crew/drain"))
                .andExpect(status().isForbidden());
    }

    // ── 봇 아바타 (P1) ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void avatarCatalog_admin_returns200WithBody() throws Exception {
        given(botAvatarAdminService.catalog())
                .willReturn(List.of(new BotAvatarAdminService.CatalogItem(
                        "https://cdn/body.png", "바디", "https://cdn/body.png", true, "BASIC")));
        mockMvc.perform(get("/api/v1/admin/virtual-crew/avatar-catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].bodyUri").value("https://cdn/body.png"))
                .andExpect(jsonPath("$.data[0].name").value("바디"))
                .andExpect(jsonPath("$.data[0].thumbnailUri").value("https://cdn/body.png"))
                .andExpect(jsonPath("$.data[0].combinable").value(true))
                .andExpect(jsonPath("$.data[0].obtainableType").value("BASIC"));
        verify(botAvatarAdminService).catalog();
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void avatarCatalog_member_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/avatar-catalog"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void bots_admin_returns200WithBody() throws Exception {
        // userId 는 TSID(2^53 초과) — JSON 문자열로 직렬화돼 어드민 프론트에서 정밀도 손실이 없어야 한다.
        given(botAvatarAdminService.roster())
                .willReturn(List.of(new BotRosterRow(
                        864530440482800637L, "봇", "https://cdn/body.png", "https://cdn/icon.png", 7L, "룸", 3L, "DJ 챌린저")));
        mockMvc.perform(get("/api/v1/admin/virtual-crew/bots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value("864530440482800637"))
                .andExpect(jsonPath("$.data[0].nickname").value("봇"))
                .andExpect(jsonPath("$.data[0].avatarBodyUri").value("https://cdn/body.png"))
                .andExpect(jsonPath("$.data[0].avatarIconUri").value("https://cdn/icon.png"))
                .andExpect(jsonPath("$.data[0].placementRoomId").value(7))
                .andExpect(jsonPath("$.data[0].placementRoomTitle").value("룸"))
                .andExpect(jsonPath("$.data[0].personaId").value(3))
                .andExpect(jsonPath("$.data[0].personaName").value("DJ 챌린저"));
        verify(botAvatarAdminService).roster();
    }

    @Test
    @WithAnonymousUser
    void bots_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/bots"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void setBotAvatar_admin_returns204() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/bots/42/avatar")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"avatarBodyUri":"https://cdn/body.png"}
                                """))
                .andExpect(status().isNoContent());
        verify(botAvatarAdminService).setIndividual(new UserId(42L), "https://cdn/body.png");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void setBotAvatar_blankBodyUri_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/bots/42/avatar")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"avatarBodyUri":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void distribute_admin_returns200WithBody() throws Exception {
        given(botAvatarAdminService.distribute(List.of(1L, 2L), List.of("https://cdn/a.png", "https://cdn/b.png")))
                .willReturn(List.of(
                        new BotAvatarAssigner.Assigned(1L, "https://cdn/a.png"),
                        new BotAvatarAssigner.Assigned(2L, "https://cdn/b.png")));
        mockMvc.perform(post("/api/v1/admin/virtual-crew/bots/avatar/distribute")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"botIds":[1,2],"bodyUris":["https://cdn/a.png","https://cdn/b.png"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigned[0].userId").value("1"))
                .andExpect(jsonPath("$.data.assigned[0].avatarBodyUri").value("https://cdn/a.png"))
                .andExpect(jsonPath("$.data.assigned[1].userId").value("2"));
        verify(botAvatarAdminService).distribute(List.of(1L, 2L), List.of("https://cdn/a.png", "https://cdn/b.png"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void distribute_emptyBotIds_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/bots/avatar/distribute")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"botIds":[],"bodyUris":["https://cdn/a.png"]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void distribute_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/bots/avatar/distribute")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"botIds":[1],"bodyUris":["https://cdn/a.png"]}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void distribute_missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/bots/avatar/distribute")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"botIds":[1],"bodyUris":["https://cdn/a.png"]}
                                """))
                .andExpect(status().isForbidden());
    }

    // ── 페르소나 CRUD (P3) ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void listPersonas_admin_returns200WithBody() throws Exception {
        given(personaService.list())
                .willReturn(List.of(
                        new VirtualPersonaService.PersonaListItem(1L, "DJ 챌린저", true),
                        new VirtualPersonaService.PersonaListItem(2L, "DJ 힐러", false)));
        mockMvc.perform(get("/api/v1/admin/virtual-crew/personas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("DJ 챌린저"))
                .andExpect(jsonPath("$.data[0].active").value(true))
                .andExpect(jsonPath("$.data[1].id").value(2))
                .andExpect(jsonPath("$.data[1].active").value(false));
        verify(personaService).list();
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void listPersonas_member_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/personas"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void listPersonas_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/personas"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getPersona_admin_returns200WithBody() throws Exception {
        given(personaService.get(3L))
                .willReturn(new VirtualPersonaService.PersonaDetail(
                        3L, "DJ 챌린저", "당신은 에너지 넘치는 DJ입니다.", true));
        mockMvc.perform(get("/api/v1/admin/virtual-crew/personas/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(3))
                .andExpect(jsonPath("$.data.name").value("DJ 챌린저"))
                .andExpect(jsonPath("$.data.instruction").value("당신은 에너지 넘치는 DJ입니다."))
                .andExpect(jsonPath("$.data.active").value(true));
        verify(personaService).get(3L);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void getPersona_member_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/personas/3"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void getPersona_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/personas/3"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createPersona_admin_returns201() throws Exception {
        given(personaService.create("DJ 챌린저", "당신은 에너지 넘치는 DJ입니다.")).willReturn(5L);
        mockMvc.perform(post("/api/v1/admin/virtual-crew/personas")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"DJ 챌린저","instruction":"당신은 에너지 넘치는 DJ입니다."}
                                """))
                .andExpect(status().isCreated());
        verify(personaService).create("DJ 챌린저", "당신은 에너지 넘치는 DJ입니다.");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createPersona_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/personas")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"","instruction":"당신은 에너지 넘치는 DJ입니다."}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createPersona_blankInstruction_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/personas")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"DJ 챌린저","instruction":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void createPersona_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/personas")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"DJ 챌린저","instruction":"지시문"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updatePersona_admin_returns204() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/personas/3")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"DJ 힐러","instruction":"당신은 차분한 DJ입니다.","active":true}
                                """))
                .andExpect(status().isNoContent());
        verify(personaService).update(3L, "DJ 힐러", "당신은 차분한 DJ입니다.", true);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updatePersona_blankName_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/personas/3")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"","instruction":"당신은 차분한 DJ입니다.","active":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void updatePersona_member_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/personas/3")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"DJ 힐러","instruction":"지시문","active":false}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deletePersona_admin_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/virtual-crew/personas/3").with(csrf()))
                .andExpect(status().isNoContent());
        verify(personaService).delete(3L);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void deletePersona_member_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/virtual-crew/personas/3").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void deletePersona_anonymous_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/virtual-crew/personas/3").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // ── 봇↔페르소나 매핑 (P3) ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void assignPersona_admin_returns200WithApplied() throws Exception {
        given(botPersonaAssignmentService.assign(List.of(1L, 2L), 5L)).willReturn(2);
        mockMvc.perform(post("/api/v1/admin/virtual-crew/bots/persona/assign")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"botIds":[1,2],"personaId":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(2));
        verify(botPersonaAssignmentService).assign(List.of(1L, 2L), 5L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void assignPersona_emptyBotIds_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/bots/persona/assign")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"botIds":[],"personaId":5}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void assignPersona_nullPersonaId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/bots/persona/assign")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"botIds":[1]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void assignPersona_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/bots/persona/assign")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"botIds":[1],"personaId":5}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void assignPersona_missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/bots/persona/assign")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"botIds":[1],"personaId":5}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unassignPersona_admin_returns200WithApplied() throws Exception {
        given(botPersonaAssignmentService.unassign(List.of(1L, 2L))).willReturn(2);
        mockMvc.perform(post("/api/v1/admin/virtual-crew/bots/persona/unassign")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"botIds":[1,2]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(2));
        verify(botPersonaAssignmentService).unassign(List.of(1L, 2L));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unassignPersona_emptyBotIds_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/bots/persona/unassign")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"botIds":[]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void unassignPersona_member_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/virtual-crew/bots/persona/unassign")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"botIds":[1]}
                                """))
                .andExpect(status().isForbidden());
    }

    // ── 채팅/자가갱신 설정 (P3) ──

    @Test
    @WithMockUser(roles = "ADMIN")
    void getChatConfig_admin_returns200WithBody() throws Exception {
        given(chatConfigAdminService.read())
                .willReturn(new ChatConfigView(true, false, 40, 60, 10, 512));
        mockMvc.perform(get("/api/v1/admin/virtual-crew/chat-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatEnabled").value(true))
                .andExpect(jsonPath("$.data.selfUpdateEnabled").value(false))
                .andExpect(jsonPath("$.data.probabilityPercent").value(40))
                .andExpect(jsonPath("$.data.cooldownSeconds").value(60))
                .andExpect(jsonPath("$.data.contextSize").value(10))
                .andExpect(jsonPath("$.data.outputMaxTokens").value(512));
        verify(chatConfigAdminService).read();
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void getChatConfig_member_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/chat-config"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void getChatConfig_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/virtual-crew/chat-config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateChatConfig_admin_returns204() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/chat-config")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"chatEnabled":true,"selfUpdateEnabled":true,"probabilityPercent":50,"cooldownSeconds":45,"contextSize":15,"outputMaxTokens":300}
                                """))
                .andExpect(status().isNoContent());
        verify(chatConfigAdminService).update(true, true, 50, 45, 15, 300);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateChatConfig_probabilityOutOfRange_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/chat-config")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"chatEnabled":true,"selfUpdateEnabled":false,"probabilityPercent":101,"cooldownSeconds":30,"contextSize":20,"outputMaxTokens":256}
                                """))
                .andExpect(status().isBadRequest());
        verify(chatConfigAdminService, never()).update(
                anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateChatConfig_cooldownTooLow_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/chat-config")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"chatEnabled":true,"selfUpdateEnabled":false,"probabilityPercent":12,"cooldownSeconds":0,"contextSize":20,"outputMaxTokens":256}
                                """))
                .andExpect(status().isBadRequest());
        verify(chatConfigAdminService, never()).update(
                anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void updateChatConfig_member_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/chat-config")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"chatEnabled":true,"selfUpdateEnabled":false,"probabilityPercent":12,"cooldownSeconds":30,"contextSize":20,"outputMaxTokens":256}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void updateChatConfig_anonymous_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/chat-config")
                        .with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"chatEnabled":true,"selfUpdateEnabled":false,"probabilityPercent":12,"cooldownSeconds":30,"contextSize":20,"outputMaxTokens":256}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateChatConfig_missingCsrf_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/admin/virtual-crew/chat-config")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"chatEnabled":true,"selfUpdateEnabled":false,"probabilityPercent":12,"cooldownSeconds":30,"contextSize":20,"outputMaxTokens":256}
                                """))
                .andExpect(status().isForbidden());
    }
}
