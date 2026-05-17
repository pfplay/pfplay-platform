package com.pfplaybackend.realtime.event;

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
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("UnsubscriptionEventListener 단위 테스트")
class UnsubscriptionEventListenerTest {

    @Mock
    private SessionCachePort sessionCachePort;

    @InjectMocks
    private UnsubscriptionEventListener listener;

    @Test
    @DisplayName("유효한 Principal이면 채팅용 세션 캐시를 삭제한다 (clean 경로 정리)")
    void onApplicationEventValidPrincipalDeletesSessionCache() {
        // given
        String sessionId = "session-abc";

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        accessor.setSessionId(sessionId);
        Principal principal = mock(Principal.class);
        accessor.setUser(principal);

        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        SessionUnsubscribeEvent event = new SessionUnsubscribeEvent(this, message);

        // when
        listener.onApplicationEvent(event);

        // then — UNSUBSCRIBE는 채팅 세션 캐시의 clean-path 정리를 담당한다 (#209 #31).
        verify(sessionCachePort).deleteSessionCache(sessionId);
    }

    @Test
    @DisplayName("Principal이 null이면 AuthenticationServiceException을 던진다")
    void onApplicationEventNullPrincipalThrowsException() {
        // given
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        accessor.setSessionId("session-xyz");
        // principal은 설정하지 않음 (null)

        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        SessionUnsubscribeEvent event = new SessionUnsubscribeEvent(this, message);

        // when & then
        assertThatThrownBy(() -> listener.onApplicationEvent(event))
                .isInstanceOf(AuthenticationServiceException.class)
                .hasMessage("Unauthorized Session Requested");
    }
}
