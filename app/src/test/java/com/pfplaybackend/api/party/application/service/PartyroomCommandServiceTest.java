package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.application.dto.command.CreatePartyroomCommand;
import com.pfplaybackend.api.party.application.dto.command.UpdateDjQueueStatusCommand;
import com.pfplaybackend.api.party.application.dto.command.UpdatePartyroomCommand;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.QueueStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
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

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartyroomCommandServiceTest {

    @Mock PartyroomAggregatePort aggregatePort;
    @Mock PartyroomAccessCommandService partyroomAccessCommandService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks PartyroomCommandService partyroomCommandService;

    private UserId userId;

    @BeforeEach
    void setUp() {
        userId = new UserId(1L);
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        lenient().when(authContext.getAuthorityTier()).thenReturn(AuthorityTier.FM);
        ThreadLocalContext.setContext(authContext);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    // ========== createGeneralPartyRoom ==========

    @Test
    @DisplayName("createGeneralPartyRoom — 정상 생성 시 파티룸, 재생 상태, DJ 큐 상태를 저장한다")
    void createGeneralPartyRoomSuccess() {
        // given
        CreatePartyroomCommand command = new CreatePartyroomCommand("My Room", "Intro", "mylink", 10);
        when(aggregatePort.findNonTerminatedHostRoom(userId)).thenReturn(Optional.empty());
        when(aggregatePort.savePartyroom(any(PartyroomData.class))).thenAnswer(invocation -> {
            PartyroomData p = invocation.getArgument(0);
            return PartyroomData.builder()
                    .id(1L).hostId(p.getHostId()).stageType(p.getStageType())
                    .title(p.getTitle()).introduction(p.getIntroduction())
                    .linkDomain(p.getLinkDomain()).playbackTimeLimit(p.getPlaybackTimeLimit())
                    .noticeContent("").status(PartyroomStatus.ACTIVE).build();
        });

        // when
        PartyroomData result = partyroomCommandService.createGeneralPartyRoom(command);

        // then
        assertThat(result.getTitle()).isEqualTo("My Room");
        verify(aggregatePort).savePartyroom(any(PartyroomData.class));
        verify(aggregatePort).savePlaybackState(any(PartyroomPlaybackData.class));
        verify(aggregatePort).saveDjQueueState(any(DjQueueData.class));
        verify(partyroomAccessCommandService).enterByHost(eq(userId), any(PartyroomData.class));
    }

    @Test
    @DisplayName("createGeneralPartyRoom — 이미 호스트인 파티룸이 있으면 예외가 발생한다")
    void createGeneralPartyRoomAlreadyHost() {
        // given
        CreatePartyroomCommand command = new CreatePartyroomCommand("My Room", "Intro", "mylink", 10);
        PartyroomData existing = PartyroomData.builder().id(99L).hostId(userId).build();
        when(aggregatePort.findNonTerminatedHostRoom(userId)).thenReturn(Optional.of(existing));

        // when & then
        assertThatThrownBy(() -> partyroomCommandService.createGeneralPartyRoom(command))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("createGeneralPartyRoom — linkDomain이 비어있으면 자동 생성된다")
    void createGeneralPartyRoomAutoGeneratesLinkDomain() {
        // given
        CreatePartyroomCommand command = new CreatePartyroomCommand("My Room", "Intro", "", 10);
        when(aggregatePort.findNonTerminatedHostRoom(userId)).thenReturn(Optional.empty());

        ArgumentCaptor<PartyroomData> captor = ArgumentCaptor.forClass(PartyroomData.class);
        when(aggregatePort.savePartyroom(any(PartyroomData.class))).thenAnswer(invocation -> {
            PartyroomData p = invocation.getArgument(0);
            return PartyroomData.builder()
                    .id(1L).hostId(p.getHostId()).stageType(p.getStageType())
                    .title(p.getTitle()).introduction(p.getIntroduction())
                    .linkDomain(p.getLinkDomain()).playbackTimeLimit(p.getPlaybackTimeLimit())
                    .noticeContent("").status(PartyroomStatus.ACTIVE).build();
        });

        // when
        partyroomCommandService.createGeneralPartyRoom(command);

        // then
        verify(aggregatePort).savePartyroom(captor.capture());
        assertThat(captor.getValue().getLinkDomain().getValue()).isNotEmpty();
        assertThat(captor.getValue().getLinkDomain().getValue()).hasSize(12);
    }

    // ========== updatePartyroom ==========

    @Test
    @DisplayName("updatePartyroom — 호스트가 파티룸 정보를 수정한다")
    void updatePartyroomSuccess() {
        // given
        PartyroomId partyroomId = new PartyroomId(1L);
        PartyroomData partyroom = PartyroomData.builder()
                .id(1L).hostId(userId).stageType(StageType.GENERAL)
                .title("Old Title").introduction("Old Intro")
                .linkDomain(LinkDomain.of("old")).playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .noticeContent("").status(PartyroomStatus.ACTIVE).build();
        when(aggregatePort.findPartyroomById(1L)).thenReturn(Optional.of(partyroom));

        UpdatePartyroomCommand command = new UpdatePartyroomCommand("New Title", "New Intro", "newlink", 10);

        // when
        partyroomCommandService.updatePartyroom(partyroomId, command);

        // then
        verify(aggregatePort).savePartyroom(partyroom);
        assertThat(partyroom.getTitle()).isEqualTo("New Title");
    }

    @Test
    @DisplayName("updatePartyroom — 파티룸이 존재하지 않으면 NotFoundException이 발생한다")
    void updatePartyroomNotFound() {
        // given
        PartyroomId partyroomId = new PartyroomId(999L);
        when(aggregatePort.findPartyroomById(999L)).thenReturn(Optional.empty());

        UpdatePartyroomCommand command = new UpdatePartyroomCommand("title", "intro", "link", 5);

        // when & then
        assertThatThrownBy(() -> partyroomCommandService.updatePartyroom(partyroomId, command))
                .isInstanceOf(NotFoundException.class);
    }

    // ========== deletePartyRoom ==========

    @Test
    @DisplayName("deletePartyRoom — FM 권한 사용자가 파티룸을 삭제하면 terminate 후 이벤트가 발행된다")
    void deletePartyRoomSuccess() {
        // given
        PartyroomId partyroomId = new PartyroomId(1L);
        PartyroomData partyroom = PartyroomData.builder()
                .id(1L).partyroomId(partyroomId).hostId(userId).stageType(StageType.GENERAL)
                .title("Room").introduction("Intro")
                .linkDomain(LinkDomain.of("link")).playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .noticeContent("").status(PartyroomStatus.ACTIVE).build();
        when(aggregatePort.findPartyroomById(1L)).thenReturn(Optional.of(partyroom));

        // when
        partyroomCommandService.deletePartyRoom(partyroomId);

        // then
        assertThat(partyroom.isTerminated()).isTrue();
        verify(aggregatePort).savePartyroom(partyroom);
        verify(eventPublisher, atLeastOnce()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("deletePartyRoom — FM이 아닌 사용자는 파티룸을 삭제할 수 없다")
    void deletePartyRoomRestrictedAuthority() {
        // given
        AuthContext authContext = mock(AuthContext.class);
        when(authContext.getAuthorityTier()).thenReturn(AuthorityTier.AM);
        ThreadLocalContext.setContext(authContext);

        PartyroomId partyroomId = new PartyroomId(1L);

        // when & then
        assertThatThrownBy(() -> partyroomCommandService.deletePartyRoom(partyroomId))
                .isInstanceOf(ForbiddenException.class);
    }

    // ========== deleteUnusedPartyroom ==========

    @Test
    @DisplayName("deleteUnusedPartyroom — 미사용 파티룸을 일괄 종료하고 이벤트를 발행한다")
    void deleteUnusedPartyroomSuccess() {
        // given
        PartyroomData p1 = PartyroomData.builder()
                .id(1L).partyroomId(new PartyroomId(1L)).hostId(userId).stageType(StageType.GENERAL)
                .title("Unused 1").introduction("").linkDomain(LinkDomain.of("u1"))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .noticeContent("").status(PartyroomStatus.ACTIVE).build();
        PartyroomData p2 = PartyroomData.builder()
                .id(2L).partyroomId(new PartyroomId(2L)).hostId(userId).stageType(StageType.GENERAL)
                .title("Unused 2").introduction("").linkDomain(LinkDomain.of("u2"))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .noticeContent("").status(PartyroomStatus.ACTIVE).build();
        when(aggregatePort.findAllUnusedPartyroomDataByDay(30)).thenReturn(List.of(p1, p2));

        // when
        partyroomCommandService.deleteUnusedPartyroom();

        // then
        assertThat(p1.isTerminated()).isTrue();
        assertThat(p2.isTerminated()).isTrue();
        verify(aggregatePort, times(2)).savePartyroom(any(PartyroomData.class));
        verify(eventPublisher, atLeast(2)).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("deleteUnusedPartyroom — 미사용 파티룸이 없으면 아무 작업도 하지 않는다")
    void deleteUnusedPartyroomEmpty() {
        // given
        when(aggregatePort.findAllUnusedPartyroomDataByDay(30)).thenReturn(Collections.emptyList());

        // when
        partyroomCommandService.deleteUnusedPartyroom();

        // then
        verify(aggregatePort, never()).savePartyroom(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    // ========== updateDjQueueStatus ==========

    @Test
    @DisplayName("updateDjQueueStatus — 호스트가 DJ 큐를 닫을 수 있다")
    void updateDjQueueStatusClose() {
        // given
        PartyroomId partyroomId = new PartyroomId(1L);
        PartyroomData partyroom = PartyroomData.builder()
                .id(1L).hostId(userId).stageType(StageType.GENERAL)
                .title("Room").introduction("").linkDomain(LinkDomain.of("link"))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .noticeContent("").status(PartyroomStatus.ACTIVE).build();
        when(aggregatePort.findPartyroomById(1L)).thenReturn(Optional.of(partyroom));

        DjQueueData djQueue = DjQueueData.createFor(new PartyroomId(1L));
        when(aggregatePort.findDjQueueState(new PartyroomId(1L))).thenReturn(djQueue);

        UpdateDjQueueStatusCommand command = new UpdateDjQueueStatusCommand(QueueStatus.CLOSE);

        // when
        partyroomCommandService.updateDjQueueStatus(partyroomId, command);

        // then
        assertThat(djQueue.isClosed()).isTrue();
        verify(aggregatePort).saveDjQueueState(djQueue);
    }

    @Test
    @DisplayName("updateDjQueueStatus — 호스트가 DJ 큐를 열 수 있다")
    void updateDjQueueStatusOpen() {
        // given
        PartyroomId partyroomId = new PartyroomId(1L);
        PartyroomData partyroom = PartyroomData.builder()
                .id(1L).hostId(userId).stageType(StageType.GENERAL)
                .title("Room").introduction("").linkDomain(LinkDomain.of("link"))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .noticeContent("").status(PartyroomStatus.ACTIVE).build();
        when(aggregatePort.findPartyroomById(1L)).thenReturn(Optional.of(partyroom));

        DjQueueData djQueue = DjQueueData.createFor(new PartyroomId(1L));
        djQueue.close();
        when(aggregatePort.findDjQueueState(new PartyroomId(1L))).thenReturn(djQueue);

        UpdateDjQueueStatusCommand command = new UpdateDjQueueStatusCommand(QueueStatus.OPEN);

        // when
        partyroomCommandService.updateDjQueueStatus(partyroomId, command);

        // then
        assertThat(djQueue.isClosed()).isFalse();
        verify(aggregatePort).saveDjQueueState(djQueue);
    }
}
