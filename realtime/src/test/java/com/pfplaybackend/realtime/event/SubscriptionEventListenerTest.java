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
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionEventListener 단위 테스트")
class SubscriptionEventListenerTest {

    @Mock
    private SessionCachePort sessionCachePort;

    @Mock
    private PresencePort presencePort;

    @InjectMocks
    private SubscriptionEventListener listener;

    @Test
    @DisplayName("유효한 Principal이면 세션 캐시에 저장한다")
    void onApplicationEventValidPrincipalSavesSessionCache() {
        // given
        String sessionId = "session-abc";
        String userId = "user-123";
        String destination = "/topic/partyroom/1";

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        Principal principal = mock(Principal.class);
        org.mockito.Mockito.when(principal.getName()).thenReturn(userId);
        accessor.setUser(principal);

        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        SessionSubscribeEvent event = new SessionSubscribeEvent(this, message);

        // when
        listener.onApplicationEvent(event);

        // then
        verify(sessionCachePort).saveSessionCache(sessionId, userId, destination);
        verify(presencePort).onSessionConnected(sessionId);
    }

    @Test
    @DisplayName("Principal이 null이면 AuthenticationServiceException을 던진다")
    void onApplicationEventNullPrincipalThrowsException() {
        // given
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("session-xyz");
        accessor.setDestination("/topic/partyroom/1");
        // principal은 설정하지 않음 (null)

        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        SessionSubscribeEvent event = new SessionSubscribeEvent(this, message);

        // when & then
        assertThatThrownBy(() -> listener.onApplicationEvent(event))
                .isInstanceOf(AuthenticationServiceException.class)
                .hasMessage("Unauthorized Session Requested");
    }
}
