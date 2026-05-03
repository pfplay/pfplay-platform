package com.pfplaybackend.api.common.config.security.jwt.properties;

import lombok.Data;

@Data
public class SharedCookieProperties {
    private String name = "SharedSessionToken";
    private String domain;
    private String path = "/";
    private boolean secure = true;
    private String sameSite = "Lax";
    private int maxAgeSeconds = 86400; // 24h
}
