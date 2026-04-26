package com.pfplaybackend.api.user.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.user.adapter.in.web.payload.response.QueryMyProfileSummaryResponse;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSummaryDto;
import com.pfplaybackend.api.user.application.service.UserProfileQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Profile API")
@RequestMapping("/api/v1/users")
@RestController
@RequiredArgsConstructor
public class UserProfileQueryController {

    private final UserProfileQueryService userProfileQueryService;

    @Operation(summary = "내 프로필 요약 조회", description = "현재 인증된 사용자(게스트 또는 회원)의 프로필 요약 정보를 조회합니다. 닉네임, 아바타, 자기소개 등 프로필 핵심 정보가 포함됩니다.")
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping("/me/profile/summary")
    @PreAuthorize("hasAnyRole('GUEST', 'MEMBER')")
    public ResponseEntity<ApiCommonResponse<QueryMyProfileSummaryResponse>> getMyProfileSummary() {
        ProfileSummaryDto profileSummaryDto = userProfileQueryService.getMyProfileSummary();
        return ResponseEntity.ok().body(ApiCommonResponse.success(QueryMyProfileSummaryResponse.from(profileSummaryDto)));
    }
}