package com.pfplaybackend.api.user.adapter.out.persistence.impl;

import com.pfplaybackend.api.user.adapter.out.persistence.custom.MemberRepositoryCustom;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.QMemberData;
import com.pfplaybackend.api.user.domain.entity.data.QProfileData;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RequiredArgsConstructor
public class MemberRepositoryImpl implements MemberRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<MemberData> findByUserAccountId(Long userAccountId) {
        QMemberData qMemberData = QMemberData.memberData;
        QProfileData qProfileData = QProfileData.profileData;

        MemberData memberData = queryFactory
                .select(qMemberData)
                .from(qMemberData)
                .leftJoin(qMemberData.profileData, qProfileData).fetchJoin()
                .where(qMemberData.userAccountId.eq(userAccountId))
                .fetchOne();

        return Optional.ofNullable(memberData);
    }
}
