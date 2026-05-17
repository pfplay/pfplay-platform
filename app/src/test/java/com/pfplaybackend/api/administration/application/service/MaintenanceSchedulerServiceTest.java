package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.MaintenanceEndedEvent;
import com.pfplaybackend.api.administration.domain.event.MaintenanceStartedEvent;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link MaintenanceSchedulerService} (V14 system-announcement Task 5).
 *
 * <p>매분 cron tick 시점을 mock {@code findDueForMaintenanceActivation} 결과로 시뮬레이션 —
 * 실 entity 가 {@code markMaintenanceStarted(clock)} 호출되면 {@code maintenanceStartedAt} 가
 * 채워지고 {@link MaintenanceStartedEvent} 가 entity 마다 1건씩 발행되는지 검증.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-03-system-announcement-design.md §5.4
 * Plan: docs/superpowers/plans/2026-05-03-system-announcement.md Task 5 Step 2
 */
@ExtendWith(MockitoExtension.class)
class MaintenanceSchedulerServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-05-04T03:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock SystemAnnouncementRepository repository;
    @Mock ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @Test
    @DisplayName("promoteScheduledMaintenance — due 2건: 각각 markMaintenanceStarted + 2건 MaintenanceStartedEvent 발행")
    void due_marksAndEmits() {
        // Build entities created BEFORE 'now' (start <= now < end) — factory requires future-relative
        // schedule fields only enforced for invariants of window + non-null, not vs current time.
        SystemAnnouncementData a = newDueMaintenance(NOW.minusMinutes(5), NOW.plusMinutes(55));
        SystemAnnouncementData b = newDueMaintenance(NOW.minusMinutes(1), NOW.plusMinutes(59));
        // sanity: factory does not auto-set maintenanceStartedAt
        assertThat(a.getMaintenanceStartedAt()).isNull();
        assertThat(b.getMaintenanceStartedAt()).isNull();

        given(repository.findDueForMaintenanceActivation(NOW)).willReturn(List.of(a, b));

        MaintenanceSchedulerService service =
                new MaintenanceSchedulerService(repository, eventPublisher, clock);

        service.promoteScheduledMaintenance();

        // both entities are marked started at NOW
        assertThat(a.getMaintenanceStartedAt()).isEqualTo(NOW);
        assertThat(b.getMaintenanceStartedAt()).isEqualTo(NOW);

        ArgumentCaptor<MaintenanceStartedEvent> captor =
                ArgumentCaptor.forClass(MaintenanceStartedEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        Set<SystemAnnouncementData> emitted = Set.copyOf(
                captor.getAllValues().stream().map(MaintenanceStartedEvent::entity).toList());
        assertThat(emitted).containsExactlyInAnyOrder(a, b);
    }

    @Test
    @DisplayName("promoteScheduledMaintenance — due 0건: 이벤트 발행 / save 호출 없음")
    void none_due_noOp() {
        given(repository.findDueForMaintenanceActivation(NOW)).willReturn(List.of());

        MaintenanceSchedulerService service =
                new MaintenanceSchedulerService(repository, eventPublisher, clock);

        service.promoteScheduledMaintenance();

        verify(repository).findDueForMaintenanceActivation(eq(NOW));
        verify(eventPublisher, never()).publishEvent(any());
        verify(repository, never()).save(any());
    }

    private static SystemAnnouncementData newDueMaintenance(LocalDateTime start, LocalDateTime end) {
        return SystemAnnouncementData.create(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검", "M", "안내", "N",
                start, end, null,
                start, 1L);
    }

    /** ACTIVE maintenance: 이미 markMaintenanceStarted 가 호출된 entity (completedAt=null). */
    private SystemAnnouncementData newActiveMaintenance(LocalDateTime start, LocalDateTime end) {
        SystemAnnouncementData entity = newDueMaintenance(start, end);
        entity.markMaintenanceStarted(clock);
        return entity;
    }

    @Test
    @DisplayName("completeExpiredMaintenance — due 2건: 각각 markCompleted + 2건 MaintenanceEndedEvent 발행")
    void completeExpiredMaintenance_due_marksAndPublishes() {
        // entities that are ACTIVE (started) and whose scheduledEndAt has passed
        SystemAnnouncementData a = newActiveMaintenance(NOW.minusMinutes(65), NOW.minusMinutes(5));
        SystemAnnouncementData b = newActiveMaintenance(NOW.minusMinutes(90), NOW.minusMinutes(10));
        // sanity: started but not yet completed
        assertThat(a.getMaintenanceStartedAt()).isEqualTo(NOW);
        assertThat(a.getCompletedAt()).isNull();
        assertThat(b.getCompletedAt()).isNull();

        given(repository.findDueForMaintenanceCompletion(NOW)).willReturn(List.of(a, b));

        MaintenanceSchedulerService service =
                new MaintenanceSchedulerService(repository, eventPublisher, clock);

        service.completeExpiredMaintenance();

        // both entities are marked completed at NOW
        assertThat(a.getCompletedAt()).isEqualTo(NOW);
        assertThat(b.getCompletedAt()).isEqualTo(NOW);

        ArgumentCaptor<MaintenanceEndedEvent> captor =
                ArgumentCaptor.forClass(MaintenanceEndedEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        Set<SystemAnnouncementData> emitted = Set.copyOf(
                captor.getAllValues().stream().map(MaintenanceEndedEvent::entity).toList());
        assertThat(emitted).containsExactlyInAnyOrder(a, b);
    }

    @Test
    @DisplayName("completeExpiredMaintenance — due 0건: 이벤트 발행 없음")
    void completeExpiredMaintenance_none_noop() {
        given(repository.findDueForMaintenanceCompletion(NOW)).willReturn(List.of());

        MaintenanceSchedulerService service =
                new MaintenanceSchedulerService(repository, eventPublisher, clock);

        service.completeExpiredMaintenance();

        verify(repository).findDueForMaintenanceCompletion(eq(NOW));
        verify(eventPublisher, never()).publishEvent(any());
        verify(repository, never()).save(any());
    }
}
