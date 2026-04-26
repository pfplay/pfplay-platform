package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

@Slf4j
@Component
@RequiredArgsConstructor
public class CookieBearerTokenResolver implements BearerTokenResolver {

    static final String ADMIN_PATH_PATTERN = "/api/v1/admin/**";

    private final JwtProperties jwtProperties;
    private final AntPathMatcher matcher = new AntPathMatcher();

    @Override
    public String resolve(HttpServletRequest request) {
        String name = pickCookieName(request);
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private String pickCookieName(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) path = request.getRequestURI();
        boolean adminPath = matcher.match(ADMIN_PATH_PATTERN, path);
        return adminPath
                ? jwtProperties.getCookie().getAdmin().getName()
                : jwtProperties.getCookie().getShared().getName();
    }
}
