package com.pfplaybackend.realtime.interceptor;

import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * STOMP inbound channel 의 sessionId / userId 를 MDC 에 푸시.
 *
 * <p>Spring 의 default {@code ExecutorSubscribableChannel} single-thread dispatch 가
 * preSend → @MessageMapping handler → afterSendCompletion 을 같은 thread 에서 실행 —
 * MDC 가 handler 내부 log 에 자동 가시. {@code registration.taskExecutor(...)} 가
 * 도입되면 TaskDecorator wiring 필요 (spec §7.3 참조).
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §7.3.
 */
@Component
public class WebSocketMdcChannelInterceptor implements ChannelInterceptor {

    private static final String MDC_SESSION_ID = "sessionId";
    private static final String MDC_USER_ID = "userId";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null) {
            String sessionId = accessor.getSessionId();
            if (sessionId != null) MDC.put(MDC_SESSION_ID, sessionId);

            Principal user = accessor.getUser();
            if (user != null) MDC.put(MDC_USER_ID, user.getName());
        }
        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        MDC.remove(MDC_SESSION_ID);
        MDC.remove(MDC_USER_ID);
    }
}
