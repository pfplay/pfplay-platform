package com.pfplaybackend.api.party.adapter.in.web;

import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.AdminTokenRenewalFilter;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.config.security.web.AdminOriginGuardFilter;
import com.pfplaybackend.api.party.application.service.*;
import com.pfplaybackend.api.partyview.adapter.in.web.PartyroomSetupController;
import com.pfplaybackend.api.partyview.application.service.PartyroomSetupQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({
        PartyroomQueryController.class,
        PartyroomNoticeQueryController.class,
        CrewQueryController.class,
        CrewBlockQueryController.class,
        CrewPenaltyQueryController.class,
        PlaybackQueryController.class,
        PartyroomSetupController.class
})
@Import(AbstractPartyQueryWebMvcTest.SharedMethodSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
public abstract class AbstractPartyQueryWebMvcTest {

    @EnableMethodSecurity
    static class SharedMethodSecurityConfig {}

    @Autowired protected MockMvc mockMvc;
    @MockBean protected PartyroomQueryService partyroomQueryService;
    @MockBean protected PartyroomNoticeQueryService partyroomNoticeQueryService;
    @MockBean protected CrewBlockQueryService crewBlockQueryService;
    @MockBean protected CrewPenaltyQueryService crewPenaltyQueryService;
    @MockBean protected PlaybackQueryService playbackQueryService;
    @MockBean protected PartyroomSetupQueryService partyroomSetupQueryService;
    @MockBean protected JwtDecoder jwtDecoder;
    @MockBean protected JwtService jwtService;
    @MockBean protected JwtProperties jwtProperties;
    @MockBean protected SharedSessionCookieWriter sharedSessionCookieWriter;
    @MockBean protected AdminCookieWriter adminCookieWriter;
    @MockBean protected AdminTokenRenewalFilter adminTokenRenewalFilter;
    @MockBean protected AdminOriginGuardFilter adminOriginGuardFilter;
}
