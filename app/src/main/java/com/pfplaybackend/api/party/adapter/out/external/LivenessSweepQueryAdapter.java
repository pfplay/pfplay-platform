package com.pfplaybackend.api.party.adapter.out.external;

import com.pfplaybackend.api.party.application.port.out.LivenessSweepQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.QCrewData;
import com.pfplaybackend.api.user.domain.entity.data.QUserAccountData;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * #356 liveness 스윕 후보 조회 — crew × user_account(is_dummy) cross-BC read-only 조인.
 *
 * <p>{@code adapter.out.external} 은 party 모듈의 합법적 cross-BC 통합 경계
 * (CrossContextDependencyTest 예외 패키지). 조인 선례: virtualcrew
 * {@code ActiveDjSnapshotQueryRepositoryImpl} 의 crew × user_account.
 */
@Component
@RequiredArgsConstructor
public class LivenessSweepQueryAdapter implements LivenessSweepQueryPort {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<CrewData> findLivenessSweepCandidates(LocalDateTime enteredBefore) {
        QCrewData qCrewData = QCrewData.crewData;
        QUserAccountData qUserAccount = QUserAccountData.userAccountData;

        return queryFactory
                .selectFrom(qCrewData)
                .join(qUserAccount).on(qUserAccount.userId.uid.eq(qCrewData.userId.uid))
                .where(qCrewData.isActive.isTrue()
                        .and(qCrewData.pendingExitAt.isNull())
                        .and(qUserAccount.isDummy.isFalse())
                        .and(qCrewData.enteredAt.lt(enteredBefore)))
                .fetch();
    }
}
