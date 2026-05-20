package com.pfplaybackend.api.admin.adapter.in.web;

import com.pfplaybackend.api.admin.application.service.AdminDemoService;
import com.pfplaybackend.api.admin.application.service.AdminPartyroomService;
import com.pfplaybackend.api.admin.application.service.AdminUserService;
import com.pfplaybackend.api.admin.application.service.ChatSimulationService;
import com.pfplaybackend.api.administration.adapter.in.web.AdminAnnouncementController;
import com.pfplaybackend.api.administration.adapter.in.web.AdminAvatarCommandController;
import com.pfplaybackend.api.administration.adapter.in.web.AdminAvatarQueryController;
import com.pfplaybackend.api.administration.adapter.in.web.AdminCrewPenaltyCommandController;
import com.pfplaybackend.api.administration.adapter.in.web.AdminGuestQueryController;
import com.pfplaybackend.api.administration.adapter.in.web.AdminMemberQueryController;
import com.pfplaybackend.api.administration.adapter.in.web.AdminMemberTierCommandController;
import com.pfplaybackend.api.administration.adapter.in.web.AdminMemberWithdrawCommandController;
import com.pfplaybackend.api.administration.adapter.in.web.AdminPartyroomCommandController;
import com.pfplaybackend.api.administration.adapter.in.web.AdminPartyroomQueryController;
import com.pfplaybackend.api.administration.adapter.in.web.AdminReportCommandController;
import com.pfplaybackend.api.administration.adapter.in.web.AdminReportQueryController;
import com.pfplaybackend.api.administration.adapter.in.web.AdministratorManagementController;
import com.pfplaybackend.api.administration.adapter.in.web.AdminPasswordController;
import com.pfplaybackend.api.avatar.application.port.in.AvatarAdminCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogCommandUseCase;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.service.AdminBulkPartyroomActionService;
import com.pfplaybackend.api.administration.application.service.AdminCrewPenaltyCommandService;
import com.pfplaybackend.api.administration.application.service.AdminGuestQueryService;
import com.pfplaybackend.api.administration.application.service.AdminMemberQueryService;
import com.pfplaybackend.api.administration.application.service.AdminMemberTierCommandService;
import com.pfplaybackend.api.administration.application.service.AdminMemberWithdrawCommandService;
import com.pfplaybackend.api.administration.application.service.AdminPartyroomCommandService;
import com.pfplaybackend.api.administration.application.service.AdminPartyroomQueryService;
import com.pfplaybackend.api.administration.application.service.AdminReportCommandService;
import com.pfplaybackend.api.administration.application.service.AdminReportQueryService;
import com.pfplaybackend.api.administration.application.service.AdministratorManagementService;
import com.pfplaybackend.api.administration.application.service.AdminPasswordService;
import com.pfplaybackend.api.administration.application.service.SystemAnnouncementCommandService;
import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.common.config.security.authorization.AdminAuthorizationSpEL;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({
        AdminUserController.class,
        AdminPartyroomController.class,
        AdminDemoController.class,
        AdministratorManagementController.class,
        AdminPasswordController.class,
        AdminPartyroomCommandController.class,
        AdminPartyroomQueryController.class,
        AdminCrewPenaltyCommandController.class,
        AdminAvatarCommandController.class,
        AdminAvatarQueryController.class,
        AdminMemberQueryController.class,
        AdminGuestQueryController.class,
        AdminMemberTierCommandController.class,
        AdminMemberWithdrawCommandController.class,
        AdminReportQueryController.class,
        AdminReportCommandController.class,
        AdminAnnouncementController.class
})
@Import({
        AbstractAdminWebMvcTest.SharedMethodSecurityConfig.class,
        AdminAuthorizationSpEL.class
})
public abstract class AbstractAdminWebMvcTest {

    @Configuration
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
    @MockBean protected AdministratorManagementService administratorManagementService;
    @MockBean protected AdminContext adminContext;
    @MockBean protected AdminPasswordService adminPasswordService;
    @MockBean protected AdminPartyroomCommandService adminPartyroomCommandService;
    @MockBean protected AdminPartyroomQueryService adminPartyroomQueryService;
    @MockBean protected AdminMemberQueryService adminMemberQueryService;
    @MockBean protected AdminGuestQueryService adminGuestQueryService;
    @MockBean protected AdminMemberTierCommandService adminMemberTierCommandService;
    @MockBean protected AdminMemberWithdrawCommandService adminMemberWithdrawCommandService;
    @MockBean protected AdminBulkPartyroomActionService adminBulkPartyroomActionService;
    @MockBean protected AdminCrewPenaltyCommandService adminCrewPenaltyCommandService;
    @MockBean protected AvatarCatalogCommandUseCase avatarCatalogCommandUseCase;
    @MockBean protected AvatarAdminCatalogQueryUseCase avatarAdminCatalogQueryUseCase;
    @MockBean protected AdminReportQueryService adminReportQueryService;
    @MockBean protected AdminReportCommandService adminReportCommandService;
    @MockBean protected SystemAnnouncementCommandService systemAnnouncementCommandService;
    @MockBean protected SystemAnnouncementRepository systemAnnouncementRepository;
}
