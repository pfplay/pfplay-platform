package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackAggregationConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private PlaybackAggregationRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;

    private static final int THREAD_COUNT = 100;

    /** New TransactionTemplate per call — required because @Modifying queries need a tx context,
     *  and raw test threads don't have one by default. */
    private TransactionTemplate newTx() {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt;
    }

    @Test
    @DisplayName("applyAggregationDelta — 100 스레드 동시 like → likeCount == 100")
    void concurrent_likes() throws Exception {
        PlaybackId pid = new PlaybackId(81001L);
        newTx().executeWithoutResult(status ->
                repository.saveAndFlush(PlaybackAggregationData.createFor(pid))
        );

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);

        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        newTx().executeWithoutResult(status ->
                                repository.applyAggregationDelta(pid, 1, 0, 0)
                        );
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        PlaybackAggregationData reloaded = newTx().execute(status ->
                repository.findById(pid).orElseThrow()
        );
        assertThat(reloaded.getLikeCount())
                .as("100 동시 +1 → likeCount는 정확히 100 (lost-update 가드 검증)")
                .isEqualTo(THREAD_COUNT);
        assertThat(reloaded.getDislikeCount()).isZero();
        assertThat(reloaded.getGrabCount()).isZero();
    }
}
