package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.assertj.core.api.Assertions.*;

class SystemAnnouncementDataTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private static final ZoneId Z = ZoneId.of("Asia/Seoul");

    private Clock clockAt(LocalDateTime t) {
        return Clock.fixed(t.atZone(Z).toInstant(), Z);
    }

    /** Common clock used by the original create/cancel/phaseActive tests */
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.now(fixedClock);

    private SystemAnnouncementData activeMaintenance(LocalDateTime start, LocalDateTime end, Clock startedClock) {
        SystemAnnouncementData e = SystemAnnouncementData.create(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검", "maint", "본문", "body",
                start, end, null, start.minusHours(1), 1L, false);
        e.markMaintenanceStarted(startedClock);
        return e;
    }

    // ── original tests (Task 1 coverage) ─────────────────────────────────────

    @Test
    @DisplayName("create EVENT — 스케줄 NULL 강제")
    void createEvent() {
        SystemAnnouncementData a = SystemAnnouncementData.create(
            AnnouncementType.EVENT, AnnouncementSeverity.INFO,
            "이벤트", "Event", "본문", "Body", null, null, now.plusDays(1), now, 1L, false);
        assertThat(a.getScheduledStartAt()).isNull();
    }

    @Test
    @DisplayName("create MAINTENANCE_NOTICE — 스케줄 필수 (BadRequest ANN-003)")
    void maintenanceRequiresSchedule() {
        assertThatThrownBy(() -> SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", null, null, null, now, 1L, false))
            .isInstanceOf(BadRequestException.class)
            .hasFieldOrPropertyWithValue("errorCode", "ANN-003");
    }

    @Test
    @DisplayName("create MAINTENANCE_NOTICE — end <= start 거부 (ANN-004)")
    void invertedWindow() {
        LocalDateTime s = now.plusHours(1);
        assertThatThrownBy(() -> SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", s, s, null, now, 1L, false))
            .isInstanceOf(BadRequestException.class)
            .hasFieldOrPropertyWithValue("errorCode", "ANN-004");
    }

    @Test
    @DisplayName("create MAINTENANCE_NOTICE — expires_at NULL 강제")
    void maintenanceRejectsExpiresAt() {
        LocalDateTime s = now.plusHours(1), e = s.plusHours(1);
        assertThatThrownBy(() -> SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", s, e, now.plusDays(1), now, 1L, false))
            .isInstanceOf(BadRequestException.class)
            .hasFieldOrPropertyWithValue("errorCode", "ANN-003");
    }

    @Test
    @DisplayName("markMaintenanceStarted — type guard")
    void markStartedTypeGuard() {
        SystemAnnouncementData event = SystemAnnouncementData.create(
            AnnouncementType.EVENT, AnnouncementSeverity.INFO,
            "이벤트", "E", "본문", "B", null, null, null, now, 1L, false);
        assertThatThrownBy(() -> event.markMaintenanceStarted(fixedClock))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("cancel — already cancelled CONFLICT")
    void cancelIdempotent() {
        SystemAnnouncementData a = SystemAnnouncementData.create(
            AnnouncementType.EVENT, AnnouncementSeverity.INFO,
            "이벤트", "E", "본문", "B", null, null, null, now, 1L, false);
        a.cancel(2L, fixedClock);
        assertThatThrownBy(() -> a.cancel(3L, fixedClock))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("errorCode", "ANN-002");
    }

    @Test
    @DisplayName("isMaintenancePhaseActive — started + not cancelled")
    void phaseActive() {
        LocalDateTime s = now.plusHours(1), e = s.plusHours(1);
        SystemAnnouncementData a = SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", s, e, null, now, 1L, false);
        assertThat(a.isMaintenancePhaseActive()).isFalse();
        a.markMaintenanceStarted(fixedClock);
        assertThat(a.isMaintenancePhaseActive()).isTrue();
        a.cancel(2L, fixedClock);
        assertThat(a.isMaintenancePhaseActive()).isFalse();
    }

    // ── Task 3: markCompleted / adjustScheduledEndTime ────────────────────────

    @Test
    @DisplayName("markCompleted — ACTIVE면 completedAt set")
    void markCompleted_active_setsCompletedAt() {
        LocalDateTime start = LocalDateTime.of(2026, 5, 4, 3, 0);
        LocalDateTime end = LocalDateTime.of(2026, 5, 4, 4, 0);
        SystemAnnouncementData e = activeMaintenance(start, end, clockAt(start));

        e.markCompleted(clockAt(end));

        assertThat(e.getCompletedAt()).isEqualTo(end);
        assertThat(e.isMaintenancePhaseActive()).isFalse();
    }

    @Test
    @DisplayName("markCompleted — 이미 completed면 ANN-008(Conflict)")
    void markCompleted_alreadyCompleted_throwsConflict() {
        LocalDateTime start = LocalDateTime.of(2026, 5, 4, 3, 0);
        SystemAnnouncementData e = activeMaintenance(start, start.plusHours(1), clockAt(start));
        e.markCompleted(clockAt(start.plusMinutes(30)));

        assertThatThrownBy(() -> e.markCompleted(clockAt(start.plusMinutes(40))))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ANN-008");
    }

    @Test
    @DisplayName("markCompleted — PLANNED(미시작)면 ANN-007(Conflict)")
    void markCompleted_planned_throwsConflict() {
        SystemAnnouncementData e = SystemAnnouncementData.create(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검", "m", "b", "b",
                LocalDateTime.of(2026, 5, 4, 3, 0), LocalDateTime.of(2026, 5, 4, 4, 0),
                null, LocalDateTime.of(2026, 5, 4, 2, 0), 1L, false);

        assertThatThrownBy(() -> e.markCompleted(clockAt(LocalDateTime.of(2026, 5, 4, 2, 30))))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ANN-007");
    }

    @Test
    @DisplayName("adjustScheduledEndTime — ACTIVE & newEnd>now면 갱신")
    void adjust_active_futureEnd_updates() {
        LocalDateTime start = LocalDateTime.of(2026, 5, 4, 3, 0);
        SystemAnnouncementData e = activeMaintenance(start, start.plusHours(1), clockAt(start));
        LocalDateTime now = start.plusMinutes(50);
        LocalDateTime newEnd = start.plusHours(2);

        e.adjustScheduledEndTime(newEnd, clockAt(now));

        assertThat(e.getScheduledEndAt()).isEqualTo(newEnd);
    }

    @Test
    @DisplayName("adjustScheduledEndTime — newEnd<=now면 ANN-006(BadRequest)")
    void adjust_pastEnd_throwsBadRequest() {
        LocalDateTime start = LocalDateTime.of(2026, 5, 4, 3, 0);
        SystemAnnouncementData e = activeMaintenance(start, start.plusHours(1), clockAt(start));
        LocalDateTime now = start.plusMinutes(50);

        assertThatThrownBy(() -> e.adjustScheduledEndTime(now, clockAt(now)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ANN-006");
    }

    @Test
    @DisplayName("adjustScheduledEndTime — PLANNED면 ANN-007(Conflict)")
    void adjust_planned_throwsConflict() {
        SystemAnnouncementData e = SystemAnnouncementData.create(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검", "m", "b", "b",
                LocalDateTime.of(2026, 5, 4, 3, 0), LocalDateTime.of(2026, 5, 4, 4, 0),
                null, LocalDateTime.of(2026, 5, 4, 2, 0), 1L, false);

        assertThatThrownBy(() -> e.adjustScheduledEndTime(
                LocalDateTime.of(2026, 5, 4, 5, 0), clockAt(LocalDateTime.of(2026, 5, 4, 2, 30))))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ANN-007");
    }
}
