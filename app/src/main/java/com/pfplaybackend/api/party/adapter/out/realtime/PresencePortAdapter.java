package com.pfplaybackend.api.party.adapter.out.realtime;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.PartyroomQueryPort;
import com.pfplaybackend.api.party.application.service.PartyroomPresenceService;
import com.pfplaybackend.api.party.application.service.UserSessionRegistry;
import com.pfplaybackend.api.party.application.service.UserSessionRegistry.UnregisterResult;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.realtime.port.PresencePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Bridges STOMP session events (in the realtime module) to the partyroom presence
 * service (in the app module). The realtime listener fires with only a sessionId;
 * we resolve it to {@code (partyroomId, userId)} using the server-side authority,
 * not the subscribe-timing session cache:
 *
 * <ul>
 *   <li>{@code userId} ← {@link UserSessionRegistry} (live STOMP session registry)</li>
 *   <li>active room ← {@link PartyroomQueryPort#getActivePartyroomByUserId} (DB authority)</li>
 * </ul>
 *
 * <p>If the user cannot be resolved via the registry, or has no active room, both
 * methods are silent no-ops (preserving the original cache-miss contract — now
 * "not resolvable via registry/DB" is the silent no-op).
 *
 * <p>On disconnect we only mark PENDING_EXIT when the user's <em>last</em> live
 * session is gone; if other sessions remain (multi-tab) the disconnect is a silent
 * no-op so a closed tab does not start the grace timer for an still-present user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresencePortAdapter implements PresencePort {

    private final UserSessionRegistry userSessionRegistry;
    private final PartyroomQueryPort partyroomQueryPort;
    private final PartyroomPresenceService presenceService;

    @Override
    public void onSessionConnected(String sessionId) {
        Optional<UserId> user = userSessionRegistry.findUserBySession(sessionId);
        if (user.isEmpty()) {
            log.debug("[presence] onSessionConnected no-op: no user for sessionId={}", sessionId);
            return;
        }
        UserId userId = user.get();
        Optional<PartyroomId> roomId = resolveActiveRoom(userId);
        if (roomId.isEmpty()) {
            log.debug("[presence] onSessionConnected no-op: no active room for userId={}", userId);
            return;
        }
        presenceService.clearPending(roomId.get(), userId);
    }

    @Override
    public void onSessionDisconnected(String sessionId) {
        UnregisterResult result = userSessionRegistry.unregister(sessionId);
        if (!result.wasLastSession()) {
            // Multi-tab: another live session for this user remains (or the session
            // was unknown). Closing one tab must not start the grace timer.
            log.debug("[presence] onSessionDisconnected no-op: not last session, sessionId={}", sessionId);
            return;
        }
        if (result.userId().isEmpty()) {
            log.debug("[presence] onSessionDisconnected no-op: last session but no userId, sessionId={}", sessionId);
            return;
        }
        UserId userId = result.userId().get();
        Optional<PartyroomId> roomId = resolveActiveRoom(userId);
        if (roomId.isEmpty()) {
            log.debug("[presence] onSessionDisconnected no-op: last session but no active room, userId={}", userId);
            return;
        }
        presenceService.markPending(roomId.get(), userId);
    }

    private Optional<PartyroomId> resolveActiveRoom(UserId userId) {
        return partyroomQueryPort.getActivePartyroomByUserId(userId)
                .map(ActivePartyroomDto::id)
                // ActivePartyroomDto.id() is the partyroom PK — non-null by query contract
                // (locked by ClusterAPresenceIntegrationTest); a null here is a broken
                // invariant and must fail loudly, not be silently filtered.
                .map(PartyroomId::of);
    }
}
