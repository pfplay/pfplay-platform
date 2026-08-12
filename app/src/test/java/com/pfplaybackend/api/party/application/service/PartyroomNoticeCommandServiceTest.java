package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.application.dto.command.UpdatePartyroomNoticeCommand;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.PartyroomNoticeUpdatedEvent;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartyroomNoticeCommandServiceTest {

    @Mock PartyroomAggregatePort aggregatePort;
    @Mock PartyroomQueryService partyroomQueryService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks PartyroomNoticeCommandService service;

    private final PartyroomId partyroomId = new PartyroomId(1L);
    private final UserId userId = new UserId(10L);
    private PartyroomData partyroom;

    @BeforeEach
    void setUp() {
        ThreadLocalContext.setContext(new AuthContext(userId, AuthorityTier.FM));
        partyroom = PartyroomData.builder()
                .id(partyroomId.getId())
                .partyroomId(partyroomId)
                .hostId(new UserId(99L))
                .stageType(StageType.GENERAL)
                .noticeContent("old notice")
                .status(PartyroomStatus.ACTIVE)
                .build();
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("updateNotice — COMMUNITY_MANAGER 이상이 공지를 저장하고 이벤트를 발행한다")
    void updateNoticePublishesEvent() {
        CrewData updater = crew(GradeType.COMMUNITY_MANAGER);
        when(aggregatePort.findPartyroomById(1L)).thenReturn(Optional.of(partyroom));
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId)).thenReturn(updater);

        service.updateNotice(partyroomId, new UpdatePartyroomNoticeCommand("new notice"));

        assertThat(partyroom.getNoticeContent()).isEqualTo("new notice");
        verify(aggregatePort).savePartyroom(partyroom);
        ArgumentCaptor<PartyroomNoticeUpdatedEvent> eventCaptor =
                ArgumentCaptor.forClass(PartyroomNoticeUpdatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getPartyroomId()).isEqualTo(partyroomId);
        assertThat(eventCaptor.getValue().getContent()).isEqualTo("new notice");
    }

    @Test
    @DisplayName("updateNotice — 빈 문자열은 공지 해제로 저장한다")
    void updateNoticeAllowsEmptyContent() {
        when(aggregatePort.findPartyroomById(1L)).thenReturn(Optional.of(partyroom));
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId))
                .thenReturn(crew(GradeType.HOST));

        service.updateNotice(partyroomId, new UpdatePartyroomNoticeCommand(""));

        assertThat(partyroom.getNoticeContent()).isEmpty();
        verify(eventPublisher).publishEvent(any(PartyroomNoticeUpdatedEvent.class));
    }

    @Test
    @DisplayName("updateNotice — 공지 내용이 같으면 저장하거나 이벤트를 발행하지 않는다")
    void updateNoticeSkipsUnchangedContent() {
        when(aggregatePort.findPartyroomById(1L)).thenReturn(Optional.of(partyroom));
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId))
                .thenReturn(crew(GradeType.COMMUNITY_MANAGER));

        service.updateNotice(partyroomId, new UpdatePartyroomNoticeCommand("old notice"));

        assertThat(partyroom.getNoticeContent()).isEqualTo("old notice");
        verify(aggregatePort, never()).savePartyroom(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("updateNotice — MODERATOR 이하는 403으로 거부한다")
    void updateNoticeRejectsModerator() {
        when(aggregatePort.findPartyroomById(1L)).thenReturn(Optional.of(partyroom));
        when(partyroomQueryService.getCrewOrThrow(partyroomId, userId))
                .thenReturn(crew(GradeType.MODERATOR));

        assertThatThrownBy(() -> service.updateNotice(
                partyroomId, new UpdatePartyroomNoticeCommand("new notice")))
                .isInstanceOf(ForbiddenException.class);

        assertThat(partyroom.getNoticeContent()).isEqualTo("old notice");
        verify(aggregatePort, never()).savePartyroom(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("updateNotice — 파티룸이 없으면 404")
    void updateNoticeRejectsMissingPartyroom() {
        when(aggregatePort.findPartyroomById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateNotice(
                partyroomId, new UpdatePartyroomNoticeCommand("new notice")))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(partyroomQueryService, eventPublisher);
    }

    @Test
    @DisplayName("updateNotice — 종료된 파티룸은 거부한다")
    void updateNoticeRejectsTerminatedPartyroom() {
        partyroom.terminate();
        when(aggregatePort.findPartyroomById(1L)).thenReturn(Optional.of(partyroom));

        assertThatThrownBy(() -> service.updateNotice(
                partyroomId, new UpdatePartyroomNoticeCommand("new notice")))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(partyroomQueryService, eventPublisher);
        verify(aggregatePort, never()).savePartyroom(any());
    }

    private CrewData crew(GradeType gradeType) {
        return CrewData.builder()
                .id(100L)
                .partyroomId(partyroomId)
                .userId(userId)
                .gradeType(gradeType)
                .isActive(true)
                .build();
    }
}
