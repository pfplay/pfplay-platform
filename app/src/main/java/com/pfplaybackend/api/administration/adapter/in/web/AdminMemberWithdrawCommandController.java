package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberWithdrawResponse;
import com.pfplaybackend.api.administration.application.service.AdminMemberWithdrawCommandService;
import com.pfplaybackend.api.administration.domain.exception.AdminMemberException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-4 POST /api/v1/admin/members/{memberId}/withdraw — Member 비식별화 탈퇴.
 *
 * <p>Idempotent — 이미 탈퇴된 member 재호출 시 200 + {@code alreadyWithdrawn=true}.
 * {@link AdminMemberException#MEMBER_NOT_FOUND} 404 매핑은
 * {@link com.pfplaybackend.api.common.exception.GlobalExceptionHandler}.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b2-design.md §3.2
 */
@Tag(name = "Admin Member Withdraw API", description = "A-4 Member 비식별화 탈퇴")
@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
public class AdminMemberWithdrawCommandController {

    private final AdminMemberWithdrawCommandService adminMemberWithdrawCommandService;

    @Operation(summary = "A-4 Member 비식별화 탈퇴",
            description = "어드민이 member를 강제 탈퇴 처리한다. email PII erase, lastLoginAt 보존, " +
                    "UserAccountWithdrawnEvent publish. 재호출은 idempotent — alreadyWithdrawn flag 응답.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PostMapping("/{memberId}/withdraw")
    public ResponseEntity<ApiCommonResponse<AdminMemberWithdrawResponse>> withdraw(
            @PathVariable Long memberId) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                adminMemberWithdrawCommandService.withdraw(memberId)));
    }
}
