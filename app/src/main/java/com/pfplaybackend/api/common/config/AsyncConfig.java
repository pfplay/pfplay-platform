package com.pfplaybackend.api.common.config;

import com.pfplaybackend.api.common.log.MdcTaskDecorator;
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

    /** UserActivityLogListener `@Async` qualifier — 컴파일 타임 linkage. */
    public static final String UAL_EXECUTOR_BEAN = "userActivityLogExecutor";

    /** AnnouncementPushNotifier `@Async` qualifier — 컴파일 타임 linkage. */
    public static final String WEB_PUSH_EXECUTOR_BEAN = "webPushExecutor";

    @Bean(name = UAL_EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor userActivityLogExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("ual-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setTaskDecorator(new MdcTaskDecorator());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(10);
        exec.initialize();
        return exec;
    }

    /**
     * 공지→Web Push fan-out 전용 executor.
     * - AnnouncementPushNotifier (AFTER_COMMIT 리스너)가 @Async 로 위임
     * - core=2, max=4, queue=500, CallerRunsPolicy (saturation 시 호출 thread가 직접 처리)
     * - 그레이스풀 종료: 큐 비울 때까지 15초 대기 (push 발송 손실 최소화)
     */
    @Bean(name = WEB_PUSH_EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor webPushExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("webpush-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setTaskDecorator(new MdcTaskDecorator());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(15);
        exec.initialize();
        return exec;
    }
}
