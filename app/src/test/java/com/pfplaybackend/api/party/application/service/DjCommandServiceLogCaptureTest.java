package com.pfplaybackend.api.party.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.application.port.out.PlaylistQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.*;
import com.pfplaybackend.api.party.domain.enums.GradeType;
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
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 옵저버빌리티 A1 — 최우선 SLO 시그널 [enqueueDj] CREW_FOUND ... isActive=... 로그 캡처 검증.
 * isActive=false 누수 시그널이 실제 crew 상태를 반영해 한 줄 emit 되는지 ListAppender 로 확인.
 */
@ExtendWith(MockitoExtension.class)
class DjCommandServiceLogCaptureTest {

    @Mock PartyroomAggregatePort aggregatePort;
    @Mock PlaybackControlPort playbackControlPort;
    @Mock PlaylistQueryPort playlistQueryPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PartyroomAggregateService partyroomAggregateService;
    @Mock PartyroomQueryService partyroomQueryService;

    @InjectMocks DjCommandService djCommandService;

    private final UserId userId = new UserId(1L);
    private final PartyroomId partyroomId = new PartyroomId(10L);
    private final PlaylistId playlistId = new PlaylistId(100L);

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        lenient().when(authContext.getAuthorityTier()).thenReturn(AuthorityTier.FM);
        ThreadLocalContext.setContext(authContext);

        logger = (Logger) LoggerFactory.getLogger(DjCommandService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("enqueueDj — CREW_FOUND 로그가 crew 의 isActive 상태를 반영해 emit 된다")
    void enqueueDjEmitsCrewFoundLogWithIsActive() {
        // given
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId()).partyroomId(partyroomId).build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);

        CrewData crew = CrewData.builder()
                .id(7L).partyroomId(partyroomId).userId(userId)
                .gradeType(GradeType.CLUBBER).isActive(true).build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(crew);
        when(aggregatePort.isDjRegistered(partyroomId, new CrewId(7L))).thenReturn(false);
        when(playlistQueryPort.isOwnedBy(playlistId.getId(), userId.getUid())).thenReturn(true);
        when(playlistQueryPort.isEmptyPlaylist(playlistId.getId())).thenReturn(false);
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(Collections.emptyList());
        when(aggregatePort.saveDj(any(DjData.class))).thenReturn(mock(DjData.class));

        // when
        djCommandService.enqueueDj(partyroomId, playlistId);

        // then
        List<ILoggingEvent> events = appender.list;
        ILoggingEvent crewFound = events.stream()
                .filter(e -> e.getFormattedMessage().contains("[enqueueDj] CREW_FOUND"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "CREW_FOUND log not emitted. Captured: " +
                                events.stream().map(ILoggingEvent::getFormattedMessage).toList()));

        assertThat(crewFound.getFormattedMessage())
                .contains("crewId=7")
                .contains("isActive=true");
    }
}
