package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminBugReportListResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminBugReportQueryRepository;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportListQuery;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminBugReportQueryService {

    private static final Set<String> ALLOWED_SORT_BY = Set.of("createdAt");
    private static final Set<String> ALLOWED_DIRECTION = Set.of("ASC", "DESC");

    private final AdminBugReportQueryRepository repository;

    public AdminBugReportListResponse getList(AdminBugReportListQuery query) {
        if (!ALLOWED_SORT_BY.contains(query.getSortBy())
                || !ALLOWED_DIRECTION.contains(query.getDirection())
                || query.getPage() < 0
                || query.getSize() <= 0
                || query.getSize() > 100) {
            throw ExceptionCreator.create(BugReportException.INVALID_LIST_QUERY);
        }
        List<AdminBugReportSummaryDto> rows = repository.findRows(query);
        long total = repository.count(query);
        long totalPages = total == 0 ? 0 : (long) Math.ceil((double) total / query.getSize());
        return new AdminBugReportListResponse(total, totalPages, query.getPage(), query.getSize(), rows);
    }

    public AdminBugReportDetailDto getDetail(Long bugReportId) {
        return repository.findDetail(bugReportId)
                .orElseThrow(() -> ExceptionCreator.create(BugReportException.BUG_REPORT_NOT_FOUND));
    }
}
