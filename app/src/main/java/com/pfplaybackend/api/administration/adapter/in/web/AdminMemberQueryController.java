package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberDetailResponse;
import com.pfplaybackend.api.administration.application.service.AdminMemberQueryService;
import com.pfplaybackend.api.common.ApiCommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-1 list / A-2 detail 어드민 멤버 조회 controller.
 *
 * <p>모든 endpoint는 {@code @adminAuth.isAdmin()}로 게이팅된다. {@code MEMBER_NOT_FOUND}
 * 도메인 예외는 {@link com.pfplaybackend.api.common.exception.GlobalExceptionHandler}가
 * 404로 매핑한다.
 *
 * <p>PR 12b1 G3 scope: A-2 detail only. A-1 list 메서드는 G4(PR 12b1 Chunk 4)에서 추가된다.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §3.2
 */
@Tag(name = "Admin Member Queries API", description = "A-1 list / A-2 detail")
@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
public class AdminMemberQueryController {

    private final AdminMemberQueryService adminMemberQueryService;

    @Operation(summary = "A-2 멤버 상세 — recentActivityLog top 30")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping("/{memberId}")
    public ResponseEntity<ApiCommonResponse<AdminMemberDetailResponse>> getDetail(
            @PathVariable Long memberId) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                adminMemberQueryService.getDetail(memberId)));
    }

    // GET / list 메서드는 G4(PR 12b1 Chunk 4)에서 추가.
}
