package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogAnalyticsRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.application.dto.DailyAttendanceBucket;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserActivityLogAnalyticsRepository IT — Testcontainers MySQL, V34 인덱스 자동 적용.
 *
 * 시나리오(test room P = 990001):
 * - D-1(2026-06-20): ENTERED user 10/11/10 (3건), EXITED 2건
 * - D-0(2026-06-21): ENTERED user 12 (1건), EXITED 1건
 * - 격리 검증: 다른 룸(990002) ENTERED/EXITED, 같은 룸의 SIGNED_IN 은 집계 제외되어야 함.
 *
 * cleanup: AfterEach native DELETE (테스트 전용 고번호 partyroom id).
 */
class UserActivityLogAnalyticsRepositoryImplIT extends AbstractIntegrationTest {

    private static final long ROOM_P = 990001L;
    private static final long ROOM_OTHER = 990002L;

    private static final LocalDate D_MINUS_1 = LocalDate.of(2026, 6, 20);
    private static final LocalDate D_ZERO = LocalDate.of(2026, 6, 21);

    private static final LocalDateTime FROM = D_MINUS_1.atStartOfDay();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 22, 0, 0);

    @Autowired UserActivityLogAnalyticsRepository analyticsRepository;
    @Autowired UserActivityLogRepository userActivityLogRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager entityManager;

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "DELETE FROM user_activity_log WHERE partyroom_id IN (:ids)")
                        .setParameter("ids", List.of(ROOM_P, ROOM_OTHER))
                        .executeUpdate());
    }

    private void seed(long userAccountId, UserActivityEventType eventType, Long partyroomId,
                      LocalDateTime occurredAt) {
        transactionTemplate.executeWithoutResult(status ->
                userActivityLogRepository.saveAndFlush(UserActivityLogData.of(
                        userAccountId, eventType, partyroomId, JsonMetadata.empty(), occurredAt)));
    }

    private void seedScenario() {
        // D-1: ENTERED 10/11/10, EXITED 10/11
        seed(10L, UserActivityEventType.PARTYROOM_ENTERED, ROOM_P, LocalDateTime.of(2026, 6, 20, 10, 0));
        seed(11L, UserActivityEventType.PARTYROOM_ENTERED, ROOM_P, LocalDateTime.of(2026, 6, 20, 11, 0));
        seed(10L, UserActivityEventType.PARTYROOM_ENTERED, ROOM_P, LocalDateTime.of(2026, 6, 20, 12, 0));
        seed(10L, UserActivityEventType.PARTYROOM_EXITED, ROOM_P, LocalDateTime.of(2026, 6, 20, 13, 0));
        seed(11L, UserActivityEventType.PARTYROOM_EXITED, ROOM_P, LocalDateTime.of(2026, 6, 20, 14, 0));
        // D-0: ENTERED 12, EXITED 12
        seed(12L, UserActivityEventType.PARTYROOM_ENTERED, ROOM_P, LocalDateTime.of(2026, 6, 21, 9, 0));
        seed(12L, UserActivityEventType.PARTYROOM_EXITED, ROOM_P, LocalDateTime.of(2026, 6, 21, 10, 0));
        // 격리: 같은 룸 SIGNED_IN (집계/방문/퇴장 모두 제외)
        seed(10L, UserActivityEventType.SIGNED_IN, ROOM_P, LocalDateTime.of(2026, 6, 20, 15, 0));
        // 격리: 다른 룸 ENTERED/EXITED
        seed(20L, UserActivityEventType.PARTYROOM_ENTERED, ROOM_OTHER, LocalDateTime.of(2026, 6, 20, 10, 0));
        seed(20L, UserActivityEventType.PARTYROOM_EXITED, ROOM_OTHER, LocalDateTime.of(2026, 6, 20, 11, 0));
    }

    @Test
    @DisplayName("findDailyAttendance — 발생일만 asc, 일자별 entered/exited 집계")
    void findDailyAttendance() {
        seedScenario();

        List<DailyAttendanceBucket> buckets = analyticsRepository.findDailyAttendance(ROOM_P, FROM, NOW);

        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(0).date()).isEqualTo(D_MINUS_1);
        assertThat(buckets.get(0).entered()).isEqualTo(3);
        assertThat(buckets.get(0).exited()).isEqualTo(2);
        assertThat(buckets.get(1).date()).isEqualTo(D_ZERO);
        assertThat(buckets.get(1).entered()).isEqualTo(1);
        assertThat(buckets.get(1).exited()).isEqualTo(1);
    }

    @Test
    @DisplayName("countUniqueVisitors — ENTERED distinct user (10,11,12) = 3")
    void countUniqueVisitors() {
        seedScenario();

        long unique = analyticsRepository.countUniqueVisitors(ROOM_P, FROM, NOW);

        assertThat(unique).isEqualTo(3L);
    }

    @Test
    @DisplayName("findExitOccurredAt — 윈도우 내 EXITED 3건, 타룸/타이벤트 제외")
    void findExitOccurredAt() {
        seedScenario();

        List<LocalDateTime> exits = analyticsRepository.findExitOccurredAt(ROOM_P, FROM, NOW);

        assertThat(exits).hasSize(3);
    }
}
