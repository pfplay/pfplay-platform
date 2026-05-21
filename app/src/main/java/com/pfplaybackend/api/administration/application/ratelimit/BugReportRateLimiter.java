package com.pfplaybackend.api.administration.application.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * VOC 버그리포트 rate limit — userId 별 1분 1회.
 *
 * AdminLoginRateLimiter (auth 모듈) 패턴 미러:
 * - hand-written constructor (Lombok @RequiredArgsConstructor 가 @Qualifier 를 strip)
 * - bucket4j 신 API (Bandwidth.builder().refillIntervally(...))
 * - 별도 Caffeine cache (@Qualifier("bugReportUserBuckets")) — cache key namespace 분리
 *
 * AdminLoginRateLimiter 와 차이: 본 클래스는 ExceptionCreator → TooManyRequestsException 매핑 사용
 * (inner RateLimitedException 패턴 안 씀, BUG-001 errorCode 보존 위해).
 *
 * Spec: docs/superpowers/specs/2026-05-21-voc-bug-report-design.md §3-4
 */
@Component
public class BugReportRateLimiter {

    private static final int CAPACITY = 1;
    private static final Duration REFILL_INTERVAL = Duration.ofMinutes(1);

    private final Cache<String, Bucket> userBuckets;

    public BugReportRateLimiter(
            @Qualifier("bugReportUserBuckets") Cache<String, Bucket> userBuckets) {
        this.userBuckets = userBuckets;
    }

    public void acquireOrThrow(Long userId) {
        Bucket bucket = userBuckets.get(String.valueOf(userId), k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(CAPACITY)
                        .refillIntervally(CAPACITY, REFILL_INTERVAL)
                        .build())
                .build());
        if (!bucket.tryConsume(1)) {
            throw ExceptionCreator.create(BugReportException.RATE_LIMIT_EXCEEDED);
        }
    }
}
