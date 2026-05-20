package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class PartyroomAccessCommandServiceRaceIT extends AbstractIntegrationTest {

    @Autowired private PartyroomAccessCommandService accessCommandService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    /** saveAndFlush 가 별도 tx 로 commit 하고 tryEnter 가 crew 행까지 생성하므로
     *  partyroom + 참조 자식 테이블 전부 정리. user_id 7001 은 AdminPartyroomQueryRepositoryImplIT
     *  의 aliceUid 와 겹쳐 cross-class pollution 가능 — crew 도 반드시 cleanup. */
    private final List<Long> createdRoomIds = new ArrayList<>();

    /**
     * tryEnter → assertHasProfile(PA-7.1) 게이트만 무력화 — race invariant 검증과 직교.
     * (실제 ProfileData 행 구성은 fragile — ClusterAPresenceIntegrationTest 와 동일 패턴.)
     */
    @MockBean
    private UserProfileQueryPort userProfileQueryPort;

    @BeforeEach
    void seedProfileGate() {
        // 어떤 userId 든 프로필 보유 상태로 — assertHasProfile 통과.
        lenient().when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenAnswer(inv -> {
                    List<UserId> ids = inv.getArgument(0);
                    Map<UserId, ProfileSettingDto> result = new java.util.HashMap<>();
                    for (UserId id : ids) {
                        result.put(id, mock(ProfileSettingDto.class));
                    }
                    return result;
                });
    }

    private long createActiveRoom(long hostUid) {
        PartyroomData p = PartyroomData.create(
                "race", "intro", LinkDomain.of("link-race-" + hostUid),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(hostUid));
        long id = partyroomRepository.saveAndFlush(p).getId();
        createdRoomIds.add(id);
        return id;
    }

    @AfterEach
    void cleanupCreatedRooms() {
        if (createdRoomIds.isEmpty()) return;
        // FK 제약이 없어 순서 자유 — tryEnter 가 만든 crew + (혹시 모를) dj/dj_queue/partyroom_playback
        // 까지 partyroom_id 기준 일괄 정리. 마지막에 partyroom 본 행 삭제.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                            "DELETE FROM crew WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM dj WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM dj_queue WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM partyroom_playback WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM partyroom WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
        });
        createdRoomIds.clear();
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
        assertThat(reloaded.getActiveCrewCount())
                .as("100 동시 enter → crew_count는 정확히 1 (race B + spurious ENTER 차단 검증)")
                .isEqualTo(1);
    }

    private AuthContext authContextOf(UserId userId) {
        // AuthContext: @AllArgsConstructor only (no @Builder). 기존 user 테스트 컨벤션 따름.
        return new AuthContext(userId, AuthorityTier.GT);
    }
}
