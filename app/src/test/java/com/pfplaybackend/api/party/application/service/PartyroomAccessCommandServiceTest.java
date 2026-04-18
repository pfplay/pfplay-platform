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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartyroomAccessCommandServiceTest {

    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PartyroomAggregatePort aggregatePort;
    @Mock private PartyroomAggregateService partyroomAggregateService;
    @Mock private PartyroomQueryService partyroomQueryService;
    @Mock private PlaybackControlPort playbackControlPort;
    @Mock private Clock clock;

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
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("같은 룸 재진입 시 publishAccessChangedEvent가 호출되어야 한다")
    void tryEnterSameRoomReEntryShouldPublishAccessEvent() {
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
                .isTerminated(false)
                .build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroomData);
        when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(10L);
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(crew));

        // 같은 룸에 이미 active
        ActivePartyroomDto activeRoomInfo = mock(ActivePartyroomDto.class);
        when(activeRoomInfo.id()).thenReturn(1L);
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeRoomInfo));

        // when
        partyroomAccessCommandService.tryEnter(partyroomId, null);

        // then — 재진입 시에도 이벤트가 발행되어야 함
        verify(eventPublisher, times(1)).publishEvent(any(CrewAccessedEvent.class));
    }

    @Test
    @DisplayName("다른 룸이 active일 때 ACTIVE_ANOTHER_ROOM 예외 대신 exit이 호출되어야 한다")
    void tryEnterAnotherRoomActiveShouldAutoExitInsteadOfException() {
        // given
        PartyroomId newRoomId = new PartyroomId(2L);
        PartyroomId oldRoomId = new PartyroomId(1L);

        // 새 룸 PartyroomData — 사용자가 inactive crew로 존재; addOrActivateCrew가 reactivate
        CrewData newRoomCrew = CrewData.builder()
                .id(20L)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(false)
                .build();

        PartyroomData newPartyroomData = PartyroomData.builder()
                .id(2L)
                .partyroomId(newRoomId)
                .isTerminated(false)
                .build();

        when(partyroomQueryService.getPartyroomById(newRoomId)).thenReturn(newPartyroomData);
        when(aggregatePort.countActiveCrews(newRoomId)).thenReturn(5L);

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
                .isTerminated(false)
                .build();

        PartyroomPlaybackData oldPlaybackState = PartyroomPlaybackData.createFor(new PartyroomId(1L));

        when(partyroomQueryService.getPartyroomById(oldRoomId)).thenReturn(oldPartyroomData);
        // exit() mock: crew lookup
        when(aggregatePort.findCrew(oldRoomId, userId)).thenReturn(Optional.of(oldCrew));
        when(aggregatePort.findDj(oldRoomId, new CrewId(5L))).thenReturn(Optional.empty());
        when(aggregatePort.findPlaybackState(oldRoomId)).thenReturn(oldPlaybackState);

        // addOrActivateCrew mock for new room: inactive crew found → reactivate
        when(aggregatePort.findCrew(newRoomId, userId)).thenReturn(Optional.of(newRoomCrew));
        when(aggregatePort.saveCrew(any(CrewData.class))).thenReturn(newRoomCrew);

        // when — 예외 없이 정상 실행되어야 함
        partyroomAccessCommandService.tryEnter(newRoomId, null);

        // then — exit 이벤트 + enter 이벤트 = 최소 2번 publish 호출
        verify(eventPublisher, atLeast(2)).publishEvent(any(Object.class));
    }
}
