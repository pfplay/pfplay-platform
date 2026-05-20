package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.application.service.BugReportCommandService;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BugReportCommandController.class)
@Import(AbstractVocCommandWebMvcTest.SharedMethodSecurityConfig.class)
abstract class AbstractVocCommandWebMvcTest {

    @Configuration
    @EnableMethodSecurity
    static class SharedMethodSecurityConfig {}

    @Autowired protected MockMvc mockMvc;
    @MockBean protected BugReportCommandService bugReportCommandService;
    @MockBean protected JwtDecoder jwtDecoder;
    @MockBean protected JwtService jwtService;
    @MockBean protected JwtProperties jwtProperties;
    @MockBean protected SharedSessionCookieWriter sharedSessionCookieWriter;
    @MockBean protected AdminCookieWriter adminCookieWriter;
}
