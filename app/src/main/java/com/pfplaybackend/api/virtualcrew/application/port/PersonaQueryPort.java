package com.pfplaybackend.api.virtualcrew.application.port;

/**
 * 봇 user_id 로 매핑된 페르소나 지시문을 읽는 포트.
 *
 * <p>채팅 워커({@code LlmChatTaskRunner})가 LLM system 프롬프트 조립 직전에 호출한다.
 * 매핑이 없으면 {@code null} 을 반환하고, 워커는 그 회차를 드롭한다.
 *
 * <p><b>is_active 무관:</b> 페르소나가 비활성({@code is_active=false})이어도 <b>기존 매핑은
 * 보존</b>되므로 지시문을 그대로 반환한다 — {@code is_active} 는 신규 매핑만 차단한다.
 */
public interface PersonaQueryPort {

    /**
     * @param botUserId 봇 user_account id
     * @return 매핑된 페르소나의 지시문, 매핑 row 가 없으면 {@code null}
     */
    String instructionOf(long botUserId);
}
