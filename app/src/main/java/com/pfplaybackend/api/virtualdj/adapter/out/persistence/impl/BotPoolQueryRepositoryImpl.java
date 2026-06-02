package com.pfplaybackend.api.virtualdj.adapter.out.persistence.impl;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.user.domain.value.Nickname;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.application.dto.BotRosterRow;
import com.pfplaybackend.api.virtualdj.application.dto.PoolPlacementRow;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

import static com.pfplaybackend.api.party.domain.entity.data.QCrewData.crewData;
import static com.pfplaybackend.api.party.domain.entity.data.QPartyroomData.partyroomData;
import static com.pfplaybackend.api.user.domain.entity.data.QProfileData.profileData;
import static com.pfplaybackend.api.user.domain.entity.data.QUserAccountData.userAccountData;
import static com.pfplaybackend.api.virtualdj.domain.entity.data.QBotPersonaAssignmentData.botPersonaAssignmentData;
import static com.pfplaybackend.api.virtualdj.domain.entity.data.QVirtualPersonaData.virtualPersonaData;

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

    @Override
    public List<Long> filterBotUserIds(List<Long> candidateUserIds) {
        if (candidateUserIds == null || candidateUserIds.isEmpty()) {
            return Collections.emptyList();
        }
        return queryFactory
                .select(userAccountData.userId.uid)
                .from(userAccountData)
                .where(
                        userAccountData.userId.uid.in(candidateUserIds),
                        userAccountData.isDummy.isTrue(),
                        userAccountData.withdrawnAt.isNull())
                .fetch();
    }

    @Override
    public long countBots() {
        Long count = queryFactory
                .select(userAccountData.count())
                .from(userAccountData)
                .where(
                        userAccountData.isDummy.isTrue(),
                        userAccountData.withdrawnAt.isNull())
                .fetchOne();
        return count == null ? 0L : count;
    }

    @Override
    public long countIdleBots() {
        Long count = queryFactory
                .select(userAccountData.count())
                .from(userAccountData)
                .where(
                        userAccountData.isDummy.isTrue(),
                        userAccountData.withdrawnAt.isNull(),
                        JPAExpressions.selectOne()
                                .from(crewData)
                                .where(crewData.userId.uid.eq(userAccountData.userId.uid)
                                        .and(crewData.isActive.isTrue()))
                                .notExists())
                .fetchOne();
        return count == null ? 0L : count;
    }

    @Override
    public List<PoolPlacementRow> findPlacements() {
        List<Tuple> tuples = queryFactory
                .select(
                        partyroomData.id,
                        partyroomData.title,
                        crewData.count())
                .from(crewData)
                .join(userAccountData).on(userAccountData.userId.uid.eq(crewData.userId.uid))
                .join(partyroomData).on(partyroomData.id.eq(crewData.partyroomId.id))
                .where(
                        crewData.isActive.isTrue(),
                        userAccountData.isDummy.isTrue(),
                        userAccountData.withdrawnAt.isNull(),
                        partyroomData.status.eq(PartyroomStatus.ACTIVE))
                .groupBy(partyroomData.id, partyroomData.title)
                .orderBy(crewData.count().desc())
                .fetch();

        return tuples.stream()
                .map(t -> new PoolPlacementRow(
                        t.get(partyroomData.id),
                        t.get(partyroomData.title),
                        t.get(crewData.count()) == null ? 0L : t.get(crewData.count())))
                .toList();
    }

    @Override
    public List<BotRosterRow> findRoster() {
        List<Tuple> tuples = queryFactory
                .select(
                        userAccountData.userId.uid,
                        profileData.bio.nickname,   // @Convert(Nickname) → tuple.get 시 Nickname 객체 반환
                        profileData.avatarSetting.avatarBodyUri.value,
                        profileData.avatarSetting.avatarIconUri.value,
                        partyroomData.id,
                        partyroomData.title,
                        virtualPersonaData.id,
                        virtualPersonaData.name)
                .from(userAccountData)
                .join(profileData).on(profileData.userId.uid.eq(userAccountData.userId.uid))
                .leftJoin(crewData).on(crewData.userId.uid.eq(userAccountData.userId.uid)
                        .and(crewData.isActive.isTrue()))
                .leftJoin(partyroomData).on(partyroomData.id.eq(crewData.partyroomId.id)
                        .and(partyroomData.status.eq(PartyroomStatus.ACTIVE)))
                // 봇↔페르소나 매핑(없으면 null) — assignment 미존재 봇도 로스터에 남도록 LEFT JOIN.
                .leftJoin(botPersonaAssignmentData)
                        .on(botPersonaAssignmentData.botUserId.eq(userAccountData.userId.uid))
                .leftJoin(virtualPersonaData)
                        .on(virtualPersonaData.id.eq(botPersonaAssignmentData.personaId))
                .where(
                        userAccountData.isDummy.isTrue(),
                        userAccountData.withdrawnAt.isNull())
                .orderBy(userAccountData.userId.uid.asc())
                .fetch();

        return tuples.stream()
                .map(t -> {
                    Nickname nick = t.get(profileData.bio.nickname);
                    return new BotRosterRow(
                            t.get(userAccountData.userId.uid),
                            nick == null ? null : nick.value(),
                            t.get(profileData.avatarSetting.avatarBodyUri.value),
                            t.get(profileData.avatarSetting.avatarIconUri.value),
                            t.get(partyroomData.id),
                            t.get(partyroomData.title),
                            t.get(virtualPersonaData.id),
                            t.get(virtualPersonaData.name));
                })
                .toList();
    }
}
