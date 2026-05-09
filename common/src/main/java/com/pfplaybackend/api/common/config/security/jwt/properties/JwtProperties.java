package com.pfplaybackend.api.common.config.security.jwt.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private String secret;
    private long adminAccessTokenExpirationMs = 900_000L;     // 15 min
    private long sharedSessionTokenExpirationMs = 86_400_000L; // 24h

    private Cookie cookie = new Cookie();

    @Data
    public static class Cookie {
        private AdminCookieProperties admin = new AdminCookieProperties();
        private SharedCookieProperties shared = new SharedCookieProperties();
    }
}
