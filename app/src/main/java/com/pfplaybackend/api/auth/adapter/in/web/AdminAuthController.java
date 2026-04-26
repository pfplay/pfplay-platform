package com.pfplaybackend.api.auth.adapter.in.web;

import com.pfplaybackend.api.auth.adapter.in.web.payload.request.AdminLoginRequest;
import com.pfplaybackend.api.auth.adapter.in.web.payload.response.AdminLoginResponse;
import com.pfplaybackend.api.auth.application.dto.command.AdminLoginCommand;
import com.pfplaybackend.api.auth.application.dto.result.AdminAuthResult;
import com.pfplaybackend.api.auth.application.service.AdminLoginService;
import com.pfplaybackend.api.auth.domain.exception.AdminAuthException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Admin Auth API", description = "어드민 로컬 로그인/로그아웃")
@RestController
@RequestMapping("/api/v1/auth/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminLoginService adminLoginService;
    private final AdminCookieWriter adminCookieWriter;
    private final SharedSessionCookieWriter sharedSessionCookieWriter;

    @Operation(summary = "어드민 로그인",
            description = "이메일+비밀번호 로컬 인증. 성공 시 AdminAccessToken 쿠키(15분), Member 연결 시 SharedSessionToken 쿠키(24h)도 함께 발급.")
    @ApiErrorCodes({AdminAuthException.class})
    @PostMapping("/login")
    public ResponseEntity<ApiCommonResponse<AdminLoginResponse>> login(
            @Valid @RequestBody AdminLoginRequest req,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        String clientIp = resolveClientIp(httpRequest);

        AdminAuthResult result = adminLoginService.login(
                new AdminLoginCommand(req.getEmail(), req.getPassword(), clientIp));

        adminCookieWriter.write(response, result.adminAccessToken());
        if (result.sharedSessionToken() != null) {
            sharedSessionCookieWriter.write(response, result.sharedSessionToken());
        }

        return ResponseEntity.ok(ApiCommonResponse.success(AdminLoginResponse.builder()
                .tokenType("Cookie")
                .expiresIn(result.adminAccessTokenTtlMs() / 1000)
                .issuedAt(result.issuedAt())
                .role(result.role())
                .build()));
    }

    @Operation(summary = "어드민 로그아웃", description = "AdminAccessToken과 SharedSessionToken 쿠키를 모두 만료시킵니다.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        adminCookieWriter.clear(response);
        sharedSessionCookieWriter.clear(response);
        return ResponseEntity.noContent().build();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }
}
