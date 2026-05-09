package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class CookieBearerTokenResolverPathAwareTest {

    private CookieBearerTokenResolver sut;

    @BeforeEach
    void setup() {
        JwtProperties props = new JwtProperties();
        props.getCookie().getAdmin().setName("AdminAccessToken");
        props.getCookie().getShared().setName("SharedSessionToken");
        sut = new CookieBearerTokenResolver(props);
    }

    @Test
    void admin_path_reads_admin_cookie() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/admin/users");
        req.setCookies(
                new Cookie("AdminAccessToken", "admin-jwt"),
                new Cookie("SharedSessionToken", "shared-jwt")
        );

        assertThat(sut.resolve(req)).isEqualTo("admin-jwt");
    }

    @Test
    void non_admin_path_reads_shared_cookie() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/users/me");
        req.setCookies(
                new Cookie("AdminAccessToken", "admin-jwt"),
                new Cookie("SharedSessionToken", "shared-jwt")
        );

        assertThat(sut.resolve(req)).isEqualTo("shared-jwt");
    }

    @Test
    void admin_login_path_falls_through_to_shared_cookie_lookup() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/auth/admin/login");
        req.setCookies(new Cookie("SharedSessionToken", "shared-jwt"));

        assertThat(sut.resolve(req)).isEqualTo("shared-jwt");
    }

    @Test
    void no_cookies_returns_null() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/admin/anything");

        assertThat(sut.resolve(req)).isNull();
    }
}
