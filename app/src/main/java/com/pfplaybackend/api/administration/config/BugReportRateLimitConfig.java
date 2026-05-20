package com.pfplaybackend.api.administration.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class BugReportRateLimitConfig {

    @Bean(name = "bugReportUserBuckets")
    public Cache<String, Bucket> bugReportUserBuckets() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(100_000)
                .build();
    }
}
