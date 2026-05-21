package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminBugReportDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminBugReportListResponse;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportListQuery;
import com.pfplaybackend.api.administration.application.service.AdminBugReportQueryService;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Tag(name = "Admin VOC — Bug Report Query API")
@RequestMapping("/api/v1/admin/voc/bug-reports")
@RestController
@RequiredArgsConstructor
public class AdminBugReportQueryController {

    private final AdminBugReportQueryService adminBugReportQueryService;

    @Operation(summary = "버그 리포트 목록 조회", description = "어드민 read-only. 기간/키워드 필터, createdAt DESC 정렬.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @SecurityRequirement(name = "adminAuth")
    @ApiErrorCodes({BugReportException.class})
    @GetMapping
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<AdminBugReportListResponse>> getList(
            @Parameter @RequestParam(defaultValue = "0") int page,
            @Parameter @RequestParam(defaultValue = "20") int size,
            @Parameter @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter @RequestParam(defaultValue = "DESC") String direction,
            @Parameter @RequestParam(required = false) String contentKeyword,
            @Parameter @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
            @Parameter @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo) {
        AdminBugReportListQuery query = AdminBugReportListQuery.builder()
                .page(page).size(size).sortBy(sortBy).direction(direction)
                .contentKeyword(contentKeyword)
                .createdFrom(createdFrom).createdTo(createdTo)
                .build();
        return ResponseEntity.ok(ApiCommonResponse.success(adminBugReportQueryService.getList(query)));
    }

    @Operation(summary = "버그 리포트 상세 조회", description = "어드민 read-only.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @SecurityRequirement(name = "adminAuth")
    @ApiErrorCodes({BugReportException.class})
    @GetMapping("/{bugReportId}")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<AdminBugReportDetailResponse>> getDetail(
            @Parameter @PathVariable Long bugReportId) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                new AdminBugReportDetailResponse(adminBugReportQueryService.getDetail(bugReportId))));
    }
}
