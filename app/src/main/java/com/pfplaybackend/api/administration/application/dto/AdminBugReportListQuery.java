package com.pfplaybackend.api.administration.application.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AdminBugReportListQuery {
    private final LocalDateTime createdFrom;
    private final LocalDateTime createdTo;
    private final String contentKeyword;
    private final int page;
    private final int size;
    private final String sortBy;        // "createdAt" only (1차 도입)
    private final String direction;     // "ASC"|"DESC"
}
