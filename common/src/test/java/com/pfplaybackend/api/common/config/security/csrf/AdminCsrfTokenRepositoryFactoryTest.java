package com.pfplaybackend.api.common.config.security.csrf;

import com.pfplaybackend.api.common.config.security.csrf.properties.AdminCsrfProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCsrfTokenRepositoryFactoryTest {

    @Test
    void buildsRepositoryWithAdminCookieAttributes() {
        AdminCsrfProperties props = new AdminCsrfProperties();
        props.setCookieDomain("admin.pfplay.xyz");
        props.setCookieName("XSRF-TOKEN");
        props.setHeaderName("X-XSRF-TOKEN");
        props.setSameSite("Strict");
        props.setSecure(true);

        CookieCsrfTokenRepository repo = new AdminCsrfTokenRepositoryFactory(props).build();

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CsrfToken token = repo.generateToken(req);
        repo.saveToken(token, req, res);

        Cookie cookie = res.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo(token.getToken());
        assertThat(cookie.getDomain()).isEqualTo("admin.pfplay.xyz");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
    }

    @Test
    void buildsRepository_withCustomHeaderName() {
        AdminCsrfProperties props = new AdminCsrfProperties();
        props.setCookieDomain("admin.pfplay.xyz");
        props.setHeaderName("X-CUSTOM-XSRF");

        CookieCsrfTokenRepository repo = new AdminCsrfTokenRepositoryFactory(props).build();

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/admin/x");
        CsrfToken token = repo.generateToken(req);

        assertThat(token.getHeaderName()).isEqualTo("X-CUSTOM-XSRF");
    }

    @Test
    void buildsRepository_withSecureFalseForTestProfile() {
        AdminCsrfProperties props = new AdminCsrfProperties();
        props.setCookieDomain("localhost");
        props.setSecure(false);

        CookieCsrfTokenRepository repo = new AdminCsrfTokenRepositoryFactory(props).build();

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        repo.saveToken(repo.generateToken(req), req, res);

        Cookie cookie = res.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getSecure()).isFalse();
    }
}
