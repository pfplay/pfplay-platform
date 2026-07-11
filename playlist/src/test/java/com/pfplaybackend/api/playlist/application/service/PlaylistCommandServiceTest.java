package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaylistCommandServiceTest {

    @Mock
    private PlaylistAggregatePort aggregatePort;

    @InjectMocks
    private PlaylistCommandService playlistCommandService;

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

    // ========== createDefaultDjPlaylist ==========

    @Test
    @DisplayName("createDefaultDjPlaylist — PLAYLIST 타입 '내 플레이리스트'(order 1)를 정책 검사 없이 저장한다")
    void createDefaultDjPlaylist_savesPlaylistTypeWithFixedName() {
        playlistCommandService.createDefaultDjPlaylist(userId);

        ArgumentCaptor<PlaylistData> captor = ArgumentCaptor.forClass(PlaylistData.class);
        verify(aggregatePort).savePlaylist(captor.capture());
        PlaylistData saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(PlaylistType.PLAYLIST);
        assertThat(saved.getName()).isEqualTo("내 플레이리스트");
        assertThat(saved.getOrderNumber()).isEqualTo(1);
        assertThat(saved.getOwnerId()).isEqualTo(userId);
        // 정책 우회: findPlaylistsByOwnerAndType(상한 카운트용 조회)가 호출되지 않아야 한다
        verify(aggregatePort, never()).findPlaylistsByOwnerAndType(any(), any());
    }

    // ========== createPlaylist ==========

    @Test
    @DisplayName("플레이리스트 생성 성공 — save 호출")
    void createPlaylistSuccess() {
        // given
        String playlistName = "My Playlist";
        when(aggregatePort.findPlaylistsByOwnerAndType(userId, PlaylistType.PLAYLIST))
                .thenReturn(Collections.emptyList());

        PlaylistData savedData = PlaylistData.builder()
                .id(1L).ownerId(userId).name(playlistName).type(PlaylistType.PLAYLIST).orderNumber(1).build();
        when(aggregatePort.savePlaylist(any(PlaylistData.class))).thenReturn(savedData);

        // when
        PlaylistData result = playlistCommandService.createPlaylist(playlistName);

        // then
        verify(aggregatePort, times(1)).savePlaylist(any(PlaylistData.class));
        assertThat(result.getName()).isEqualTo(playlistName);
    }

    @Test
    @DisplayName("플레이리스트 생성 실패 — 제약 위반 시 예외 전파")
    void createPlaylistExceedConstraints() {
        // given
        String playlistName = "My Playlist";
        List<PlaylistData> existingPlaylists = Collections.nCopies(10,
                PlaylistData.builder().ownerId(userId).name("pl").type(PlaylistType.PLAYLIST).orderNumber(0).build());
        when(aggregatePort.findPlaylistsByOwnerAndType(userId, PlaylistType.PLAYLIST))
                .thenReturn(existingPlaylists);

        // when & then — PlaylistCreationPolicy.enforce(FM, 10) throws ConflictException since FM limit is 10
        assertThatThrownBy(() -> playlistCommandService.createPlaylist(playlistName))
                .isInstanceOf(ConflictException.class);
    }

    // ========== renamePlaylist ==========

    @Test
    @DisplayName("플레이리스트 이름 변경 성공 — 반환된 이름 검증")
    void renamePlaylistSuccess() {
        // given
        Long playlistId = 1L;
        String newName = "Renamed Playlist";

        PlaylistData playlistData = PlaylistData.builder()
                .id(playlistId).ownerId(userId).name("Old Name").type(PlaylistType.PLAYLIST).orderNumber(0).build();

        when(aggregatePort.findPlaylistByIdAndOwnerAndType(playlistId, userId, PlaylistType.PLAYLIST))
                .thenReturn(Optional.of(playlistData));
        when(aggregatePort.savePlaylist(any(PlaylistData.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        PlaylistData result = playlistCommandService.renamePlaylist(playlistId, newName);

        // then
        verify(aggregatePort, times(1)).savePlaylist(any(PlaylistData.class));
        assertThat(result.getName()).isEqualTo(newName);
    }

    @Test
    @DisplayName("플레이리스트 이름 변경 실패 — 미존재 시 NotFoundException")
    void renamePlaylistNotFound() {
        // given
        Long playlistId = 999L;
        when(aggregatePort.findPlaylistByIdAndOwnerAndType(playlistId, userId, PlaylistType.PLAYLIST))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> playlistCommandService.renamePlaylist(playlistId, "New Name"))
                .isInstanceOf(NotFoundException.class);
    }

    // ========== deletePlaylist ==========

    @Test
    @DisplayName("플레이리스트 삭제 성공 — deletePlaylistsByIds 호출")
    void deletePlaylistSuccess() {
        // given
        List<Long> playlistIds = List.of(1L, 2L);

        PlaylistData pd1 = PlaylistData.builder().id(1L).ownerId(userId).name("p1").type(PlaylistType.PLAYLIST).orderNumber(0).build();
        PlaylistData pd2 = PlaylistData.builder().id(2L).ownerId(userId).name("p2").type(PlaylistType.PLAYLIST).orderNumber(1).build();

        when(aggregatePort.findAllPlaylistsByOwner(userId)).thenReturn(List.of(pd1, pd2));
        when(aggregatePort.deletePlaylistsByIds(playlistIds)).thenReturn(2L);

        // when
        playlistCommandService.deletePlaylist(playlistIds);

        // then
        verify(aggregatePort, times(1)).deletePlaylistsByIds(playlistIds);
    }

    @Test
    @DisplayName("플레이리스트 삭제 실패 — 소유하지 않은 ID 포함 시 NotFoundException")
    void deletePlaylistNotOwned() {
        // given
        List<Long> playlistIds = List.of(1L, 999L);

        PlaylistData pd1 = PlaylistData.builder().id(1L).ownerId(userId).name("p1").type(PlaylistType.PLAYLIST).orderNumber(0).build();

        when(aggregatePort.findAllPlaylistsByOwner(userId)).thenReturn(List.of(pd1));

        // when & then
        assertThatThrownBy(() -> playlistCommandService.deletePlaylist(playlistIds))
                .isInstanceOf(NotFoundException.class);
    }
}
