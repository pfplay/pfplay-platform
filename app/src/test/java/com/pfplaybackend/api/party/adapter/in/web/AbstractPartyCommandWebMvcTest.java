package com.pfplaybackend.api.party.adapter.in.web;

import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.party.application.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({
        DjCommandController.class,
        CrewPenaltyCommandController.class,
        CrewBlockCommandController.class,
        CrewGradeCommandController.class,
        PartyroomCommandController.class,
        PartyroomAccessCommandController.class,
        PartyroomNoticeCommandController.class,
        PlaybackCommandController.class,
        PlaybackReactionCommandController.class
})
@Import(AbstractPartyCommandWebMvcTest.SharedMethodSecurityConfig.class)
abstract class AbstractPartyCommandWebMvcTest {

    @Configuration
    @EnableMethodSecurity
    static class SharedMethodSecurityConfig {}

    @Autowired protected MockMvc mockMvc;
    @MockBean protected DjCommandService djCommandService;
    @MockBean protected QuickDjService quickDjService;
    @MockBean protected CrewPenaltyCommandService crewPenaltyCommandService;
    @MockBean protected CrewBlockCommandService crewBlockCommandService;
    @MockBean protected CrewGradeCommandService crewGradeCommandService;
    @MockBean protected PartyroomCommandService partyroomCommandService;
    @MockBean protected PartyroomNoticeCommandService partyroomNoticeCommandService;
    @MockBean protected PartyroomAccessCommandService partyroomAccessCommandService;
    @MockBean protected PlaybackCommandService playbackCommandService;
    @MockBean protected PlaybackReactionCommandService playbackReactionCommandService;
    @MockBean protected JwtDecoder jwtDecoder;
    @MockBean protected JwtService jwtService;
    @MockBean protected JwtProperties jwtProperties;
    @MockBean protected SharedSessionCookieWriter sharedSessionCookieWriter;
    @MockBean protected AdminCookieWriter adminCookieWriter;
}
