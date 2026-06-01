package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;

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
}
