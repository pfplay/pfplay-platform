package com.pfplaybackend.api.user.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.application.service.GuestSignService;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Sign API")
@RequestMapping("/api/v1/users")
@RestController
@RequiredArgsConstructor
public class GuestSignController {

    private final GuestSignService guestSignService;
    private final SharedSessionCookieWriter sharedSessionCookieWriter;
    private final JwtService jwtService;

    @Operation(summary = "게스트 로그인", description = "게스트 사용자를 생성하거나 기존 게스트를 조회하여 액세스 토큰을 쿠키로 발급합니다. 인증 없이 호출 가능합니다.")
    @PostMapping("/guests/sign")
    public ResponseEntity<ApiCommonResponse<Void>> createGuest(
            HttpServletResponse response
    ) {
        GuestData guest = guestSignService.getGuestOrCreate();
        // user_id moved off Guest in the V4 IAM refactor — Guest now keys on
        // user_account_id (which equals UserAccount.userId.uid by construction).
        sharedSessionCookieWriter.write(response, jwtService.mintSharedSessionToken(new TokenClaimsRequest(
                guest.getUserAccountId().toString(),
                "N/A",
                java.util.List.of(AccessLevel.ROLE_GUEST),
                AuthorityTier.GT
        )));

        return ResponseEntity.ok()
                .body(ApiCommonResponse.ok());
    }
}
