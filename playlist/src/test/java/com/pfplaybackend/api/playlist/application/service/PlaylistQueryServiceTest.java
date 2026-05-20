package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.application.dto.PlaylistSummaryDto;
import com.pfplaybackend.api.playlist.application.port.out.PlaylistQueryPort;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaylistQueryServiceTest {

    @Mock PlaylistQueryPort queryPort;
    @InjectMocks PlaylistQueryService playlistQueryService;

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
    @DisplayName("getPlaylists — 사용자의 전체 플레이리스트 목록을 반환한다")
    void getPlaylistsSuccess() {
        // given
        List<PlaylistSummaryDto> expected = List.of(
                new PlaylistSummaryDto(1L, "My Playlist", 1, PlaylistType.PLAYLIST, 5L),
                new PlaylistSummaryDto(2L, "Grab", 0, PlaylistType.GRABLIST, 3L)
        );
        when(queryPort.findAllByUserId(userId)).thenReturn(expected);

        // when
        List<PlaylistSummaryDto> result = playlistQueryService.getPlaylists();

        // then
        assertThat(result).hasSize(2)
                .isEqualTo(expected);
        verify(queryPort).findAllByUserId(userId);
    }

    @Test
    @DisplayName("getPlaylists — 플레이리스트가 없으면 빈 목록을 반환한다")
    void getPlaylistsEmpty() {
        // given
        when(queryPort.findAllByUserId(userId)).thenReturn(Collections.emptyList());

        // when
        List<PlaylistSummaryDto> result = playlistQueryService.getPlaylists();

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getPlaylist — 특정 플레이리스트를 ID로 조회한다")
    void getPlaylistSuccess() {
        // given
        Long playlistId = 1L;
        PlaylistSummaryDto expected = new PlaylistSummaryDto(playlistId, "My Playlist", 0, PlaylistType.PLAYLIST, 10L);
        when(queryPort.findByIdAndUserId(playlistId, userId)).thenReturn(expected);

        // when
        PlaylistSummaryDto result = playlistQueryService.getPlaylist(playlistId);

        // then
        assertThat(result).isEqualTo(expected);
        verify(queryPort).findByIdAndUserId(playlistId, userId);
    }

    @Test
    @DisplayName("isOwnedBy — playlist 가 본인 소유면 true")
    void isOwnedByReturnsTrueWhenOwned() {
        // given
        PlaylistSummaryDto owned = new PlaylistSummaryDto(99L, "My Playlist", 5, PlaylistType.PLAYLIST, 1L);
        when(queryPort.findByIdAndUserId(99L, userId)).thenReturn(owned);

        // when
        boolean result = playlistQueryService.isOwnedBy(99L, userId);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isOwnedBy — playlist 가 타인 소유거나 미존재면 false")
    void isOwnedByReturnsFalseWhenNotOwned() {
        // given
        when(queryPort.findByIdAndUserId(99L, userId)).thenReturn(null);

        // when
        boolean result = playlistQueryService.isOwnedBy(99L, userId);

        // then
        assertThat(result).isFalse();
    }
}
