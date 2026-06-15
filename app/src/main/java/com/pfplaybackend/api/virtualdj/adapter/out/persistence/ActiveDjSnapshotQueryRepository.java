package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;

import java.util.List;

/**
 * 룸의 커밋된 활성 DJ 집합을 사람/봇으로 나눠 조회한다 (Chunk 4 reconcile 읽기 경로).
 *
 * <p>{@code DJ} → {@code CREW} → {@code user_account.is_dummy} 를 가로지르는 조회라
 * app 모듈 adapter 에 둔다(기존 {@link BotPoolQueryRepository} 와 동일한 cross-BC 패턴).
 * 오케스트레이터는 이 repo 를 직접 주입하지 않고 query 서비스를 거친다.
 */
public interface ActiveDjSnapshotQueryRepository {

    /**
     * 룸의 활성 DJ(= 활성 crew 를 가진 DJ) 중 봇(is_dummy=true)의 {@link UserId} 를
     * 가장 최근에 등록된 순서(= {@code dj.order_number} 내림차순)로 반환한다.
     * 제거는 가장 최근 합류 봇부터 수행되므로 이 정렬이 결정성을 보장한다.
     */
    List<UserId> findActiveBotDjUserIdsByJoinedDesc(PartyroomId partyroomId);

    /**
     * 룸의 활성 DJ 중 사람(is_dummy=false)의 수.
     */
    long countActiveHumanDjs(PartyroomId partyroomId);
}
