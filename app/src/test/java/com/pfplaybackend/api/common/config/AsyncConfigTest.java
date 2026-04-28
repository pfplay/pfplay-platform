package com.pfplaybackend.api.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AsyncConfig.class)
class AsyncConfigTest {

    @Autowired
    org.springframework.context.ApplicationContext ctx;

    @Test
    @DisplayName("userActivityLogExecutor bean 등록 + sizing")
    void userActivityLogExecutor_registered_with_expected_sizing() {
        ThreadPoolTaskExecutor exec = (ThreadPoolTaskExecutor) ctx.getBean("userActivityLogExecutor");

        assertThat(exec.getCorePoolSize()).isEqualTo(2);
        assertThat(exec.getMaxPoolSize()).isEqualTo(4);
        assertThat(exec.getQueueCapacity()).isEqualTo(200);
        assertThat(exec.getThreadNamePrefix()).isEqualTo("ual-");
        assertThat(exec.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }
}
