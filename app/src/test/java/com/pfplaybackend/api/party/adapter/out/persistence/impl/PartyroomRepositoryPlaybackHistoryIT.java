package com.pfplaybackend.api.party.adapter.out.persistence.impl;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findPlaybackHistory IT — Testcontainers MySQL.
 *
 * 어드민 파티룸 행동분석: "디제잉 이력" 페이지네이션 조회.
 * 반환 계약: 해당 파티룸의 playback row 를 created_at DESC 로 고정 정렬, Pageable 페이지네이션.
 *
 * created_at 은 JPA auditing 으로 자동 채워지므로 특정 시각 seed 를 위해
 * native INSERT 로 created_at 을 명시. cleanup 은 AfterEach native DELETE.
 * 충돌 회피 위해 test-only high range(990000+) id 사용.
 */
class PartyroomRepositoryPlaybackHistoryIT extends AbstractIntegrationTest {

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
    @DisplayName("첫 페이지: totalElements=5, content 2건, created_at desc, 다른 룸 제외")
    void 첫_페이지는_created_at_desc로_페이지네이션_다른_룸_제외() {
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 12, 0, 0);
        // 오래된 → 최신 순서로 seed (조회 결과는 desc 이므로 역순이어야 함)
        seedPlayback(ROOM_P, base.plusMinutes(0), "t0");
        seedPlayback(ROOM_P, base.plusMinutes(10), "t1");
        seedPlayback(ROOM_P, base.plusMinutes(20), "t2");
        seedPlayback(ROOM_P, base.plusMinutes(30), "t3");
        seedPlayback(ROOM_P, base.plusMinutes(40), "t4");
        seedPlayback(ROOM_OTHER, base.plusMinutes(25), "otherRoom");

        Page<PlaybackData> page = partyroomRepository.findPlaybackHistory(
                new PartyroomId(ROOM_P), PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).hasSize(2);
        // created_at DESC: 가장 최신 t4, 그다음 t3
        assertThat(page.getContent()).extracting(PlaybackData::getName)
                .containsExactly("t4", "t3");
        // 첫 원소 created_at >= 두 번째 원소 created_at
        assertThat(page.getContent().get(0).getCreatedAt())
                .isAfterOrEqualTo(page.getContent().get(1).getCreatedAt());
        // 다른 룸 제외
        assertThat(page.getContent()).extracting(PlaybackData::getName)
                .doesNotContain("otherRoom");
    }

    @Test
    @DisplayName("마지막 페이지: page index 2, size 2 → 1건")
    void 마지막_페이지는_1건() {
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 12, 0, 0);
        seedPlayback(ROOM_P, base.plusMinutes(0), "t0");
        seedPlayback(ROOM_P, base.plusMinutes(10), "t1");
        seedPlayback(ROOM_P, base.plusMinutes(20), "t2");
        seedPlayback(ROOM_P, base.plusMinutes(30), "t3");
        seedPlayback(ROOM_P, base.plusMinutes(40), "t4");

        Page<PlaybackData> page = partyroomRepository.findPlaybackHistory(
                new PartyroomId(ROOM_P), PageRequest.of(2, 2));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).hasSize(1);
        // desc 정렬 3페이지(index 2)의 유일 원소 = 가장 오래된 t0
        assertThat(page.getContent()).extracting(PlaybackData::getName)
                .containsExactly("t0");
    }
}
