package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

/**
 * C-2 list 어드민 query custom interface — QueryDSL 동적 query.
 *
 * <p>{@code statuses} / {@code categories}가 null이거나 비어있으면 해당 필터 미적용. {@code
 * createdFrom}는 {@code goe(at start of day)}, {@code createdTo}는 inclusive — {@code lt(plus 1
 * day at start of day)}로 변환. sort key 분기는 {@code Pageable}이 controller에서 build해 전달.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.2
 */
public interface PartyroomReportRepositoryCustom {

    Page<PartyroomReportData> findList(
            List<ReportStatus> statuses,
            List<ReportCategory> categories,
            LocalDate createdFrom,
            LocalDate createdTo,
            Pageable pageable);
}
