package com.pfplaybackend.api.auth.application.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.pfplaybackend.api.auth.config.properties.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AdminLoginRateLimiter {

    private final RateLimitProperties props;
    private final Cache<String, Bucket> ipBuckets;
    private final Cache<String, Bucket> emailBuckets;

    // Hand-written constructor — Lombok @RequiredArgsConstructor strips @Qualifier
    // annotations on the generated parameters, causing NoUniqueBeanDefinitionException
    // when two beans of type Cache<String, Bucket> exist.
    public AdminLoginRateLimiter(
            RateLimitProperties props,
            @Qualifier("adminLoginIpBuckets") Cache<String, Bucket> ipBuckets,
            @Qualifier("adminLoginEmailBuckets") Cache<String, Bucket> emailBuckets) {
        this.props = props;
        this.ipBuckets = ipBuckets;
        this.emailBuckets = emailBuckets;
    }

    public void checkOrThrow(String clientIp, String email) {
        if (clientIp != null && !clientIp.isBlank()) {
            consume(ipBuckets, clientIp, props.getIp());
        }
        if (email != null && !email.isBlank()) {
            consume(emailBuckets, email.toLowerCase(), props.getEmail());
        }
    }

    public void onLoginSuccess(String email) {
        if (email == null) return;
        emailBuckets.invalidate(email.toLowerCase());
    }

    private void consume(Cache<String, Bucket> cache, String key, RateLimitProperties.Bucket cfg) {
        Bucket bucket = cache.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(cfg.getCapacity())
                        .refillIntervally(cfg.getCapacity(), Duration.ofSeconds(cfg.getWindowSeconds()))
                        .build())
                .build());
        if (!bucket.tryConsume(1)) {
            throw new RateLimitedException();
        }
    }

    public static class RateLimitedException extends RuntimeException {}
}
