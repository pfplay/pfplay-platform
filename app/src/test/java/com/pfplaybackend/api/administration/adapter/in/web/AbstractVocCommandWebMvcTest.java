package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.application.service.BugReportCommandService;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.CustomJwtAuthenticationToken;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

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

    /**
     * 인증 주체를 {@link CustomJwtAuthenticationToken} 으로 주입한다. principal 이 {@link UserId} 이므로
     * 컨트롤러의 {@code @AuthenticationPrincipal UserId} 가 채워진다. (Spring Security test 의 jwt()
     * post-processor 는 principal 이 Jwt 라 @AuthenticationPrincipal UserId 가 null — 실제 토큰 형태 미러)
     */
    protected static RequestPostProcessor authedUser(long userId) {
        return authedPrincipal(userId, "ROLE_MEMBER", AuthorityTier.AM);
    }

    /**
     * 게스트 인증 주체(ROLE_GUEST + {@link AuthorityTier#GT}). VOC 버그 신고는 게스트도 허용이
     * 의도된 동작이라, 그 계약을 회귀 잠금하기 위한 헬퍼.
     */
    protected static RequestPostProcessor authedGuest(long userId) {
        return authedPrincipal(userId, "ROLE_GUEST", AuthorityTier.GT);
    }

    private static RequestPostProcessor authedPrincipal(long userId, String role, AuthorityTier tier) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .claim("sub", String.valueOf(userId)).build();
        CustomJwtAuthenticationToken token = new CustomJwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority(role)),
                new UserId(userId),
                "tester@pfplay.local",
                tier);
        return authentication(token);
    }
}
