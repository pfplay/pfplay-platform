package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F#220 — 인증 진입점 4경로는 토큰을 검증하면 안 된다.
 * <p>
 * stale/무효 {@code SharedSessionToken} 쿠키가 {@code .pfplay.xyz}에 남아 있어도
 * 로그인/로그아웃/oauth 진입점에서 데코레이터가 {@code null}을 반환해
 * {@code BearerTokenAuthenticationFilter}가 인증을 시도하지 않게 한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthEntryPointSkippingBearerTokenResolverTest {

    @Mock
    private BearerTokenResolverDelegateStub delegateProbe;

    private CookieBearerTokenResolver realDelegate;
    private AuthEntryPointSkippingBearerTokenResolver sut;

    @BeforeEach
    void setup() {
        JwtProperties props = new JwtProperties();
        props.getCookie().getAdmin().setName("AdminAccessToken");
        props.getCookie().getShared().setName("SharedSessionToken");
        realDelegate = new CookieBearerTokenResolver(props);
        sut = new AuthEntryPointSkippingBearerTokenResolver(
                AuthEntryPoints.requestMatcher(), realDelegate);
    }

    static String[] authEntryPaths() {
        return new String[]{
                "/api/v1/auth/admin/login",
                "/api/v1/auth/logout",
                "/api/v1/auth/oauth/callback",
                "/api/v1/auth/oauth/url",
        };
    }

    @Test
    void all_four_entry_paths_with_stale_shared_cookie_resolve_to_null() {
        for (String path : authEntryPaths()) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.setServletPath(path);
            req.setCookies(new Cookie("SharedSessionToken", "stale-or-invalid-jwt"));

            assertThat(sut.resolve(req))
                    .as("entry path %s must skip token resolution", path)
                    .isNull();
        }
    }

    @Test
    void entry_path_does_not_consult_delegate() {
        // Spy decorator built with a probe delegate to assert it is never called.
        AuthEntryPointSkippingBearerTokenResolver spySut =
                new AuthEntryPointSkippingBearerTokenResolver(
                        AuthEntryPoints.requestMatcher(), delegateProbe);

        for (String path : authEntryPaths()) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.setServletPath(path);
            req.setCookies(new Cookie("SharedSessionToken", "stale-jwt"));

            assertThat(spySut.resolve(req)).isNull();
        }
        verifyNoInteractions(delegateProbe);
    }

    @Test
    void entry_path_with_no_cookies_still_null() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/auth/admin/login");

        assertThat(sut.resolve(req)).isNull();
    }

    @Test
    void entry_path_with_admin_cookie_is_cookie_agnostic_and_skips() {
        // 진입점 skip은 쿠키 종류와 무관하다 — Shared가 아닌 AdminAccessToken 쿠키가
        // 붙어 있어도 진입점에서는 토큰을 검증하지 않고 delegate도 호출하지 않는다.
        AuthEntryPointSkippingBearerTokenResolver spySut =
                new AuthEntryPointSkippingBearerTokenResolver(
                        AuthEntryPoints.requestMatcher(), delegateProbe);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/auth/admin/login");
        req.setCookies(new Cookie("AdminAccessToken", "admin-jwt"));

        assertThat(spySut.resolve(req))
                .as("entry path must skip regardless of cookie type")
                .isNull();
        verifyNoInteractions(delegateProbe);
    }

    @Test
    void non_entry_admin_path_delegates_to_inner_resolver() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/admin/users");
        req.setCookies(
                new Cookie("AdminAccessToken", "admin-jwt"),
                new Cookie("SharedSessionToken", "shared-jwt")
        );

        // Inner resolver picks AdminAccessToken for /api/v1/admin/** — delegation intact.
        assertThat(sut.resolve(req)).isEqualTo("admin-jwt");
    }

    @Test
    void non_entry_user_path_delegates_to_inner_resolver() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/users/me");
        req.setCookies(new Cookie("SharedSessionToken", "shared-jwt"));

        assertThat(sut.resolve(req)).isEqualTo("shared-jwt");
    }

    @Test
    void non_entry_path_actually_invokes_delegate_once() {
        when(delegateProbe.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("delegated-token");
        AuthEntryPointSkippingBearerTokenResolver spySut =
                new AuthEntryPointSkippingBearerTokenResolver(
                        AuthEntryPoints.requestMatcher(), delegateProbe);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/api/v1/users/me");
        req.setCookies(new Cookie("SharedSessionToken", "shared-jwt"));

        assertThat(spySut.resolve(req)).isEqualTo("delegated-token");
        verify(delegateProbe).resolve(req);
        verify(delegateProbe, never()).resolve(null);
    }

    /**
     * Probe interface so Mockito can verify the delegate is (not) consulted.
     * Implements the same {@link org.springframework.security.oauth2.server.resource.web.BearerTokenResolver}
     * contract the decorator delegates to.
     */
    interface BearerTokenResolverDelegateStub
            extends org.springframework.security.oauth2.server.resource.web.BearerTokenResolver {
    }
}
