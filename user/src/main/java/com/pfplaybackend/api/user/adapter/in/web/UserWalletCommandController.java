package com.pfplaybackend.api.user.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.CookieUtil;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.in.web.payload.request.UpdateMyWalletRequest;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.dto.command.UpdateWalletCommand;
import com.pfplaybackend.api.user.application.service.UserWalletCommandService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Profile API")
@RequestMapping("/api/v1/users")
@RestController
@RequiredArgsConstructor
public class UserWalletCommandController {

    private final UserWalletCommandService userWalletService;
    private final UserAccountRepository userAccountRepository;
    private final CookieUtil cookieUtil;
    private final JwtService jwtService;

    @Operation(summary = "지갑 주소 수정", description = "현재 인증된 회원의 지갑 주소를 수정합니다. 수정 후 갱신된 정보로 액세스 토큰이 재발급됩니다. 회원만 사용 가능합니다.")
    @SecurityRequirement(name = "cookieAuth")
    @PutMapping("/me/profile/wallet")
    @PreAuthorize("hasRole('ROLE_MEMBER')")
    public ResponseEntity<ApiCommonResponse<Void>> updateMyWallet(@Valid @RequestBody UpdateMyWalletRequest request, HttpServletResponse response) {
        MemberData member = userWalletService.updateMyWalletAddress(new UpdateWalletCommand(request.getWalletAddress()));
        // Email and user_id live on UserAccount post-V4. Fetch the bound account
        // for token claims rather than reaching through Member.
        UserAccountData userAccount = userAccountRepository.findById(new UserId(member.getUserAccountId())).orElseThrow();
        cookieUtil.addAccessTokenCookie(response, jwtService.generateNonExpiringAccessToken(new TokenClaimsRequest(
                userAccount.getUserId().getUid().toString(),
                userAccount.getEmail(),
                AccessLevel.ROLE_MEMBER,
                member.getAuthorityTier()
        )));

        return ResponseEntity.ok()
                .body(ApiCommonResponse.ok());
    }
}
