package com.pfplaybackend.realtime.port;

/**
 * Bridges realtime STOMP session events to the partyroom presence model in the app
 * module. Implementation lives in app and translates a sessionId into the associated
 * (partyroomId, userId) before calling the domain service.
 *
 * <p>Both methods MUST be idempotent — STOMP can deliver duplicate connect/disconnect
 * frames under reconnection storms, and the listener fires for every subscribe.
 */
public interface PresencePort {

    /**
     * Called when a STOMP session for a partyroom subscription is established or
     * re-established. Implementation cancels any PENDING_EXIT for the user/room.
     */
    void onSessionConnected(String sessionId);

    /**
     * Called when a STOMP session disconnects (clean close, abnormal close, or
     * heartbeat timeout). Implementation marks the user PENDING_EXIT in the
     * associated partyroom and starts the grace timer.
     */
    void onSessionDisconnected(String sessionId);
}
