package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtCookieValidator {

    private static final long NEAR_EXPIRY_THRESHOLD_MS = 600_000L;

    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public Optional<String> extractAndValidateAccessToken(HttpServletRequest request) {
        return extractCookie(request, jwtProperties.getCookie().getShared().getName())
                .filter(this::isValidAccessToken);
    }

    public boolean hasValidAccessToken(HttpServletRequest request) {
        return extractAndValidateAccessToken(request).isPresent();
    }

    public boolean isAuthenticated(HttpServletRequest request) {
        return hasValidAccessToken(request);
    }

    public boolean isTokenNearExpiry(HttpServletRequest request) {
        return extractAndValidateAccessToken(request)
                .map(token -> jwtService.timeUntilExpiryMs(token) < NEAR_EXPIRY_THRESHOLD_MS)
                .orElse(true);
    }

    public Optional<String> extractUserId(HttpServletRequest request) {
        return extractAndValidateAccessToken(request)
                .map(token -> {
                    try {
                        return jwtService.getSubject(token);
                    } catch (Exception e) {
                        log.debug("Failed to extract subject from token: {}", e.getMessage());
                        return null;
                    }
                });
    }

    public Optional<String> extractUserEmail(HttpServletRequest request) {
        return extractAndValidateAccessToken(request)
                .map(token -> {
                    try {
                        return jwtService.getEmail(token);
                    } catch (Exception e) {
                        log.debug("Failed to extract email from token: {}", e.getMessage());
                        return null;
                    }
                });
    }

    public Optional<String> extractCookie(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                    return Optional.of(cookie.getValue());
                }
            }
        }
        return Optional.empty();
    }

    private boolean isValidAccessToken(String token) {
        try {
            return jwtService.validate(token);
        } catch (Exception e) {
            log.debug("Invalid access token: {}", e.getMessage());
            return false;
        }
    }
}
