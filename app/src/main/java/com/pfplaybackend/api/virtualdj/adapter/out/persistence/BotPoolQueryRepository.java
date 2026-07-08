package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.application.dto.BotCandidate;
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

    /**
     * 후보 userId 중 실제 봇(is_dummy=true, withdrawn_at IS NULL)인 부분집합만 반환한다.
     *
     * <p>일괄 배분(distribute)에서 비-봇/미존재 id 를 apply 예외로 잡아 격리하면 공유 트랜잭션이
     * rollback-only 로 마킹돼 성공분까지 롤백되는 문제를 피하기 위해, 사전 필터로 유효 봇만 추린다.
     * 입력이 null/빈 리스트면 빈 리스트를 반환한다.
     */
    List<Long> filterBotUserIds(List<Long> candidateUserIds);

    /**
     * 주어진 파티룸 안에서 채팅 응답 후보가 될 수 있는 페르소나 봇 목록을 반환한다.
     *
     * <p>조건: (1) 봇 계정(is_dummy=true, withdrawn_at IS NULL), (2) 해당 파티룸의 활성 crew,
     * (3) {@code bot_persona_assignment} 매핑 존재(페르소나 INNER JOIN). 매핑 없는 봇·비활성 crew·
     * 비봇은 제외된다. 결과는 crewId 오름차순(deterministic).
     */
    List<BotCandidate> findActivePersonaBotsInRoom(PartyroomId partyroomId);

    /**
     * 주어진 파티룸 안의 활성 봇 crew 수를 반환한다(DJ/리스너 등 역할 무관, 페르소나 비게이트).
     *
     * <p>조건: (1) 해당 파티룸의 활성 crew(is_active=true), (2) 봇 계정(is_dummy=true,
     * withdrawn_at IS NULL). {@link #findActivePersonaBotsInRoom} 와 달리 페르소나 매핑 유무는
     * 게이트하지 않는다 — 페르소나 미배정 봇도 카운트에 포함된다.
     */
    long countActiveBotCrewsInRoom(PartyroomId partyroomId);

    /**
     * 주어진 파티룸 안의 활성 봇 crew userId 목록을 입장 시각(enteredAt) 내림차순(가장 최근 입장 우선)으로
     * 반환한다(역할 무관, 페르소나 비게이트). 조건은 {@link #countActiveBotCrewsInRoom} 와 동일하다.
     */
    List<UserId> findActiveBotCrewUserIdsByJoinedDesc(PartyroomId partyroomId);
}
