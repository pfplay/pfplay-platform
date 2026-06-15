package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.adapter.out.persistence.PlaylistRepository;
import com.pfplaybackend.api.playlist.adapter.out.persistence.TrackRepository;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.virtualdj.application.dto.NewTrack;
import com.pfplaybackend.api.virtualdj.application.service.BotPlaylistEditor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class BotPlaylistEditorIT extends AbstractIntegrationTest {

    @Autowired private BotPlaylistEditor editor;
    @Autowired private TrackRepository trackRepository;
    @Autowired private PlaylistRepository playlistRepository;

    // ── 헬퍼 ──

    private PlaylistData seedPlaylist() {
        return playlistRepository.save(
                PlaylistData.create(1, "봇_플리", PlaylistType.PLAYLIST, new UserId(9999L)));
    }

    private List<TrackData> seedTracks(Long playlistId, int count) {
        List<TrackData> tracks = IntStream.rangeClosed(1, count)
                .mapToObj(i -> TrackData.builder()
                        .playlistId(new PlaylistId(playlistId))
                        .orderNumber(i)
                        .name("Old Song " + i)
                        .linkId("old" + i)
                        .duration(Duration.fromString("3:00"))
                        .thumbnailImage("thumb" + i)
                        .build())
                .collect(Collectors.toList());
        return trackRepository.saveAll(tracks);
    }

    // ── 케이스 1: n=2 swap ──

    @Test
    @DisplayName("swap — n=2 교체 시 크기 불변·헤드 정렬·prune 제거 확인")
    void swap_n2_교체_크기불변_헤드정렬() {
        // given
        PlaylistData playlist = seedPlaylist();
        Long playlistId = playlist.getId();
        List<TrackData> saved = seedTracks(playlistId, 5);
        entityManager.flush();
        entityManager.clear();

        // old4, old5 를 prune 대상으로 선택 (orderNumber 4, 5)
        List<TrackData> reloaded = trackRepository.findAllByPlaylistId(new PlaylistId(playlistId))
                .stream().sorted(Comparator.comparingInt(TrackData::getOrderNumber)).toList();
        Long old4Id = reloaded.get(3).getId(); // orderNumber=4 → old4
        Long old5Id = reloaded.get(4).getId(); // orderNumber=5 → old5

        List<Long> pruneIds = List.of(old4Id, old5Id);
        List<NewTrack> addTracks = List.of(
                new NewTrack("X", "newX", Duration.fromString("3:00"), "t"),
                new NewTrack("Y", "newY", Duration.fromString("3:10"), "t")
        );

        // when
        int swapped = editor.swap(playlistId, pruneIds, addTracks);
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(swapped).isEqualTo(2);

        List<TrackData> after = trackRepository.findAllByPlaylistId(new PlaylistId(playlistId))
                .stream().sorted(Comparator.comparingInt(TrackData::getOrderNumber)).toList();

        // 크기 불변: 5 - 2 + 2 = 5
        assertThat(after).hasSize(5);

        // prune 된 linkId 가 없어야 함
        Set<String> linkIds = after.stream().map(TrackData::getLinkId).collect(Collectors.toSet());
        assertThat(linkIds).doesNotContain("old4", "old5");

        // 신규 트랙 추가됨
        assertThat(linkIds).contains("newX", "newY");

        // orderNumber 가 1..5 연속 유일
        List<Integer> orders = after.stream().map(TrackData::getOrderNumber).sorted().toList();
        assertThat(orders).containsExactly(1, 2, 3, 4, 5);

        // 신규 트랙이 헤드(1 또는 2) 에 위치
        Set<Integer> newOrders = after.stream()
                .filter(t -> t.getLinkId().equals("newX") || t.getLinkId().equals("newY"))
                .map(TrackData::getOrderNumber)
                .collect(Collectors.toSet());
        assertThat(newOrders).allMatch(o -> o == 1 || o == 2);
    }

    // ── 케이스 2: addTracks 비어 있으면 n=0, 변경 없음 ──

    @Test
    @DisplayName("swap — addTracks 가 비어 있으면 0 반환, 플리 불변")
    void swap_addTracks_비어있으면_0반환() {
        // given
        PlaylistData playlist = seedPlaylist();
        Long playlistId = playlist.getId();
        List<TrackData> saved = seedTracks(playlistId, 5);
        entityManager.flush();
        entityManager.clear();

        List<TrackData> reloaded = trackRepository.findAllByPlaylistId(new PlaylistId(playlistId));
        Long someId = reloaded.get(0).getId();

        // when
        int swapped = editor.swap(playlistId, List.of(someId), List.of());
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(swapped).isEqualTo(0);

        List<TrackData> after = trackRepository.findAllByPlaylistId(new PlaylistId(playlistId));
        assertThat(after).hasSize(5);

        // someId 의 트랙이 여전히 존재
        Set<Long> ids = after.stream().map(TrackData::getId).collect(Collectors.toSet());
        assertThat(ids).contains(someId);
    }
}
