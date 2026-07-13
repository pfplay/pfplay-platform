package com.pfplaybackend.api.virtualcrew.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackAggregationRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackReactionHistoryRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackRepository;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.history.PlaybackReactionHistoryData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.virtualcrew.application.dto.LinkReactionScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReactionScoreQueryRepositoryImpl 통합 테스트.
 *
 * <p>픽스처: bot=7L, room=99L plays (linkA×2, linkB×1),
 * 데코이 play: 다른 user(8L)/같은 방 + 같은 bot(7L)/다른 방(100L).
 */
@Transactional
class ReactionScoreQueryRepositoryIT extends AbstractIntegrationTest {

    @Autowired private ReactionScoreQueryRepository reactionScoreQueryRepository;
    @Autowired private PlaybackRepository playbackRepository;
    @Autowired private PlaybackAggregationRepository playbackAggregationRepository;
    @Autowired private PlaybackReactionHistoryRepository playbackReactionHistoryRepository;

    private static final long BOT_UID   = 7L;
    private static final long DECOY_UID = 8L;
    private static final long ROOM_ID   = 99L;
    private static final long OTHER_ROOM_ID = 100L;

    // linkA: 2 plays → like총3, dislike총1, grab총1
    private long playA1Id;
    private long playA2Id;
    // linkB: 1 play → dislike총2
    private long playB1Id;
    // 데코이 playback ids
    private long decoyUserPlayId;
    private long decoyRoomPlayId;

    // reaction_history row 수 (bot7/room99 한정)
    private int historyRowCount;

    @BeforeEach
    void setUp() {
        // ── bot 7, room 99 ──────────────────────────────────────────────
        // linkA play #1
        PlaybackData a1 = playbackRepository.saveAndFlush(
                PlaybackData.builder()
                        .partyroomId(new PartyroomId(ROOM_ID))
                        .userId(new UserId(BOT_UID))
                        .linkId("linkA")
                        .name("Song A1")
                        .build());
        playA1Id = a1.getId();

        // linkA play #2
        PlaybackData a2 = playbackRepository.saveAndFlush(
                PlaybackData.builder()
                        .partyroomId(new PartyroomId(ROOM_ID))
                        .userId(new UserId(BOT_UID))
                        .linkId("linkA")
                        .name("Song A2")
                        .build());
        playA2Id = a2.getId();

        // linkB play #1
        PlaybackData b1 = playbackRepository.saveAndFlush(
                PlaybackData.builder()
                        .partyroomId(new PartyroomId(ROOM_ID))
                        .userId(new UserId(BOT_UID))
                        .linkId("linkB")
                        .name("Song B1")
                        .build());
        playB1Id = b1.getId();

        // ── 데코이: 다른 user, 같은 방 ──────────────────────────────────
        PlaybackData decoyUser = playbackRepository.saveAndFlush(
                PlaybackData.builder()
                        .partyroomId(new PartyroomId(ROOM_ID))
                        .userId(new UserId(DECOY_UID))
                        .linkId("linkDecoy")
                        .name("Decoy User Song")
                        .build());
        decoyUserPlayId = decoyUser.getId();

        // ── 데코이: 같은 bot, 다른 방 ───────────────────────────────────
        PlaybackData decoyRoom = playbackRepository.saveAndFlush(
                PlaybackData.builder()
                        .partyroomId(new PartyroomId(OTHER_ROOM_ID))
                        .userId(new UserId(BOT_UID))
                        .linkId("linkOtherRoom")
                        .name("Other Room Song")
                        .build());
        decoyRoomPlayId = decoyRoom.getId();

        // ── aggregation rows ─────────────────────────────────────────────
        // linkA play1: like2, dislike1, grab1
        playbackAggregationRepository.saveAndFlush(PlaybackAggregationData.createFor(new PlaybackId(playA1Id)));
        playbackAggregationRepository.applyAggregationDelta(new PlaybackId(playA1Id), 2, 1, 1);

        // linkA play2: like1, dislike0, grab0
        playbackAggregationRepository.saveAndFlush(PlaybackAggregationData.createFor(new PlaybackId(playA2Id)));
        playbackAggregationRepository.applyAggregationDelta(new PlaybackId(playA2Id), 1, 0, 0);

        // linkB play1: like0, dislike2, grab0
        playbackAggregationRepository.saveAndFlush(PlaybackAggregationData.createFor(new PlaybackId(playB1Id)));
        playbackAggregationRepository.applyAggregationDelta(new PlaybackId(playB1Id), 0, 2, 0);

        // 데코이 aggregation (이것들은 aggregateByLink 결과에 나오면 안 됨)
        playbackAggregationRepository.saveAndFlush(PlaybackAggregationData.createFor(new PlaybackId(decoyUserPlayId)));
        playbackAggregationRepository.applyAggregationDelta(new PlaybackId(decoyUserPlayId), 10, 0, 0);

        playbackAggregationRepository.saveAndFlush(PlaybackAggregationData.createFor(new PlaybackId(decoyRoomPlayId)));
        playbackAggregationRepository.applyAggregationDelta(new PlaybackId(decoyRoomPlayId), 10, 0, 0);

        // ── reaction_history rows (bot7/room99 plays 에 달린 반응) ───────
        // play A1 에 user1, user2 반응
        playbackReactionHistoryRepository.saveAndFlush(
                new PlaybackReactionHistoryData(new UserId(1001L), new PlaybackId(playA1Id)));
        playbackReactionHistoryRepository.saveAndFlush(
                new PlaybackReactionHistoryData(new UserId(1002L), new PlaybackId(playA1Id)));
        // play B1 에 user3 반응
        playbackReactionHistoryRepository.saveAndFlush(
                new PlaybackReactionHistoryData(new UserId(1003L), new PlaybackId(playB1Id)));

        historyRowCount = 3;

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("countReactionsSince — 과거 watermark: bot7/room99 의 history row 모두 포함")
    void countReactionsSince_pastWatermark_returnsAll() {
        long count = reactionScoreQueryRepository.countReactionsSince(
                List.of(BOT_UID), ROOM_ID, LocalDateTime.now().minusHours(1));
        assertThat(count).isEqualTo(historyRowCount);
    }

    @Test
    @DisplayName("countReactionsSince — 미래 watermark: 아무것도 포함 안 됨")
    void countReactionsSince_futureWatermark_returnsZero() {
        long count = reactionScoreQueryRepository.countReactionsSince(
                List.of(BOT_UID), ROOM_ID, LocalDateTime.now().plusHours(1));
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("countReactionsSince — 빈 botUserIds: 즉시 0 반환")
    void countReactionsSince_emptyBots_returnsZero() {
        long count = reactionScoreQueryRepository.countReactionsSince(
                List.of(), ROOM_ID, null);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("aggregateByLink — linkA(like3/dislike1/grab1), linkB(like0/dislike2/grab0), 데코이 제외")
    void aggregateByLink_correctSumsAndNoDecoy() {
        List<LinkReactionScore> scores = reactionScoreQueryRepository.aggregateByLink(BOT_UID, ROOM_ID);

        assertThat(scores).extracting(LinkReactionScore::linkId)
                .containsExactlyInAnyOrder("linkA", "linkB");

        Map<String, LinkReactionScore> byLink = scores.stream()
                .collect(Collectors.toMap(LinkReactionScore::linkId, s -> s));

        LinkReactionScore a = byLink.get("linkA");
        assertThat(a.likeSum()).isEqualTo(3L);
        assertThat(a.dislikeSum()).isEqualTo(1L);
        assertThat(a.grabSum()).isEqualTo(1L);

        LinkReactionScore b = byLink.get("linkB");
        assertThat(b.likeSum()).isEqualTo(0L);
        assertThat(b.dislikeSum()).isEqualTo(2L);
        assertThat(b.grabSum()).isEqualTo(0L);

        // 데코이 linkId 가 결과에 없어야 함
        assertThat(byLink).doesNotContainKey("linkDecoy");
        assertThat(byLink).doesNotContainKey("linkOtherRoom");
    }
}
