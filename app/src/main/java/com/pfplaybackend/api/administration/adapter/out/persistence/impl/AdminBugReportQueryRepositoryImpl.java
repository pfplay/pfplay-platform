package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdminBugReportQueryRepository;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportListQuery;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;
import com.pfplaybackend.api.administration.domain.entity.data.QBugReportData;
import com.pfplaybackend.api.party.domain.entity.data.QPartyroomData;
import com.pfplaybackend.api.user.domain.entity.data.QUserAccountData;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * QueryDSL impl of {@link AdminBugReportQueryRepository}.
 *
 * Spec: docs/superpowers/specs/2026-05-21-voc-bug-report-design.md §3-5
 *
 * D/#8 AdminGuestQueryRepositoryImpl 패턴 미러:
 * - leftJoin user_account on userId.uid = reporter_user_account_id
 * - leftJoin partyroom on id = partyroom_id (detail only)
 * - contentPreview = content.substring(0, 80)
 * - 1차 도입은 reporterNickname null (user_account 컬럼 없음 / profile join 후속)
 */
@Repository
@RequiredArgsConstructor
public class AdminBugReportQueryRepositoryImpl implements AdminBugReportQueryRepository {

    private static final int PREVIEW_LENGTH = 80;

    private final JPAQueryFactory queryFactory;

    @Override
    public List<AdminBugReportSummaryDto> findRows(AdminBugReportListQuery query) {
        QBugReportData br = QBugReportData.bugReportData;
        QUserAccountData ua = QUserAccountData.userAccountData;

        StringExpression preview = br.content.substring(0, PREVIEW_LENGTH);

        return queryFactory
                .select(Projections.constructor(AdminBugReportSummaryDto.class,
                        br.bugReportId,
                        br.reporterUserAccountId,
                        ua.email,
                        Expressions.nullExpression(String.class),    // reporterNickname — 1차 도입 null (profile join 후속)
                        preview,
                        br.partyroomId,
                        br.createdAt))
                .from(br)
                .leftJoin(ua).on(ua.userId.uid.eq(br.reporterUserAccountId))
                .where(buildPredicate(br, query))
                .orderBy(br.createdAt.desc())
                .offset((long) query.getPage() * query.getSize())
                .limit(query.getSize())
                .fetch();
    }

    @Override
    public long count(AdminBugReportListQuery query) {
        QBugReportData br = QBugReportData.bugReportData;
        Long total = queryFactory.select(br.count())
                .from(br)
                .where(buildPredicate(br, query))
                .fetchOne();
        return total == null ? 0L : total;
    }

    @Override
    public Optional<AdminBugReportDetailDto> findDetail(Long bugReportId) {
        QBugReportData br = QBugReportData.bugReportData;
        QUserAccountData ua = QUserAccountData.userAccountData;
        QPartyroomData p = QPartyroomData.partyroomData;

        AdminBugReportDetailDto result = queryFactory
                .select(Projections.constructor(AdminBugReportDetailDto.class,
                        br.bugReportId,
                        br.reporterUserAccountId,
                        ua.email,
                        Expressions.nullExpression(String.class),    // reporterNickname
                        br.content,
                        br.pageUrl,
                        br.userAgent,
                        br.partyroomId,
                        p.title,
                        br.createdAt))
                .from(br)
                .leftJoin(ua).on(ua.userId.uid.eq(br.reporterUserAccountId))
                .leftJoin(p).on(p.id.eq(br.partyroomId))
                .where(br.bugReportId.eq(bugReportId))
                .fetchOne();
        return Optional.ofNullable(result);
    }

    private BooleanBuilder buildPredicate(QBugReportData br, AdminBugReportListQuery query) {
        BooleanBuilder builder = new BooleanBuilder();
        if (query.getCreatedFrom() != null) builder.and(br.createdAt.goe(query.getCreatedFrom()));
        if (query.getCreatedTo() != null)   builder.and(br.createdAt.loe(query.getCreatedTo()));
        if (query.getContentKeyword() != null && !query.getContentKeyword().isBlank()) {
            builder.and(br.content.containsIgnoreCase(query.getContentKeyword()));
        }
        return builder;
    }
}
