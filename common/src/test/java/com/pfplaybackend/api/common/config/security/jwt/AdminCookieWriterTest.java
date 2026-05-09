package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.jwt.properties.AdminCookieProperties;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCookieWriterTest {

    @Test
    void write_emits_set_cookie_with_admin_attributes() {
        var props = new JwtProperties();
        AdminCookieProperties admin = props.getCookie().getAdmin();
        admin.setName("AdminAccessToken");
        admin.setDomain("admin.pfplay.xyz");
        admin.setSameSite("Strict");
        admin.setMaxAgeSeconds(900);
        AdminCookieWriter sut = new AdminCookieWriter(props);

        HttpServletResponse response = new MockHttpServletResponse();
        sut.write(response, "tok-abc");

        String header = response.getHeader("Set-Cookie");
        assertThat(header)
                .contains("AdminAccessToken=tok-abc")
                .contains("Domain=admin.pfplay.xyz")
                .contains("SameSite=Strict")
                .contains("Max-Age=900")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("Path=/");
    }

    @Test
    void clear_emits_zero_max_age_cookie() {
        var props = new JwtProperties();
        AdminCookieWriter sut = new AdminCookieWriter(props);

        HttpServletResponse response = new MockHttpServletResponse();
        sut.clear(response);

        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }
}
