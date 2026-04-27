package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.*;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.common.domain.value.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPartyroomCommandServiceTest {

    @Mock private PartyroomAggregatePort aggregatePort;
    @Mock private CrewRepository crewRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    private final Clock clock = Clock.fixed(
            LocalDateTime.of(2026, 4, 27, 12, 0).atZone(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());

    private AdminPartyroomCommandService service;

    private static final Long ADMIN_ID = 1L;
    private static final PartyroomId PID = new PartyroomId(100L);

    @BeforeEach
    void setUp() {
        // Construct service manually so the non-mock Clock is wired correctly.
        // (@InjectMocks would inject null for the Clock since there is no @Mock for it.)
        service = new AdminPartyroomCommandService(aggregatePort, crewRepository, eventPublisher, clock);
    }

    private PartyroomData activeRoom() {
        return PartyroomData.create(
                "Test", "intro", LinkDomain.of("link"),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL, new UserId(1L)
        );
    }

    @Test
    @DisplayName("terminate - bulk deactivate + status TERMINATED + event publish")
    void terminate_happy() {
        PartyroomData p = activeRoom();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));
        when(crewRepository.bulkDeactivateByPartyroomId(eq(PID), any())).thenReturn(5);

        service.terminate(PID, "violation", ADMIN_ID);

        verify(crewRepository).bulkDeactivateByPartyroomId(eq(PID), any());
        verify(aggregatePort).savePartyroom(p);
        assertThat(p.getStatus()).isEqualTo(PartyroomStatus.TERMINATED);

        ArgumentCaptor<PartyroomTerminatedEvent> captor = ArgumentCaptor.forClass(PartyroomTerminatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("violation");
        assertThat(captor.getValue().getAdministratorId()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("terminate - room not found -> NotFoundException")
    void terminate_not_found() {
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.terminate(PID, "x", ADMIN_ID))
                .isInstanceOf(NotFoundException.class);
        verify(crewRepository, never()).bulkDeactivateByPartyroomId(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("terminate - already TERMINATED -> ConflictException, audit not published")
    void terminate_already_terminated() {
        PartyroomData p = activeRoom();
        p.terminate();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.terminate(PID, "x", ADMIN_ID))
                .isInstanceOf(ConflictException.class);
        verify(eventPublisher, never()).publishEvent(any(PartyroomTerminatedEvent.class));
    }

    @Test
    @DisplayName("suspend - ACTIVE -> SUSPENDED + event")
    void suspend_happy() {
        PartyroomData p = activeRoom();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        service.suspend(PID, "investigation", ADMIN_ID);

        assertThat(p.getStatus()).isEqualTo(PartyroomStatus.SUSPENDED);
        verify(eventPublisher).publishEvent(any(PartyroomSuspendedEvent.class));
    }

    @Test
    @DisplayName("restore - SUSPENDED -> ACTIVE + event")
    void restore_happy() {
        PartyroomData p = activeRoom();
        p.suspend();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        service.restore(PID, ADMIN_ID);

        assertThat(p.getStatus()).isEqualTo(PartyroomStatus.ACTIVE);
        verify(eventPublisher).publishEvent(any(PartyroomRestoredEvent.class));
    }

    @Test
    @DisplayName("setDisplayFlag - FEATURED + event")
    void setDisplayFlag_featured() {
        PartyroomData p = activeRoom();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        service.setDisplayFlag(PID, DisplayFlag.FEATURED, ADMIN_ID);

        assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.FEATURED);

        ArgumentCaptor<PartyroomDisplayFlagChangedEvent> captor =
                ArgumentCaptor.forClass(PartyroomDisplayFlagChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getOldFlag()).isEqualTo(DisplayFlag.NORMAL);
        assertThat(captor.getValue().getNewFlag()).isEqualTo(DisplayFlag.FEATURED);
    }

    @Test
    @DisplayName("updateMeta - title change -> diff.title.{old,new}")
    void updateMeta_title() {
        PartyroomData p = activeRoom();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        service.updateMeta(PID, "New Title", null, null, ADMIN_ID);

        assertThat(p.getTitle()).isEqualTo("New Title");

        ArgumentCaptor<PartyroomMetaUpdatedEvent> captor =
                ArgumentCaptor.forClass(PartyroomMetaUpdatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getDiff()).containsKey("title");
        assertThat(captor.getValue().getDiff().get("title"))
                .containsEntry("old", "Test")
                .containsEntry("new", "New Title");
    }

    @Test
    @DisplayName("updateMeta - all fields identical -> no event (no-op)")
    void updateMeta_no_changes() {
        PartyroomData p = activeRoom();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        service.updateMeta(PID, "Test", "intro", 5, ADMIN_ID);

        verify(eventPublisher, never()).publishEvent(any(PartyroomMetaUpdatedEvent.class));
    }

    @Test
    @DisplayName("updateMeta - TERMINATED room -> ForbiddenException (ALREADY_TERMINATED)")
    void updateMeta_terminated() {
        PartyroomData p = activeRoom();
        p.terminate();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.updateMeta(PID, "x", null, null, ADMIN_ID))
                .isInstanceOf(ForbiddenException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }
}
