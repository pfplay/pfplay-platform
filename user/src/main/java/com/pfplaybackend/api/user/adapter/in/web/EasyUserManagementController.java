package com.pfplaybackend.api.user.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.CookieUtil;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.service.initialize.TemporaryUserInitializeService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Sign API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class EasyUserManagementController {

    private final CookieUtil cookieUtil;
    private final JwtService jwtService;
    private final UserAccountRepository userAccountRepository;

    private final TemporaryUserInitializeService temporaryUserInitializeService;

    @Operation(summary = "임시 준회원(AM) 생성", description = "개발/테스트 용도로 임시 준회원(Associate Member)을 생성합니다. 만료되지 않는 액세스 토큰이 쿠키에 설정됩니다.")
    @PostMapping("/members/sign/temporary/associate-member")
    public ResponseEntity<ApiCommonResponse<Void>> createAssociateMember(HttpServletResponse response) {
        UserId userId = new UserId();
        MemberData member = temporaryUserInitializeService.addAssociateMember(userId, userId.getUid().toString().substring(0,12) + "@gmail.com");
        UserAccountData userAccount = loadUserAccount(member);
        cookieUtil.addAccessTokenCookie(response, jwtService.generateNonExpiringAccessToken(new TokenClaimsRequest(
                userAccount.getUserId().getUid().toString(),
                userAccount.getEmail(),
                AccessLevel.ROLE_MEMBER,
                member.getAuthorityTier()
        )));

        return ResponseEntity.ok()
                .body(ApiCommonResponse.ok());
    }

    @Operation(summary = "임시 정회원(FM) 생성", description = "개발/테스트 용도로 임시 정회원(Full Member)을 생성합니다. 준회원 생성 후 정회원으로 승격되며, 만료되지 않는 액세스 토큰이 쿠키에 설정됩니다.")
    @PostMapping("/members/sign/temporary/full-member")
    public ResponseEntity<ApiCommonResponse<Void>> createFullMember(HttpServletResponse response) {
        UserId userId = new UserId();
        MemberData member = temporaryUserInitializeService.upgradeMember(
                temporaryUserInitializeService.addAssociateMember(userId, userId.getUid().toString().substring(0,12) + "@gmail.com"));
        UserAccountData userAccount = loadUserAccount(member);
        cookieUtil.addAccessTokenCookie(response, jwtService.generateNonExpiringAccessToken(new TokenClaimsRequest(
                userAccount.getUserId().getUid().toString(),
                userAccount.getEmail(),
                AccessLevel.ROLE_MEMBER,
                member.getAuthorityTier()
        )));

        return ResponseEntity.ok()
                .body(ApiCommonResponse.ok());
    }

    /**
     * Look up the {@link UserAccountData} bound to a {@link MemberData}. Email
     * and userId moved to UserAccount in the V4 IAM refactor, so the
     * controller fetches the account directly to populate token claims.
     */
    private UserAccountData loadUserAccount(MemberData member) {
        return userAccountRepository.findById(new UserId(member.getUserAccountId())).orElseThrow();
    }
}
