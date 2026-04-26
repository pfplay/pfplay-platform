package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.config.security.jwt.properties.SharedCookieProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SharedSessionCookieWriter {

    private final JwtProperties jwtProperties;

    public void write(HttpServletResponse response, String token) {
        SharedCookieProperties p = jwtProperties.getCookie().getShared();
        emit(response, p, token, p.getMaxAgeSeconds());
    }

    public void clear(HttpServletResponse response) {
        SharedCookieProperties p = jwtProperties.getCookie().getShared();
        emit(response, p, "", 0);
    }

    private void emit(HttpServletResponse response, SharedCookieProperties p, String value, int maxAge) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.getName()).append('=').append(value);
        sb.append("; Path=").append(p.getPath());
        sb.append("; Max-Age=").append(maxAge);
        sb.append("; HttpOnly");
        if (p.isSecure()) sb.append("; Secure");
        sb.append("; SameSite=").append(p.getSameSite());
        if (p.getDomain() != null && !p.getDomain().isBlank()) {
            sb.append("; Domain=").append(p.getDomain());
        }
        response.addHeader("Set-Cookie", sb.toString());
    }
}
