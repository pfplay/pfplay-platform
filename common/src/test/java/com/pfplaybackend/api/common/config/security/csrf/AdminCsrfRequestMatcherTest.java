package com.pfplaybackend.api.common.config.security.csrf;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCsrfRequestMatcherTest {

    private final AdminCsrfRequestMatcher matcher = new AdminCsrfRequestMatcher();

    @Test
    void matches_postToAdminPath() {
        assertThat(matcher.matches(req("POST", "/api/v1/admin/partyrooms"))).isTrue();
    }

    @Test
    void matches_putToAdminSubpath() {
        assertThat(matcher.matches(req("PUT", "/api/v1/admin/users/virtual/42/avatar"))).isTrue();
    }

    @Test
    void matches_deleteToAdminPath() {
        assertThat(matcher.matches(req("DELETE", "/api/v1/admin/demo/partyrooms/7/chat"))).isTrue();
    }

    @Test
    void matches_postToAdminLogout() {
        assertThat(matcher.matches(req("POST", "/api/v1/auth/admin/logout"))).isTrue();
    }

    @Test
    void doesNotMatch_postToAdminLogin() {
        // Login has no session yet — chicken-and-egg.
        assertThat(matcher.matches(req("POST", "/api/v1/auth/admin/login"))).isFalse();
    }

    @Test
    void doesNotMatch_safeMethodsOnAdminPath() {
        assertThat(matcher.matches(req("GET", "/api/v1/admin/partyrooms"))).isFalse();
        assertThat(matcher.matches(req("HEAD", "/api/v1/admin/partyrooms"))).isFalse();
        assertThat(matcher.matches(req("OPTIONS", "/api/v1/admin/partyrooms"))).isFalse();
        assertThat(matcher.matches(req("TRACE", "/api/v1/admin/partyrooms"))).isFalse();
    }

    @Test
    void doesNotMatch_postToNonAdminPath() {
        assertThat(matcher.matches(req("POST", "/api/v1/auth/oauth/callback"))).isFalse();
        assertThat(matcher.matches(req("POST", "/api/v1/users/members/sign/up"))).isFalse();
        assertThat(matcher.matches(req("POST", "/api/v1/partyrooms"))).isFalse();
    }

    @Test
    void doesNotMatch_pathOutsideApiPrefix() {
        assertThat(matcher.matches(req("POST", "/actuator/health"))).isFalse();
        assertThat(matcher.matches(req("POST", "/ws/handshake"))).isFalse();
    }

    private static HttpServletRequest req(String method, String path) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setServletPath(path);
        return r;
    }
}
