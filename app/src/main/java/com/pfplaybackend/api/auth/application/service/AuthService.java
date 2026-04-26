package com.pfplaybackend.api.auth.application.service;

import com.pfplaybackend.api.auth.application.dto.command.OAuthLoginCommand;
import com.pfplaybackend.api.auth.application.dto.result.AuthResult;
import com.pfplaybackend.api.auth.domain.enums.OAuthProvider;
import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.AuthenticationException;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.service.MemberSignService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final OAuthClientService oAuthClientService;
    private final MemberSignService memberSignService;
    private final UserAccountRepository userAccountRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    @Transactional
    public AuthResult processOAuthLogin(OAuthLoginCommand command) {

        try {
            OAuthProvider provider = OAuthProvider.fromString(command.provider());
            log.debug("Processing OAuth login for provider: {}", provider);

            var tokenResponse = oAuthClientService.exchangeCodeForToken(
                    provider,
                    command.code(),
                    command.codeVerifier()
            );

            var userProfile = oAuthClientService.getUserProfile(
                    provider,
                    tokenResponse.accessToken()
            );

            ProviderType providerType = ProviderType.valueOf(provider.name());
            MemberData member = memberSignService.getMemberOrCreate(userProfile.email(), providerType);

            UserAccountData userAccount = userAccountRepository
                    .findByUserId(new UserId(member.getUserAccountId()))
                    .orElseThrow(() -> new IllegalStateException(
                            "UserAccount missing for member " + member.getMemberId()));

            String token = jwtService.mintSharedSessionToken(new TokenClaimsRequest(
                    String.valueOf(member.getUserAccountId()),
                    userAccount.getEmail(),
                    List.of(AccessLevel.ROLE_MEMBER),
                    member.getAuthorityTier()
            ));

            return new AuthResult(token, "Cookie", jwtProperties.getSharedSessionTokenExpirationMs(), LocalDateTime.now(clock));

        } catch (Exception e) {
            log.error("OAuth login failed: {}", e.getMessage(), e);
            throw new AuthenticationException("Authentication failed: " + e.getMessage());
        }
    }

    public boolean validateToken(String token) {
        return jwtService.validate(token);
    }
}
