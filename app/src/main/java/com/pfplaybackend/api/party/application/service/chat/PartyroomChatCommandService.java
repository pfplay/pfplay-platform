package com.pfplaybackend.api.party.application.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pfplaybackend.api.common.config.redis.RedisMessagePublisher;
import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.application.dto.chat.ChatMessageDto;
import com.pfplaybackend.api.party.application.dto.partyroom.PartyroomSessionDto;
import com.pfplaybackend.api.party.application.port.out.ChatPenaltyCachePort;
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
        // write하고 UNSUBSCRIBE에서 delete한다. presence는 WS CONNECT/DISCONNECT가 권위로
        // 세션캐시와 분리됨 (#209 #31).
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

    public boolean isPossibleChat(Long crewIdValue) {
        return !chatPenaltyCachePort.isChatBanned(crewIdValue);
    }
}
