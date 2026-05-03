package com.pfplaybackend.api.common.config.security.web;

import com.pfplaybackend.api.common.config.security.web.properties.AdminOriginProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class AdminOriginGuardFilter extends OncePerRequestFilter {

    private static final String ADMIN_PATH_PATTERN = "/api/v1/admin/**";
    private static final String ADMIN_AUTH_PATTERN = "/api/v1/auth/admin/**";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final AdminOriginProperties props;
    private final AntPathMatcher matcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        if (!props.isEnabled()) {
            chain.doFilter(req, res);
            return;
        }
        String path = req.getServletPath();
        if (path == null) path = req.getRequestURI();
        boolean inScope = matcher.match(ADMIN_PATH_PATTERN, path)
                || matcher.match(ADMIN_AUTH_PATTERN, path);
        if (!inScope || SAFE_METHODS.contains(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }

        String origin = req.getHeader("Origin");
        if (origin == null) origin = req.getHeader("Referer");
        if (origin == null || !isAllowed(origin)) {
            log.warn("admin_origin_guard.deny path={} origin={}", path, origin);
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"FORBIDDEN_ORIGIN\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean isAllowed(String headerValue) {
        try {
            URI uri = URI.create(headerValue);
            String origin = uri.getScheme() + "://" + uri.getAuthority();
            return props.getAllowed().contains(origin);
        } catch (Exception e) {
            return false;
        }
    }
}
