package com.pfplaybackend.api.auth.application.service;

import com.pfplaybackend.api.auth.application.dto.command.OAuthLoginCommand;
import com.pfplaybackend.api.auth.application.dto.result.AuthResult;
import com.pfplaybackend.api.auth.domain.enums.OAuthProvider;
import com.pfplaybackend.api.auth.domain.event.UserAccountSignedInEvent;
import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.AuthenticationException;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.dto.result.MemberSignResult;
import com.pfplaybackend.api.user.application.service.MemberSignService;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

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
            MemberSignResult signResult =
                    memberSignService.getMemberOrCreateWithStatus(userProfile.email(), providerType);
            var member = signResult.member();

            UserAccountData userAccount = userAccountRepository
                    .findByUserId(new UserId(member.getUserAccountId()))
                    .orElseThrow(() -> new IllegalStateException(
                            "UserAccount missing for member " + member.getMemberId()));

            // PR 12a — UserActivityLogListener consumes this for SIGNED_IN row (actor_type=USER).
            // Publish AFTER validation succeeds (member resolved, userAccount loaded).
            eventPublisher.publishEvent(new UserAccountSignedInEvent(
                    userAccount.getUserId().getUid(),
                    userAccount.getProviderType(),
                    UserAccountSignedInEvent.ActorType.USER));

            String token = jwtService.mintSharedSessionToken(new TokenClaimsRequest(
                    String.valueOf(member.getUserAccountId()),
                    userAccount.getEmail(),
                    List.of(AccessLevel.ROLE_MEMBER),
                    member.getAuthorityTier()
            ));

            return new AuthResult(
                    token,
                    "Cookie",
                    jwtProperties.getSharedSessionTokenExpirationMs(),
                    LocalDateTime.now(clock),
                    signResult.isNewUser());

        } catch (Exception e) {
            log.error("OAuth login failed: {}", e.getMessage(), e);
            throw new AuthenticationException("Authentication failed: " + e.getMessage());
        }
    }

    public boolean validateToken(String token) {
        return jwtService.validate(token);
    }
}
