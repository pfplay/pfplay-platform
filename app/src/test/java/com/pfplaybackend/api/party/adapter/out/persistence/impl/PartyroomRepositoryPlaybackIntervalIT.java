package com.pfplaybackend.api.party.adapter.out.persistence.impl;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findPlaybackForInterval IT — Testcontainers MySQL.
 *
 * 어드민 파티룸 행동분석: "음악 재생 구간" 재구성을 위한 playback row 조회.
 * 반환 계약: created_at ∈ [from, now) 인 모든 row + from 직전 최신 1건(straddle),
 *           created_at ASC 정렬.
 *
 * created_at 은 JPA auditing 으로 자동 채워지므로 특정 시각 seed 를 위해
 * native INSERT 로 created_at 을 명시. cleanup 은 AfterEach native DELETE.
 * 충돌 회피 위해 test-only high range(990000+) id 사용.
 */
class PartyroomRepositoryPlaybackIntervalIT extends AbstractIntegrationTest {

    private static final long ROOM_P = 990001L;
    private static final long ROOM_OTHER = 990002L;
    private static final long USER = 990100L;

    @Autowired PartyroomRepository partyroomRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager entityManager;

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "DELETE FROM playback WHERE partyroom_id IN (:ids)")
                        .setParameter("ids", List.of(ROOM_P, ROOM_OTHER))
                        .executeUpdate());
    }

    private void seedPlayback(long partyroomId, LocalDateTime createdAt, String name) {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "INSERT INTO playback (partyroom_id, user_id, name, end_time, created_at) " +
                                        "VALUES (:pid, :uid, :name, :endTime, :createdAt)")
                        .setParameter("pid", partyroomId)
                        .setParameter("uid", USER)
                        .setParameter("name", name)
                        .setParameter("endTime", 0L)
                        .setParameter("createdAt", createdAt)
                        .executeUpdate());
    }

    @Test
    @DisplayName("윈도우 내 트랙과 straddle 1건을 created_at asc 로 반환, 다른 룸 제외")
    void 윈도우_내_트랙과_straddle_1건을_created_at_asc로_반환() {
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 12, 0, 0);
        LocalDateTime straddle = base.minusMinutes(10);
        LocalDateTime in1 = base.plusMinutes(10);
        LocalDateTime in2 = base.plusMinutes(30);

        seedPlayback(ROOM_P, straddle, "straddle");
        seedPlayback(ROOM_P, in1, "in1");
        seedPlayback(ROOM_P, in2, "in2");
        seedPlayback(ROOM_OTHER, base.plusMinutes(5), "otherRoom");

        List<PlaybackData> result = partyroomRepository.findPlaybackForInterval(
                new PartyroomId(ROOM_P), base, base.plusHours(2));

        assertThat(result).hasSize(3);
        assertThat(result).extracting(p -> p.getCreatedAt().truncatedTo(ChronoUnit.SECONDS))
                .containsExactly(
                        straddle.truncatedTo(ChronoUnit.SECONDS),
                        in1.truncatedTo(ChronoUnit.SECONDS),
                        in2.truncatedTo(ChronoUnit.SECONDS));
        assertThat(result).extracting(PlaybackData::getName)
                .containsExactly("straddle", "in1", "in2");
    }

    @Test
    @DisplayName("straddle 후보가 여러개면 from 직전 최신 1건만")
    void straddle_후보가_여러개면_from_직전_최신_1건만() {
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 12, 0, 0);
        LocalDateTime older = base.minusMinutes(30);
        LocalDateTime straddle = base.minusMinutes(10);
        LocalDateTime in1 = base.plusMinutes(10);

        seedPlayback(ROOM_P, older, "older");
        seedPlayback(ROOM_P, straddle, "straddle");
        seedPlayback(ROOM_P, in1, "in1");

        List<PlaybackData> result = partyroomRepository.findPlaybackForInterval(
                new PartyroomId(ROOM_P), base, base.plusHours(2));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(p -> p.getCreatedAt().truncatedTo(ChronoUnit.SECONDS))
                .containsExactly(
                        straddle.truncatedTo(ChronoUnit.SECONDS),
                        in1.truncatedTo(ChronoUnit.SECONDS));
        assertThat(result).extracting(PlaybackData::getName)
                .containsExactly("straddle", "in1");
    }
}
