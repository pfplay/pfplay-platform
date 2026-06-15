package com.pfplaybackend.api.party.application.dto.chat;

import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.party.application.dto.partyroom.PartyroomSessionDto;
import com.pfplaybackend.api.party.domain.value.PartyroomId;

import java.io.Serializable;
import java.util.UUID;

public record ChatMessageDto(
        PartyroomId partyroomId,
        MessageTopic eventType,
        String id,
        long timestamp,
        CrewInfo crew,
        ChatContent message
) implements Serializable {

    public record CrewInfo(long crewId) {}
    public record ChatContent(String messageId, String content) {}

    public static ChatMessageDto from(PartyroomSessionDto sessionDto, String content, long timestamp) {
        return new ChatMessageDto(
                sessionDto.partyroomId(),
                MessageTopic.CHAT_MESSAGE_SENT,
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                new CrewInfo(sessionDto.crewId()),
                new ChatContent(timestamp + ":" + sessionDto.crewId(), content)
        );
    }

    public static ChatMessageDto from(PartyroomSessionDto sessionDto, String content) {
        return from(sessionDto, content, System.currentTimeMillis());
    }

    /**
     * 봇(가상 사용자) 채팅 송신용 팩토리 — STOMP 세션 없이 crewId 만으로 페이로드를 구성한다 (path A).
     * {@link #from} 과 동일한 record 형태를 유지하되, 세션 DTO 대신 partyroomId/crewId 를 직접 받는다.
     */
    public static ChatMessageDto ofCrew(PartyroomId partyroomId, long crewId, String content, long timestamp) {
        return new ChatMessageDto(
                partyroomId,
                MessageTopic.CHAT_MESSAGE_SENT,
                UUID.randomUUID().toString(),
                timestamp,
                new CrewInfo(crewId),
                new ChatContent(timestamp + ":" + crewId, content)
        );
    }
}
