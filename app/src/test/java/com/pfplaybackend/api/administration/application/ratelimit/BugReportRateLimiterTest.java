package com.pfplaybackend.api.administration.application.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.exception.http.TooManyRequestsException;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BugReportRateLimiterTest {

    private BugReportRateLimiter limiter;

    @BeforeEach
    void setUp() {
        Cache<String, Bucket> userBuckets = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
        limiter = new BugReportRateLimiter(userBuckets);
    }

    @Test
    @DisplayName("첫 호출 — 통과")
    void firstCallPasses() {
        assertThatNoException().isThrownBy(() -> limiter.acquireOrThrow(1L));
    }

    @Test
    @DisplayName("1분 내 연속 2회 — 두 번째는 BUG-001 TooManyRequestsException")
    void secondCallWithinWindowThrows() {
        limiter.acquireOrThrow(1L);
        assertThatThrownBy(() -> limiter.acquireOrThrow(1L))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining(BugReportException.RATE_LIMIT_EXCEEDED.getMessage());
    }

    @Test
    @DisplayName("다른 user — 독립적으로 통과")
    void differentUserPasses() {
        limiter.acquireOrThrow(1L);
        assertThatNoException().isThrownBy(() -> limiter.acquireOrThrow(2L));
    }
}
