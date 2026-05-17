package com.pfplaybackend.realtime.port;

/**
 * Bridges a freshly established realtime STOMP session to the live user-session
 * registry in the app module. The realtime listener knows {@code (sessionId,
 * userId)} at CONNECT time (Principal); the app implementation translates the
 * userId String into the domain value and records the mapping so that later
 * presence resolution can map a bare sessionId back to its user.
 *
 * <p>This MUST be called before any presence resolution for the same session —
 * the resolver reads back the very mapping written here.
 *
 * <p>There is intentionally no {@code unregister} on this port: the disconnect
 * path needs the unregister result (was-this-the-last-session) fused with the
 * presence decision app-side, so it is handled inside
 * {@code PresencePortAdapter.onSessionDisconnected} rather than split across the
 * realtime↔app seam.
 */
public interface SessionRegistryPort {

    /**
     * Registers the live STOMP session for the authenticated user. Invoked once
     * per CONNECT frame, before {@link PresencePort#onSessionConnected(String)}.
     */
    void register(String sessionId, String userId);
}
