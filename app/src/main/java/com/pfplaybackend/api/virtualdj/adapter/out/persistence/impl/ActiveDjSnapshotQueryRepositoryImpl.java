package com.pfplaybackend.api.virtualdj.adapter.out.persistence.impl;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.ActiveDjSnapshotQueryRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.pfplaybackend.api.party.domain.entity.data.QCrewData.crewData;
import static com.pfplaybackend.api.party.domain.entity.data.QDjData.djData;
import static com.pfplaybackend.api.user.domain.entity.data.QUserAccountData.userAccountData;

/**
 * QueryDSL impl — {@code DJ} ⨝ {@code CREW}(is_active) ⨝ {@code user_account}(is_dummy) 조인.
 *
 * <p>"활성 DJ" 는 DJ 행이 가리키는 crew 가 현재 활성({@code is_active=true})인 경우다.
 * exit 경로({@code removeDjFromQueue})가 DJ 행을 지우므로 일반적으로 DJ 행은 곧 활성이지만,
 * 안전하게 crew 의 is_active 까지 단언한다(stale DJ 방어). cross-BC 참조는 이 adapter 안에만 둔다.
 */
@Repository
@RequiredArgsConstructor
public class ActiveDjSnapshotQueryRepositoryImpl implements ActiveDjSnapshotQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<UserId> findActiveBotDjUserIdsByJoinedDesc(PartyroomId partyroomId) {
        List<Long> uids = queryFactory
                .select(crewData.userId.uid)
                .from(djData)
                .join(crewData).on(crewData.id.eq(djData.crewId.id))
                .join(userAccountData).on(userAccountData.userId.uid.eq(crewData.userId.uid))
                .where(
                        djData.partyroomId.id.eq(partyroomId.getId()),
                        crewData.isActive.isTrue(),
                        userAccountData.isDummy.isTrue())
                // 가장 최근 등록된 봇부터 (order_number 내림차순) — 제거 결정성 보장.
                .orderBy(djData.orderNumber.desc())
                .fetch();

        return uids.stream().map(UserId::new).toList();
    }

    @Override
    public long countActiveHumanDjs(PartyroomId partyroomId) {
        Long count = queryFactory
                .select(djData.count())
                .from(djData)
                .join(crewData).on(crewData.id.eq(djData.crewId.id))
                .join(userAccountData).on(userAccountData.userId.uid.eq(crewData.userId.uid))
                .where(
                        djData.partyroomId.id.eq(partyroomId.getId()),
                        crewData.isActive.isTrue(),
                        userAccountData.isDummy.isFalse())
                .fetchOne();
        return count == null ? 0L : count;
    }
}
