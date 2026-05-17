package com.pfplaybackend.realtime.event;

import com.pfplaybackend.realtime.port.PresencePort;
import com.pfplaybackend.realtime.port.SessionRegistryPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;

import java.security.Principal;

@Component
@RequiredArgsConstructor
public class ConnectionEventListener implements ApplicationListener<SessionConnectEvent> {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionEventListener.class);
    private final SessionRegistryPort sessionRegistryPort;
    private final PresencePort presencePort;

    @Override
    public void onApplicationEvent(SessionConnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        logger.info("Received a new web socket connection: {}", sessionId);

        Principal principal = headerAccessor.getUser();
        if (principal == null) {
            logger.warn("CONNECT without an authenticated principal, skipping session registry: {}", sessionId);
            return;
        }
        // Order matters: register the live session BEFORE presence resolution —
        // PresencePort resolves the userId by reading back this very mapping.
        sessionRegistryPort.register(sessionId, principal.getName());
        presencePort.onSessionConnected(sessionId);
    }
}
