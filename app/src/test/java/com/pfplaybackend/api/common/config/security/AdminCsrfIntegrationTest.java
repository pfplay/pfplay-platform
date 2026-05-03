package com.pfplaybackend.api.common.config.security;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §5.7.3 — CSRF token validation on admin endpoints.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.4.3 (Option A).
 *
 * <p>The CSRF gate is independent of the auth gate. We use a probe path under
 * /api/v1/admin/** so URL-level auth (hasRole(ADMIN)) and method-level CSRF
 * are exercised as orthogonal layers. With @WithMockUser(roles="ADMIN"), the
 * auth gate passes; the failure (or success) is attributable to CSRF.
 *
 * <p>Implementation note — {@code CookieCsrfTokenRepository} semantics:
 * The repository validates the X-XSRF-TOKEN request header against the XSRF-TOKEN cookie.
 * Validation only fires when a cookie is present (the cookie establishes the "session token").
 * Tests that expect rejection therefore supply the cookie but omit or corrupt the header.
 */
@AutoConfigureMockMvc
class AdminCsrfIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private static final String ADMIN_PROBE = "/api/v1/admin/__test_probe__";
    private static final String LOGIN = "/api/v1/auth/admin/login";
    /** Cookie name configured in application-test.yml (app.security.admin-csrf.cookie-name). */
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    /** Header name configured in application-test.yml (app.security.admin-csrf.header-name). */
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWithValidCsrf_passesGate() throws Exception {
        // Use Spring Security's csrf() post-processor to inject a valid CSRF token.
        // Auth gate also passes via @WithMockUser(roles="ADMIN").
        //
        // NOTE: We do NOT manually set the XSRF-TOKEN cookie + X-XSRF-TOKEN header here.
        // Spring Security Test's csrf() post-processor replaces the CsrfFilter's repository
        // with TestCsrfTokenRepository(HttpSessionCsrfTokenRepository) on first use in any
        // test sharing this Spring context. If that mutation has already occurred (because
        // another test class running earlier in the :app:integrationTest suite also called
        // csrf()), then manually setting cookie/header values will be compared against an
        // HttpSession-based token — and the comparison fails with 403. Using csrf() here
        // is order-independent: TestCsrfTokenRepository activates its request-attribute path
        // when enabled by the post-processor, so the token always matches regardless of which
        // underlying repository is currently installed on the CsrfFilter.
        mockMvc.perform(post(ADMIN_PROBE).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(Matchers.not(Matchers.is(403))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWithoutCsrfHeader_returns403() throws Exception {
        // Cookie is present (establishes the session token) but the X-XSRF-TOKEN header is absent.
        // CookieCsrfTokenRepository compares header to cookie — mismatch → 403.
        String tokenValue = "test-csrf-token-value";
        mockMvc.perform(post(ADMIN_PROBE)
                        .cookie(new Cookie(CSRF_COOKIE, tokenValue))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWithInvalidCsrfToken_returns403() throws Exception {
        // Send XSRF-TOKEN cookie and X-XSRF-TOKEN header with deliberately wrong value.
        // CookieCsrfTokenRepository compares header to cookie — mismatch → 403.
        String tokenValue = "test-csrf-token-value";
        mockMvc.perform(post(ADMIN_PROBE)
                        .cookie(new Cookie(CSRF_COOKIE, tokenValue))
                        .header(CSRF_HEADER, "invalid-token-value")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getOnAdminPath_emitsFreshCsrfCookieAlongsideDeletion() throws Exception {
        // A6 regression: the response from any authenticated admin GET must include a
        // freshly-generated XSRF-TOKEN Set-Cookie header. CsrfAuthenticationStrategy may
        // emit a deletion cookie (Max-Age=0) as part of token rotation; without the
        // EagerCsrfTokenRequestAttributeHandler fix, no replacement is emitted and the
        // browser is left token-less, blocking subsequent mutations with 403.
        MvcResult result = mockMvc.perform(get(ADMIN_PROBE)
                        .cookie(new Cookie(CSRF_COOKIE, "previous-token-value")))
                .andReturn();

        Collection<String> setCookies = result.getResponse().getHeaders("Set-Cookie");
        boolean hasFreshToken = setCookies.stream()
                .filter(c -> c.startsWith(CSRF_COOKIE + "="))
                .anyMatch(c -> !c.contains("Max-Age=0") && !c.startsWith(CSRF_COOKIE + "=;"));
        assertThat(hasFreshToken)
                .as("response Set-Cookie headers: %s", setCookies)
                .isTrue();
    }

    @Test
    void loginEndpoint_doesNotRequireCsrf() throws Exception {
        // Login is excluded from the matcher; no CSRF cookie or header should still pass the CSRF
        // gate. Auth itself will fail (no real credentials), but the failure must NOT be CSRF (403
        // with "Invalid CSRF token" body). A 4xx other than 403 is acceptable here.
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@x.x\",\"password\":\"y\"}"))
                .andExpect(status().is(Matchers.not(Matchers.is(403))));
    }
}
