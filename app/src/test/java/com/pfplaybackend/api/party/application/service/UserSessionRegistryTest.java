package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserSessionRegistry — 세션 생존·마지막세션 판정 단위")
class UserSessionRegistryTest {

    /**
     * In-memory fake of the {@link UserSessionRegistry.SessionStore} port.
     * Mirrors the Redis semantics the real adapter must provide:
     * a session→user key/value and a user→sessions Set with SREM-then-SCARD.
     */
    static class FakeSessionStore implements UserSessionRegistry.SessionStore {
        final Map<String, String> sessionToUser = new HashMap<>();
        final Map<String, Set<String>> userSessions = new HashMap<>();

        @Override
        public void bindSession(String sessionId, String userId) {
            sessionToUser.put(sessionId, userId);
            userSessions.computeIfAbsent(userId, k -> new HashSet<>()).add(sessionId);
        }

        @Override
        public String getUserBySession(String sessionId) {
            return sessionToUser.get(sessionId);
        }

        @Override
        public long unbindSessionAndCount(String sessionId, String userId) {
            sessionToUser.remove(sessionId);
            Set<String> set = userSessions.get(userId);
            if (set == null) {
                return 0L;
            }
            set.remove(sessionId);
            long remaining = set.size();
            if (remaining == 0) {
                userSessions.remove(userId);
            }
            return remaining;
        }
    }

    private FakeSessionStore store;
    private UserSessionRegistry registry;

    private final UserId user = new UserId(42L);

    @BeforeEach
    void setUp() {
        store = new FakeSessionStore();
        registry = new UserSessionRegistry(store);
    }

    @Test
    @DisplayName("register 후 findUserBySession 으로 uid 가 조회된다")
    void registerThenFindUser() {
        registry.register("sid-1", user);

        Optional<UserId> found = registry.findUserBySession("sid-1");

        assertThat(found).contains(user);
    }

    @Test
    @DisplayName("findUserBySession — 등록되지 않은 세션은 빈 Optional")
    void findUserByUnknownSession() {
        assertThat(registry.findUserBySession("nope")).isEmpty();
    }

    @Test
    @DisplayName("동일 uid 2세션 중 1개 unregister → wasLastSession=false")
    void unregisterOneOfTwoSessionsNotLast() {
        registry.register("sid-1", user);
        registry.register("sid-2", user);

        UserSessionRegistry.UnregisterResult result = registry.unregister("sid-1");

        assertThat(result.userId()).contains(user);
        assertThat(result.wasLastSession()).isFalse();
    }

    @Test
    @DisplayName("마지막 세션 unregister → wasLastSession=true")
    void unregisterLastSessionIsLast() {
        registry.register("sid-1", user);
        registry.register("sid-2", user);
        registry.unregister("sid-1");

        UserSessionRegistry.UnregisterResult result = registry.unregister("sid-2");

        assertThat(result.userId()).contains(user);
        assertThat(result.wasLastSession()).isTrue();
    }

    @Test
    @DisplayName("단일 세션 unregister → wasLastSession=true")
    void unregisterSingleSessionIsLast() {
        registry.register("sid-1", user);

        UserSessionRegistry.UnregisterResult result = registry.unregister("sid-1");

        assertThat(result.userId()).contains(user);
        assertThat(result.wasLastSession()).isTrue();
    }

    @Test
    @DisplayName("등록되지 않은 세션 unregister → userId 비어있고 wasLastSession=false")
    void unregisterUnknownSession() {
        UserSessionRegistry.UnregisterResult result = registry.unregister("ghost");

        assertThat(result.userId()).isEmpty();
        assertThat(result.wasLastSession()).isFalse();
    }
}
