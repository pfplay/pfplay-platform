package com.pfplaybackend.realtime.event;

import com.pfplaybackend.realtime.port.SessionCachePort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;

@Component
@RequiredArgsConstructor
public class SubscriptionEventListener implements ApplicationListener<SessionSubscribeEvent> {
    private static final Logger logger = LoggerFactory.getLogger(SubscriptionEventListener.class);
    private final SessionCachePort sessionCachePort;

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
        // 세션캐시 write는 채팅(비-presence) 라이프사이클이다: PartyroomChatCommandService가
        // getSessionCache로 read하는 sessionId→(partyroom,user,crew) source를 여기서 채운다.
        // 이 write는 24h TTL 백스톱을 함께 건다 — clean path는 UNSUBSCRIBE에서 delete하지만
        // 비정상 종료 orphan은 그 TTL로 self-heal된다 (RedisSessionCacheAdapter).
        // presence 트리거(onSessionConnected)는 디커플링됨 — presence는 WS CONNECT/DISCONNECT가
        // 권위이며 SUBSCRIBE 타이밍에 의존하지 않는다 (#209 #31, Task 1.4/1.6).
        sessionCachePort.saveSessionCache(sessionId, userId, destination);

        logger.info("Session has subscribed, sessionId : {}, userId : {}, destination : {}", sessionId, userId, destination);
    }
}
