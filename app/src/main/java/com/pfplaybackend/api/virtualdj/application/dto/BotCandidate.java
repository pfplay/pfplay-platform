package com.pfplaybackend.api.virtualdj.application.dto;

/**
 * 채팅 트리거 게이트가 응답 후보로 고를 수 있는, 방 안의 페르소나 봇 1명.
 *
 * <p>{@code is_dummy} 봇이면서 (1) 해당 파티룸의 활성 crew 이고 (2) {@code bot_persona_assignment}
 * 매핑이 존재하는 봇만 후보가 된다. {@code personaId} 는 INNER JOIN 으로 항상 non-null 이지만,
 * 호출부의 명시성을 위해 박싱 타입으로 둔다.
 */
public record BotCandidate(long botUserId, long crewId, Long personaId) {}
