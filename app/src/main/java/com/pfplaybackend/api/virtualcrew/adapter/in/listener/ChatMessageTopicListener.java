package com.pfplaybackend.api.virtualcrew.adapter.in.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pfplaybackend.api.party.application.dto.chat.ChatMessageDto;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

/**
 * {@code chat_message_sent} 토픽의 두 번째 구독자 — 가상 DJ 채팅 관찰 트리거 진입점 (Chunk 4).
 *
 * <p>같은 토픽의 WS 브로드캐스트 리스너({@code GroupBroadcastTopicListener})와 독립적으로 동작한다
 * (한 토픽에 리스너 둘, 각자 모든 메시지를 받는다). 역직렬화 실패는 best-effort 로 로깅만 하고
 * 삼킨다 — 채팅 관찰은 부가 기능이라 메시지 1건 파싱 실패가 파이프라인을 멈추면 안 된다.
 */
@Slf4j
@AllArgsConstructor
public class ChatMessageTopicListener implements MessageListener {

    private final ObjectMapper objectMapper;
    private final BotChatTrigger botChatTrigger;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            ChatMessageDto dto = objectMapper.readValue(message.getBody(), ChatMessageDto.class);
            botChatTrigger.onChatMessage(dto);
        } catch (Exception e) {
            log.warn("[vcrew.chat] ChatMessageDto 역직렬화/트리거 실패: {}",
                    new String(message.getBody()), e);
        }
    }
}
