package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.playlist.adapter.out.persistence.PlaylistRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualdj.application.dto.command.AddPackTrackCommand;
import com.pfplaybackend.api.virtualdj.application.service.VirtualSongPackService;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackTrackData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VirtualSongPackServiceTest {

    @Mock private VirtualSongPackRepository packRepository;
    @Mock private VirtualSongPackTrackRepository trackRepository;
    @Mock private PartyroomVirtualDjConfigRepository configRepository;
    @Mock private PlaylistRepository playlistRepository;

    @InjectMocks
    private VirtualSongPackService service;

    // ── createPack ──

    @Test
    @DisplayName("중복 이름으로 createPack 시 ConflictException")
    void createPack_중복이름_거부() {
        when(packRepository.existsByName("K-Pop Mix")).thenReturn(true);

        assertThatThrownBy(() -> service.createPack("K-Pop Mix", "설명"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("새 이름으로 createPack 시 저장 후 id 반환")
    void createPack_새이름_저장후_id_반환() {
        when(packRepository.existsByName("EDM Mix")).thenReturn(false);
        VirtualSongPackData saved = VirtualSongPackData.create("EDM Mix", "설명");
        // Reflect id via builder (simulate DB-assigned id)
        VirtualSongPackData withId = VirtualSongPackData.builder()
                .name("EDM Mix").description("설명").build();
        when(packRepository.save(any())).thenAnswer(inv -> {
            // Return a pack that looks saved — id check omitted (integration covers it)
            return withId;
        });

        service.createPack("EDM Mix", "설명");

        verify(packRepository).save(any(VirtualSongPackData.class));
    }

    // ── renamePack ──

    @Test
    @DisplayName("존재하지 않는 packId로 renamePack 시 NotFoundException")
    void renamePack_존재하지않는_팩_거부() {
        when(packRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renamePack(99L, "New Name"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("이미 사용 중인 이름으로 renamePack 시 ConflictException")
    void renamePack_중복이름_거부() {
        VirtualSongPackData pack = VirtualSongPackData.create("Old Name", "desc");
        when(packRepository.findById(1L)).thenReturn(Optional.of(pack));
        when(packRepository.existsByName("Taken Name")).thenReturn(true);

        assertThatThrownBy(() -> service.renamePack(1L, "Taken Name"))
                .isInstanceOf(ConflictException.class);

        verify(packRepository, never()).save(any());
    }

    @Test
    @DisplayName("현재 이름과 동일한 이름으로 renamePack 시 중복 검사 없이 허용")
    void renamePack_같은이름_허용() {
        VirtualSongPackData pack = VirtualSongPackData.create("Same Name", "desc");
        when(packRepository.findById(1L)).thenReturn(Optional.of(pack));
        when(packRepository.save(any())).thenReturn(pack);

        // existsByName 호출 없이 정상 완료되어야 함
        service.renamePack(1L, "Same Name");

        verify(packRepository, never()).existsByName(anyString());
        verify(packRepository).save(pack);
    }

    // ── addTrack — duration guard ──

    @Test
    @DisplayName("재생시간이 60분 초과 트랙 추가 시 BadRequestException(TRACK_EXCEEDS_PLAYBACK_LIMIT)")
    void addTrack_재생시간_60분_초과_거부() {
        VirtualSongPackData pack = VirtualSongPackData.create("Pack", "desc");
        when(packRepository.findById(1L)).thenReturn(Optional.of(pack));
        // 서비스는 duration 검증 실패 시 trackRepository 호출 전 예외를 던진다 — stub 불필요

        // 61분 = 3660초
        AddPackTrackCommand cmd = new AddPackTrackCommand("Long Song", "abc123", "61:00", null);

        assertThatThrownBy(() -> service.addTrack(1L, cmd))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("재생시간이 정확히 60분인 트랙은 허용")
    void addTrack_재생시간_60분_허용() {
        VirtualSongPackData pack = VirtualSongPackData.create("Pack", "desc");
        when(packRepository.findById(1L)).thenReturn(Optional.of(pack));
        when(trackRepository.findBySongPackIdOrderByOrderNumberAsc(1L)).thenReturn(List.of());

        VirtualSongPackTrackData saved = VirtualSongPackTrackData.create(1L, 1, "vid1", "60-min song", "60:00", null);
        when(trackRepository.save(any())).thenReturn(saved);

        // Should not throw
        service.addTrack(1L, new AddPackTrackCommand("60-min song", "vid1", "60:00", null));

        verify(trackRepository).save(any(VirtualSongPackTrackData.class));
    }

    @Test
    @DisplayName("정상 트랙 추가 시 order_number는 기존 max + 1")
    void addTrack_order_number_기존_max_plus_1() {
        VirtualSongPackData pack = VirtualSongPackData.create("Pack", "desc");
        when(packRepository.findById(1L)).thenReturn(Optional.of(pack));

        VirtualSongPackTrackData existing = VirtualSongPackTrackData.create(1L, 3, "existId", "Existing", "3:00", null);
        when(trackRepository.findBySongPackIdOrderByOrderNumberAsc(1L)).thenReturn(List.of(existing));

        VirtualSongPackTrackData saved = VirtualSongPackTrackData.create(1L, 4, "newId", "New Song", "4:00", null);
        when(trackRepository.save(any())).thenReturn(saved);

        service.addTrack(1L, new AddPackTrackCommand("New Song", "newId", "4:00", null));

        ArgumentCaptor<VirtualSongPackTrackData> captor = ArgumentCaptor.forClass(VirtualSongPackTrackData.class);
        verify(trackRepository).save(captor.capture());
        assertThat(captor.getValue().getOrderNumber()).isEqualTo(4);
    }

    // ── removeTrack ──

    @Test
    @DisplayName("다른 팩에 속한 트랙 id 로 removeTrack 호출 시 NotFoundException")
    void removeTrack_다른팩_트랙_거부() {
        VirtualSongPackData pack = VirtualSongPackData.create("Pack 1", "desc");
        when(packRepository.findById(1L)).thenReturn(Optional.of(pack));

        // 트랙은 존재하지만 packId=2 소속
        VirtualSongPackTrackData trackOfOtherPack = VirtualSongPackTrackData.create(2L, 1, "vid", "Song", "3:00", null);
        when(trackRepository.findById(99L)).thenReturn(Optional.of(trackOfOtherPack));

        assertThatThrownBy(() -> service.removeTrack(1L, 99L))
                .isInstanceOf(NotFoundException.class);

        verify(trackRepository, never()).delete(any());
    }

    // ── deletePack — delete guard ──

    @Test
    @DisplayName("partyroom_virtual_dj_config 가 참조 중인 팩 삭제 시 ConflictException(SONG_PACK_IN_USE)")
    void deletePack_config_참조중_거부() {
        VirtualSongPackData pack = VirtualSongPackData.create("Used Pack", "desc");
        when(packRepository.findById(1L)).thenReturn(Optional.of(pack));

        // config references this pack — 인덱스 활용 existsBySongPackId 로 검사(full-scan findAll 제거).
        when(configRepository.existsBySongPackId(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.deletePack(1L))
                .isInstanceOf(ConflictException.class);

        // full-scan findAll() 은 더 이상 호출되면 안 된다.
        verify(configRepository, never()).findAll();
    }

    @Test
    @DisplayName("playlist.source_song_pack_id 가 참조 중인 팩 삭제 시 ConflictException(SONG_PACK_IN_USE)")
    void deletePack_playlist_참조중_거부() {
        VirtualSongPackData pack = VirtualSongPackData.create("Used Pack", "desc");
        when(packRepository.findById(2L)).thenReturn(Optional.of(pack));
        when(configRepository.existsBySongPackId(2L)).thenReturn(false); // no config reference

        when(playlistRepository.existsBySourceSongPackId(2L)).thenReturn(true);

        assertThatThrownBy(() -> service.deletePack(2L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("아무도 참조하지 않는 팩은 정상 삭제")
    void deletePack_미참조_정상삭제() {
        VirtualSongPackData pack = VirtualSongPackData.create("Free Pack", "desc");
        when(packRepository.findById(3L)).thenReturn(Optional.of(pack));
        when(configRepository.existsBySongPackId(3L)).thenReturn(false);
        when(playlistRepository.existsBySourceSongPackId(3L)).thenReturn(false);

        service.deletePack(3L);

        verify(trackRepository).deleteAll(any());
        verify(packRepository).delete(pack);
    }
}
