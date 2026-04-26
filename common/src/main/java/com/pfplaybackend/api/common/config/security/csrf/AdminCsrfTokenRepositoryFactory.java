package com.pfplaybackend.api.common.config.security.csrf;

import com.pfplaybackend.api.common.config.security.csrf.properties.AdminCsrfProperties;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link CookieCsrfTokenRepository} used by Spring Security's
 * {@code CsrfFilter} for admin endpoints.
 *
 * <p>Cookie attributes mirror {@code AdminAccessToken} (admin subdomain, SameSite=Strict,
 * Secure=true), with one exception: {@code httpOnly=false} so the admin frontend's JS
 * can read the value and echo it as the {@code X-XSRF-TOKEN} header.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-04-19-admin-platform-security.md} §5.4.2 + §5.4.3.
 */
@Component
public class AdminCsrfTokenRepositoryFactory {

    private final AdminCsrfProperties props;

    public AdminCsrfTokenRepositoryFactory(AdminCsrfProperties props) {
        this.props = props;
    }

    public CookieCsrfTokenRepository build() {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookieName(props.getCookieName());
        repo.setHeaderName(props.getHeaderName());
        repo.setCookieCustomizer(cookie -> cookie
                .domain(props.getCookieDomain())
                .path("/")
                .secure(props.isSecure())
                .httpOnly(false)
                .sameSite(props.getSameSite())
        );
        return repo;
    }
}
