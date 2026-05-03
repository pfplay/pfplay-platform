package com.pfplaybackend.api.common.config.security.csrf.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Properties for admin CSRF token defense (Option A).
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-04-19-admin-platform-security.md} §5.4.3.
 *
 * <p>Cookie attributes mirror {@code AdminAccessToken} (admin subdomain, SameSite=Strict,
 * Secure=true) — the only exception is {@code httpOnly=false} so the admin frontend's
 * JS can read the cookie value and echo it as the {@code X-XSRF-TOKEN} header.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.security.admin-csrf")
public class AdminCsrfProperties {

    /** Master switch — disable in tests that need to bypass CSRF entirely. */
    private boolean enabled = true;

    /** Cookie name read by the admin frontend. */
    private String cookieName = "XSRF-TOKEN";

    /** Header name the frontend echoes back. */
    private String headerName = "X-XSRF-TOKEN";

    /** Cookie domain — must match {@code app.jwt.cookie.admin.domain} for cookie scoping. */
    private String cookieDomain;

    /** SameSite attribute — {@code Strict} aligns with AdminAccessToken. */
    private String sameSite = "Strict";

    /** Secure attribute — true everywhere except local/test profiles (which use plain HTTP). */
    private boolean secure = true;
}
