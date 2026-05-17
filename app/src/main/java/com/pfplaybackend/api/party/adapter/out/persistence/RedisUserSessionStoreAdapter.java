package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.party.application.service.UserSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed {@link UserSessionRegistry.SessionStore} adapter (Cluster A PR-1).
 *
 * <p>Keys (per {@link UserSessionRegistry} Javadoc):
 * <ul>
 *   <li>{@code presence:session:{sid}} → uid (STRING, session → user binding)</li>
 *   <li>{@code presence:usersessions:{uid}} → Set&lt;sid&gt; (user → live sessions)</li>
 * </ul>
 *
 * <p>Values are plain strings (no JSON wrapping) via {@link StringRedisTemplate} so
 * the Lua script can compare/return them directly — consistent with V16 presence
 * which also uses {@code RedisTemplate} string semantics for control keys.
 *
 * <p>{@link #unbindSessionAndCount} is the atomic pair {@code DEL session} +
 * {@code SREM usersessions} then {@code SCARD usersessions}, executed as a single
 * server-side Lua script so two concurrent disconnects for the same user cannot
 * both observe a non-empty set (exactly one sees {@code 0} ⇒ wasLastSession).
 *
 * <p><b>Self-healing TTL backstop.</b> The authoritative cleanup is
 * {@link #unbindSessionAndCount} on STOMP DISCONNECT. But DISCONNECT is not
 * guaranteed (network partition, pod kill, broker crash, ungraceful client
 * teardown); a missed DISCONNECT would otherwise leave an orphan sid in
 * {@code presence:usersessions:{uid}} forever, permanently keeping that user's
 * {@code SCARD} ≥ 1 so {@code wasLastSession} can never again be true and the
 * presence grace timer can never fire for them again. The DB-crew
 * reconcile-cron sweeps {@code crew.pending_exit_at} rows only — it does NOT
 * touch this Redis user-session registry, so the registry must bound its own
 * leak. Therefore both keys carry a (configurable, default 24h) TTL refreshed
 * on every {@link #bindSession} (a reconnect re-CONNECT naturally refreshes it);
 * the TTL is deliberately far longer than any realistic continuous live session
 * so it never expires an actually-live tab, while still self-expiring orphans.
 */
@Slf4j
@Repository
public class RedisUserSessionStoreAdapter implements UserSessionRegistry.SessionStore {

    private static final String SESSION_KEY_PREFIX = "presence:session:";
    private static final String USER_SESSIONS_KEY_PREFIX = "presence:usersessions:";

    /**
     * KEYS[1] = presence:session:{sid}, KEYS[2] = presence:usersessions:{uid},
     * ARGV[1] = sid. Atomically removes the session binding and reports the
     * remaining live session count for that user. SCARD on a now-empty set
     * returns 0 (Redis auto-deletes the empty set), so 0 ⇒ that was the last.
     */
    private static final RedisScript<Long> UNBIND_AND_COUNT = new DefaultRedisScript<>(
            "redis.call('DEL', KEYS[1]) "
                    + "redis.call('SREM', KEYS[2], ARGV[1]) "
                    + "return redis.call('SCARD', KEYS[2])",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Self-healing backstop TTL (seconds) applied to both registry keys on every
     * {@link #bindSession}. Default 24h — generously longer than any realistic
     * continuous live session so a live tab is never expired, while bounding a
     * missed-DISCONNECT orphan to at most this window.
     */
    private final long ttlSeconds;

    public RedisUserSessionStoreAdapter(
            StringRedisTemplate stringRedisTemplate,
            @Value("${presence.user-session.ttl-seconds:86400}") long ttlSeconds) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void bindSession(String sessionId, String userId) {
        // Refresh TTL on every bind: a reconnect re-CONNECT re-binds and thus
        // naturally pushes the self-heal expiry forward for a still-live session.
        stringRedisTemplate.opsForValue().set(sessionKey(sessionId), userId, ttlSeconds, TimeUnit.SECONDS);
        String userSetKey = userSessionsKey(userId);
        stringRedisTemplate.opsForSet().add(userSetKey, sessionId);
        stringRedisTemplate.expire(userSetKey, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public String getUserBySession(String sessionId) {
        return stringRedisTemplate.opsForValue().get(sessionKey(sessionId));
    }

    @Override
    public long unbindSessionAndCount(String sessionId, String userId) {
        Long remaining = stringRedisTemplate.execute(
                UNBIND_AND_COUNT,
                List.of(sessionKey(sessionId), userSessionsKey(userId)),
                sessionId);
        return remaining == null ? 0L : remaining;
    }

    private static String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private static String userSessionsKey(String userId) {
        return USER_SESSIONS_KEY_PREFIX + userId;
    }
}
