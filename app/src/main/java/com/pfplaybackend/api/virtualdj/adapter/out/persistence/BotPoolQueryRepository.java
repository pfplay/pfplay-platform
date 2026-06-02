package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.virtualdj.application.dto.BotRosterRow;
import com.pfplaybackend.api.virtualdj.application.dto.PoolPlacementRow;

import java.util.List;

/**
 * 봇 풀 조회 — is_dummy 계정 중 현재 어느 방에도 활성 crew 가 없는(=idle) 봇을 찾는다.
 *
 * <p>{@code user_account}(user 모듈)와 {@code CREW}(party 도메인, app 모듈)를 가로지르는 조회라
 * app 모듈에 둔다. cross-BC 엔티티 참조는 이 adapter 안에서만 일어난다(기존 admin 조회 패턴과 동일).
 */
public interface BotPoolQueryRepository {

    /**
     * 활성 crew 가 없는 봇 최대 {@code limit} 명의 {@link UserId} 를 반환한다.
     * 탈퇴(withdrawn) 계정은 제외한다. {@code limit <= 0} 이면 빈 리스트.
     */
    List<UserId> findIdleBotUserIds(int limit);

    /**
     * 탈퇴하지 않은 봇 계정(is_dummy=true, withdrawn_at IS NULL) 전체 수를 반환한다.
     */
    long countBots();

    /**
     * 활성 crew 가 없는(=어느 방에도 배치되지 않은) idle 봇 수를 반환한다.
     * 탈퇴 계정은 제외한다.
     */
    long countIdleBots();

    /**
     * 현재 활성 crew 로 배치된 봇을 파티룸 별로 집계해 반환한다.
     * 봇이 배치된 파티룸만 포함된다(botCount > 0 보장).
     */
    List<PoolPlacementRow> findPlacements();

    /**
     * 봇 전체 로스터(is_dummy=true, withdrawn_at IS NULL) — 닉네임/현재 아바타 + 현재 배치된
     * ACTIVE 파티룸(활성 crew 기준, 없으면 null). uid 오름차순(oldest-first).
     */
    List<BotRosterRow> findRoster();
}
