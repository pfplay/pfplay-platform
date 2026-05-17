package com.pfplaybackend.realtime.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.security.Principal;

@Component
public class UnsubscriptionEventListener implements ApplicationListener<SessionUnsubscribeEvent> {
    private static final Logger logger = LoggerFactory.getLogger(UnsubscriptionEventListener.class);

    @Override
    public void onApplicationEvent(SessionUnsubscribeEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        Principal principal = headerAccessor.getUser();
        if (principal == null) {
            logger.warn("Unauthorized session requested, UserId is null, Session ID: {}", sessionId);
            throw new AuthenticationServiceException("Unauthorized Session Requested");
        }
        // 세션캐시 디커미션 (#209 #31): UNSUBSCRIBE에서 deleteSessionCache를 더 이상
        // 호출하지 않는다. 세션 정리는 WS DISCONNECT가 권위. 인증 가드와 로깅만 남긴다.
        logger.info("Session has unsubscribed, sessionId : {}", sessionId);
    }
}
