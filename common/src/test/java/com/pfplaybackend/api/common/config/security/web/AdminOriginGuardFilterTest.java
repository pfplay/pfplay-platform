package com.pfplaybackend.api.common.config.security.web;

import com.pfplaybackend.api.common.config.security.web.properties.AdminOriginProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminOriginGuardFilterTest {

    private AdminOriginProperties props;
    private AdminOriginGuardFilter sut;

    @BeforeEach
    void setup() {
        props = new AdminOriginProperties();
        props.setEnabled(true);
        props.setAllowed(List.of("https://admin.pfplay.xyz", "https://localhost:3000"));
        sut = new AdminOriginGuardFilter(props);
    }

    @Test
    void post_admin_path_with_allowed_origin_passes() throws Exception {
        var req = post("/api/v1/admin/users", "https://admin.pfplay.xyz");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(req);
    }

    @Test
    void post_admin_path_with_disallowed_origin_returns_403() throws Exception {
        var req = post("/api/v1/admin/users", "https://evil.com");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("FORBIDDEN_ORIGIN");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void post_admin_path_with_no_origin_or_referer_returns_403() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/admin/users");
        req.setServletPath("/api/v1/admin/users");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void post_admin_path_falls_back_to_referer_when_origin_missing() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/admin/users");
        req.setServletPath("/api/v1/admin/users");
        req.addHeader("Referer", "https://admin.pfplay.xyz/some/page");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(req);
    }

    @Test
    void get_admin_path_passes_without_origin() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        req.setServletPath("/api/v1/admin/users");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(req);
    }

    @Test
    void post_non_admin_path_passes_with_disallowed_origin() throws Exception {
        var req = post("/api/v1/users/me", "https://evil.com");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(req);
    }

    @Test
    void post_admin_login_path_with_disallowed_origin_returns_403() throws Exception {
        var req = post("/api/v1/auth/admin/login", "https://evil.com");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void disabled_filter_passes_everything() throws Exception {
        props.setEnabled(false);
        var req = post("/api/v1/admin/users", "https://evil.com");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        sut.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(req);
    }

    private MockHttpServletRequest post(String path, String origin) {
        var req = new MockHttpServletRequest("POST", path);
        req.setServletPath(path);
        if (origin != null) req.addHeader("Origin", origin);
        return req;
    }
}
