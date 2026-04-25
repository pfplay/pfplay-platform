package com.pfplaybackend.api.user.adapter.out.persistence.impl;

import com.pfplaybackend.api.user.adapter.out.persistence.custom.GuestRepositoryCustom;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import com.pfplaybackend.api.user.domain.entity.data.QGuestData;
import com.pfplaybackend.api.user.domain.entity.data.QProfileData;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RequiredArgsConstructor
public class GuestRepositoryImpl implements GuestRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<GuestData> findByUserAccountId(Long userAccountId) {
        QGuestData qGuestData = QGuestData.guestData;
        QProfileData qProfileData = QProfileData.profileData;

        GuestData guestData = queryFactory
                .select(qGuestData)
                .from(qGuestData)
                .leftJoin(qGuestData.profileData, qProfileData).fetchJoin()
                .where(qGuestData.userAccountId.eq(userAccountId))
                .fetchOne();

        return Optional.ofNullable(guestData);
    }
}
