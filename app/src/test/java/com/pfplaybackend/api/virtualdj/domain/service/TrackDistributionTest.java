package com.pfplaybackend.api.virtualdj.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TrackDistributionTest {

    @Test
    @DisplayName("10트랙 3조각 → [4,3,3] 연속 분할")
    void split_10_into_3() {
        List<Integer> tracks = IntStream.rangeClosed(1, 10).boxed().toList();
        assertThat(TrackDistribution.chunkFor(tracks, 3, 0)).containsExactly(1, 2, 3, 4);
        assertThat(TrackDistribution.chunkFor(tracks, 3, 1)).containsExactly(5, 6, 7);
        assertThat(TrackDistribution.chunkFor(tracks, 3, 2)).containsExactly(8, 9, 10);
    }

    @Test
    @DisplayName("균등 분할 — 9트랙 3조각 → [3,3,3]")
    void split_9_into_3_even() {
        List<Integer> tracks = IntStream.rangeClosed(1, 9).boxed().toList();
        assertThat(TrackDistribution.chunkFor(tracks, 3, 0)).containsExactly(1, 2, 3);
        assertThat(TrackDistribution.chunkFor(tracks, 3, 1)).containsExactly(4, 5, 6);
        assertThat(TrackDistribution.chunkFor(tracks, 3, 2)).containsExactly(7, 8, 9);
    }

    @Test
    @DisplayName("effectiveDjTarget = min(djCount, trackCount)")
    void effective_clamped() {
        assertThat(TrackDistribution.effectiveDjTarget(5, 2)).isEqualTo(2);
        assertThat(TrackDistribution.effectiveDjTarget(3, 10)).isEqualTo(3);
    }

    @Test
    @DisplayName("effective 범위 안에서 조각 합 = 전체 트랙(누락·중복 없음)")
    void chunks_partition_all_tracks() {
        List<Integer> tracks = IntStream.rangeClosed(1, 7).boxed().toList();
        int djCount = 3; // effective=min(3,7)=3
        List<Integer> reassembled = IntStream.range(0, djCount)
                .boxed()
                .flatMap(slot -> TrackDistribution.chunkFor(tracks, djCount, slot).stream())
                .toList();
        assertThat(reassembled).containsExactlyElementsOf(tracks); // [3,2,2] 조각이 순서대로 전체 복원
    }
}
