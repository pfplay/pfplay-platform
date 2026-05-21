package com.pfplaybackend.api.administration.application.dto;

import java.time.LocalDateTime;

public record AdminBugReportDetailDto(
        Long bugReportId,
        Long reporterUserAccountId,
        String reporterEmail,
        String reporterNickname,
        String content,
        String pageUrl,
        String userAgent,
        Long partyroomId,
        String partyroomName,
        LocalDateTime createdAt
) {}
