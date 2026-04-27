package com.pfplaybackend.api.auth.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.value.AdminRole;
import com.pfplaybackend.api.auth.application.dto.command.AdminLoginCommand;
import com.pfplaybackend.api.auth.application.dto.result.AdminAuthResult;
import com.pfplaybackend.api.auth.application.ratelimit.AdminLoginRateLimiter;
import com.pfplaybackend.api.auth.domain.exception.AdminAuthException;
import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.service.MemberSignService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLoginService {

    private final UserAccountRepository userAccountRepository;
    private final AdministratorRepository administratorRepository;
    private final MemberSignService memberSignService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AdminLoginRateLimiter rateLimiter;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public AdminAuthResult login(AdminLoginCommand cmd) {
        try {
            rateLimiter.checkOrThrow(cmd.clientIp(), cmd.email());
        } catch (AdminLoginRateLimiter.RateLimitedException e) {
            log.warn("admin_login.rate_limited ip={} email={}", cmd.clientIp(), cmd.email());
            throw ExceptionCreator.create(AdminAuthException.RATE_LIMITED);
        }

        Optional<UserAccountData> uaOpt =
                userAccountRepository.findByEmailAndProviderType(cmd.email(), ProviderType.LOCAL);
        if (uaOpt.isEmpty()) {
            log.info("admin_login.unknown_email email={}", cmd.email());
            throw ExceptionCreator.create(AdminAuthException.INVALID_CREDENTIALS);
        }
        UserAccountData ua = uaOpt.get();

        if (!passwordEncoder.matches(cmd.password(), ua.getPasswordHash())) {
            log.info("admin_login.wrong_password user_id={} email={}",
                    ua.getUserId().getUid(), cmd.email());
            throw ExceptionCreator.create(AdminAuthException.INVALID_CREDENTIALS);
        }

        Optional<AdministratorData> admOpt =
                administratorRepository.findByUserAccountId(ua.getUserId().getUid());
        if (admOpt.isEmpty()) {
            log.warn("admin_login.no_administrator user_id={} email={}",
                    ua.getUserId().getUid(), cmd.email());
            throw ExceptionCreator.create(AdminAuthException.INVALID_CREDENTIALS);
        }
        AdministratorData adm = admOpt.get();
        if (adm.isRevoked()) {
            log.warn("admin_login.revoked user_id={}", ua.getUserId().getUid());
            throw ExceptionCreator.create(AdminAuthException.ACCOUNT_REVOKED);
        }

        // Admins must function as platform members too (partyroom access).
        // Lazy-create the linked Member if missing.
        MemberData member = memberSignService.getMemberOrCreate(cmd.email(), ProviderType.LOCAL);

        List<AccessLevel> adminLevels = new ArrayList<>();
        adminLevels.add(AccessLevel.ROLE_ADMIN);
        if (adm.getRole() == AdminRole.SUPER_ADMIN) {
            adminLevels.add(AccessLevel.ROLE_SUPER_ADMIN);
        }

        String adminToken = jwtService.mintAdminAccessToken(new TokenClaimsRequest(
                String.valueOf(ua.getUserId().getUid()),
                ua.getEmail(),
                adminLevels,
                member.getAuthorityTier()
        ));

        String sharedToken = jwtService.mintSharedSessionToken(new TokenClaimsRequest(
                String.valueOf(ua.getUserId().getUid()),
                ua.getEmail(),
                List.of(AccessLevel.ROLE_MEMBER),
                member.getAuthorityTier()
        ));

        rateLimiter.onLoginSuccess(cmd.email());

        log.info("admin_login.success user_id={} role={}",
                ua.getUserId().getUid(), adm.getRole());

        return new AdminAuthResult(
                adminToken,
                sharedToken,
                adm.getRole(),
                jwtProperties.getAdminAccessTokenExpirationMs(),
                jwtProperties.getSharedSessionTokenExpirationMs(),
                LocalDateTime.now(clock),
                ua.isMustChangePassword()
        );
    }
}
