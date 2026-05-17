package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackAggregationRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackReactionHistoryRepository;
import com.pfplaybackend.api.party.application.port.out.PlaylistCommandPort;
import com.pfplaybackend.api.party.application.port.out.UserActivityPort;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.history.PlaybackReactionHistoryData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.ReactionType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * E/#6 (#231) 리액션 카운터 race 회귀 잠금.
 *
 * <p>{@link PlaybackReactionCommandService#reactToCurrentPlayback} 는 PLAYBACK_REACTION_HISTORY
 * 를 락 없이 읽어 (target - existing) delta 를 트랜잭션 밖 스냅샷 기준으로 계산한 뒤 atomic
 * UPDATE 로 적용한다. 두 동시 트랜잭션이 같은 stale 히스토리 스냅샷을 읽으면 각자 delta 를
 * 계산해 이중 적용 → PLAYBACK_AGGREGATION 카운터 drift / 음수.
 *
 * <p>실제 빈(Testcontainers MySQL+Redis)으로 race 를 끝까지 구동한다. 두 워커 스레드가
 * {@link TransactionTemplate}(REQUIRES_NEW) 안에서 동시에 {@code reactToCurrentPlayback}
 * 를 호출하고, {@link CountDownLatch} 로 히스토리 read 직후 진입을 최대한 겹치게 만든다.
 *
 * <p>fix 전(현재 코드): 시나리오 (a) like_count 가 2 로 drift, 시나리오 (b) 카운터 음수 가능 →
 * RED. fix 후(history PESSIMISTIC_WRITE 락 + aggregation 음수 floor 가드): GREEN.
 */
class PlaybackReactionCounterRaceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PlaybackReactionCommandService reactionCommandService;
    @Autowired private PlaybackReactionHistoryRepository historyRepository;
    @Autowired private PlaybackAggregationRepository aggregationRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    /** reactToCurrentPlayback 와 직교한 주변 게이트만 무력화. */
    @MockBean private UserProfileQueryPort userProfileQueryPort;
    @MockBean private PlaylistCommandPort playlistCommandPort;
    @MockBean private UserActivityPort userActivityPort;

    @BeforeEach
    void seedOrthogonalGates() {
        lenient().when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenReturn(java.util.Collections.emptyMap());
        lenient().when(playlistCommandPort.grabTrack(any(), any())).thenReturn(null);
    }

    @AfterEach
    void clearAuthContext() {
        ThreadLocalContext.clearContext();
    }

    private TransactionTemplate newTx() {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt;
    }

    /** PARTYROOM + 활성화된 PARTYROOM_PLAYBACK(currentPlaybackId 지정) + DJ_QUEUE + PLAYBACK + AGGREGATION. */
    private record Fixture(PartyroomId partyroomId, PlaybackId playbackId, long djCrewId) {}

    private Fixture persistActivePlaybackGraph(UserId hostId) {
        return newTx().execute(status -> {
            PartyroomData partyroom = PartyroomData.create(
                    "리액션 race 파티룸", "리액션 카운터 race 잠금용",
                    LinkDomain.of("reaction-race-" + hostId.getUid()),
                    PlaybackTimeLimit.ofMinutes(5),
                    StageType.GENERAL, hostId);
            entityManager.persist(partyroom);
            entityManager.flush();
            PartyroomId partyroomId = new PartyroomId(partyroom.getId());

            // 호스트 crew (DJ) — currentDjCrewId 용
            CrewData hostCrew = CrewData.create(partyroomId, hostId, GradeType.HOST, null);
            entityManager.persist(hostCrew);
            entityManager.flush();
            long djCrewId = hostCrew.getId();

            // 실제 PLAYBACK 행 — playbackQueryService.getPlaybackById 가 조회
            PlaybackData playback = PlaybackData.create(
                    partyroomId, hostId, "race-track", Duration.ofSeconds(180),
                    "link-race", "thumb-race");
            entityManager.persist(playback);
            entityManager.flush();
            PlaybackId playbackId = new PlaybackId(playback.getId());

            PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
            playbackState.activate(playbackId, new CrewId(djCrewId));
            entityManager.persist(playbackState);
            entityManager.persist(DjQueueData.createFor(partyroomId));
            entityManager.persist(PlaybackAggregationData.createFor(playbackId));
            entityManager.flush();

            return new Fixture(partyroomId, playbackId, djCrewId);
        });
    }

    /** 반응 사용자를 활성 crew 로 등록. */
    private void persistReactingCrew(PartyroomId partyroomId, UserId userId) {
        newTx().executeWithoutResult(status -> {
            entityManager.persist(CrewData.create(partyroomId, userId, GradeType.CLUBBER, null));
            entityManager.flush();
        });
    }

    /** 히스토리 행을 base 상태(모두 false)로 미리 생성 — 시나리오 (a) 는 UPDATE 경로 race 만 격리. */
    private void persistBaseHistory(PlaybackId playbackId, UserId userId) {
        newTx().executeWithoutResult(status ->
                historyRepository.saveAndFlush(new PlaybackReactionHistoryData(userId, playbackId)));
    }

    private Runnable reactionTask(PartyroomId partyroomId, UserId userId, ReactionType type,
                                  CountDownLatch start, CountDownLatch done,
                                  AtomicReference<Throwable> err) {
        return () -> {
            try {
                start.await();
                ThreadLocalContext.setContext(new AuthContext(userId, AuthorityTier.FM));
                newTx().executeWithoutResult(status ->
                        reactionCommandService.reactToCurrentPlayback(partyroomId, type));
            } catch (Throwable t) {
                err.compareAndSet(null, t);
            } finally {
                ThreadLocalContext.clearContext();
                done.countDown();
            }
        };
    }

    @Test
    @DisplayName("(a) 같은 유저 동시 LIKE 2회 — fix 전: like_count drift(2), fix 후: 정확히 1")
    void concurrentSameUserLikeDoesNotDrift() throws Exception {
        UserId hostId = new UserId(770001L);
        UserId reactor = new UserId(770002L);
        Fixture fx = persistActivePlaybackGraph(hostId);
        persistReactingCrew(fx.partyroomId(), reactor);
        persistBaseHistory(fx.playbackId(), reactor);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> err = new AtomicReference<>();

        try {
            pool.submit(reactionTask(fx.partyroomId(), reactor, ReactionType.LIKE, start, done, err));
            pool.submit(reactionTask(fx.partyroomId(), reactor, ReactionType.LIKE, start, done, err));
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        PlaybackAggregationData agg = newTx().execute(s ->
                aggregationRepository.findById(fx.playbackId()).orElseThrow());

        assertThat(agg.getLikeCount())
                .as("동시 LIKE×2 는 멱등이어야 한다 — history 기준 like_count 는 정확히 1 (drift 차단)")
                .isEqualTo(1);
        assertThat(agg.getDislikeCount()).isZero();
        assertThat(agg.getGrabCount()).isZero();
    }

    @Test
    @DisplayName("(b) 토글 race (DISLIKE/LIKE/DISLIKE 동시) — 카운터가 절대 음수가 되지 않고 최종 상태가 history 와 일치")
    void concurrentToggleNeverGoesNegative() throws Exception {
        UserId hostId = new UserId(770011L);
        UserId reactor = new UserId(770012L);
        Fixture fx = persistActivePlaybackGraph(hostId);
        persistReactingCrew(fx.partyroomId(), reactor);
        persistBaseHistory(fx.playbackId(), reactor);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        AtomicReference<Throwable> err = new AtomicReference<>();

        try {
            pool.submit(reactionTask(fx.partyroomId(), reactor, ReactionType.DISLIKE, start, done, err));
            pool.submit(reactionTask(fx.partyroomId(), reactor, ReactionType.LIKE, start, done, err));
            pool.submit(reactionTask(fx.partyroomId(), reactor, ReactionType.DISLIKE, start, done, err));
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        PlaybackAggregationData agg = newTx().execute(s ->
                aggregationRepository.findById(fx.playbackId()).orElseThrow());

        // 핵심 잠금: 어떤 인터리빙에서도 카운터는 절대 음수가 될 수 없다.
        assertThat(agg.getLikeCount())
                .as("토글 race 에서 like_count 음수 금지 (floor 가드)")
                .isGreaterThanOrEqualTo(0);
        assertThat(agg.getDislikeCount())
                .as("토글 race 에서 dislike_count 음수 금지 (floor 가드)")
                .isGreaterThanOrEqualTo(0);
        assertThat(agg.getGrabCount())
                .as("토글 race 에서 grab_count 음수 금지 (floor 가드)")
                .isGreaterThanOrEqualTo(0);

        // 직렬화(락) 후 최종 history 와 카운터 합계가 일관 — like/dislike 합은 0 또는 1
        PlaybackReactionHistoryData history =
                historyRepository.findByPlaybackIdAndUserId(fx.playbackId(), reactor).orElseThrow();
        int expectedLike = history.isLiked() ? 1 : 0;
        int expectedDislike = history.isDisliked() ? 1 : 0;
        assertThat(agg.getLikeCount())
                .as("최종 like_count 가 최종 history.liked 와 일치 (단일 유저이므로 0 또는 1)")
                .isEqualTo(expectedLike);
        assertThat(agg.getDislikeCount())
                .as("최종 dislike_count 가 최종 history.disliked 와 일치")
                .isEqualTo(expectedDislike);
        assertThat(List.of(expectedLike, expectedDislike))
                .as("단일 유저: like/dislike 동시 true 불가")
                .containsAnyOf(0);
    }
}
