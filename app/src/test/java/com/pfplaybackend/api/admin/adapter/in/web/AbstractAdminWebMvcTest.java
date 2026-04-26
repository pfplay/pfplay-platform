package com.pfplaybackend.api.admin.adapter.in.web;

import com.pfplaybackend.api.admin.application.service.AdminDemoService;
import com.pfplaybackend.api.admin.application.service.AdminPartyroomService;
import com.pfplaybackend.api.admin.application.service.AdminUserService;
import com.pfplaybackend.api.admin.application.service.ChatSimulationService;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.AdminTokenRenewalFilter;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.config.security.web.AdminOriginGuardFilter;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({
        AdminUserController.class,
        AdminPartyroomController.class,
        AdminDemoController.class
})
@Import(AbstractAdminWebMvcTest.SharedMethodSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
abstract class AbstractAdminWebMvcTest {

    @EnableMethodSecurity
    static class SharedMethodSecurityConfig {}

    @Autowired protected MockMvc mockMvc;
    @MockBean protected AdminUserService adminUserService;
    @MockBean protected AdminPartyroomService adminPartyroomService;
    @MockBean protected AdminDemoService adminDemoService;
    @MockBean protected ChatSimulationService chatSimulationService;
    @MockBean protected UserAccountRepository userAccountRepository;
    @MockBean protected JwtDecoder jwtDecoder;
    @MockBean protected JwtService jwtService;
    @MockBean protected JwtProperties jwtProperties;
    @MockBean protected SharedSessionCookieWriter sharedSessionCookieWriter;
    @MockBean protected AdminCookieWriter adminCookieWriter;
    @MockBean protected AdminTokenRenewalFilter adminTokenRenewalFilter;
    @MockBean protected AdminOriginGuardFilter adminOriginGuardFilter;
}
