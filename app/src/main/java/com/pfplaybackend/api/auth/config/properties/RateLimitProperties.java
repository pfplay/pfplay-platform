package com.pfplaybackend.api.auth.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.ratelimit.admin-login")
public class RateLimitProperties {
    private Bucket ip = new Bucket(10, 300);
    private Bucket email = new Bucket(5, 900);

    @Data
    public static class Bucket {
        private int capacity;
        private int windowSeconds;
        public Bucket() {}
        public Bucket(int capacity, int windowSeconds) {
            this.capacity = capacity;
            this.windowSeconds = windowSeconds;
        }
    }
}
