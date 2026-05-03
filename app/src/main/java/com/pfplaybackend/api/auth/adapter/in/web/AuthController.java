package com.pfplaybackend.api.auth.adapter.in.web;

import com.pfplaybackend.api.auth.adapter.in.web.payload.request.GenerateOAuthUrlRequest;
import com.pfplaybackend.api.auth.adapter.in.web.payload.request.LoginOAuthRequest;
import com.pfplaybackend.api.auth.adapter.in.web.payload.response.GenerateOAuthUrlResponse;
import com.pfplaybackend.api.auth.adapter.in.web.payload.response.LoginOAuthResponse;
import com.pfplaybackend.api.auth.application.dto.command.OAuthLoginCommand;
import com.pfplaybackend.api.auth.application.dto.result.AuthResult;
import com.pfplaybackend.api.auth.application.dto.result.OAuthUrlResult;
import com.pfplaybackend.api.auth.application.service.AuthService;
import com.pfplaybackend.api.auth.application.service.LogoutService;
import com.pfplaybackend.api.auth.application.service.OAuthUrlService;
import com.pfplaybackend.api.auth.domain.enums.OAuthProvider;
import com.pfplaybackend.api.auth.domain.exception.AuthException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Auth API", description = "OAuth 인증 및 로그아웃 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final OAuthUrlService oAuthUrlService;
    private final AuthService authService;
    private final LogoutService logoutService;
    private final SharedSessionCookieWriter sharedSessionCookieWriter;

    @Operation(summary = "OAuth 인증 URL 생성", description = "지정된 OAuth 제공자(Google, Twitter)의 인증 URL을 생성합니다. 프론트엔드에서 이 URL로 사용자를 리다이렉트하여 OAuth 인증을 시작합니다.")
    @ApiErrorCodes({AuthException.class})
    @PostMapping("/oauth/url")
    public ResponseEntity<ApiCommonResponse<GenerateOAuthUrlResponse>> generateOAuthUrl(@Valid @RequestBody GenerateOAuthUrlRequest request) {
        OAuthProvider provider = resolveProvider(request.getProvider());
        OAuthUrlResult result = oAuthUrlService.generateAuthUrl(provider, request.getCodeVerifier());

        GenerateOAuthUrlResponse response = GenerateOAuthUrlResponse.builder()
                .authUrl(result.authUrl())
                .state(result.state())
                .provider(result.provider())
                .expiresIn(result.expiresIn())
                .build();

        return ResponseEntity.ok(ApiCommonResponse.success(response));
    }

    @Operation(summary = "OAuth 콜백 로그인", description = "OAuth 제공자로부터 받은 인증 코드를 처리하여 로그인을 완료합니다. 성공 시 JWT 토큰이 HttpOnly 쿠키로 설정됩니다.")
    @ApiErrorCodes({AuthException.class})
    @PostMapping("/oauth/callback")
    public ResponseEntity<ApiCommonResponse<LoginOAuthResponse>> oauthCallback(
            @Valid @RequestBody LoginOAuthRequest request,
            HttpServletResponse response) {

        OAuthProvider provider = resolveProvider(request.getProvider());

        if (request.getState() != null) {
            boolean stateValid = oAuthUrlService.validateAndConsumeState(
                    request.getState(), provider, request.getCodeVerifier());
            if (!stateValid) {
                throw ExceptionCreator.create(AuthException.INVALID_STATE);
            }
        }

        OAuthLoginCommand command = new OAuthLoginCommand(request.getProvider(), request.getCode(), request.getCodeVerifier());
        AuthResult authResult = authService.processOAuthLogin(command);

        sharedSessionCookieWriter.write(response, authResult.accessToken());

        LoginOAuthResponse loginResponse = LoginOAuthResponse.builder()
                .tokenType(authResult.tokenType())
                .expiresIn(authResult.expiresIn())
                .issuedAt(authResult.issuedAt())
                .isNewUser(authResult.isNewUser())
                .build();

        return ResponseEntity.ok(ApiCommonResponse.success(loginResponse));
    }

    @Operation(summary = "로그아웃", description = "현재 사용자를 로그아웃합니다. 활성 파티룸이 있으면 퇴장 처리 후, 인증 쿠키를 삭제합니다.")
    @ApiResponse(responseCode = "204", description = "로그아웃 성공")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        try {
            logoutService.exitActivePartyroomIfPresent();
        } catch (Exception e) {
            log.warn("Failed to exit active partyroom during logout: {}", e.getMessage());
        }

        sharedSessionCookieWriter.clear(response);

        return ResponseEntity.noContent().build();
    }

    private OAuthProvider resolveProvider(String provider) {
        try {
            return OAuthProvider.fromString(provider);
        } catch (IllegalArgumentException e) {
            throw ExceptionCreator.create(AuthException.INVALID_PROVIDER);
        }
    }
}
