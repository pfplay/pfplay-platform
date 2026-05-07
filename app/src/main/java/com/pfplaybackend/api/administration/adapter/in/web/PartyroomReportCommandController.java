package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.PartyroomReportCreateRequest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.PartyroomReportCreateResponse;
import com.pfplaybackend.api.administration.application.service.PartyroomReportCommandService;
import com.pfplaybackend.api.administration.domain.exception.AdminReportException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.security.jwt.CustomJwtAuthenticationToken;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-1 POST /api/v1/partyrooms/{partyroomId}/reports — 유저가 파티룸을 신고한다.
 *
 * <p>SecurityConfig에서 {@code /api/**}는 {@code authenticated()}만 보장한다 — Member tier(Guest 차단)는
 * service-layer guard ({@link PartyroomReportCommandService#create})가 담당. Anonymous는 OAuth2 resource server가
 * 401로 차단하고, authenticated Guest는 service에서 PTR-005 (FORBIDDEN) 매핑.
 *
 * <p>{@link AdminReportException} 매핑은 {@code GlobalExceptionHandler}.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.1
 */
@Tag(name = "Partyroom Report API", description = "C-1 유저 신고 접수")
@RestController
@RequestMapping("/api/v1/partyrooms")
@RequiredArgsConstructor
@Validated
public class PartyroomReportCommandController {

    private final PartyroomReportCommandService reportCommandService;

    @Operation(summary = "C-1 파티룸 신고 접수",
            description = "인증된 Member가 active 파티룸을 신고한다. self-report / 24h 동일 카테고리 중복 / 비-active 룸은 차단.")
    @ApiResponse(responseCode = "201", description = "신고 생성 성공")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{partyroomId}/reports")
    public ResponseEntity<ApiCommonResponse<PartyroomReportCreateResponse>> create(
            @Parameter(description = "파티룸 ID") @PathVariable @Min(1) Long partyroomId,
            @Valid @RequestBody PartyroomReportCreateRequest request) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        CustomJwtAuthenticationToken token = (CustomJwtAuthenticationToken) auth;
        AuthorityTier reporterTier = token.getAuthorityTier();
        Long reporterUserAccountId = token.getUserId().getUid();

        PartyroomReportCreateResponse response = reportCommandService.create(
                partyroomId, request, reporterTier, reporterUserAccountId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiCommonResponse.success(response));
    }
}
