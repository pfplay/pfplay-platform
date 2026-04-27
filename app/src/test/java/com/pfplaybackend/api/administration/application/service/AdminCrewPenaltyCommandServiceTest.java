package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.dto.command.AdminApplyPenaltyCommand;
import com.pfplaybackend.api.administration.domain.enums.AdminPenaltyType;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewPenaltyHistoryRepository;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.history.CrewPenaltyHistoryData;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.PunisherType;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenaltyReleasedEvent;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.exception.PenaltyException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminCrewPenaltyCommandServiceTest {

    @Mock private PartyroomAggregatePort aggregatePort;
    @Mock private PartyroomAccessCommandService partyroomAccessCommandService;
    @Mock private CrewPenaltyHistoryRepository crewPenaltyHistoryRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AdminContext adminContext;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-04-28T12:00:00Z"), ZoneId.of("UTC"));

    private AdminCrewPenaltyCommandService service;

    private static final Long PARTYROOM_ID = 1L;
    private static final Long CREW_ID = 42L;
    private static final Long ADMIN_ID = 100L;
    private static final Long HISTORY_ID = 999L;

    @BeforeEach
    void setUp() {
        service = new AdminCrewPenaltyCommandService(
                aggregatePort,
                partyroomAccessCommandService,
                crewPenaltyHistoryRepository,
                eventPublisher,
                adminContext,
                clock);
    }

    private PartyroomData mockPartyroom(boolean terminated) {
        PartyroomData p = Mockito.mock(PartyroomData.class);
        Mockito.lenient().when(p.isTerminated()).thenReturn(terminated);
        return p;
    }

    private CrewData mockCrew(long crewId, long partyroomId) {
        CrewData c = Mockito.mock(CrewData.class);
        Mockito.lenient().when(c.getId()).thenReturn(crewId);
        Mockito.lenient().when(c.getPartyroomId()).thenReturn(new PartyroomId(partyroomId));
        return c;
    }

    @Test
    @DisplayName("apply - PERMANENT: expel(true) + history INSERT + event with historyId")
    void apply_permanent() throws Exception {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(false);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));
        CrewData crew = mockCrew(CREW_ID, PARTYROOM_ID);
        given(aggregatePort.findCrewById(CREW_ID)).willReturn(Optional.of(crew));

        // Inject id 999 onto saved history (simulating IDENTITY-generated PK).
        given(crewPenaltyHistoryRepository.save(any())).willAnswer(inv -> {
            CrewPenaltyHistoryData h = inv.getArgument(0);
            var f = CrewPenaltyHistoryData.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(h, HISTORY_ID);
            return h;
        });

        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                CREW_ID, AdminPenaltyType.PERMANENT_EXPULSION, "abuse");

        Long resultId = service.apply(PARTYROOM_ID, cmd);

        assertThat(resultId).isEqualTo(HISTORY_ID);
        verify(partyroomAccessCommandService).expel(partyroom, crew, true);

        ArgumentCaptor<CrewPenaltyHistoryData> historyCaptor =
                ArgumentCaptor.forClass(CrewPenaltyHistoryData.class);
        verify(crewPenaltyHistoryRepository).save(historyCaptor.capture());
        CrewPenaltyHistoryData savedHistory = historyCaptor.getValue();
        assertThat(savedHistory.getPunisherType()).isEqualTo(PunisherType.ADMIN);
        assertThat(savedHistory.getPunisherCrewId()).isNull();
        assertThat(savedHistory.getPunishedCrewId().getId()).isEqualTo(CREW_ID);
        assertThat(savedHistory.getPenaltyType()).isEqualTo(PenaltyType.PERMANENT_EXPULSION);
        assertThat(savedHistory.isReleased()).isFalse();
        assertThat(savedHistory.getPenaltyReason()).isEqualTo("abuse");
        assertThat(savedHistory.getPartyroomId()).isEqualTo(new PartyroomId(PARTYROOM_ID));

        ArgumentCaptor<AdminCrewPenalizedEvent> eventCaptor =
                ArgumentCaptor.forClass(AdminCrewPenalizedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AdminCrewPenalizedEvent event = eventCaptor.getValue();
        assertThat(event.getAdministratorId()).isEqualTo(ADMIN_ID);
        assertThat(event.getCrewPenaltyHistoryId()).isEqualTo(HISTORY_ID);
        assertThat(event.getPenaltyType()).isEqualTo(PenaltyType.PERMANENT_EXPULSION);
        assertThat(event.getPunishedCrewId().getId()).isEqualTo(CREW_ID);
        assertThat(event.getPartyroomId()).isEqualTo(new PartyroomId(PARTYROOM_ID));
        assertThat(event.getReason()).isEqualTo("abuse");
    }

    @Test
    @DisplayName("apply - ONE_TIME: expel(false) + no history save + event with null historyId")
    void apply_one_time() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(false);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));
        CrewData crew = mockCrew(CREW_ID, PARTYROOM_ID);
        given(aggregatePort.findCrewById(CREW_ID)).willReturn(Optional.of(crew));

        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                CREW_ID, AdminPenaltyType.ONE_TIME_EXPULSION, "warn");

        Long resultId = service.apply(PARTYROOM_ID, cmd);

        assertThat(resultId).isNull();
        verify(partyroomAccessCommandService).expel(partyroom, crew, false);
        verify(crewPenaltyHistoryRepository, never()).save(any());

        ArgumentCaptor<AdminCrewPenalizedEvent> eventCaptor =
                ArgumentCaptor.forClass(AdminCrewPenalizedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AdminCrewPenalizedEvent event = eventCaptor.getValue();
        assertThat(event.getCrewPenaltyHistoryId()).isNull();
        assertThat(event.getPenaltyType()).isEqualTo(PenaltyType.ONE_TIME_EXPULSION);
        assertThat(event.getAdministratorId()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("apply - partyroom not found -> NotFoundException (PartyroomException.NOT_FOUND_ROOM)")
    void apply_partyroom_not_found() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.empty());

        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                CREW_ID, AdminPenaltyType.ONE_TIME_EXPULSION, "x");

        assertThatThrownBy(() -> service.apply(PARTYROOM_ID, cmd))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(PartyroomException.NOT_FOUND_ROOM.getMessage());

        verify(partyroomAccessCommandService, never()).expel(any(), any(), Mockito.anyBoolean());
        verify(crewPenaltyHistoryRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("apply - partyroom TERMINATED -> ForbiddenException (ALREADY_TERMINATED)")
    void apply_partyroom_terminated() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(true);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));

        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                CREW_ID, AdminPenaltyType.ONE_TIME_EXPULSION, "x");

        assertThatThrownBy(() -> service.apply(PARTYROOM_ID, cmd))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(PartyroomException.ALREADY_TERMINATED.getMessage());

        verify(partyroomAccessCommandService, never()).expel(any(), any(), Mockito.anyBoolean());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("apply - crew belongs to different partyroom -> NotFoundException (CrewException.NOT_FOUND_ROOM)")
    void apply_crew_other_partyroom() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(false);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));
        // crew's partyroomId = 999 (different from path 1)
        CrewData crew = mockCrew(CREW_ID, 999L);
        given(aggregatePort.findCrewById(CREW_ID)).willReturn(Optional.of(crew));

        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                CREW_ID, AdminPenaltyType.ONE_TIME_EXPULSION, "x");

        assertThatThrownBy(() -> service.apply(PARTYROOM_ID, cmd))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(CrewException.NOT_FOUND_ROOM.getMessage());

        verify(partyroomAccessCommandService, never()).expel(any(), any(), Mockito.anyBoolean());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("release - ADMIN-applied row -> releaseBan + history.releaseByAdmin + event")
    void release_admin() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(false);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));

        CrewPenaltyHistoryData history = Mockito.mock(CrewPenaltyHistoryData.class);
        given(history.getPunisherType()).willReturn(PunisherType.ADMIN);
        given(history.getPunishedCrewId()).willReturn(new CrewId(CREW_ID));
        given(crewPenaltyHistoryRepository.findByIdAndPartyroomIdAndReleasedIsFalse(
                HISTORY_ID, new PartyroomId(PARTYROOM_ID)))
                .willReturn(Optional.of(history));

        CrewData crew = mockCrew(CREW_ID, PARTYROOM_ID);
        given(aggregatePort.findCrewById(CREW_ID)).willReturn(Optional.of(crew));

        service.release(PARTYROOM_ID, HISTORY_ID);

        verify(crew).releaseBan();
        verify(aggregatePort).saveCrew(crew);
        verify(history).releaseByAdmin(any());
        verify(crewPenaltyHistoryRepository).save(history);

        ArgumentCaptor<AdminCrewPenaltyReleasedEvent> eventCaptor =
                ArgumentCaptor.forClass(AdminCrewPenaltyReleasedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AdminCrewPenaltyReleasedEvent event = eventCaptor.getValue();
        assertThat(event.getAdministratorId()).isEqualTo(ADMIN_ID);
        assertThat(event.getCrewPenaltyHistoryId()).isEqualTo(HISTORY_ID);
        assertThat(event.getReleasedCrewId().getId()).isEqualTo(CREW_ID);
        assertThat(event.getPartyroomId()).isEqualTo(new PartyroomId(PARTYROOM_ID));
    }

    @Test
    @DisplayName("release - CREW-applied row -> ForbiddenException, no event published")
    void release_crew_applied_blocked() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(false);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));

        CrewPenaltyHistoryData history = Mockito.mock(CrewPenaltyHistoryData.class);
        given(history.getPunisherType()).willReturn(PunisherType.CREW);
        given(crewPenaltyHistoryRepository.findByIdAndPartyroomIdAndReleasedIsFalse(
                HISTORY_ID, new PartyroomId(PARTYROOM_ID)))
                .willReturn(Optional.of(history));

        assertThatThrownBy(() -> service.release(PARTYROOM_ID, HISTORY_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(PenaltyException.CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE.getMessage());

        verify(history, never()).releaseByAdmin(any());
        verify(aggregatePort, never()).saveCrew(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("release - history row not found -> NotFoundException (PENALTY_HISTORY_NOT_FOUND)")
    void release_history_not_found() {
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        PartyroomData partyroom = mockPartyroom(false);
        given(aggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(partyroom));
        given(crewPenaltyHistoryRepository.findByIdAndPartyroomIdAndReleasedIsFalse(
                HISTORY_ID, new PartyroomId(PARTYROOM_ID)))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.release(PARTYROOM_ID, HISTORY_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(PenaltyException.PENALTY_HISTORY_NOT_FOUND.getMessage());

        verify(aggregatePort, never()).saveCrew(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

}
