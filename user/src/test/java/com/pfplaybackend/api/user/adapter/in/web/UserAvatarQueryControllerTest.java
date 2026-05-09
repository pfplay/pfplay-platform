package com.pfplaybackend.api.user.adapter.in.web;

import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAvatarQueryControllerTest extends AbstractUserWebMvcTest {

    @Test
    @DisplayName("getMyAllAvatarBodies — 200 OK")
    void getMyAllAvatarBodiesReturns200() throws Exception {
        // given
        when(userAvatarQueryService.findMyAvatarBodies()).thenReturn(List.of());

        // when & then
        mockMvc.perform(get("/api/v1/users/me/profile/avatar/bodies")
                        .with(jwt().authorities(() -> "ROLE_MEMBER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("getMyDefaultAvatarFaces — 200 OK")
    void getMyDefaultAvatarFacesReturns200() throws Exception {
        // given
        when(userAvatarQueryService.findMyAvatarFaces()).thenReturn(
                List.of(new AvatarFaceDto(1L, "face1", "face-uri", true)));

        // when & then
        mockMvc.perform(get("/api/v1/users/me/profile/avatar/faces")
                        .with(jwt().authorities(() -> "ROLE_GUEST")))
                .andExpect(status().isOk());
    }
}
