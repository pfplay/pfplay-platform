package com.pfplaybackend.api.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async 인프라.
 *
 * `userActivityLogExecutor`:
 * - UserActivityLogListener (audit timeline) 전용
 * - core=2, max=4, queue=200, CallerRunsPolicy
 * - drop-가능 정책 (saturation 시 producer thread가 직접 INSERT)
 * - 그레이스풀 종료: 큐 비울 때까지 10초 대기 (잔여 task drop 허용)
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §6
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "userActivityLogExecutor")
    public ThreadPoolTaskExecutor userActivityLogExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("ual-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(10);
        exec.initialize();
        return exec;
    }
}
