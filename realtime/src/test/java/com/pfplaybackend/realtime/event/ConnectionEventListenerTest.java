package com.pfplaybackend.realtime.event;

import com.pfplaybackend.realtime.port.PresencePort;
import com.pfplaybackend.realtime.port.SessionRegistryPort;
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
import org.springframework.web.socket.messaging.SessionConnectEvent;

import java.security.Principal;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import org.mockito.InOrder;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectionEventListener 단위 테스트")
class ConnectionEventListenerTest {

    @Mock
    private SessionRegistryPort sessionRegistryPort;

    @Mock
    private PresencePort presencePort;

    @InjectMocks
    private ConnectionEventListener listener;

    @Test
    @DisplayName("Principal 이 있으면 세션을 먼저 등록한 뒤 presence 연결을 트리거한다")
    void registersBeforeResolvingPresence() {
        // given
        String sessionId = "session-connect";
        String uid = "12345";
        SessionConnectEvent event = createConnectEvent(sessionId, () -> uid);

        // when
        listener.onApplicationEvent(event);

        // then
        InOrder order = inOrder(sessionRegistryPort, presencePort);
        order.verify(sessionRegistryPort).register(sessionId, uid);
        order.verify(presencePort).onSessionConnected(sessionId);
    }

    @Test
    @DisplayName("Principal 이 없으면 등록도 presence 트리거도 하지 않으며 예외도 던지지 않는다")
    void noPrincipalIsSilentNoOp() {
        // given
        String sessionId = "session-anon";
        SessionConnectEvent event = createConnectEvent(sessionId, null);

        // when
        listener.onApplicationEvent(event);

        // then
        verifyNoInteractions(sessionRegistryPort, presencePort);
    }

    // --- helper ---

    private SessionConnectEvent createConnectEvent(String sessionId, Principal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        if (principal != null) {
            accessor.setUser(principal);
        }

        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionConnectEvent(this, message, principal);
    }
}
