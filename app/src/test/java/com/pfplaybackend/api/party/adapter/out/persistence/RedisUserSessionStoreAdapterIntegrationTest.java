package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.party.application.service.UserSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@DisplayName("RedisUserSessionStoreAdapter — 실제 Redis 대상 SessionStore 포트 동작/원자성")
class RedisUserSessionStoreAdapterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserSessionRegistry.SessionStore sessionStore;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void flush() {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("bindSession 후 getUserBySession 은 바인딩된 유저를 반환한다")
    void bindThenLookupReturnsUser() {
        sessionStore.bindSession("sid-1", "1001");

        assertThat(sessionStore.getUserBySession("sid-1")).isEqualTo("1001");
    }

    @Test
    @DisplayName("bindSession 후 두 키 모두 양수 TTL 을 갖는다 (missed-DISCONNECT self-heal 백스톱)")
    void bindSessionAppliesPositiveTtlToBothKeys() {
        sessionStore.bindSession("sid-ttl", "9009");

        Long sessionTtl = stringRedisTemplate.getExpire("presence:session:sid-ttl");
        Long userSetTtl = stringRedisTemplate.getExpire("presence:usersessions:9009");

        assertThat(sessionTtl)
                .as("session 키는 양수 TTL 을 가져야 한다")
                .isNotNull()
                .isGreaterThan(0L)
                .isLessThanOrEqualTo(86400L);
        assertThat(userSetTtl)
                .as("usersessions 키는 양수 TTL 을 가져야 한다")
                .isNotNull()
                .isGreaterThan(0L)
                .isLessThanOrEqualTo(86400L);
    }

    @Test
    @DisplayName("같은 유저의 두 세션 중 하나를 unbind 하면 남은 세션 수는 1 (마지막 아님)")
    void unbindOneOfTwoReturnsRemainingCount() {
        sessionStore.bindSession("sid-a", "2002");
        sessionStore.bindSession("sid-b", "2002");

        long remaining = sessionStore.unbindSessionAndCount("sid-a", "2002");

        assertThat(remaining).isEqualTo(1L);
        // unbound session no longer resolves; sibling still does
        assertThat(sessionStore.getUserBySession("sid-a")).isNull();
        assertThat(sessionStore.getUserBySession("sid-b")).isEqualTo("2002");
    }

    @Test
    @DisplayName("마지막 세션을 unbind 하면 남은 세션 수는 0 (마지막)")
    void unbindLastReturnsZero() {
        sessionStore.bindSession("sid-only", "3003");

        long remaining = sessionStore.unbindSessionAndCount("sid-only", "3003");

        assertThat(remaining).isZero();
        assertThat(sessionStore.getUserBySession("sid-only")).isNull();
    }

    @Test
    @DisplayName("알 수 없는 세션을 unbind 해도 안전하다 (0 반환, 예외 없음)")
    void unbindUnknownSessionIsSafe() {
        long remaining = sessionStore.unbindSessionAndCount("ghost", "4004");

        assertThat(remaining).isZero();
        assertThat(sessionStore.getUserBySession("ghost")).isNull();
    }

    @Test
    @DisplayName("동일 유저의 두 세션을 두 스레드가 동시에 unbind — 정확히 한 스레드만 마지막(0)을 관측한다")
    void concurrentUnbindExactlyOneObservesLast() throws InterruptedException {
        String userId = "5005";
        sessionStore.bindSession("sid-x", userId);
        sessionStore.bindSession("sid-y", userId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ConcurrentLinkedQueue<Long> results = new ConcurrentLinkedQueue<>();

        for (String sid : new String[]{"sid-x", "sid-y"}) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(sessionStore.unbindSessionAndCount(sid, userId));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        AtomicInteger zeroCount = new AtomicInteger();
        results.forEach(r -> {
            if (r == 0L) zeroCount.incrementAndGet();
        });
        assertThat(results).hasSize(2);
        assertThat(zeroCount.get())
                .as("exactly one disconnect must observe wasLast (count==0)")
                .isEqualTo(1);
    }
}
