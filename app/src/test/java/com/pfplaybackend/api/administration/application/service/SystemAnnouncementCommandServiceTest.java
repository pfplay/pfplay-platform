package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.AnnouncementCancelledEvent;
import com.pfplaybackend.api.administration.domain.event.AnnouncementPublishedEvent;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link SystemAnnouncementCommandService} (V14 system-announcement Task 5).
 *
 * <p>Mockito-based — repository / eventPublisher mocked. {@link Clock#fixed} freezes time so
 * SCHEDULED_START_IN_PAST guard is deterministic. Real {@link SystemAnnouncementData} factory
 * used for cancel-path so domain {@code cancel(...)} actually mutates.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-03-system-announcement-design.md §5.3
 * Plan: docs/superpowers/plans/2026-05-03-system-announcement.md Task 5
 */
@ExtendWith(MockitoExtension.class)
class SystemAnnouncementCommandServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-05-04T00:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
    private static final Long ADMIN_ID = 11L;

    @Mock SystemAnnouncementRepository repository;
    @Mock ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    SystemAnnouncementCommandService service;

    // ============================== publish ==============================

    @Test
    @DisplayName("publish EVENT — repo.save 호출 + AnnouncementPublishedEvent 발행 + saved.id 반환")
    void publish_event_publishesAnnouncementPublishedEvent() {
        service = new SystemAnnouncementCommandService(repository, eventPublisher, clock);

        // repo.save returns saved entity with id assigned (simulate JPA IDENTITY)
        ArgumentCaptor<SystemAnnouncementData> saveCaptor = ArgumentCaptor.forClass(SystemAnnouncementData.class);
        given(repository.save(any(SystemAnnouncementData.class))).willAnswer(inv -> {
            SystemAnnouncementData arg = inv.getArgument(0);
            // factory built fresh; emulate identity assignment via reflection
            org.springframework.test.util.ReflectionTestUtils.setField(arg, "id", 42L);
            return arg;
        });

        Long id = service.publish(
                AnnouncementType.EVENT, AnnouncementSeverity.INFO,
                "이벤트", "Event", "본문", "Body",
                null, null, NOW.plusDays(1),
                ADMIN_ID);

        assertThat(id).isEqualTo(42L);

        verify(repository).save(saveCaptor.capture());
        SystemAnnouncementData saved = saveCaptor.getValue();
        assertThat(saved.getType()).isEqualTo(AnnouncementType.EVENT);
        assertThat(saved.getSeverity()).isEqualTo(AnnouncementSeverity.INFO);
        assertThat(saved.getTitleKo()).isEqualTo("이벤트");
        assertThat(saved.getMessageEn()).isEqualTo("Body");
        assertThat(saved.getSentAt()).isEqualTo(NOW);
        assertThat(saved.getSentByAdministratorId()).isEqualTo(ADMIN_ID);
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plusDays(1));

        ArgumentCaptor<AnnouncementPublishedEvent> evtCaptor =
                ArgumentCaptor.forClass(AnnouncementPublishedEvent.class);
        verify(eventPublisher).publishEvent(evtCaptor.capture());
        assertThat(evtCaptor.getValue().entity()).isSameAs(saved);
    }

    @Test
    @DisplayName("publish MAINTENANCE_NOTICE — scheduledStartAt 과거 → SCHEDULED_START_IN_PAST ANN-005")
    void publish_maintenanceWithPastStart_throws() {
        service = new SystemAnnouncementCommandService(repository, eventPublisher, clock);

        LocalDateTime pastStart = NOW.minusMinutes(1);
        LocalDateTime end = NOW.plusHours(1);

        assertThatThrownBy(() -> service.publish(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검", "Maintenance", "메시지", "msg",
                pastStart, end, null,
                ADMIN_ID))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ANN-005");

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("publish MAINTENANCE_NOTICE — scheduledStartAt == now → SCHEDULED_START_IN_PAST ANN-005 (경계 — !isAfter)")
    void publish_maintenanceWithStartEqualsNow_throws() {
        service = new SystemAnnouncementCommandService(repository, eventPublisher, clock);

        LocalDateTime end = NOW.plusHours(1);

        assertThatThrownBy(() -> service.publish(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검", "Maintenance", "메시지", "msg",
                NOW, end, null,
                ADMIN_ID))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ANN-005");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("publish MAINTENANCE_NOTICE — 미래 start → 정상 publish (factory invariants OK)")
    void publish_maintenanceWithFutureStart_succeeds() {
        service = new SystemAnnouncementCommandService(repository, eventPublisher, clock);

        LocalDateTime start = NOW.plusHours(1);
        LocalDateTime end = NOW.plusHours(2);

        given(repository.save(any(SystemAnnouncementData.class))).willAnswer(inv -> {
            SystemAnnouncementData arg = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(arg, "id", 7L);
            return arg;
        });

        Long id = service.publish(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검", "M", "안내", "N",
                start, end, null,
                ADMIN_ID);

        assertThat(id).isEqualTo(7L);
        verify(eventPublisher).publishEvent(any(AnnouncementPublishedEvent.class));
    }

    // ============================== cancel ==============================

    @Test
    @DisplayName("cancel — 정상: entity.cancel 호출 + AnnouncementCancelledEvent 발행")
    void cancel_emitsCancelledEvent() {
        service = new SystemAnnouncementCommandService(repository, eventPublisher, clock);

        // real entity (EVENT — no schedule complications)
        SystemAnnouncementData entity = SystemAnnouncementData.create(
                AnnouncementType.EVENT, AnnouncementSeverity.INFO,
                "이벤트", "E", "본문", "B",
                null, null, null,
                NOW.minusDays(1), 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", 100L);
        given(repository.findById(100L)).willReturn(Optional.of(entity));

        service.cancel(100L, ADMIN_ID);

        assertThat(entity.getCancelledAt()).isEqualTo(NOW);
        assertThat(entity.getCancelledByAdministratorId()).isEqualTo(ADMIN_ID);

        ArgumentCaptor<AnnouncementCancelledEvent> evtCaptor =
                ArgumentCaptor.forClass(AnnouncementCancelledEvent.class);
        verify(eventPublisher).publishEvent(evtCaptor.capture());
        assertThat(evtCaptor.getValue().entity()).isSameAs(entity);
    }

    @Test
    @DisplayName("cancel — 부재 id → ANNOUNCEMENT_NOT_FOUND ANN-001")
    void cancel_notFound_throws() {
        service = new SystemAnnouncementCommandService(repository, eventPublisher, clock);

        given(repository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(999L, ADMIN_ID))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ANN-001");

        verify(eventPublisher, never()).publishEvent(any());
    }
}
