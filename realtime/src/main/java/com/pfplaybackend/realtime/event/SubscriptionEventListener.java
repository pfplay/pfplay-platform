package com.pfplaybackend.realtime.event;

import com.pfplaybackend.realtime.port.PresencePort;
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
    private final PresencePort presencePort;

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
        sessionCachePort.saveSessionCache(sessionId, userId, destination);
        // PRESENCE: subscribe to a partyroom topic = user is back. Clear any
        // PENDING_EXIT for their associated room (no-op if not pending).
        presencePort.onSessionConnected(sessionId);

        logger.info("Session has subscribed, sessionId : {}, userId : {}, destination : {}", sessionId, userId, destination);
    }
}
