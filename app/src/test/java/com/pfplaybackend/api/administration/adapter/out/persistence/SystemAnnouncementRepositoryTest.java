package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class SystemAnnouncementRepositoryTest extends AbstractIntegrationTest {

    @Autowired SystemAnnouncementRepository repository;

    private static final ZoneId Z = ZoneId.of("Asia/Seoul");
    private Clock clockAt(LocalDateTime t) { return Clock.fixed(t.atZone(Z).toInstant(), Z); }

    private SystemAnnouncementData maintenance(LocalDateTime start, LocalDateTime end) {
        return SystemAnnouncementData.create(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검", "m", "b", "b", start, end, null, start.minusHours(1), 1L);
    }

    @Test
    @DisplayName("findDueForMaintenanceCompletion — ACTIVE & end<=now 만 반환")
    void completion_returnsActiveExpiredOnly() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 4, 4, 0);

        SystemAnnouncementData due = maintenance(now.minusHours(1), now.minusMinutes(1));
        due.markMaintenanceStarted(clockAt(now.minusHours(1)));
        SystemAnnouncementData notExpired = maintenance(now.minusHours(1), now.plusHours(1));
        notExpired.markMaintenanceStarted(clockAt(now.minusHours(1)));
        SystemAnnouncementData notStarted = maintenance(now.plusHours(1), now.plusHours(2));
        repository.saveAll(List.of(due, notExpired, notStarted));
        flushAndClear();

        List<SystemAnnouncementData> result = repository.findDueForMaintenanceCompletion(now);

        assertThat(result).extracting(SystemAnnouncementData::getId).containsExactly(due.getId());
    }

    @Test
    @DisplayName("findDueForMaintenanceCompletion — cancelled/completed 제외")
    void completion_excludesCancelledCompleted() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 4, 4, 0);

        SystemAnnouncementData cancelled = maintenance(now.minusHours(1), now.minusMinutes(1));
        cancelled.markMaintenanceStarted(clockAt(now.minusHours(1)));
        cancelled.cancel(1L, clockAt(now.minusMinutes(30)));
        SystemAnnouncementData completed = maintenance(now.minusHours(1), now.minusMinutes(1));
        completed.markMaintenanceStarted(clockAt(now.minusHours(1)));
        completed.markCompleted(clockAt(now.minusMinutes(20)));
        repository.saveAll(List.of(cancelled, completed));
        flushAndClear();

        assertThat(repository.findDueForMaintenanceCompletion(now)).isEmpty();
    }

    @Test
    @DisplayName("findCurrentMaintenance — completed row 제외")
    void current_excludesCompleted() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 4, 3, 0);
        SystemAnnouncementData completed = maintenance(base, base.plusHours(1));
        completed.markMaintenanceStarted(clockAt(base));
        completed.markCompleted(clockAt(base.plusMinutes(30)));
        repository.save(completed);
        flushAndClear();

        assertThat(repository.findCurrentMaintenance()).isEmpty();
    }
}
