package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestListQuery;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestSummaryResponse;
import com.pfplaybackend.api.administration.application.service.AdminGuestQueryService;
import com.pfplaybackend.api.administration.domain.exception.AdminGuestException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Guest 어드민 조회 controller (read-only).
 *
 * <p>모든 endpoint 는 {@code @adminAuth.isAdmin()} 로 게이팅. {@code GUEST_NOT_FOUND}
 * 도메인 예외는 GlobalExceptionHandler 가 404 로, {@code INVALID_LIST_QUERY} 는 400.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-d8-admin-guest-readonly-design.md §5.
 */
@Tag(name = "Admin Guest Queries API", description = "Guest 목록/상세 read-only")
@RestController
@RequestMapping("/api/v1/admin/guests")
@RequiredArgsConstructor
@Validated
public class AdminGuestQueryController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final String SORT_PATTERN = "created_at_desc|created_at_asc|last_activity_desc";

    private final AdminGuestQueryService adminGuestQueryService;

    @Operation(summary = "Guest 목록 — filter(email/joined_*)/sort/pagination")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping
    public ResponseEntity<ApiCommonResponse<Page<AdminGuestSummaryResponse>>> getList(
            @RequestParam(required = false) @Size(max = 255) String email,
            @RequestParam(name = "joined_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
            @RequestParam(name = "joined_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(defaultValue = AdminGuestListQuery.SORT_CREATED_AT_DESC)
            @Pattern(regexp = SORT_PATTERN) String sort
    ) {
        if (joinedFrom != null && joinedTo != null && joinedFrom.isAfter(joinedTo)) {
            throw ExceptionCreator.create(AdminGuestException.INVALID_LIST_QUERY);
        }

        AdminGuestListQuery query = new AdminGuestListQuery(email, joinedFrom, joinedTo, sort);
        Pageable pageable = PageRequest.of(page, size);
        Page<AdminGuestSummaryResponse> result = adminGuestQueryService.getList(query, pageable);
        return ResponseEntity.ok(ApiCommonResponse.success(result));
    }

    @Operation(summary = "Guest 상세 — recentActivityLog top 30")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping("/{guestId}")
    public ResponseEntity<ApiCommonResponse<AdminGuestDetailResponse>> getDetail(
            @PathVariable Long guestId) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                adminGuestQueryService.getDetail(guestId)));
    }
}
