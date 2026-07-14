package com.pfplaybackend.api.admin.application.service;

import com.pfplaybackend.api.admin.application.dto.command.AdminCreatePartyroomCommand;
import com.pfplaybackend.api.admin.application.dto.result.AdminPartyroomResult;
import com.pfplaybackend.api.admin.application.port.out.AdminPartyroomPort;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.application.service.PlaybackQueryService;
import com.pfplaybackend.api.party.application.service.PlaybackReactionSimulationService;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.ReactionType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminPartyroomServiceTest {

    @Mock
    private AdminPartyroomPort adminPartyroomPort;

    @Mock
    private PartyroomAccessCommandService partyroomAccessCommandService;

    @Mock
    private AdminUserService adminUserService;

    @Mock
    private PlaybackQueryService playbackQueryService;

    @Mock
    private PlaybackReactionSimulationService playbackReactionSimulationService;

    @Mock
    private Clock clock;

    @Mock
    private ExecutorService reactionSimulationExecutor;

    @InjectMocks
    private AdminPartyroomService adminPartyroomService;

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        lenient().when(clock.millis()).thenReturn(1735689600000L);
    }

    private PartyroomData createTestPartyroom(String title, String linkDomain) {
        UserId hostId = new UserId(1L);
        return PartyroomData.builder()
                .id(1L)
                .partyroomId(new PartyroomId(1L))
                .hostId(hostId)
                .stageType(StageType.GENERAL)
                .title(title)
                .introduction("Test introduction")
                .linkDomain(LinkDomain.of(linkDomain))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .noticeContent("")
                .status(PartyroomStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("createPartyroomWithHost — 성공 시 파티룸 생성 후 호스트가 입장한다")
    void createPartyroomWithHostSuccess() {
        // given
        AdminCreatePartyroomCommand command = new AdminCreatePartyroomCommand(
                "100", "Test Room", "Welcome", "testdomain01", 5);

        PartyroomData savedPartyroom = createTestPartyroom("Test Room", "testdomain01");

        when(adminPartyroomPort.findPartyroomByLinkDomain(LinkDomain.of("testdomain01"))).thenReturn(Optional.empty());
        when(adminPartyroomPort.savePartyroom(any(PartyroomData.class))).thenReturn(savedPartyroom);

        // when
        AdminPartyroomResult result = adminPartyroomService.createPartyroomWithHost(command);

        // then
        assertThat(result.title()).isEqualTo("Test Room");
        assertThat(result.linkDomain()).isEqualTo("testdomain01");
        verify(partyroomAccessCommandService).enterByHost(any(UserId.class), eq(savedPartyroom));
    }

    @Test
    @DisplayName("createPartyroomWithHost — 사용자가 지정한 linkDomain이 이미 존재하면 CONFLICT(409)을 던진다")
    void createPartyroomWithHostRejectsDuplicateLinkDomain() {
        // given
        AdminCreatePartyroomCommand command = new AdminCreatePartyroomCommand(
                "100", "Test Room", "Welcome", "taken", 5);
        PartyroomData existing = createTestPartyroom("Existing", "taken");
        when(adminPartyroomPort.findPartyroomByLinkDomain(LinkDomain.of("taken"))).thenReturn(Optional.of(existing));

        // when & then
        assertThatThrownBy(() -> adminPartyroomService.createPartyroomWithHost(command))
                .isInstanceOf(ConflictException.class);
        verify(adminPartyroomPort, never()).savePartyroom(any());
        verify(partyroomAccessCommandService, never()).enterByHost(any(), any());
    }

    @Test
    @DisplayName("createPartyroomWithHost — linkDomain이 null이면 12자 자동 생성된다")
    void createPartyroomWithHostAutoLinkDomain() {
        // given
        AdminCreatePartyroomCommand command = new AdminCreatePartyroomCommand(
                "100", "Auto Link Room", null, null, 5);

        when(adminPartyroomPort.findPartyroomByLinkDomain(any(LinkDomain.class))).thenReturn(Optional.empty());
        when(adminPartyroomPort.savePartyroom(any(PartyroomData.class))).thenAnswer(invocation -> {
            PartyroomData input = invocation.getArgument(0);
            // Simulate JPA @PostPersist by returning with id/partyroomId set
            return PartyroomData.builder()
                    .id(2L)
                    .partyroomId(new PartyroomId(2L))
                    .hostId(input.getHostId())
                    .stageType(input.getStageType())
                    .title(input.getTitle())
                    .introduction(input.getIntroduction())
                    .linkDomain(input.getLinkDomain())
                    .playbackTimeLimit(input.getPlaybackTimeLimit())
                    .noticeContent(input.getNoticeContent())
                    .status(input.getStatus())
                    .build();
        });

        // when
        AdminPartyroomResult result = adminPartyroomService.createPartyroomWithHost(command);

        // then
        assertThat(result.linkDomain()).hasSize(12);
        verify(partyroomAccessCommandService).enterByHost(any(UserId.class), any(PartyroomData.class));
    }

    @Test
    @DisplayName("createPartyroomWithHost — 숫자가 아닌 userId 전달 시 BadRequestException이 발생한다")
    void createPartyroomWithHostInvalidUserIdThrows() {
        // given
        AdminCreatePartyroomCommand command = new AdminCreatePartyroomCommand(
                "not-a-number", "Room", null, "domain123456", 5);

        // when & then
        assertThatThrownBy(() -> adminPartyroomService.createPartyroomWithHost(command))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("simulateReactions — 활성 재생이 없으면 NotFoundException이 발생한다")
    void simulateReactionsNoActivePlaybackThrows() {
        // given
        Long partyroomId = 999L;
        PartyroomData partyroom = createTestPartyroom("Room", "linkdomain00");

        PartyroomPlaybackData playbackState = mock(PartyroomPlaybackData.class);
        when(playbackState.getCurrentPlaybackId()).thenReturn(null);

        when(adminPartyroomPort.findPartyroomById(partyroomId)).thenReturn(Optional.of(partyroom));
        when(adminPartyroomPort.findPlaybackState(new PartyroomId(partyroomId)))
                .thenReturn(Optional.of(playbackState));

        // when & then
        assertThatThrownBy(() -> adminPartyroomService.simulateReactions(partyroomId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("simulateReactions — 현재 DJ를 제외한 선택 크루에 apply를 위임하고 두 그룹(LIKE/GRAB)을 보존한다")
    void simulateReactionsDelegatesToPlaybackServiceExcludingCurrentDj() {
        // given
        Long partyroomId = 1L;
        PartyroomId roomId = new PartyroomId(partyroomId);
        PartyroomData partyroom = createTestPartyroom("Room", "linkdomain00");

        PlaybackId playbackId = new PlaybackId(500L);
        UserId djUserId = new UserId(99L);

        PartyroomPlaybackData playbackState = mock(PartyroomPlaybackData.class);
        when(playbackState.getCurrentPlaybackId()).thenReturn(playbackId);

        PlaybackData playback = mock(PlaybackData.class);
        when(playback.getUserId()).thenReturn(djUserId);

        // 현재 DJ 크루(제외 대상) + 일반 크루 2명
        CrewData djCrew = mock(CrewData.class);
        when(djCrew.getUserId()).thenReturn(djUserId);
        CrewData crewA = mock(CrewData.class);
        when(crewA.getUserId()).thenReturn(new UserId(1L));
        lenient().when(crewA.getId()).thenReturn(10L);
        CrewData crewB = mock(CrewData.class);
        when(crewB.getUserId()).thenReturn(new UserId(2L));
        lenient().when(crewB.getId()).thenReturn(20L);

        PlaybackAggregationData aggregation = mock(PlaybackAggregationData.class);
        when(aggregation.getLikeCount()).thenReturn(0);
        when(aggregation.getDislikeCount()).thenReturn(0);
        when(aggregation.getGrabCount()).thenReturn(0);

        when(adminPartyroomPort.findPartyroomById(partyroomId)).thenReturn(Optional.of(partyroom));
        when(adminPartyroomPort.findPlaybackState(roomId)).thenReturn(Optional.of(playbackState));
        when(playbackQueryService.getPlaybackById(playbackId)).thenReturn(playback);
        when(adminPartyroomPort.findActiveCrewByPartyroom(roomId))
                .thenReturn(List.of(djCrew, crewA, crewB));
        when(adminPartyroomPort.findPlaybackAggregation(playbackId)).thenReturn(Optional.of(aggregation));

        // executor: supplyAsync 태스크를 동기 실행(join이 조립된 SimulatedReaction 반환하도록)
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(reactionSimulationExecutor).execute(any());

        // when
        adminPartyroomService.simulateReactions(partyroomId);

        // then — 선택된 2명(crewA, crewB)에 apply 위임, 현재 DJ는 제외
        verify(playbackReactionSimulationService, times(2)).apply(
                any(UserId.class), any(CrewId.class), eq(playbackId), eq(roomId), any(ReactionType.class), anyInt());
        verify(playbackReactionSimulationService).apply(
                eq(new UserId(1L)), eq(new CrewId(10L)), eq(playbackId), eq(roomId), any(ReactionType.class), anyInt());
        verify(playbackReactionSimulationService).apply(
                eq(new UserId(2L)), eq(new CrewId(20L)), eq(playbackId), eq(roomId), any(ReactionType.class), anyInt());
        verify(playbackReactionSimulationService, never()).apply(
                eq(djUserId), any(), any(), any(), any(), anyInt());
    }
}
