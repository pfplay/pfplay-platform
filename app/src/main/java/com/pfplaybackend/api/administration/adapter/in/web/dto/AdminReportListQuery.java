package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * C-2 list query parameters. Controller에서 Spring binding 후 service로 전달.
 *
 * <p>{@code statuses} / {@code categories}는 null 또는 empty 시 전체. {@code createdFrom} /
 * {@code createdTo}는 inclusive — repository 구현이 to+1 day 미만으로 변환. {@code sort} 허용
 * 값: {@link #SORT_CREATED_AT_DESC}(default), {@link #SORT_CREATED_AT_ASC}.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.2
 */
public record AdminReportListQuery(
        List<ReportStatus> statuses,
        List<ReportCategory> categories,
        LocalDate createdFrom,
        LocalDate createdTo
) {
    public static final String SORT_CREATED_AT_DESC = "created_at_desc";
    public static final String SORT_CREATED_AT_ASC = "created_at_asc";
}
