package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdminMemberQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberDetailRow;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

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
}
