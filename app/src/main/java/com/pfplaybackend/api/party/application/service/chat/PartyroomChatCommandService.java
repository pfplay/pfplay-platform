package com.pfplaybackend.api.party.application.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pfplaybackend.api.common.config.redis.RedisMessagePublisher;
import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.application.dto.chat.ChatMessageDto;
import com.pfplaybackend.api.party.application.dto.partyroom.PartyroomSessionDto;
import com.pfplaybackend.api.party.application.port.out.ChatPenaltyCachePort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.realtime.port.SessionCachePort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PartyroomChatCommandService {

    private static final Logger logger = LoggerFactory.getLogger(PartyroomChatCommandService.class);

    private final ChatPenaltyCachePort chatPenaltyCachePort;
    private final RedisMessagePublisher messagePublisher;
    private final SessionCachePort sessionCachePort;
    private final Clock clock;

    private final ObjectMapper objectMapper;

    public void sendMessage(String sessionId, String content) {
        // 세션캐시는 채팅 전용 source다: SUBSCRIBE에서 sessionId→(partyroom,user,crew)를
        // write(24h TTL 백스톱 포함)하고 UNSUBSCRIBE에서 delete한다. 비정상 종료 orphan은
        // 그 TTL로 self-heal된다. presence는 WS CONNECT/DISCONNECT가 권위로 분리됨 (#209 #31).
        Optional<Object> optional = sessionCachePort.getSessionCache(sessionId);

        if (optional.isEmpty()) {
            throw new NotFoundException("SESSION", "세션 캐시 데이터를 찾을 수 없습니다");
        }

        final Object object = optional.get();
        if (object instanceof Map) {
            try {
                PartyroomSessionDto sessionDto = objectMapper.convertValue(object, PartyroomSessionDto.class);
                if(isPossibleChat(sessionDto.crewId())) {
                    ChatMessageDto chatPayload = ChatMessageDto.from(sessionDto, content, clock.millis());
                    messagePublisher.publish(MessageTopic.CHAT_MESSAGE_SENT.topic(), chatPayload);
                }
            } catch (IllegalArgumentException e) {
                logger.error(
                        "Cannot send message, SessionId: " + sessionId
                                + ", message: " + content
                                + ", ex: " + e.getMessage()
                );
            }
        }
    }

    /**
     * 봇(가상 사용자) 채팅 송신 — STOMP 세션이 없는 실계정 봇이 crewId 만으로 채팅을 발행하는 path A 시임.
     * 세션 캐시를 우회하지만 {@link #isPossibleChat} 채팅밴 가드는 동일하게 적용된다.
     */
    public void sendMessageAsCrew(PartyroomId partyroomId, long crewId, String content) {
        if (isPossibleChat(crewId)) {
            ChatMessageDto chatPayload = ChatMessageDto.ofCrew(partyroomId, crewId, content, clock.millis());
            messagePublisher.publish(MessageTopic.CHAT_MESSAGE_SENT.topic(), chatPayload);
        }
    }

    public boolean isPossibleChat(Long crewIdValue) {
        return !chatPenaltyCachePort.isChatBanned(crewIdValue);
    }
}
