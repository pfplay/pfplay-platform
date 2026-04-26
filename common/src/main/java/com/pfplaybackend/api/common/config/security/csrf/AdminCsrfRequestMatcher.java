package com.pfplaybackend.api.common.config.security.csrf;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Matches state-changing admin requests that require CSRF token validation.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-04-19-admin-platform-security.md} §5.4.3
 * (CSRF Option A) + §5.1.4 (logout requires CSRF).
 *
 * <p>Match rule:
 * <pre>
 *   (method NOT IN {GET, HEAD, OPTIONS, TRACE})
 *   AND (path startsWith "/api/v1/admin/" OR path == "/api/v1/auth/admin/logout")
 * </pre>
 *
 * <p>Login (`POST /api/v1/auth/admin/login`) is excluded — there is no session
 * to bind a CSRF token to before login completes.
 */
public class AdminCsrfRequestMatcher implements RequestMatcher {

    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin/";
    private static final String ADMIN_LOGOUT_PATH = "/api/v1/auth/admin/logout";

    @Override
    public boolean matches(HttpServletRequest request) {
        if (isSafeMethod(request.getMethod())) return false;
        String path = request.getServletPath();
        if (path == null) path = request.getRequestURI();
        if (path == null) return false;
        if (path.startsWith(ADMIN_PATH_PREFIX)) return true;
        if (ADMIN_LOGOUT_PATH.equals(path)) return true;
        return false;
    }

    private boolean isSafeMethod(String method) {
        return HttpMethod.GET.matches(method)
                || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method)
                || HttpMethod.TRACE.matches(method);
    }
}
