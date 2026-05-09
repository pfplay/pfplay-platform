package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomReportRepositoryCustom;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

import static com.pfplaybackend.api.administration.domain.entity.data.QPartyroomReportData.partyroomReportData;

/**
 * QueryDSL 구현 — C-2 list endpoint용.
 *
 * <p>필터 동적 build (BooleanBuilder), createdAt range는 inclusive(상한은 +1d 미만으로 변환),
 * sort는 {@link Pageable#getSort()}의 첫 order만 사용 — controller가 {@code created_at_asc|desc}
 * 두 값만 입력으로 받으므로 stable. count subquery 별도 — pageable.getOffset/Limit과 일관.
 *
 * <p>nickname/title 등 cross-context 컬럼은 detail에서만 join (D7) — 본 list query는 partyroom_report
 * 단독 row 노출.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.2, §6
 */
@Repository
@RequiredArgsConstructor
public class PartyroomReportRepositoryImpl implements PartyroomReportRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<PartyroomReportData> findList(
            List<ReportStatus> statuses,
            List<ReportCategory> categories,
            LocalDate createdFrom,
            LocalDate createdTo,
            Pageable pageable) {

        BooleanBuilder where = new BooleanBuilder();
        if (statuses != null && !statuses.isEmpty()) {
            where.and(partyroomReportData.status.in(statuses));
        }
        if (categories != null && !categories.isEmpty()) {
            where.and(partyroomReportData.category.in(categories));
        }
        if (createdFrom != null) {
            where.and(partyroomReportData.createdAt.goe(createdFrom.atStartOfDay()));
        }
        if (createdTo != null) {
            // inclusive upper — to + 1day < boundary
            where.and(partyroomReportData.createdAt.lt(createdTo.plusDays(1).atStartOfDay()));
        }

        boolean ascending = false;
        Sort.Order order = pageable.getSort().getOrderFor("createdAt");
        if (order != null && order.isAscending()) {
            ascending = true;
        }

        List<PartyroomReportData> rows = queryFactory
                .selectFrom(partyroomReportData)
                .where(where)
                .orderBy(ascending
                        ? partyroomReportData.createdAt.asc()
                        : partyroomReportData.createdAt.desc(),
                        ascending
                                ? partyroomReportData.reportId.asc()
                                : partyroomReportData.reportId.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(partyroomReportData.count())
                .from(partyroomReportData)
                .where(where)
                .fetchOne();

        return new PageImpl<>(rows, pageable, total != null ? total : 0L);
    }
}
