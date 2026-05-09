package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberTierChangeRequest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberTierChangeResponse;
import com.pfplaybackend.api.administration.application.service.AdminMemberTierCommandService;
import com.pfplaybackend.api.administration.domain.exception.AdminMemberException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-3 PATCH /api/v1/admin/members/{memberId}/tier — Member tier 변경.
 *
 * <p>{@link AdminMemberException#TIER_UNCHANGED} 400, {@link AdminMemberException#MEMBER_NOT_FOUND} 404
 * 매핑은 {@link com.pfplaybackend.api.common.exception.GlobalExceptionHandler}.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b2-design.md §3.1
 */
@Tag(name = "Admin Member Tier API", description = "A-3 Member tier change")
@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
public class AdminMemberTierCommandController {

    private final AdminMemberTierCommandService adminMemberTierCommandService;

    @Operation(summary = "A-3 Member tier 변경",
            description = "Member의 authority_tier를 변경하고 MemberTierChangedEvent를 publish한다.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PatchMapping("/{memberId}/tier")
    public ResponseEntity<ApiCommonResponse<AdminMemberTierChangeResponse>> changeTier(
            @PathVariable Long memberId,
            @Valid @RequestBody AdminMemberTierChangeRequest request) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                adminMemberTierCommandService.changeTier(memberId, request)));
    }
}
