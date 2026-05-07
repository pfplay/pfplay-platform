package com.pfplaybackend.api.common.config.security.csrf;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.util.function.Supplier;

/**
 * Variant of {@link CsrfTokenRequestAttributeHandler} that eagerly resolves the
 * deferred CSRF token at {@link #handle(HttpServletRequest, HttpServletResponse, Supplier)}
 * time, rather than leaving resolution to whichever piece of application code
 * happens to call {@code CsrfToken#getToken()}.
 *
 * <p><strong>Why eager resolution matters (A6 fix):</strong>
 * Spring Security's {@code CsrfAuthenticationStrategy} rotates the token on
 * authentication success by:
 * <ol>
 *   <li>{@code saveToken(null, request, response)} — adds {@code Set-Cookie:
 *       XSRF-TOKEN=; Max-Age=0} to the response and marks the repository's
 *       "removed" attribute on the request, invalidating the previous cookie
 *       client-side.</li>
 *   <li>{@code loadDeferredToken(request, response)} — registers a new
 *       supplier-backed {@link CsrfToken} on the request.</li>
 *   <li>{@code requestHandler.handle(...)} — exposes that supplier as the
 *       request attribute consumed downstream.</li>
 * </ol>
 * The fresh cookie is only emitted when something resolves the supplier
 * ({@code RepositoryDeferredCsrfToken#get}) — which generates a new token and
 * calls {@code saveToken(newToken, ...)} that adds {@code Set-Cookie:
 * XSRF-TOKEN=&lt;new-uuid&gt;}. The default handler does not call the supplier;
 * it only stores the {@code SupplierCsrfToken} wrapper as an attribute. If no
 * controller code reads the token (which is the norm for read endpoints under
 * {@code /api/v1/admin/**}), the response carries only the deletion cookie.
 * The browser then has no token to echo on the next mutation request, and
 * {@code CsrfFilter} returns 403.
 *
 * <p>Resolving the supplier inside {@code handle} guarantees the new
 * {@code Set-Cookie} header is appended <em>before</em> the controller writes
 * the response body (i.e., before commit). Doing the same work after
 * {@code chain.doFilter} returns is too late — the response is already
 * committed and additional headers are dropped.
 *
 * <p>For the non-rotation path (regular {@link
 * org.springframework.security.web.csrf.CsrfFilter} dispatch), eager
 * resolution is a no-op when a valid cookie is already present:
 * {@code RepositoryDeferredCsrfToken#get} returns the loaded token without
 * calling {@code saveToken}.
 *
 * <p>The {@code SupplierCsrfToken} attribute installed by the parent class is
 * preserved for API compatibility (e.g., {@code ${_csrf.token}} in views),
 * even though resolution has already happened.
 *
 * @see org.springframework.security.web.csrf.CsrfAuthenticationStrategy
 * @see org.springframework.security.web.csrf.CookieCsrfTokenRepository
 */
public class EagerCsrfTokenRequestAttributeHandler extends CsrfTokenRequestAttributeHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       Supplier<CsrfToken> deferredCsrfToken) {
        super.handle(request, response, deferredCsrfToken);
        // Force resolution. For requests with an already-valid cookie this is
        // cheap (loadToken hit) and idempotent (no Set-Cookie). For rotation
        // (post-saveToken(null) state) this triggers generate + save → the new
        // Set-Cookie is appended now, while the response is still uncommitted.
        deferredCsrfToken.get();
    }
}
