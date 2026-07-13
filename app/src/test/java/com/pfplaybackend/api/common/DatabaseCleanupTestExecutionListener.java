package com.pfplaybackend.api.common;

import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 각 test 메서드 <b>전(pre-transaction)</b> 에 async executor 를 idle 로 몰아넣고 DB 를 truncate 한다 (스펙 §3.2).
 *
 * <h2>실행 순서 (C1)</h2>
 * {@link #getOrder()} = 2900 → DependencyInjectionTestExecutionListener(2000) 이후,
 * TransactionalTestExecutionListener(4000) 이전. 즉 test 트랜잭션이 열리기 <b>전</b> 에 truncate 되므로,
 * 롤백형 IT 의 test tx 를 침범하지 않는다. (truncate 자체는 {@link DatabaseCleaner} 의 별도 커넥션에서
 * 실행되어 이중 안전.)
 *
 * <h2>async quiescence (C2)</h2>
 * AFTER_COMMIT + {@code @Async} 리스너의 late write 가 다음 테스트 truncate 뒤에 착지하는 레이스를 막기 위해,
 * truncate <b>직전</b> 에 컨텍스트의 모든 {@link ThreadPoolTaskExecutor} 빈(실재 3종: userActivityLogExecutor,
 * webPushExecutor, vdjChatExecutor)이 idle 임을 확인한다. TOCTOU 방어를 위해 (activeCount==0 ∧ queue empty)
 * 를 <b>연속 {@value #REQUIRED_IDLE_OBSERVATIONS}회</b> 관측할 때까지 폴링하고, 상한
 * ({@value #QUIESCE_TIMEOUT_MILLIS}ms) 초과 시 fail-loud 예외를 던진다(조용한 오염 방지).
 */
public class DatabaseCleanupTestExecutionListener extends AbstractTestExecutionListener {

    /** DI(2000) < 2900 < Transactional(4000). Micrometer TEL(2500) 과도 비충돌. */
    private static final int ORDER = 2900;

    private static final int REQUIRED_IDLE_OBSERVATIONS = 3;
    private static final long QUIESCE_TIMEOUT_MILLIS = 5_000L;
    private static final long POLL_INTERVAL_MILLIS = 20L;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        ApplicationContext appCtx = testContext.getApplicationContext();
        quiesceAsyncExecutors(appCtx);
        appCtx.getBean(DatabaseCleaner.class).clean();
    }

    private void quiesceAsyncExecutors(ApplicationContext appCtx) {
        Map<String, ThreadPoolTaskExecutor> executors =
                appCtx.getBeansOfType(ThreadPoolTaskExecutor.class);
        long deadline = System.currentTimeMillis() + QUIESCE_TIMEOUT_MILLIS;
        int consecutiveIdle = 0;
        while (consecutiveIdle < REQUIRED_IDLE_OBSERVATIONS) {
            String busy = firstBusyExecutor(executors);
            if (busy == null) {
                consecutiveIdle++;
            } else {
                consecutiveIdle = 0;
                if (System.currentTimeMillis() >= deadline) {
                    throw new IllegalStateException(
                            "async executor quiescence 타임아웃(" + QUIESCE_TIMEOUT_MILLIS
                                    + "ms): '" + busy + "' 가 여전히 바쁨 — truncate 진행 시 C2 오염 위험");
                }
            }
            sleep();
        }
    }

    /** 바쁜 executor 의 빈 이름을 반환, 전부 idle 이면 null. */
    private String firstBusyExecutor(Map<String, ThreadPoolTaskExecutor> executors) {
        for (Map.Entry<String, ThreadPoolTaskExecutor> e : executors.entrySet()) {
            ThreadPoolExecutor pool = e.getValue().getThreadPoolExecutor();
            if (pool.getActiveCount() != 0 || !pool.getQueue().isEmpty()) {
                return e.getKey();
            }
        }
        return null;
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("async quiescence 폴링 중 인터럽트", ie);
        }
    }
}
