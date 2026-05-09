package com.pfplaybackend.api.user.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.exception.http.UnauthorizedException;
import com.pfplaybackend.api.user.application.dto.result.MyInfoResult;
import com.pfplaybackend.api.user.application.service.UserInfoQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Sign API")
@RequestMapping("/api/v1/users")
@RestController
@RequiredArgsConstructor
public class UserInfoQueryController {

    private final UserInfoQueryService userInfoService;
    private final SharedSessionCookieWriter sharedSessionCookieWriter;

    @Operation(summary = "내 정보 조회", description = "현재 인증된 사용자(게스트 또는 회원)의 기본 정보를 조회합니다. 인증이 유효하지 않은 경우 토큰 쿠키가 삭제됩니다.")
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping("/me/info")
    @PreAuthorize("hasAnyRole('GUEST', 'MEMBER')")
    public ResponseEntity<ApiCommonResponse<MyInfoResult>> getMyInfo(HttpServletResponse response) {
        try {
            MyInfoResult myInfoResult = userInfoService.getMyInfo();
            return ResponseEntity
                    .status(HttpStatus.OK)
                    .body(ApiCommonResponse.success(myInfoResult));
        } catch (UnauthorizedException e) {
            sharedSessionCookieWriter.clear(response);
            throw e;
        }
    }
}
