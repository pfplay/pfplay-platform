package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminTokenRenewalFilterTest {

    private JwtProperties props;
    private JwtService jwtService;
    private AdminCookieWriter writer;
    private AdminTokenRenewalFilter sut;

    @BeforeEach
    void setup() {
        props = new JwtProperties();
        props.getCookie().getAdmin().setName("AdminAccessToken");
        props.getCookie().getAdmin().setRenewalThresholdSeconds(300);
        jwtService = mock(JwtService.class);
        writer = mock(AdminCookieWriter.class);
        sut = new AdminTokenRenewalFilter(props, jwtService, writer);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void renews_when_authenticated_admin_path_and_token_under_threshold() throws Exception {
        givenAuthenticatedAdmin();
        var req = adminRequest("/api/v1/admin/users", "stale-jwt");
        var res = new MockHttpServletResponse();

        when(jwtService.timeUntilExpiryMs("stale-jwt")).thenReturn(60_000L);
        when(jwtService.getSubject("stale-jwt")).thenReturn("1000000000000042");
        when(jwtService.getEmail("stale-jwt")).thenReturn("admin@x.com");
        when(jwtService.mintAdminAccessToken(any())).thenReturn("fresh-jwt");

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer).write(eq(res), eq("fresh-jwt"));
    }

    @Test
    void no_renewal_when_token_remaining_above_threshold() throws Exception {
        givenAuthenticatedAdmin();
        var req = adminRequest("/api/v1/admin/users", "fresh-jwt");
        var res = new MockHttpServletResponse();

        when(jwtService.timeUntilExpiryMs("fresh-jwt")).thenReturn(14L * 60_000L);

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer, never()).write(any(), any());
    }

    @Test
    void no_renewal_for_anonymous_authentication() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anon",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        var req = adminRequest("/api/v1/admin/users", "stale-jwt");
        var res = new MockHttpServletResponse();

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer, never()).write(any(), any());
        verifyNoInteractions(jwtService);
    }

    @Test
    void no_renewal_for_non_admin_path() throws Exception {
        givenAuthenticatedAdmin();
        var req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        req.setServletPath("/api/v1/users/me");
        req.setCookies(new Cookie("AdminAccessToken", "stale-jwt"));
        var res = new MockHttpServletResponse();

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer, never()).write(any(), any());
        verifyNoInteractions(jwtService);
    }

    @Test
    void no_renewal_on_admin_login_path() throws Exception {
        givenAuthenticatedAdmin();
        var req = new MockHttpServletRequest("POST", "/api/v1/auth/admin/login");
        req.setServletPath("/api/v1/auth/admin/login");
        var res = new MockHttpServletResponse();

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer, never()).write(any(), any());
    }

    @Test
    void no_renewal_when_no_cookie_present() throws Exception {
        givenAuthenticatedAdmin();
        var req = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        req.setServletPath("/api/v1/admin/users");
        var res = new MockHttpServletResponse();

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer, never()).write(any(), any());
    }

    @Test
    void invalid_token_does_not_throw_or_renew() throws Exception {
        givenAuthenticatedAdmin();
        var req = adminRequest("/api/v1/admin/users", "garbage");
        var res = new MockHttpServletResponse();

        when(jwtService.timeUntilExpiryMs("garbage"))
                .thenThrow(new RuntimeException("malformed"));

        sut.doFilter(req, res, new MockFilterChain());

        verify(writer, never()).write(any(), any());
    }

    private MockHttpServletRequest adminRequest(String path, String adminCookie) {
        var req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path);
        req.setCookies(new Cookie("AdminAccessToken", adminCookie));
        return req;
    }

    private void givenAuthenticatedAdmin() {
        var auth = new UsernamePasswordAuthenticationToken(
                "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
