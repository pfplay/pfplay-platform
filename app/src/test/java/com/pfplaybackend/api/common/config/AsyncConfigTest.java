package com.pfplaybackend.api.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AsyncConfig.class)
class AsyncConfigTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    @DisplayName("userActivityLogExecutor bean 등록 + sizing")
    void userActivityLogExecutor_registered_with_expected_sizing() {
        ThreadPoolTaskExecutor exec = (ThreadPoolTaskExecutor) ctx.getBean(AsyncConfig.UAL_EXECUTOR_BEAN);

        assertThat(exec.getCorePoolSize()).isEqualTo(2);
        assertThat(exec.getMaxPoolSize()).isEqualTo(4);
        assertThat(exec.getQueueCapacity()).isEqualTo(200);
        assertThat(exec.getThreadNamePrefix()).isEqualTo("ual-");
        assertThat(exec.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);

        // graceful-shutdown 설정(waitForTasksToCompleteOnShutdown / awaitTerminationSeconds)은
        // public getter 부재 + ReflectionTestUtils가 Spring 6.x 내부 필드명과 어긋나 단언 fragile.
        // 본 테스트는 5개 핵심 sizing/policy만 검증. shutdown 회귀 위험은 spec §6 본문 + AsyncConfig
        // bean 정의 코드 리뷰 단계에서 잡는다.
    }
}
