package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportListQuery;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportSummaryResponse;
import com.pfplaybackend.api.administration.application.service.AdminReportQueryService;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import com.pfplaybackend.api.administration.domain.exception.AdminReportException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
import java.util.List;

/**
 * C-2 어드민 신고 list/detail controller.
 *
 * <p>Bean Validation 표준 패턴(PR 12b2 G1) 적용 — {@code @Validated} 클래스 + {@code @Min/@Max
 * /@Pattern} per {@code @RequestParam}. {@code ConstraintViolationException}은
 * {@code GlobalExceptionHandler}가 400으로 매핑. cross-field({@code created_from} >
 * {@code created_to})는 inline guard로 {@link AdminReportException#INVALID_LIST_QUERY} (RPT-004).
 *
 * <p>모든 endpoint는 {@code @adminAuth.isAdmin()}로 게이팅된다 — anonymous 401, non-admin 403.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.2, §3.3
 */
@Tag(name = "Admin Report Queries API", description = "C-2 list / detail")
@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
@Validated
public class AdminReportQueryController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final String SORT_PATTERN = "created_at_desc|created_at_asc";

    private final AdminReportQueryService adminReportQueryService;

    @Operation(summary = "C-2 신고 목록 — filter/pagination/sort")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping
    public ResponseEntity<ApiCommonResponse<Page<AdminReportSummaryResponse>>> getList(
            @RequestParam(required = false) List<ReportStatus> status,
            @RequestParam(required = false) List<ReportCategory> category,
            @RequestParam(name = "created_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(name = "created_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(defaultValue = AdminReportListQuery.SORT_CREATED_AT_DESC)
            @Pattern(regexp = SORT_PATTERN) String sort
    ) {
        // cross-field 검증 — Bean Validation 표준 부재로 inline guard.
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw ExceptionCreator.create(AdminReportException.INVALID_LIST_QUERY);
        }

        Sort sortSpec = AdminReportListQuery.SORT_CREATED_AT_ASC.equals(sort)
                ? Sort.by("createdAt").ascending()
                : Sort.by("createdAt").descending();
        Pageable pageable = PageRequest.of(page, size, sortSpec);

        AdminReportListQuery query = new AdminReportListQuery(status, category, createdFrom, createdTo);
        Page<AdminReportSummaryResponse> result = adminReportQueryService.getList(query, pageable);
        return ResponseEntity.ok(ApiCommonResponse.success(result));
    }

    @Operation(summary = "C-2 신고 상세 — cross-context join (reporter, partyroom, host, review)")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping("/{reportId}")
    public ResponseEntity<ApiCommonResponse<AdminReportDetailResponse>> getDetail(
            @PathVariable @Min(1) Long reportId
    ) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                adminReportQueryService.getDetail(reportId)));
    }
}
