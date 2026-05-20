package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;

import java.util.List;

public record AdminBugReportListResponse(
        long totalElements,
        long totalPages,
        int page,
        int size,
        List<AdminBugReportSummaryDto> items
) {}
