package com.pfplaybackend.api.party.adapter.out.realtime;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.service.UserSessionRegistry;
import com.pfplaybackend.realtime.port.SessionRegistryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Bridges the realtime CONNECT event (in the realtime module) to the live
 * {@link UserSessionRegistry} (in the app module). The realtime listener fires
 * with the authenticated {@code (sessionId, userId)} pair; we translate the
 * userId String into the {@link UserId} domain value and record the live
 * session so that subsequent presence resolution can map a bare sessionId back
 * to its user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionRegistryPortAdapter implements SessionRegistryPort {

    private final UserSessionRegistry userSessionRegistry;

    @Override
    public void register(String sessionId, String userId) {
        userSessionRegistry.register(sessionId, UserId.fromString(userId));
    }
}
