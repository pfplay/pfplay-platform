package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PartyroomCounterConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private static final int THREAD_COUNT = 100;

    /** Each concurrent thread needs its own transaction for @Modifying JPQL to execute. */
    private TransactionTemplate newTx() {
        return new TransactionTemplate(transactionManager);
    }

    private long createActiveRoom(long hostUid) {
        PartyroomData p = PartyroomData.create(
                "concurrent", "intro", LinkDomain.of("link-conc-" + hostUid),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(hostUid));
        return newTx().execute(status -> partyroomRepository.saveAndFlush(p).getId());
    }

    @Test
    @DisplayName("incrementCrewCount \u2014 100 \uc2a4\ub808\ub4dc \ub3d9\uc2dc \ud638\ucd9c \u2192 crew_count == 100")
    void increment_concurrent() throws Exception {
        long roomId = createActiveRoom(5001L);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        AtomicInteger affectedSum = new AtomicInteger(0);

        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        Integer affected = newTx().execute(status ->
                                partyroomRepository.incrementCrewCount(roomId, LocalDateTime.now()));
                        affectedSum.addAndGet(affected != null ? affected : 0);
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

        assertThat(affectedSum.get()).isEqualTo(THREAD_COUNT);
        PartyroomData reloaded = newTx().execute(status ->
                partyroomRepository.findById(roomId).orElseThrow());
        assertThat(reloaded.getCrewCount()).isEqualTo(THREAD_COUNT);
    }

    @Test
    @DisplayName("incrementCrewCount + decrementCrewCount mix \u2014 100 inc + 50 dec \u2192 crew_count == 100")
    void increment_decrement_mix() throws Exception {
        long roomId = createActiveRoom(5002L);
        int increments = 100;
        int decrements = 50;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(increments + decrements);

        try {
            for (int i = 0; i < increments; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        newTx().execute(status ->
                                partyroomRepository.incrementCrewCount(roomId, LocalDateTime.now()));
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            // pre-increment 50 so underflow guard does not fire on concurrent decrements
            for (int i = 0; i < 50; i++) {
                newTx().execute(status ->
                        partyroomRepository.incrementCrewCount(roomId, LocalDateTime.now()));
            }
            for (int i = 0; i < decrements; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        newTx().execute(status ->
                                partyroomRepository.decrementCrewCount(roomId, LocalDateTime.now()));
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

        // pre-50 + concurrent 100 - concurrent 50 = 100
        PartyroomData reloaded = newTx().execute(status ->
                partyroomRepository.findById(roomId).orElseThrow());
        assertThat(reloaded.getCrewCount()).isEqualTo(100);
    }
}
