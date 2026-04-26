package com.pfplaybackend.api.common.config.security.jwt.properties;

import lombok.Data;

@Data
public class AdminCookieProperties {
    private String name = "AdminAccessToken";
    private String domain;
    private String path = "/";
    private boolean secure = true;
    private String sameSite = "Strict";
    private int maxAgeSeconds = 900;          // 15 min
    private int renewalThresholdSeconds = 300; // re-issue when < 5 min remaining
}
