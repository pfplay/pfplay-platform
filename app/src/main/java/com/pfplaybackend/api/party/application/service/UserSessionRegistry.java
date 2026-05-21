package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Server-side authority for live STOMP sessions per user (Cluster A PR-1).
 *
 * <p>Tracks which STOMP sessions are alive for each user so that on DISCONNECT we
 * can answer: "does this user still have any live session?" Only when the last
 * session is gone should the presence grace timer start
 * ({@link PartyroomPresenceService#markPending}).
 *
 * <p>State lives in Redis behind the injected {@link SessionStore} port:
 * <ul>
 *   <li>{@code presence:session:{sid}} → uid (session → user binding)</li>
 *   <li>{@code presence:usersessions:{uid}} → Set&lt;sid&gt; (user → live sessions)</li>
 * </ul>
 *
 * <p>The "was this the last session" decision is the atomic pair
 * {@code SREM} then {@code SCARD == 0}; the port exposes it as a single
 * {@link SessionStore#unbindSessionAndCount} operation so a real Redis adapter
 * can make it atomic (Lua / pipeline). The unit test uses an in-memory fake.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSessionRegistry {

    private final SessionStore sessionStore;

    /**
     * Binds a live STOMP session to its user. Encodes the uid with
     * {@link UserId#toString()} so it is exactly symmetric with the
     * {@link UserId#fromString(String)} decode used by {@link #unregister} /
     * {@link #findUserBySession} ({@code UserId.toString()} returns
     * {@code String.valueOf(uid)} — same stored form as before).
     */
    public void register(String sessionId, UserId userId) {
        sessionStore.bindSession(sessionId, userId.toString());
    }

    /**
     * Removes a session. Returns the owning user (if the session was known) and
     * whether this removed the user's last live session.
     */
    public UnregisterResult unregister(String sessionId) {
        String userIdValue = sessionStore.getUserBySession(sessionId);
        if (userIdValue == null) {
            return new UnregisterResult(Optional.empty(), false);
        }
        long remaining = sessionStore.unbindSessionAndCount(sessionId, userIdValue);
        return new UnregisterResult(
                Optional.of(UserId.fromString(userIdValue)),
                remaining == 0);
    }

    /** Resolves the user that owns a session, if it is currently registered. */
    public Optional<UserId> findUserBySession(String sessionId) {
        String userIdValue = sessionStore.getUserBySession(sessionId);
        return Optional.ofNullable(userIdValue).map(UserId::fromString);
    }

    /**
     * Outcome of {@link #unregister}. {@code userId} is empty when the session
     * was unknown; {@code wasLastSession} is true only when removal emptied the
     * user's session set.
     */
    public record UnregisterResult(Optional<UserId> userId, boolean wasLastSession) {}

    /**
     * Redis operations behind a port so the unit can use an in-memory fake.
     * The concrete adapter is {@code RedisTemplate}-backed (covered by a later
     * integration test).
     */
    public interface SessionStore {

        /** {@code SET presence:session:{sid} uid} + {@code SADD presence:usersessions:{uid} sid}. */
        void bindSession(String sessionId, String userId);

        /** {@code GET presence:session:{sid}} — null if absent. */
        String getUserBySession(String sessionId);

        /**
         * {@code DEL presence:session:{sid}} + {@code SREM presence:usersessions:{uid} sid}
         * then {@code SCARD presence:usersessions:{uid}}. Returns the remaining
         * session count (0 ⇒ that was the last session). Must be atomic in the
         * real adapter.
         */
        long unbindSessionAndCount(String sessionId, String userId);
    }
}
