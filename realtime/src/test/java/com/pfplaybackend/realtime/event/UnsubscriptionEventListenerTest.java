package com.pfplaybackend.realtime.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("UnsubscriptionEventListener 단위 테스트")
class UnsubscriptionEventListenerTest {

    @InjectMocks
    private UnsubscriptionEventListener listener;

    @Test
    @DisplayName("유효한 Principal이면 세션캐시 삭제 없이 정상 처리된다 (세션캐시 디커미션)")
    void onApplicationEventValidPrincipalPassesWithoutSessionCacheDelete() {
        // given
        String sessionId = "session-abc";

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        accessor.setSessionId(sessionId);
        Principal principal = mock(Principal.class);
        accessor.setUser(principal);

        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        SessionUnsubscribeEvent event = new SessionUnsubscribeEvent(this, message);

        // when & then — UNSUBSCRIBE는 더 이상 deleteSessionCache를 호출하지 않는다
        assertThatCode(() -> listener.onApplicationEvent(event)).doesNotThrowAnyException();
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
