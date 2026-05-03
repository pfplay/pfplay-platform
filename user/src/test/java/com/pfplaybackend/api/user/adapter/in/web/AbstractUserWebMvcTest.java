package com.pfplaybackend.api.user.adapter.in.web;

import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.service.*;
import com.pfplaybackend.api.user.application.service.initialize.TemporaryUserInitializeService;
import com.pfplaybackend.api.user.application.validation.AvatarRequestValidator;
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

@WebMvcTest({
        UserBioCommandController.class,
        UserAvatarCommandController.class,
        UserProfileQueryController.class,
        UserWalletCommandController.class,
        UserInfoQueryController.class,
        UserAvatarQueryController.class,
        GuestSignController.class,
        EasyUserManagementController.class
})
@Import(AbstractUserWebMvcTest.SharedMethodSecurityConfig.class)
abstract class AbstractUserWebMvcTest {

    @Configuration
    @EnableMethodSecurity
    static class SharedMethodSecurityConfig {}

    @Autowired protected MockMvc mockMvc;
    @MockBean protected UserBioCommandService userBioService;
    @MockBean protected UserAvatarCommandService userAvatarCommandService;
    @MockBean protected AvatarRequestValidator avatarRequestValidator;
    @MockBean protected UserProfileQueryService userProfileQueryService;
    @MockBean protected UserWalletCommandService userWalletService;
    @MockBean protected UserInfoQueryService userInfoService;
    @MockBean protected UserAvatarQueryService userAvatarQueryService;
    @MockBean protected GuestSignService guestSignService;
    @MockBean protected TemporaryUserInitializeService temporaryUserInitializeService;
    @MockBean protected UserAccountRepository userAccountRepository;
    @MockBean protected SharedSessionCookieWriter sharedSessionCookieWriter;
    @MockBean protected AdminCookieWriter adminCookieWriter;
    @MockBean protected JwtService jwtService;
    @MockBean protected JwtProperties jwtProperties;
    @MockBean protected JwtDecoder jwtDecoder;
}
