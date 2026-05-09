package com.pfplaybackend.api.party.adapter.out.realtime;

import com.pfplaybackend.api.party.application.dto.partyroom.PartyroomSessionDto;
import com.pfplaybackend.api.party.application.service.PartyroomPresenceService;
import com.pfplaybackend.realtime.port.PresencePort;
import com.pfplaybackend.realtime.port.SessionCachePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Bridges STOMP session events (in the realtime module) to the partyroom presence
 * service (in the app module). The realtime listener fires with only a sessionId;
 * we resolve it to {@code (partyroomId, userId)} via the session cache that
 * {@code SubscriptionEventListener} populates on subscribe.
 *
 * <p>If the session cache lookup misses (e.g., disconnect arrives before subscribe
 * completed, or for a non-partyroom subscription), both methods are silent no-ops.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresencePortAdapter implements PresencePort {

    private final SessionCachePort sessionCachePort;
    private final PartyroomPresenceService presenceService;

    @Override
    public void onSessionConnected(String sessionId) {
        resolve(sessionId).ifPresent(dto ->
                presenceService.clearPending(dto.partyroomId(), dto.userId()));
    }

    @Override
    public void onSessionDisconnected(String sessionId) {
        resolve(sessionId).ifPresent(dto ->
                presenceService.markPending(dto.partyroomId(), dto.userId()));
    }

    private Optional<PartyroomSessionDto> resolve(String sessionId) {
        Optional<Object> cached = sessionCachePort.getSessionCache(sessionId);
        if (cached.isEmpty()) return Optional.empty();
        Object obj = cached.get();
        if (!(obj instanceof PartyroomSessionDto dto)) {
            log.debug("[presence] session cache for {} is not a PartyroomSessionDto: {}",
                    sessionId, obj.getClass().getSimpleName());
            return Optional.empty();
        }
        return Optional.of(dto);
    }
}
