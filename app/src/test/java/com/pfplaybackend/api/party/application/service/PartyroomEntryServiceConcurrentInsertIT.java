package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService.TryEnterResult;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * #389 같은 user · 같은 방 동시 입장에서 crew INSERT 에 진 요청도 정상 응답을 받는지 검증한다.
 *
 * <p>INSERT 충돌은 실제 동시 실행에서 드물게만 나므로 순서를 고정해 재현한다.
 * 다른 트랜잭션(승자)이 crew 를 INSERT 하고 커밋을 미룬 사이 입장을 시작하면, 입장 트랜잭션은
 * 승자 행이 안 보이는 스냅샷으로 진행하다 승자 커밋 뒤 INSERT 가 uk_crew_partyroom_user 에 걸린다.
 */
class PartyroomEntryServiceConcurrentInsertIT extends AbstractIntegrationTest {

    @Autowired private PartyroomEntryService entryService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** assertHasProfile 게이트만 무력화 — PartyroomAccessCommandServiceRaceIT 와 같은 패턴. */
    @MockBean
    private UserProfileQueryPort userProfileQueryPort;

    @BeforeEach
    void seedProfileGate() {
        lenient().when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenAnswer(inv -> {
                    List<UserId> ids = inv.getArgument(0);
                    Map<UserId, ProfileSettingDto> result = new HashMap<>();
                    for (UserId id : ids) result.put(id, mock(ProfileSettingDto.class));
                    return result;
                });
    }

    @Test
    @DisplayName("같은 방 동시 입장에서 crew INSERT 에 진 요청 → 재시도로 승자 crew 를 정상 반환")
    void insert_loser_gets_winner_crew() throws Exception {
        PartyroomData room = PartyroomData.create("insert-race", "intro", LinkDomain.of("link-insert-race"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(6201L));
        PartyroomId pid = new PartyroomId(partyroomRepository.saveAndFlush(room).getId());
        UserId userId = new UserId(7201L);

        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong winnerCrewId = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 승자: crew 를 먼저 INSERT 하고 커밋을 미룬다.
            Future<?> winner = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                CrewData crew = crewRepository.saveAndFlush(
                        CrewData.create(pid, userId, GradeType.LISTENER, CountryCode.of("KR")));
                winnerCrewId.set(crew.getId());
                inserted.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

            // 패자: 승자 행이 안 보이는 스냅샷으로 입장을 시작해, 승자의 미커밋 행 잠금에서 대기한다.
            Future<TryEnterResult> loser = pool.submit(() -> {
                ThreadLocalContext.setContext(new AuthContext(userId, AuthorityTier.GT));
                try {
                    return entryService.tryEnter(pid, CountryCode.of("KR"));
                } finally {
                    ThreadLocalContext.clearContext();
                }
            });
            // 패자가 승자 잠금 대기에 들어갈 시간을 준다. 늦게 들어가도 같은 행에서 대기하므로 결과는 같다.
            Thread.sleep(1_000);
            assertThat(loser.isDone()).as("패자는 승자 커밋 전까지 대기해야 한다").isFalse();
            release.countDown();
            winner.get(30, TimeUnit.SECONDS);

            TryEnterResult result = loser.get(30, TimeUnit.SECONDS);

            assertThat(result.crew().getId()).isEqualTo(winnerCrewId.get());
            assertThat(result.reactivated()).as("이미 승자가 입장시킨 상태 → 재입장과 같은 결과").isFalse();
            Long activeRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM crew WHERE partyroom_id = ? AND is_active = 1", Long.class, pid.getId());
            assertThat(activeRows).isEqualTo(1L);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
