package com.pfplaybackend.api.admin.application.service;

import com.pfplaybackend.api.admin.application.dto.command.InitializeDemoCommand;
import com.pfplaybackend.api.admin.application.dto.result.AdminPartyroomListResult;
import com.pfplaybackend.api.admin.application.dto.result.DemoStatusResult;
import com.pfplaybackend.api.admin.application.port.out.AdminAvatarResourcePort;
import com.pfplaybackend.api.admin.application.port.out.AdminMemberPort;
import com.pfplaybackend.api.admin.application.port.out.AdminPartyroomPort;
import com.pfplaybackend.api.admin.application.port.out.AdminPlaylistPort;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminDemoServiceTest {

    @Mock
    private AdminUserService adminUserService;

    @Mock
    private AdminMemberPort adminMemberPort;

    @Mock
    private AdminAvatarResourcePort adminAvatarResourcePort;

    @Mock
    private AdminPlaylistPort adminPlaylistPort;

    @Mock
    private AdminPartyroomPort adminPartyroomPort;

    @Mock
    private PartyroomAccessCommandService partyroomAccessCommandService;

    @Mock
    private PlaybackControlPort playbackControlPort;

    @Mock
    private Clock clock;

    @InjectMocks
    private AdminDemoService adminDemoService;

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        lenient().when(clock.millis()).thenReturn(1735689600000L);
    }

    private PartyroomData createPartyroom(Long id, StageType stageType, String title, boolean terminated) {
        return PartyroomData.builder()
                .id(id)
                .partyroomId(new PartyroomId(id))
                .hostId(new UserId(1L))
                .stageType(stageType)
                .title(title)
                .linkDomain(LinkDomain.of("test-room-" + id))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .noticeContent("")
                .isTerminated(terminated)
                .build();
    }

    @Test
    @DisplayName("getDemoEnvironmentStatus \u2014 \uac00\uc0c1 \uba64\ubc84\uac00 \uc788\uc73c\uba74 initialized=true\ub97c \ubc18\ud658\ud55c\ub2e4")
    void getDemoEnvironmentStatusInitializedTrue() {
        // given
        when(adminMemberPort.countMembersByProviderType(ProviderType.LOCAL)).thenReturn(10L);
        PartyroomData generalRoom = createPartyroom(2L, StageType.GENERAL, "General Room", false);
        when(adminPartyroomPort.findAllPartyrooms()).thenReturn(List.of(generalRoom));

        // when
        DemoStatusResult result = adminDemoService.getDemoEnvironmentStatus();

        // then
        assertThat(result.initialized()).isTrue();
        assertThat(result.virtualMemberCount()).isEqualTo(10L);
        assertThat(result.generalRoomCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getDemoEnvironmentStatus \u2014 \uac00\uc0c1 \uba64\ubc84\uac00 \uc5c6\uc73c\uba74 initialized=false\ub97c \ubc18\ud658\ud55c\ub2e4")
    void getDemoEnvironmentStatusInitializedFalse() {
        // given
        when(adminMemberPort.countMembersByProviderType(ProviderType.LOCAL)).thenReturn(0L);
        when(adminPartyroomPort.findAllPartyrooms()).thenReturn(Collections.emptyList());

        // when
        DemoStatusResult result = adminDemoService.getDemoEnvironmentStatus();

        // then
        assertThat(result.initialized()).isFalse();
        assertThat(result.virtualMemberCount()).isZero();
        assertThat(result.generalRoomCount()).isZero();
    }

    @Test
    @DisplayName("getPartyrooms \u2014 \ud65c\uc131 \ud30c\ud2f0\ub8f8 \ubaa9\ub85d\uc744 \ubc18\ud658\ud55c\ub2e4")
    void getPartyroomsReturnsActiveOnly() {
        // given
        PartyroomData terminatedRoom = createPartyroom(1L, StageType.GENERAL, "Terminated Room", true);
        PartyroomData activeRoom = createPartyroom(2L, StageType.GENERAL, "Active Room", false);

        when(adminPartyroomPort.findAllPartyrooms()).thenReturn(List.of(terminatedRoom, activeRoom));
        when(adminPartyroomPort.countActiveCrewByPartyroom(new PartyroomId(2L))).thenReturn(5L);
        when(adminPartyroomPort.findDjsByPartyroomOrderByOrder(new PartyroomId(2L))).thenReturn(Collections.emptyList());

        PartyroomPlaybackData playbackState = mock(PartyroomPlaybackData.class);
        when(playbackState.isActivated()).thenReturn(false);
        when(adminPartyroomPort.findPlaybackState(new PartyroomId(2L))).thenReturn(Optional.of(playbackState));

        // when
        AdminPartyroomListResult result = adminDemoService.getPartyrooms();

        // then
        assertThat(result.partyrooms()).hasSize(1);
        assertThat(result.partyrooms().get(0).title()).isEqualTo("Active Room");
        assertThat(result.partyrooms().get(0).crewCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("initializeDemoEnvironment \u2014 \uc544\ubc14\ud0c0 \ub9ac\uc18c\uc2a4\uac00 \uc5c6\uc73c\uba74 \uc608\uc678\uac00 \ubc1c\uc0dd\ud55c\ub2e4")
    void initializeDemoEnvironmentNoAvatarResourcesThrows() {
        // given
        PartyroomData mainStage = createPartyroom(1L, StageType.MAIN, "Main Stage", false);
        when(adminPartyroomPort.findAllPartyrooms()).thenReturn(List.of(mainStage));
        when(adminAvatarResourcePort.findAllAvatarBodyResources()).thenReturn(Collections.emptyList());
        when(adminAvatarResourcePort.findAllAvatarFaceResources()).thenReturn(Collections.emptyList());

        InitializeDemoCommand command = new InitializeDemoCommand(5, "Demo Room", "Welcome", true);

        // when & then
        assertThatThrownBy(() -> adminDemoService.initializeDemoEnvironment(command))
                .isInstanceOf(BadRequestException.class);
    }
}
