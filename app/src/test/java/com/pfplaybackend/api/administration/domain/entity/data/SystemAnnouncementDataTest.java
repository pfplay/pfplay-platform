package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.administration.domain.value.*;
import com.pfplaybackend.api.common.exception.http.*;
import org.junit.jupiter.api.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class SystemAnnouncementDataTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.now(clock);

    @Test
    @DisplayName("create EVENT — 스케줄 NULL 강제")
    void createEvent() {
        SystemAnnouncementData a = SystemAnnouncementData.create(
            AnnouncementType.EVENT, AnnouncementSeverity.INFO,
            "이벤트", "Event", "본문", "Body", null, null, now.plusDays(1), now, 1L);
        assertThat(a.getScheduledStartAt()).isNull();
    }

    @Test
    @DisplayName("create MAINTENANCE_NOTICE — 스케줄 필수 (BadRequest ANN-003)")
    void maintenanceRequiresSchedule() {
        assertThatThrownBy(() -> SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", null, null, null, now, 1L))
            .isInstanceOf(BadRequestException.class)
            .hasFieldOrPropertyWithValue("errorCode", "ANN-003");
    }

    @Test
    @DisplayName("create MAINTENANCE_NOTICE — end <= start 거부 (ANN-004)")
    void invertedWindow() {
        LocalDateTime s = now.plusHours(1);
        assertThatThrownBy(() -> SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", s, s, null, now, 1L))
            .isInstanceOf(BadRequestException.class)
            .hasFieldOrPropertyWithValue("errorCode", "ANN-004");
    }

    @Test
    @DisplayName("create MAINTENANCE_NOTICE — expires_at NULL 강제")
    void maintenanceRejectsExpiresAt() {
        LocalDateTime s = now.plusHours(1), e = s.plusHours(1);
        assertThatThrownBy(() -> SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", s, e, now.plusDays(1), now, 1L))
            .isInstanceOf(BadRequestException.class)
            .hasFieldOrPropertyWithValue("errorCode", "ANN-003");
    }

    @Test
    @DisplayName("markMaintenanceStarted — type guard")
    void markStartedTypeGuard() {
        SystemAnnouncementData event = SystemAnnouncementData.create(
            AnnouncementType.EVENT, AnnouncementSeverity.INFO,
            "이벤트", "E", "본문", "B", null, null, null, now, 1L);
        assertThatThrownBy(() -> event.markMaintenanceStarted(clock))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("cancel — already cancelled CONFLICT")
    void cancelIdempotent() {
        SystemAnnouncementData a = SystemAnnouncementData.create(
            AnnouncementType.EVENT, AnnouncementSeverity.INFO,
            "이벤트", "E", "본문", "B", null, null, null, now, 1L);
        a.cancel(2L, clock);
        assertThatThrownBy(() -> a.cancel(3L, clock))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("errorCode", "ANN-002");
    }

    @Test
    @DisplayName("isMaintenancePhaseActive — started + not cancelled")
    void phaseActive() {
        LocalDateTime s = now.plusHours(1), e = s.plusHours(1);
        SystemAnnouncementData a = SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", s, e, null, now, 1L);
        assertThat(a.isMaintenancePhaseActive()).isFalse();
        a.markMaintenanceStarted(clock);
        assertThat(a.isMaintenancePhaseActive()).isTrue();
        a.cancel(2L, clock);
        assertThat(a.isMaintenancePhaseActive()).isFalse();
    }
}
