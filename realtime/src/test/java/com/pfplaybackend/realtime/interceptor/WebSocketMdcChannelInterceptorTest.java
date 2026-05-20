package com.pfplaybackend.realtime.interceptor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketMdcChannelInterceptorTest {

    private final WebSocketMdcChannelInterceptor interceptor = new WebSocketMdcChannelInterceptor();

    @AfterEach
    void cleanupMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("preSend: sessionId + userId MDC 설정")
    void preSend_sets_sessionId_and_userId() {
        Message<?> message = buildMessage("ws-session-001", () -> "user-42");

        interceptor.preSend(message, null);

        assertThat(MDC.get("sessionId")).isEqualTo("ws-session-001");
        assertThat(MDC.get("userId")).isEqualTo("user-42");
    }

    @Test
    @DisplayName("afterSendCompletion: sessionId + userId MDC 제거")
    void afterSendCompletion_removes_mdc() {
        Message<?> message = buildMessage("ws-session-002", () -> "user-7");
        interceptor.preSend(message, null);

        interceptor.afterSendCompletion(message, null, true, null);

        assertThat(MDC.get("sessionId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("preSend: principal null safe")
    void preSend_null_principal_safe() {
        Message<?> message = buildMessage("ws-session-003", null);

        interceptor.preSend(message, null);

        assertThat(MDC.get("sessionId")).isEqualTo("ws-session-003");
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("preSend: sessionId null safe")
    void preSend_null_sessionId_safe() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setUser(() -> "u-x");
        Message<?> message = MessageBuilder.createMessage("payload", accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        assertThat(MDC.get("sessionId")).isNull();
        assertThat(MDC.get("userId")).isEqualTo("u-x");
    }

    private Message<?> buildMessage(String sessionId, Principal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(sessionId);
        if (principal != null) accessor.setUser(principal);
        return MessageBuilder.createMessage("payload", accessor.getMessageHeaders());
    }
}
