package com.pfplaybackend.api.auth.application.ratelimit;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.pfplaybackend.api.auth.config.properties.RateLimitProperties;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminLoginRateLimiterTest {

    private AdminLoginRateLimiter sut;

    @BeforeEach
    void setup() {
        var props = new RateLimitProperties();
        props.setIp(new RateLimitProperties.Bucket(3, 60));
        props.setEmail(new RateLimitProperties.Bucket(2, 60));
        var ipCache = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(5)).<String, Bucket>build();
        var emailCache = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(5)).<String, Bucket>build();
        sut = new AdminLoginRateLimiter(props, ipCache, emailCache);
    }

    @Test
    void ip_bucket_exhausts_after_capacity() {
        sut.checkOrThrow("1.2.3.4", "a@x.com");
        sut.checkOrThrow("1.2.3.4", "b@x.com");
        sut.checkOrThrow("1.2.3.4", "c@x.com");

        assertThatThrownBy(() -> sut.checkOrThrow("1.2.3.4", "d@x.com"))
                .isInstanceOf(AdminLoginRateLimiter.RateLimitedException.class);
    }

    @Test
    void email_bucket_cleared_on_success() {
        sut.checkOrThrow("9.9.9.9", "victim@x.com");
        sut.checkOrThrow("9.9.9.9", "victim@x.com");
        sut.onLoginSuccess("victim@x.com");

        assertThatCode(() -> sut.checkOrThrow("9.9.9.9", "victim@x.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void distinct_ips_have_independent_buckets() {
        sut.checkOrThrow("1.1.1.1", "x@x.com");
        sut.checkOrThrow("1.1.1.1", "y@x.com");
        sut.checkOrThrow("1.1.1.1", "z@x.com");

        assertThatCode(() -> sut.checkOrThrow("2.2.2.2", "fresh@x.com"))
                .doesNotThrowAnyException();
    }
}
