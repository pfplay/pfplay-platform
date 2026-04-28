package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberListQuery;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminMemberQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberDetailRow;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberSummaryRow;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.pfplaybackend.api.administration.domain.entity.QUserActivityLogData.userActivityLogData;
import static com.pfplaybackend.api.user.domain.entity.data.QMemberData.memberData;
import static com.pfplaybackend.api.user.domain.entity.data.QUserAccountData.userAccountData;

/**
 * QueryDSL implementation of {@link AdminMemberQueryRepository}.
 *
 * <p>A-2 detail: member + userAccount left join (host row may be hard-deleted in test data).
 * Cross-BC entity reference (User) is allowed only inside this adapter — ArchUnit enforces.
 *
 * <p>Nickname path strategy mirrors PR 8 {@code AdminPartyroomQueryRepositoryImpl}:
 * {@code Bio.nickname} is annotated with {@code @Convert(NicknameConverter.class)} so a
 * direct projection of the path returns a {@link com.pfplaybackend.api.user.domain.value.Nickname}
 * VO and crashes the {@code String nickname} record component. We wrap the path in a
 * {@code cast(... as string)} {@link StringExpression} so the SQL layer hands back a raw
 * String, bypassing the converter.
 *
 * <p>A-1 search: 동일 nickname cast 패턴 + filter/sort/pagination + count subquery. {@code
 * last_activity_desc}는 user_activity_log MAX(occurredAt) GROUP BY left join, COALESCE
 * fallback (활동 0건 회원도 결과에 포함, 가입 시각으로 정렬).
 */
@Repository
@RequiredArgsConstructor
public class AdminMemberQueryRepositoryImpl implements AdminMemberQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<AdminMemberDetailRow> findDetail(Long memberId) {
        StringExpression nicknameAsString = Expressions.stringTemplate(
                "cast({0} as string)", memberData.profileData.bio.nickname);

        AdminMemberDetailRow row = queryFactory
                .select(Projections.constructor(AdminMemberDetailRow.class,
                        memberData.memberId,
                        memberData.userAccountId,
                        userAccountData.email,
                        userAccountData.providerType,
                        userAccountData.lastLoginAt,
                        userAccountData.withdrawnAt,
                        nicknameAsString,
                        memberData.profileData.bio.introduction,
                        memberData.authorityTier,
                        userAccountData.createdAt))
                .from(memberData)
                .leftJoin(userAccountData).on(userAccountData.userId.uid.eq(memberData.userAccountId))
                .where(memberData.memberId.eq(memberId))
                .fetchOne();
        return Optional.ofNullable(row);
    }

    @Override
    public Page<AdminMemberSummaryRow> search(AdminMemberListQuery query, Pageable pageable) {
        StringExpression nicknameAsString = Expressions.stringTemplate(
                "cast({0} as string)", memberData.profileData.bio.nickname);

        BooleanBuilder where = new BooleanBuilder();
        if (query.email() != null && !query.email().isBlank()) {
            where.and(userAccountData.email.containsIgnoreCase(query.email()));
        }
        if (query.tier() != null) {
            where.and(memberData.authorityTier.eq(query.tier()));
        }
        if (query.joinedFrom() != null) {
            where.and(userAccountData.createdAt.goe(query.joinedFrom().atStartOfDay()));
        }
        if (query.joinedTo() != null) {
            where.and(userAccountData.createdAt.lt(query.joinedTo().plusDays(1).atStartOfDay()));
        }

        JPAQuery<AdminMemberSummaryRow> baseQuery = queryFactory
                .select(Projections.constructor(AdminMemberSummaryRow.class,
                        memberData.memberId,
                        memberData.userAccountId,
                        userAccountData.email,
                        userAccountData.providerType,
                        nicknameAsString,
                        memberData.authorityTier,
                        userAccountData.lastLoginAt,
                        userAccountData.createdAt,
                        userAccountData.withdrawnAt))
                .from(memberData)
                .leftJoin(userAccountData).on(userAccountData.userId.uid.eq(memberData.userAccountId))
                .where(where);

        if (AdminMemberListQuery.SORT_LAST_ACTIVITY_DESC.equals(query.sort())) {
            // 활동 0건 member도 결과에 포함되도록 LEFT JOIN + COALESCE fallback. spec §6 SQL.
            baseQuery
                    .leftJoin(userActivityLogData)
                    .on(userActivityLogData.userAccountId.eq(memberData.userAccountId))
                    .groupBy(memberData.memberId,
                            memberData.userAccountId,
                            userAccountData.email,
                            userAccountData.providerType,
                            memberData.profileData.bio.nickname,
                            memberData.authorityTier,
                            userAccountData.lastLoginAt,
                            userAccountData.createdAt,
                            userAccountData.withdrawnAt)
                    .orderBy(
                            userActivityLogData.occurredAt.max()
                                    .coalesce(userAccountData.createdAt).desc(),
                            memberData.memberId.desc());
        } else if (AdminMemberListQuery.SORT_CREATED_AT_ASC.equals(query.sort())) {
            baseQuery.orderBy(userAccountData.createdAt.asc(), memberData.memberId.asc());
        } else {
            // default: created_at_desc (null sort 포함).
            baseQuery.orderBy(userAccountData.createdAt.desc(), memberData.memberId.desc());
        }

        List<AdminMemberSummaryRow> rows = baseQuery
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(memberData.count())
                .from(memberData)
                .leftJoin(userAccountData).on(userAccountData.userId.uid.eq(memberData.userAccountId))
                .where(where)
                .fetchOne();

        return new PageImpl<>(rows, pageable, total != null ? total : 0L);
    }
}
