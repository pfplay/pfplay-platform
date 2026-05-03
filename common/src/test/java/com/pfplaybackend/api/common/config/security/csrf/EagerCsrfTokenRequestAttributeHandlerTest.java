package com.pfplaybackend.api.common.config.security.csrf;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class EagerCsrfTokenRequestAttributeHandlerTest {

    private final EagerCsrfTokenRequestAttributeHandler handler = new EagerCsrfTokenRequestAttributeHandler();

    @Test
    void handle_resolvesSupplierEagerly() {
        AtomicInteger getCallCount = new AtomicInteger();
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "value-1");
        Supplier<CsrfToken> supplier = () -> {
            getCallCount.incrementAndGet();
            return token;
        };
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, supplier);

        assertThat(getCallCount.get()).isEqualTo(1);
    }

    @Test
    void handle_stillExposesSupplierBackedAttribute() {
        // Parent CsrfTokenRequestAttributeHandler installs a SupplierCsrfToken under
        // CsrfToken.class.getName(). The eager subclass must keep that contract intact —
        // downstream code (e.g. _csrf view variable) still reads via this attribute.
        Supplier<CsrfToken> supplier = () -> new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "value-2");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, supplier);

        Object attr = request.getAttribute(CsrfToken.class.getName());
        assertThat(attr).isInstanceOf(CsrfToken.class);
        assertThat(((CsrfToken) attr).getToken()).isEqualTo("value-2");
    }
}
