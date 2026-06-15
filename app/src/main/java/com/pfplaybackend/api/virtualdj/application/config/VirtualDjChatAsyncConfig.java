package com.pfplaybackend.api.virtualdj.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 가상 DJ P3 채팅 LLM 워커 전용 스레드풀 (Chunk 5).
 *
 * <p>{@code LlmChatTaskRunner} 의 비동기 디스패치를 받는 작은 bounded pool 이다.
 * {@code @Async} 를 쓰지 않고({@code @Async} 메서드를 {@code @TransactionalEventListener} 로
 * 강제하는 ArchUnit 규칙 회피) 워커가 {@code executor.execute(Runnable)} 로 직접 제출한다.
 *
 * <h2>AbortPolicy 의 안전성</h2>
 * 큐가 가득 차면 {@link ThreadPoolExecutor.AbortPolicy} 가 제출을 거부해 그 회차의 봇 응답이
 * 그냥 드롭된다. 이는 <b>안전</b>하다 — 채팅 게이트는 방별 단일 SETNX 게이트 키(TTL 만료) 이므로
 * 누수될 lock 토큰이 없다. 거부된 dispatch 는 단지 한 번의 턴을 거른 것일 뿐이다.
 */
@Configuration
public class VirtualDjChatAsyncConfig {

    /** {@code LlmChatTaskRunner} 가 {@code @Qualifier} 로 주입하는 빈 이름 — 컴파일 타임 linkage. */
    public static final String VDJ_CHAT_EXECUTOR = "vdjChatExecutor";

    @Bean(name = VDJ_CHAT_EXECUTOR)
    public ThreadPoolTaskExecutor vdjChatExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("vdj-chat-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(5);
        exec.initialize();
        return exec;
    }
}
