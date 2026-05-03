package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.partyroom.LinkEnterDto;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartyroomAccessQueryServiceTest {

    @Mock PartyroomAggregatePort aggregatePort;
    @Mock PlaybackRepository playbackRepository;
    @InjectMocks PartyroomAccessQueryService partyroomAccessQueryService;

    @Test
    @DisplayName("getPartyroomByLink — 재생 비활성 시 playback이 null이다")
    void getPartyroomByLinkWithoutPlayback() {
        // given
        String linkDomain = "test-link";
        PartyroomData partyroomData = PartyroomData.builder()
                .id(42L)
                .hostId(new UserId(1L))
                .stageType(StageType.GENERAL)
                .title("Room")
                .introduction("Intro")
                .linkDomain(LinkDomain.of(linkDomain))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(10))
                .status(PartyroomStatus.ACTIVE)
                .build()
                .assignPartyroomId(new PartyroomId(42L));
        when(aggregatePort.findByLinkDomain(LinkDomain.of(linkDomain)))
                .thenReturn(Optional.of(partyroomData));
        when(aggregatePort.countActiveCrews(new PartyroomId(42L))).thenReturn(5L);

        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(new PartyroomId(42L));
        when(aggregatePort.findPlaybackState(new PartyroomId(42L))).thenReturn(playbackState);

        // when
        LinkEnterDto result = partyroomAccessQueryService.getPartyroomByLink(linkDomain);

        // then
        assertThat(result.partyroomId()).isEqualTo(42L);
        assertThat(result.title()).isEqualTo("Room");
        assertThat(result.introduction()).isEqualTo("Intro");
        assertThat(result.crewCount()).isEqualTo(5L);
        assertThat(result.playback()).isNull();
    }

    @Test
    @DisplayName("getPartyroomByLink — 재생 활성 시 playback 정보가 포함된다")
    void getPartyroomByLinkWithPlayback() {
        // given
        String linkDomain = "test-link";
        PartyroomData partyroomData = PartyroomData.builder()
                .id(42L)
                .hostId(new UserId(1L))
                .stageType(StageType.GENERAL)
                .title("Room")
                .introduction("Intro")
                .linkDomain(LinkDomain.of(linkDomain))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(10))
                .status(PartyroomStatus.ACTIVE)
                .build()
                .assignPartyroomId(new PartyroomId(42L));
        when(aggregatePort.findByLinkDomain(LinkDomain.of(linkDomain)))
                .thenReturn(Optional.of(partyroomData));
        when(aggregatePort.countActiveCrews(new PartyroomId(42L))).thenReturn(10L);

        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(new PartyroomId(42L));
        playbackState.activate(new PlaybackId(100L), new CrewId(1L));
        when(aggregatePort.findPlaybackState(new PartyroomId(42L))).thenReturn(playbackState);

        PlaybackData playbackData = PlaybackData.builder()
                .id(100L)
                .name("Test Song")
                .thumbnailImage("thumb.jpg")
                .duration(Duration.fromString("3:30"))
                .build();
        when(playbackRepository.findById(100L)).thenReturn(Optional.of(playbackData));

        // when
        LinkEnterDto result = partyroomAccessQueryService.getPartyroomByLink(linkDomain);

        // then
        assertThat(result.partyroomId()).isEqualTo(42L);
        assertThat(result.title()).isEqualTo("Room");
        assertThat(result.crewCount()).isEqualTo(10L);
        assertThat(result.playback()).isNotNull();
        assertThat(result.playback().name()).isEqualTo("Test Song");
        assertThat(result.playback().thumbnailImage()).isEqualTo("thumb.jpg");
    }
}
