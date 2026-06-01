package com.pfplaybackend.api.virtualdj.adapter.out.persistence.impl;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

import static com.pfplaybackend.api.party.domain.entity.data.QCrewData.crewData;
import static com.pfplaybackend.api.user.domain.entity.data.QUserAccountData.userAccountData;

/**
 * QueryDSL impl — is_dummy 계정 중 활성 crew 가 없는 봇을 조회한다.
 *
 * <p>"활성 crew 없음" 은 NOT EXISTS 서브쿼리로 표현한다(deactivate 된 과거 crew row 가 남아도
 * idle 로 본다). cross-BC(User↔Party) 엔티티 참조는 이 adapter 안에서만 발생한다.
 */
@Repository
@RequiredArgsConstructor
public class BotPoolQueryRepositoryImpl implements BotPoolQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<UserId> findIdleBotUserIds(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        List<Long> uids = queryFactory
                .select(userAccountData.userId.uid)
                .from(userAccountData)
                .where(
                        userAccountData.isDummy.isTrue(),
                        userAccountData.withdrawnAt.isNull(),
                        JPAExpressions.selectOne()
                                .from(crewData)
                                .where(crewData.userId.uid.eq(userAccountData.userId.uid)
                                        .and(crewData.isActive.isTrue()))
                                .notExists())
                // uid 오름차순 = 가장 먼저 프로비저닝된 봇부터 (deterministic oldest-first FIFO).
                // 동일 입력에 동일 봇을 고르게 해 reconcile 의 멱등성·재현성을 보장한다.
                .orderBy(userAccountData.userId.uid.asc())
                .limit(limit)
                .fetch();

        return uids.stream().map(UserId::new).toList();
    }
}
