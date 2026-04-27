package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.service.PartyroomAggregateService;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartyroomAccessCommandServiceTest {

    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PartyroomAggregatePort aggregatePort;
    @Mock private PartyroomAggregateService partyroomAggregateService;
    @Mock private PartyroomQueryService partyroomQueryService;
    @Mock private PlaybackControlPort playbackControlPort;
    @Mock private Clock clock;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks
    private PartyroomAccessCommandService partyroomAccessCommandService;

    private UserId userId;
    private PartyroomId partyroomId;

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        lenient().when(clock.millis()).thenReturn(1735689600000L);
        userId = new UserId();
        partyroomId = new PartyroomId(1L);

        AuthContext authContext = mock(AuthContext.class);
        when(authContext.getUserId()).thenReturn(userId);
        ThreadLocalContext.setContext(authContext);

        // @PostConstruct does not run with @InjectMocks — manually wire the TransactionTemplate
        // by calling initTxTemplates via reflection or leaving requiresNewReadOnlyTx null.
        // Since findCrewInNewTransaction is only called on DataIntegrityViolationException path
        // (not exercised in these unit tests), this is safe for the happy-path tests below.
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("같은 룸 재진입(websocket 재연결) 시 ENTER 이벤트를 발행하지 않아야 한다 (spec §7.2 spurious ENTER 차단)")
    void tryEnterSameRoomReEntryShouldNotPublishEnterEvent() {
        // given
        CrewData crew = CrewData.builder()
                .id(10L)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(true)
                .build();

        PartyroomData partyroomData = PartyroomData.builder()
                .id(1L)
                .partyroomId(partyroomId)
                .status(PartyroomStatus.ACTIVE)
                .build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroomData);
        when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(10L);
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(crew));
        when(aggregatePort.saveCrew(any(CrewData.class))).thenReturn(crew);

        // 같은 룸에 이미 active
        ActivePartyroomDto activeRoomInfo = mock(ActivePartyroomDto.class);
        when(activeRoomInfo.id()).thenReturn(1L);
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeRoomInfo));

        // when
        partyroomAccessCommandService.tryEnter(partyroomId, null);

        // then — spec §7.2: 같은 룸 재진입 시 ENTER 이벤트 발행 금지 (counter inflate 방지)
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("다른 룸이 active일 때 ACTIVE_ANOTHER_ROOM 예외 대신 exit이 호출되어야 한다")
    void tryEnterAnotherRoomActiveShouldAutoExitInsteadOfException() {
        // given
        PartyroomId newRoomId = new PartyroomId(2L);
        PartyroomId oldRoomId = new PartyroomId(1L);

        // 새 룸 PartyroomData
        CrewData newRoomCrew = CrewData.builder()
                .id(20L)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(false)
                .build();

        PartyroomData newPartyroomData = PartyroomData.builder()
                .id(2L)
                .partyroomId(newRoomId)
                .status(PartyroomStatus.ACTIVE)
                .build();

        when(partyroomQueryService.getPartyroomById(newRoomId)).thenReturn(newPartyroomData);
        when(aggregatePort.countActiveCrews(newRoomId)).thenReturn(5L);
        when(aggregatePort.findCrew(newRoomId, userId)).thenReturn(Optional.of(newRoomCrew));

        // 다른 룸에 이미 active
        ActivePartyroomDto activeRoomInfo = mock(ActivePartyroomDto.class);
        when(activeRoomInfo.id()).thenReturn(oldRoomId.getId());
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeRoomInfo));

        // exit() 호출 시 필요한 mock — 기존 룸 조회
        CrewData oldCrew = CrewData.builder()
                .id(5L)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(true)
                .build();

        PartyroomData oldPartyroomData = PartyroomData.builder()
                .id(1L)
                .partyroomId(oldRoomId)
                .status(PartyroomStatus.ACTIVE)
                .build();

        PartyroomPlaybackData oldPlaybackState = PartyroomPlaybackData.createFor(new PartyroomId(1L));

        when(partyroomQueryService.getPartyroomById(oldRoomId)).thenReturn(oldPartyroomData);
        when(aggregatePort.findCrew(oldRoomId, userId)).thenReturn(Optional.of(oldCrew));
        when(aggregatePort.findDj(oldRoomId, new CrewId(5L))).thenReturn(Optional.empty());
        when(aggregatePort.findPlaybackState(oldRoomId)).thenReturn(oldPlaybackState);
        // deactivateCrew returns 1 → exit publishes EXIT event
        when(aggregatePort.deactivateCrew(eq(oldRoomId), eq(userId), any(LocalDateTime.class))).thenReturn(1);

        // activateCrew returns 1 → ensureCrewActive transitions inactive→active → publishes ENTER
        when(aggregatePort.activateCrew(eq(newRoomId), eq(userId), any(LocalDateTime.class))).thenReturn(1);
        when(aggregatePort.saveCrew(any(CrewData.class))).thenReturn(newRoomCrew);

        // when — 예외 없이 정상 실행되어야 함
        partyroomAccessCommandService.tryEnter(newRoomId, null);

        // then — EXIT event (from old room exit) + ENTER event (new room) = exactly 2 publish calls
        verify(eventPublisher, times(2)).publishEvent(any(Object.class));
    }
}
