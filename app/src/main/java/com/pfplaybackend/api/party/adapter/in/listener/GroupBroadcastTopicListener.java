package com.pfplaybackend.api.party.adapter.in.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pfplaybackend.api.party.adapter.in.listener.message.GroupBroadcastMessage;
import com.pfplaybackend.realtime.sender.SimpMessageSender;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

@Slf4j
@AllArgsConstructor
public class GroupBroadcastTopicListener<T extends GroupBroadcastMessage> implements MessageListener {

    private final SimpMessageSender messageSender;
    private final ObjectMapper objectMapper;
    private final Class<T> messageType;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            T msg = objectMapper.readValue(new String(message.getBody()), messageType);
            log.info("[listener.onMessage] topic={}, type={}, partyroomId={}, messageId={}",
                    new String(pattern), messageType.getSimpleName(), msg.partyroomId().getId(), msg.id());
            messageSender.sendToGroup(msg.partyroomId().getId(), msg);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize {} message: {}", messageType.getSimpleName(), new String(message.getBody()), e);
        }
    }
}
