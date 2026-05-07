package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PartyroomAccessCommandServiceRaceIT extends AbstractIntegrationTest {

    @Autowired private PartyroomAccessCommandService accessCommandService;
    @Autowired private PartyroomRepository partyroomRepository;

    private long createActiveRoom(long hostUid) {
        PartyroomData p = PartyroomData.create(
                "race", "intro", LinkDomain.of("link-race-" + hostUid),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(hostUid));
        return partyroomRepository.saveAndFlush(p).getId();
    }

    /**
     * 같은 user가 같은 룸에 100번 동시 enter → crew_count는 정확히 1.
     * Race B-first(첫 입장 동시 INSERT) + B-reentry(재입장 toggle 동시 호출) +
     * same-room spurious ENTER (ensureCrewActive idempotent return) 모두 차단 검증.
     */
    @Test
    @DisplayName("같은 user 100 스레드 동시 tryEnter → crew_count == 1 (★ acceptance test)")
    void same_user_concurrent_enter() throws Exception {
        long roomId = createActiveRoom(6001L);
        UserId userId = new UserId(7001L);
        PartyroomId pid = new PartyroomId(roomId);

        int threadCount = 100;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        // 모든 스레드가 같은 user로 인증된 척 (테스트 픽스처)
                        ThreadLocalContext.setContext(authContextOf(userId));
                        start.await();
                        try {
                            accessCommandService.tryEnter(pid, CountryCode.of("KR"));
                        } catch (Exception e) {
                            // 일부 스레드는 PartyroomEntrySpecification 등에서 예외 가능 — 무시
                            // 핵심은 어떤 race도 carry가 inflate되지 않는 것
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        ThreadLocalContext.clearContext();
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        // AFTER_COMMIT listener는 REQUIRES_NEW 동기 dispatch라 done.await() 후 모든 UPDATE 완료
        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getCrewCount())
                .as("100 동시 enter → crew_count는 정확히 1 (race B + spurious ENTER 차단 검증)")
                .isEqualTo(1);
    }

    private AuthContext authContextOf(UserId userId) {
        // AuthContext: @AllArgsConstructor only (no @Builder). 기존 user 테스트 컨벤션 따름.
        return new AuthContext(userId, AuthorityTier.GT);
    }
}
