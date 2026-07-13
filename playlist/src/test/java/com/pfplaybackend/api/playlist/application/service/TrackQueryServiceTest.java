package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.playlist.application.dto.PlaylistTrackDto;
import com.pfplaybackend.api.playlist.application.dto.TrackListView;
import com.pfplaybackend.api.playlist.application.port.out.PlaylistQueryPort;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackQueryServiceTest {

    @Mock PlaylistAggregatePort aggregatePort;
    @Mock PlaylistQueryPort queryPort;
    @InjectMocks TrackQueryService trackQueryService;

    private UserId userId;

    @BeforeEach
    void setUp() {
        userId = new UserId(1L);
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        ThreadLocalContext.setContext(authContext);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("getTracks — 플레이리스트 소유 확인 후 트랙 페이지를 반환한다")
    void getTracksSuccess() {
        // given
        Long playlistId = 1L;
        PlaylistData playlist = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name("My Playlist").type(PlaylistType.PLAYLIST).orderNumber(0).build();
        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.of(playlist));

        List<PlaylistTrackDto> tracks = List.of(
                new PlaylistTrackDto(1L, "abc", "Song A", 0, Duration.fromString("3:30"), "https://img.example.com/a.jpg"),
                new PlaylistTrackDto(2L, "def", "Song B", 1, Duration.fromString("4:00"), "https://img.example.com/b.jpg")
        );
        Page<PlaylistTrackDto> expectedPage = new PageImpl<>(tracks);
        when(queryPort.getTracksWithPagination(eq(new PlaylistId(playlistId)), any(Pageable.class))).thenReturn(expectedPage);

        // when
        TrackListView result = trackQueryService.getTracks(playlistId, 0, 10);

        // then
        assertThat(result.page().getContent()).hasSize(2);
        assertThat(result.page().getContent().get(0).name()).isEqualTo("Song A");
        // 커서 미설정(builder에 없음) → NOW 앵커 없음. NEXT는 클라이언트가 파생.
        assertThat(result.lastPlayedTrackId()).isNull();
        verify(aggregatePort).findPlaylistByIdAndOwner(playlistId, userId);
        verify(queryPort).getTracksWithPagination(eq(new PlaylistId(playlistId)), any(Pageable.class));
    }

    @Test
    @DisplayName("getTracks — 존재하지 않는 플레이리스트에 대해 NotFoundException이 발생한다")
    void getTracksNotFound() {
        // given
        Long playlistId = 999L;
        when(aggregatePort.findPlaylistByIdAndOwner(playlistId, userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> trackQueryService.getTracks(playlistId, 0, 10))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("isEmptyPlaylist — 트랙이 없으면 true를 반환한다")
    void isEmptyPlaylistTrue() {
        // given
        when(aggregatePort.hasTracksByPlaylist(new PlaylistId(1L))).thenReturn(false);

        // when & then
        assertThat(trackQueryService.isEmptyPlaylist(1L)).isTrue();
    }

    @Test
    @DisplayName("isEmptyPlaylist — 트랙이 있으면 false를 반환한다")
    void isEmptyPlaylistFalse() {
        // given
        when(aggregatePort.hasTracksByPlaylist(new PlaylistId(1L))).thenReturn(true);

        // when & then
        assertThat(trackQueryService.isEmptyPlaylist(1L)).isFalse();
    }
}
