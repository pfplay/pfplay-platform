package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.playlist.application.dto.GrabbedTrackDto;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.playlist.domain.enums.InsertPosition;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GrabTrackServiceTest {

    @Mock
    PlaylistAggregatePort aggregatePort;
    @Mock
    TrackCommandService trackCommandService;

    @InjectMocks
    GrabTrackService grabTrackService;

    private static final UserId USER_ID = new UserId(1L);
    private static final String LINK_ID = "abc123";

    private TrackData createTrackData() {
        return TrackData.builder()
                .name("Test Song")
                .linkId(LINK_ID)
                .duration(Duration.fromString("3:30"))
                .thumbnailImage("https://img.example.com/thumb.jpg")
                .build();
    }

    private PlaylistData createGrablist() {
        return PlaylistData.builder()
                .id(100L).ownerId(USER_ID).orderNumber(0).name("Grab").type(PlaylistType.GRABLIST).build();
    }

    @Test
    @DisplayName("grabTrack — 정상 그랩 시 트랙이 GRABLIST에 추가되고 GrabbedTrackDto가 반환된다")
    void grabTrackSuccess() {
        // given
        TrackData track = createTrackData();
        PlaylistData grablist = createGrablist();

        when(aggregatePort.findFirstTrackByLink(LINK_ID)).thenReturn(track);
        when(aggregatePort.findPlaylistByOwnerAndType(USER_ID, PlaylistType.GRABLIST)).thenReturn(grablist);
        when(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(grablist.getId()), LINK_ID)).thenReturn(Optional.empty());
        when(trackCommandService.insertTrack(eq(grablist.getId()), any(AddTrackCommand.class), eq(InsertPosition.TAIL))).thenReturn(777L);

        // when
        GrabbedTrackDto result = grabTrackService.grabTrack(USER_ID, LINK_ID);

        // then — grab 은 GRABLIST 맨 뒤(TAIL)로 추가 (스펙 §9)
        verify(trackCommandService).insertTrack(eq(grablist.getId()), any(AddTrackCommand.class), eq(InsertPosition.TAIL));
        assertThat(result.trackId()).isEqualTo(777L);
        assertThat(result.playlistId()).isEqualTo(grablist.getId());
    }

    @Test
    @DisplayName("grabTrack — 존재하지 않는 트랙으로 그랩 시 NotFoundException이 발생한다")
    void grabTrackTrackNotFound() {
        // given
        when(aggregatePort.findFirstTrackByLink(LINK_ID)).thenReturn(null);

        // when & then
        assertThatThrownBy(() -> grabTrackService.grabTrack(USER_ID, LINK_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("grabTrack — GRABLIST에 이미 동일한 LINK_ID가 있으면 ConflictException이 발생한다")
    void grabTrackDuplicateTrack() {
        // given
        TrackData track = createTrackData();
        PlaylistData grablist = createGrablist();

        when(aggregatePort.findFirstTrackByLink(LINK_ID)).thenReturn(track);
        when(aggregatePort.findPlaylistByOwnerAndType(USER_ID, PlaylistType.GRABLIST)).thenReturn(grablist);
        when(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(grablist.getId()), LINK_ID))
                .thenReturn(Optional.of(track));

        // when & then
        assertThatThrownBy(() -> grabTrackService.grabTrack(USER_ID, LINK_ID))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("grabTrack — 정상 그랩 시 원본 트랙의 메타데이터가 복사된다")
    void grabTrackCopiesMetadata() {
        // given
        TrackData track = createTrackData();
        PlaylistData grablist = createGrablist();

        when(aggregatePort.findFirstTrackByLink(LINK_ID)).thenReturn(track);
        when(aggregatePort.findPlaylistByOwnerAndType(USER_ID, PlaylistType.GRABLIST)).thenReturn(grablist);
        when(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(grablist.getId()), LINK_ID)).thenReturn(Optional.empty());

        ArgumentCaptor<AddTrackCommand> captor = ArgumentCaptor.forClass(AddTrackCommand.class);

        // when
        grabTrackService.grabTrack(USER_ID, LINK_ID);

        // then
        verify(trackCommandService).insertTrack(eq(grablist.getId()), captor.capture(), eq(InsertPosition.TAIL));
        AddTrackCommand command = captor.getValue();
        assertThat(command.name()).isEqualTo("Test Song");
        assertThat(command.linkId()).isEqualTo(LINK_ID);
        assertThat(command.thumbnailImage()).isEqualTo("https://img.example.com/thumb.jpg");
    }
}
