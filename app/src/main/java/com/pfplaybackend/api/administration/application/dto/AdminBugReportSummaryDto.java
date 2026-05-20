package com.pfplaybackend.api.administration.application.dto;

import java.time.LocalDateTime;

public record AdminBugReportSummaryDto(
        Long bugReportId,
        Long reporterUserAccountId,
        String reporterEmail,
        String reporterNickname,
        String contentPreview,
        Long partyroomId,
        LocalDateTime createdAt
) {}
