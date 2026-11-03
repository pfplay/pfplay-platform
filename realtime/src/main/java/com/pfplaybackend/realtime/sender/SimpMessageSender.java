package com.pfplaybackend.realtime.sender;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SimpMessageSender {

    private final SimpMessagingTemplate simpMessagingTemplate;

    public void sendToGroup(long groupId, Object object) {
        simpMessagingTemplate.convertAndSend("/sub/partyrooms/" + groupId, object);
    }

    public void sendToOne(String user, String message) {
        simpMessagingTemplate.convertAndSendToUser(user, "/sub/heartbeat", message);
    }

    /**
     * 특정 유저의 개인 세션 큐로 발송한다 (uid principal → /user/sub/session). #369 세션 승계 알림용.
     * 브로커가 {@code /sub} prefix 만 relay 하므로 user destination 도 {@code /sub/*} 를 사용한다
     * (heartbeat 와 동일 관례).
     */
    public void sendToOneSession(String user, Object object) {
        simpMessagingTemplate.convertAndSendToUser(user, "/sub/session", object);
    }
}
