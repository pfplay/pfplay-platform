package com.pfplaybackend.api.common.config.security.jwt;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.AntPathMatcher;

/**
 * F#220 — 인증 진입점 4경로의 <b>유일한 진실 원천</b>.
 * <p>
 * 이 4경로는 새 세션을 만들거나 끊는 진입점이라 토큰을 <em>검증하면 안 된다</em>.
 * {@link #paths()}는 {@code SecurityConfig}의 {@code permitAll} 인가 규칙과
 * {@link AuthEntryPointSkippingBearerTokenResolver}의 토큰검증 skip 양쪽에서
 * 동일하게 참조된다 (정의 1회, 사용 2곳, 중복 0).
 * <p>
 * {@link #requestMatcher()}가 만드는 matcher는 {@link CookieBearerTokenResolver}와
 * <b>완전히 동일한 경로 표기</b>(servletPath → 비면 requestURI → {@link AntPathMatcher})를
 * 사용한다. {@code BearerTokenAuthenticationFilter}가 런타임에 넘기는 요청이
 * 기존 resolver가 보는 경로와 같은 소스이므로, prod에서 skip이 조용히 안 먹는
 * 경로-표기 불일치 위험을 원천 차단한다.
 */
public final class AuthEntryPoints {

    /**
     * 인증 진입점 4경로. 여기 한 곳에서만 정의된다.
     * 변경 범위 = 정확히 이 4개 (이슈 #220 스코프).
     * <p>
     * 가변 배열을 public으로 노출하면 {@code AuthEntryPoints.PATHS[0] = "/x"} 같은
     * 인가 규칙 무력화가 컴파일되는 footgun이므로, 원본은 private으로 봉인하고
     * {@link #paths()} 방어적 복사 접근자로만 외부에 노출한다.
     */
    private static final String[] PATHS_INTERNAL = {
            "/api/v1/auth/admin/login",
            "/api/v1/auth/logout",
            "/api/v1/auth/oauth/callback",
            "/api/v1/auth/oauth/url",
    };

    private AuthEntryPoints() {
    }

    /**
     * 인증 진입점 4경로의 방어적 복사본. 호출 시점마다 새 배열을 반환하므로
     * 반환값을 변경해도 {@link #PATHS_INTERNAL} 단일 진실 원천은 불변이다.
     * 설정 시점(config-time)에 한 번씩만 호출된다.
     */
    public static String[] paths() {
        return PATHS_INTERNAL.clone();
    }

    /**
     * {@link CookieBearerTokenResolver#pickCookieName}와 같은 경로 표기 규칙으로
     * {@link #paths()} 중 하나에 매칭되는지 판단하는 {@link RequestMatcher}.
     */
    public static RequestMatcher requestMatcher() {
        return new AuthEntryPointRequestMatcher();
    }

    private static final class AuthEntryPointRequestMatcher implements RequestMatcher {

        private final AntPathMatcher matcher = new AntPathMatcher();

        @Override
        public boolean matches(HttpServletRequest request) {
            String path = request.getServletPath();
            if (path == null || path.isEmpty()) {
                path = request.getRequestURI();
            }
            // 핫 경로(요청마다 호출)이므로 clone() 하는 paths() 대신 private 원본을 직접 순회.
            for (String pattern : PATHS_INTERNAL) {
                if (matcher.match(pattern, path)) {
                    return true;
                }
            }
            return false;
        }
    }
}
