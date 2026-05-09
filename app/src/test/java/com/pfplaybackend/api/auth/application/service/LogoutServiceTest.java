package com.pfplaybackend.api.auth.application.service;

import com.pfplaybackend.api.auth.application.port.out.PartyCleanupPort;
import com.pfplaybackend.api.common.config.security.jwt.CustomJwtAuthenticationToken;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {

    @Mock private PartyCleanupPort partyCleanupPort;

    @InjectMocks
    private LogoutService logoutService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("인증된 사용자는 partyCleanupPort.exitActivePartyroomIfPresent가 호출된다")
    void exitActivePartyroomIfPresentShouldCallPortWhenAuthenticated() {
        // given
        UserId userId = new UserId();
        setAuthentication(userId);

        // when
        logoutService.exitActivePartyroomIfPresent();

        // then
        verify(partyCleanupPort, times(1)).exitActivePartyroomIfPresent(userId);
    }

    @Test
    @DisplayName("인증되지 않은 사용자는 아무 작업도 수행하지 않아야 한다")
    void exitActivePartyroomIfPresentShouldDoNothingWhenNotAuthenticated() {
        // given — no authentication set

        // when
        logoutService.exitActivePartyroomIfPresent();

        // then
        verify(partyCleanupPort, never()).exitActivePartyroomIfPresent(any());
    }

    private void setAuthentication(UserId userId) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", userId.getUid().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        CustomJwtAuthenticationToken token = new CustomJwtAuthenticationToken(
                jwt, Collections.emptyList(), userId, "test@test.com", AuthorityTier.FM
        );
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
