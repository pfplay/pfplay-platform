package com.pfplaybackend.realtime.event;

import com.pfplaybackend.realtime.port.SessionCachePort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.security.Principal;

@Component
@RequiredArgsConstructor
public class UnsubscriptionEventListener implements ApplicationListener<SessionUnsubscribeEvent> {
    private static final Logger logger = LoggerFactory.getLogger(UnsubscriptionEventListener.class);
    private final SessionCachePort sessionCachePort;

    @Override
    public void onApplicationEvent(SessionUnsubscribeEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        Principal principal = headerAccessor.getUser();
        if (principal == null) {
            logger.warn("Unauthorized session requested, UserId is null, Session ID: {}", sessionId);
            throw new AuthenticationServiceException("Unauthorized Session Requested");
        }
        // 세션캐시 delete는 채팅 세션 캐시의 clean-path 정리다. 비정상 종료(1006,
        // UNSUBSCRIBE 없음) orphan은 RedisSessionCacheAdapter의 24h TTL 백스톱으로
        // self-heal되며 DisconnectionEventListener는 재추가하지 않는다 (#209 #31).
        sessionCachePort.deleteSessionCache(sessionId);

        logger.info("Session has unsubscribed, sessionId : {}", sessionId);
    }
}
