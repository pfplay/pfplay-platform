package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.application.dto.DailyAttendanceBucket;
import com.pfplaybackend.api.administration.application.dto.DailyUniqueVisitorsBucket;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #361 전역 입퇴장 집계 IT — 일자 버킷 · 봇 제외(is_dummy 조인) · distinct 순방문자를
 * 실 MySQL 로 잠근다. (활성 방 수 스냅샷은 party 시드가 무거워 서비스/기존 IT 관할)
 */
class AdminGlobalAnalyticsRepositoryIT extends AbstractIntegrationTest {

    private static final long ROOM = 991001L;
    private static final long HUMAN_A = 991101L;
    private static final long HUMAN_B = 991102L;
    private static final long BOT     = 991103L;

    private static final LocalDate DAY1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate DAY2 = LocalDate.of(2026, 7, 2);
    private static final LocalDateTime FROM = DAY1.atStartOfDay();
    private static final LocalDateTime NOW = LocalDate.of(2026, 7, 3).atStartOfDay();

    @Autowired AdminGlobalAnalyticsRepository repository;
    @Autowired UserActivityLogRepository userActivityLogRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void seedAll() {
        userAccountRepository.save(UserAccountData.createForLocalWithMandatoryChange(
                new UserId(HUMAN_A), "ga-human-a@it.local", "h"));
        userAccountRepository.save(UserAccountData.createForLocalWithMandatoryChange(
                new UserId(HUMAN_B), "ga-human-b@it.local", "h"));
        UserAccountData bot = UserAccountData.createForLocalWithMandatoryChange(
                new UserId(BOT), "ga-bot@it.local", "h");
        bot.markAsDummy();
        userAccountRepository.save(bot);

        // DAY1: humanA 입장×2(같은 유저 재입장)·퇴장×1, humanB 입장×1, bot 입장×1
        seed(HUMAN_A, UserActivityEventType.PARTYROOM_ENTERED, DAY1.atTime(10, 0));
        seed(HUMAN_A, UserActivityEventType.PARTYROOM_ENTERED, DAY1.atTime(12, 0));
        seed(HUMAN_A, UserActivityEventType.PARTYROOM_EXITED, DAY1.atTime(13, 0));
        seed(HUMAN_B, UserActivityEventType.PARTYROOM_ENTERED, DAY1.atTime(14, 0));
        seed(BOT, UserActivityEventType.PARTYROOM_ENTERED, DAY1.atTime(15, 0));
        // DAY2: bot 만 활동 (봇 제외 시 이 날짜 버킷 자체가 없어야 함)
        seed(BOT, UserActivityEventType.PARTYROOM_ENTERED, DAY2.atTime(10, 0));
        seed(BOT, UserActivityEventType.PARTYROOM_EXITED, DAY2.atTime(11, 0));
    }

    private void seed(long uid, UserActivityEventType type, LocalDateTime at) {
        userActivityLogRepository.saveAndFlush(UserActivityLogData.of(uid, type, ROOM, null, at));
    }

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM user_activity_log WHERE partyroom_id = :r")
                    .setParameter("r", ROOM).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM user_account WHERE user_id IN (:ids)")
                    .setParameter("ids", List.of(HUMAN_A, HUMAN_B, BOT)).executeUpdate();
        });
    }

    @Test
    @DisplayName("excludeBots=true — 봇 이벤트 제외: DAY1 entered=3/exited=1, DAY2 버킷 없음")
    void daily_attendance_excludes_bots() {
        List<DailyAttendanceBucket> daily = repository.findDailyAttendance(FROM, NOW, true);

        assertThat(daily).hasSize(1);
        assertThat(daily.get(0).date()).isEqualTo(DAY1);
        assertThat(daily.get(0).entered()).isEqualTo(3); // humanA×2 + humanB×1
        assertThat(daily.get(0).exited()).isEqualTo(1);
    }

    @Test
    @DisplayName("excludeBots=false — 봇 포함: DAY1 entered=4, DAY2 entered=1/exited=1")
    void daily_attendance_includes_bots() {
        List<DailyAttendanceBucket> daily = repository.findDailyAttendance(FROM, NOW, false);

        assertThat(daily).hasSize(2);
        assertThat(daily.get(0).date()).isEqualTo(DAY1);
        assertThat(daily.get(0).entered()).isEqualTo(4);
        assertThat(daily.get(1).date()).isEqualTo(DAY2);
        assertThat(daily.get(1).entered()).isEqualTo(1);
        assertThat(daily.get(1).exited()).isEqualTo(1);
    }

    @Test
    @DisplayName("일별 순 방문자 — 재입장 dedup: DAY1 unique=2(humanA,humanB), 봇 제외")
    void daily_unique_visitors_dedups_and_excludes_bots() {
        List<DailyUniqueVisitorsBucket> daily = repository.findDailyUniqueVisitors(FROM, NOW, true);

        assertThat(daily).hasSize(1);
        assertThat(daily.get(0).date()).isEqualTo(DAY1);
        assertThat(daily.get(0).uniqueVisitors()).isEqualTo(2); // humanA(재입장 1로 dedup) + humanB
    }

    @Test
    @DisplayName("윈도우 전체 순 방문자 — excludeBots=true → 2, false → 3")
    void total_unique_visitors() {
        assertThat(repository.countUniqueVisitors(FROM, NOW, true)).isEqualTo(2L);
        assertThat(repository.countUniqueVisitors(FROM, NOW, false)).isEqualTo(3L);
    }

    @Test
    @DisplayName("윈도우 밖 이벤트는 미집계")
    void window_bounds_respected() {
        seed(HUMAN_A, UserActivityEventType.PARTYROOM_ENTERED, NOW.plusHours(1)); // 윈도우 밖

        List<DailyAttendanceBucket> daily = repository.findDailyAttendance(FROM, NOW, true);
        assertThat(daily).hasSize(1); // DAY1 만 — NOW 이후는 미포함
    }
}
