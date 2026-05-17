package com.pfplaybackend.realtime.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;

@Component
public class SubscriptionEventListener implements ApplicationListener<SessionSubscribeEvent> {
    private static final Logger logger = LoggerFactory.getLogger(SubscriptionEventListener.class);

    @Override
    public void onApplicationEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        String destination = headerAccessor.getDestination();
        Principal principal = headerAccessor.getUser();
        if (principal == null) {
            logger.warn("Unauthorized session requested, UserId is null, Session ID: {}", sessionId);
            throw new AuthenticationServiceException("Unauthorized Session Requested");
        }
        String userId = principal.getName();
        // PRESENCE/세션캐시 디커미션 (#209 #31): subscribe-타이밍 의존을 종결한다.
        // presence는 WS CONNECT/DISCONNECT가 권위(Task 1.4/1.5), 세션캐시 write도 더 이상
        // SUBSCRIBE에서 수행하지 않는다. 여기서는 인증 가드와 로깅만 남긴다.
        logger.info("Session has subscribed, sessionId : {}, userId : {}, destination : {}", sessionId, userId, destination);
    }
}
