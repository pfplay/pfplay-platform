package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.party.application.service.UserSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;

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
 */
@Slf4j
@Repository
@RequiredArgsConstructor
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

    @Override
    public void bindSession(String sessionId, String userId) {
        stringRedisTemplate.opsForValue().set(sessionKey(sessionId), userId);
        stringRedisTemplate.opsForSet().add(userSessionsKey(userId), sessionId);
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
