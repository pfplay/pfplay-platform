package com.pfplaybackend.realtime.event;

import com.pfplaybackend.realtime.port.PresencePort;
import com.pfplaybackend.realtime.port.SessionCachePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("DisconnectionEventListener 단위 테스트")
class DisconnectionEventListenerTest {

    @Mock
    private SessionCachePort sessionCachePort;

    @Mock
    private PresencePort presencePort;

    @InjectMocks
    private DisconnectionEventListener listener;

    @Test
    @DisplayName("정상 종료(1000) 시 presence를 PENDING으로 표시하고 세션 캐시를 삭제한다")
    void onApplicationEventNormalCloseDeletesSessionCache() {
        // given
        String sessionId = "session-normal";
        SessionDisconnectEvent event = createDisconnectEvent(sessionId, CloseStatus.NORMAL);

        // when
        listener.onApplicationEvent(event);

        // then
        verify(presencePort).onSessionDisconnected(sessionId);
        verify(sessionCachePort).deleteSessionCache(sessionId);
    }

    @Test
    @DisplayName("비정상 종료(1006) 시에도 presence와 세션 캐시 정리가 동일하게 일어난다")
    void onApplicationEventAbnormalCloseDeletesSessionCache() {
        // given
        String sessionId = "session-abnormal";
        CloseStatus abnormalClose = new CloseStatus(1006, "Abnormal closure");
        SessionDisconnectEvent event = createDisconnectEvent(sessionId, abnormalClose);

        // when
        listener.onApplicationEvent(event);

        // then
        verify(presencePort).onSessionDisconnected(sessionId);
        verify(sessionCachePort).deleteSessionCache(sessionId);
    }

    // --- helper ---

    private SessionDisconnectEvent createDisconnectEvent(String sessionId, CloseStatus closeStatus) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);

        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(this, message, sessionId, closeStatus);
    }
}
