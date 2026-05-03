package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class AdminTokenRenewalFilter extends OncePerRequestFilter {

    private static final String ADMIN_PATH_PATTERN = "/api/v1/admin/**";
    private static final String ADMIN_LOGIN_PATH = "/api/v1/auth/admin/login";

    private final JwtProperties jwtProperties;
    private final JwtService jwtService;
    private final AdminCookieWriter adminCookieWriter;
    private final AntPathMatcher matcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(request, response);
        try {
            maybeRenew(request, response);
        } catch (Exception e) {
            log.warn("admin_token_renewal.error: {}", e.getMessage());
        }
    }

    private void maybeRenew(HttpServletRequest req, HttpServletResponse res) {
        String path = req.getServletPath();
        if (path == null) path = req.getRequestURI();
        if (!matcher.match(ADMIN_PATH_PATTERN, path)) return;
        if (ADMIN_LOGIN_PATH.equals(path)) return;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return;
        if (auth instanceof AnonymousAuthenticationToken) return;
        if (!auth.isAuthenticated()) return;
        if (!hasRole(auth, "ROLE_ADMIN") && !hasRole(auth, "ROLE_SUPER_ADMIN")) return;

        String currentCookieName = jwtProperties.getCookie().getAdmin().getName();
        String currentToken = readCookie(req, currentCookieName);
        if (currentToken == null) return;

        long thresholdMs = jwtProperties.getCookie().getAdmin().getRenewalThresholdSeconds() * 1000L;
        long remaining;
        try {
            remaining = jwtService.timeUntilExpiryMs(currentToken);
        } catch (Exception e) {
            return;
        }
        if (remaining > thresholdMs) return;

        List<AccessLevel> levels = auth.getAuthorities().stream()
                .map(a -> {
                    try { return AccessLevel.valueOf(a.getAuthority()); }
                    catch (IllegalArgumentException ignored) { return null; }
                })
                .filter(Objects::nonNull)
                .toList();
        if (levels.isEmpty()) return;

        String sub;
        String email;
        try {
            sub = jwtService.getSubject(currentToken);
            email = jwtService.getEmail(currentToken);
        } catch (Exception e) {
            return;
        }
        String fresh = jwtService.mintAdminAccessToken(new TokenClaimsRequest(sub, email, levels, null));
        adminCookieWriter.write(res, fresh);
    }

    private boolean hasRole(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    private String readCookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (var c : req.getCookies()) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
