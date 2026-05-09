package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.AdminApplyPenaltyRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminApplyPenaltyResponse;
import com.pfplaybackend.api.administration.application.service.AdminCrewPenaltyCommandService;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.exception.PenaltyException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 어드민의 크루 페널티 부과/해제 endpoint.
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §4.1, §4.2
 *
 * 기존 /api/v1/partyrooms/{id}/penalties (CrewPenaltyCommandController)는
 * 크루 페널티 경로 그대로 — 본 controller는 어드민 전용 분리 경로.
 */
@Tag(name = "Admin Partyroom Penalty API")
@RequestMapping("/api/v1/admin/partyrooms/{partyroomId}/penalties")
@RestController
@RequiredArgsConstructor
public class AdminCrewPenaltyCommandController {

    private final AdminCrewPenaltyCommandService service;

    @Operation(summary = "어드민 페널티 부과",
            description = "ONE_TIME_EXPULSION 또는 PERMANENT_EXPULSION을 어드민 권한으로 부과한다.")
    @ApiResponse(responseCode = "201", description = "부과 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PartyroomException.class, CrewException.class})
    @PostMapping
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<AdminApplyPenaltyResponse>> apply(
            @Parameter(description = "파티룸 ID") @PathVariable("partyroomId") Long partyroomId,
            @Valid @RequestBody AdminApplyPenaltyRequest req) {
        Long penaltyId = service.apply(partyroomId, req.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(new AdminApplyPenaltyResponse(penaltyId)));
    }

    @Operation(summary = "어드민 페널티 해제",
            description = "어드민이 부과한 페널티만 해제 가능. crew-applied는 403.")
    @ApiResponse(responseCode = "204", description = "해제 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({PartyroomException.class, PenaltyException.class})
    @DeleteMapping("/{penaltyId}")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<Void> release(
            @Parameter(description = "파티룸 ID") @PathVariable("partyroomId") Long partyroomId,
            @Parameter(description = "페널티 이력 ID") @PathVariable("penaltyId") Long penaltyId) {
        service.release(partyroomId, penaltyId);
        return ResponseEntity.noContent().build();
    }
}
