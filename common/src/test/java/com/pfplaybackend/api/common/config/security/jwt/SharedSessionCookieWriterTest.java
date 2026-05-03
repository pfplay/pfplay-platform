package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.config.security.jwt.properties.SharedCookieProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SharedSessionCookieWriterTest {

    @Test
    void write_emits_set_cookie_with_shared_attributes() {
        var props = new JwtProperties();
        SharedCookieProperties shared = props.getCookie().getShared();
        shared.setName("SharedSessionToken");
        shared.setDomain(".pfplay.xyz");
        shared.setSameSite("Lax");
        shared.setMaxAgeSeconds(86400);
        SharedSessionCookieWriter sut = new SharedSessionCookieWriter(props);

        HttpServletResponse response = new MockHttpServletResponse();
        sut.write(response, "tok-xyz");

        String header = response.getHeader("Set-Cookie");
        assertThat(header)
                .contains("SharedSessionToken=tok-xyz")
                .contains("Domain=.pfplay.xyz")
                .contains("SameSite=Lax")
                .contains("Max-Age=86400");
    }
}
