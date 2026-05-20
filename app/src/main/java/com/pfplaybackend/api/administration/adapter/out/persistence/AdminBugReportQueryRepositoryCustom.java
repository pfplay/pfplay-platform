package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportListQuery;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;

import java.util.List;
import java.util.Optional;

public interface AdminBugReportQueryRepositoryCustom {
    List<AdminBugReportSummaryDto> findRows(AdminBugReportListQuery query);
    long count(AdminBugReportListQuery query);
    Optional<AdminBugReportDetailDto> findDetail(Long bugReportId);
}
