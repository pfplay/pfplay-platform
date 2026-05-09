package com.pfplaybackend.api.common.config.security;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §5.7.2 — Cookie isolation end-to-end.
 *
 * <p>Verifies that the path-aware {@code CookieBearerTokenResolver} (PR 4) selects the
 * correct cookie at the SecurityFilterChain level: admin path picks AdminAccessToken,
 * non-admin path picks SharedSessionToken.
 */
@AutoConfigureMockMvc
class AdminCookieIsolationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private JwtProperties jwtProperties;

    private static final String ADMIN_USER_ACCOUNT_ID = "1000000000000000001";

    @Test
    void onlySharedToken_adminPath_returns401() throws Exception {
        String sharedToken = jwtService.mintSharedSessionToken(memberClaims());
        Cookie sharedCookie = cookie(jwtProperties.getCookie().getShared().getName(), sharedToken);

        mockMvc.perform(post("/api/v1/admin/__test_probe__")
                        .cookie(sharedCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminToken_adminPath_passesUrlGate() throws Exception {
        String adminToken = jwtService.mintAdminAccessToken(adminClaims());
        Cookie adminCookie = cookie(jwtProperties.getCookie().getAdmin().getName(), adminToken);

        mockMvc.perform(post("/api/v1/admin/__test_probe__")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(Matchers.not(Matchers.isOneOf(401, 403))));
    }

    @Test
    void onlyAdminToken_nonAdminPath_returns401() throws Exception {
        String adminToken = jwtService.mintAdminAccessToken(adminClaims());
        Cookie adminCookie = cookie(jwtProperties.getCookie().getAdmin().getName(), adminToken);

        mockMvc.perform(get("/api/v1/users/me/__test_probe__")
                        .cookie(adminCookie))
                .andExpect(status().isUnauthorized());
    }

    private TokenClaimsRequest memberClaims() {
        // subject is String (JWT sub claim), authorityTier is nullable AuthorityTier
        return new TokenClaimsRequest(
                ADMIN_USER_ACCOUNT_ID,
                "member@pfplay.xyz",
                List.of(AccessLevel.ROLE_MEMBER),
                /* authorityTier */ null
        );
    }

    private TokenClaimsRequest adminClaims() {
        return new TokenClaimsRequest(
                ADMIN_USER_ACCOUNT_ID,
                "admin@pfplay.xyz",
                List.of(AccessLevel.ROLE_ADMIN),
                /* authorityTier */ null
        );
    }

    private Cookie cookie(String name, String value) {
        Cookie c = new Cookie(name, value);
        c.setPath("/");
        return c;
    }
}
