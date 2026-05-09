package com.pfplaybackend.api.user.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.user.adapter.in.web.api.UserAvatarApi;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import com.pfplaybackend.api.user.application.service.UserAvatarQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "User Profile API")
@RequestMapping("/api/v1/users")
@RestController
@RequiredArgsConstructor
public class UserAvatarQueryController implements UserAvatarApi {

    private final UserAvatarQueryService userAvatarQueryService;

    @Operation(summary = "아바타 바디 목록 조회", description = "사용 가능한 아바타 바디 리소스 목록을 조회합니다. 게스트와 회원 모두 조회 가능합니다.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasAnyRole('GUEST', 'MEMBER')")
    @GetMapping("/me/profile/avatar/bodies")
    public ResponseEntity<ApiCommonResponse<List<AvatarBodyDto>>> getMyAllAvatarBodies() {
        List<AvatarBodyDto> avatarBodies = userAvatarQueryService.findMyAvatarBodies();
        return ResponseEntity.ok().body(ApiCommonResponse.success(avatarBodies));
    }

    @Operation(summary = "아바타 얼굴 목록 조회", description = "사용 가능한 아바타 얼굴 리소스 목록을 조회합니다. 게스트와 회원 모두 조회 가능합니다.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasAnyRole('GUEST', 'MEMBER')")
    @GetMapping("/me/profile/avatar/faces")
    public ResponseEntity<ApiCommonResponse<List<AvatarFaceDto>>> getMyDefaultAvatarFaces() {
        return ResponseEntity.ok().body(ApiCommonResponse.success(
                userAvatarQueryService.findMyAvatarFaces())
        );
    }
}
