package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestListQuery;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminGuestQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestDetailRow;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestSummaryRow;
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
import static com.pfplaybackend.api.user.domain.entity.data.QGuestData.guestData;
import static com.pfplaybackend.api.user.domain.entity.data.QUserAccountData.userAccountData;

/**
 * QueryDSL impl of {@link AdminGuestQueryRepository}.
 *
 * <p>AdminMemberQueryRepositoryImpl 패턴 동형 (member→guest 치환):
 *  - from(guestData) + leftJoin(userAccountData) on user_account_id = uid
 *  - nickname cast(... as string) — NicknameConverter 우회 (Member 와 동일 trick)
 *  - last_activity_desc: userActivityLogData leftJoin + GROUP BY + COALESCE fallback
 *
 * <p>Cross-BC entity reference (User) is allowed only inside this adapter — ArchUnit
 * 기존 룰 자동 적용 (별도 룰 추가 없음).
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-d8-admin-guest-readonly-design.md §6.
 */
@Repository
@RequiredArgsConstructor
public class AdminGuestQueryRepositoryImpl implements AdminGuestQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<AdminGuestDetailRow> findDetail(Long guestId) {
        StringExpression nicknameAsString = Expressions.stringTemplate(
                "cast({0} as string)", guestData.profileData.bio.nickname);

        AdminGuestDetailRow row = queryFactory
                .select(Projections.constructor(AdminGuestDetailRow.class,
                        guestData.guestId,
                        guestData.userAccountId,
                        userAccountData.email,
                        userAccountData.providerType,
                        userAccountData.lastLoginAt,
                        userAccountData.withdrawnAt,
                        nicknameAsString,
                        guestData.profileData.bio.introduction,
                        guestData.agent,
                        guestData.isProfileUpdated,
                        userAccountData.createdAt))
                .from(guestData)
                .leftJoin(userAccountData).on(userAccountData.userId.uid.eq(guestData.userAccountId))
                .where(guestData.guestId.eq(guestId))
                .fetchOne();
        return Optional.ofNullable(row);
    }

    @Override
    public Page<AdminGuestSummaryRow> search(AdminGuestListQuery query, Pageable pageable) {
        StringExpression nicknameAsString = Expressions.stringTemplate(
                "cast({0} as string)", guestData.profileData.bio.nickname);

        BooleanBuilder where = new BooleanBuilder();
        if (query.email() != null && !query.email().isBlank()) {
            where.and(userAccountData.email.containsIgnoreCase(query.email()));
        }
        if (query.joinedFrom() != null) {
            where.and(userAccountData.createdAt.goe(query.joinedFrom().atStartOfDay()));
        }
        if (query.joinedTo() != null) {
            where.and(userAccountData.createdAt.lt(query.joinedTo().plusDays(1).atStartOfDay()));
        }

        JPAQuery<AdminGuestSummaryRow> baseQuery = queryFactory
                .select(Projections.constructor(AdminGuestSummaryRow.class,
                        guestData.guestId,
                        guestData.userAccountId,
                        userAccountData.email,
                        userAccountData.providerType,
                        nicknameAsString,
                        guestData.agent,
                        guestData.isProfileUpdated,
                        userAccountData.lastLoginAt,
                        userAccountData.createdAt,
                        userAccountData.withdrawnAt))
                .from(guestData)
                .leftJoin(userAccountData).on(userAccountData.userId.uid.eq(guestData.userAccountId))
                .where(where);

        if (AdminGuestListQuery.SORT_LAST_ACTIVITY_DESC.equals(query.sort())) {
            baseQuery
                    .leftJoin(userActivityLogData)
                    .on(userActivityLogData.userAccountId.eq(guestData.userAccountId))
                    .groupBy(guestData.guestId,
                            guestData.userAccountId,
                            userAccountData.email,
                            userAccountData.providerType,
                            guestData.profileData.bio.nickname,
                            guestData.agent,
                            guestData.isProfileUpdated,
                            userAccountData.lastLoginAt,
                            userAccountData.createdAt,
                            userAccountData.withdrawnAt)
                    .orderBy(
                            userActivityLogData.occurredAt.max()
                                    .coalesce(userAccountData.createdAt).desc(),
                            guestData.guestId.desc());
        } else if (AdminGuestListQuery.SORT_CREATED_AT_ASC.equals(query.sort())) {
            baseQuery.orderBy(userAccountData.createdAt.asc(), guestData.guestId.asc());
        } else {
            // default: created_at_desc (null sort 포함)
            baseQuery.orderBy(userAccountData.createdAt.desc(), guestData.guestId.desc());
        }

        List<AdminGuestSummaryRow> rows = baseQuery
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(guestData.count())
                .from(guestData)
                .leftJoin(userAccountData).on(userAccountData.userId.uid.eq(guestData.userAccountId))
                .where(where)
                .fetchOne();

        return new PageImpl<>(rows, pageable, total != null ? total : 0L);
    }
}
