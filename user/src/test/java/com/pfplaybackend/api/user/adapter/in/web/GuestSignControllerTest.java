package com.pfplaybackend.api.user.adapter.in.web;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GuestSignControllerTest extends AbstractUserWebMvcTest {

    @Test
    @DisplayName("createGuest — 200 OK + 쿠키 설정")
    void createGuestReturns200() throws Exception {
        // given
        GuestData guest = GuestData.createForUserAccount(1L, "test-agent");
        when(guestSignService.getGuestOrCreate()).thenReturn(guest);
        when(jwtService.mintSharedSessionToken(any())).thenReturn("mock-jwt-token");

        // when & then
        mockMvc.perform(post("/api/v1/users/guests/sign")
                        .with(jwt())
                        .with(csrf()))
                .andExpect(status().isOk());
    }
}
