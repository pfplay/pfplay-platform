package com.pfplaybackend.api.playlist.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #222 회귀 잠금 — skip(자의/타의/time-limit) 시 "재생된 트랙이 본인 플레이리스트 최하단으로" invariant 의
 * 산술 핵심인 {@link TrackRepository#rotatePlayedOrder} 의 SQL 동작을 잠근다.
 *
 * <p>E/#3 에서 legacy {@code reorderTracks} 는 단일 CASE {@code rotatePlayedOrder} 로 일반화되어 제거됐다.
 * k=1 케이스가 legacy reorderTracks(pid, total) 와 산술적으로 동일함은
 * {@link #rotatePlayedOrder_k_eq_1_equivalent_to_legacy_reorder} 가 명시적으로 잠근다.
 * DJ 큐 회전 산술은 PartyroomAggregateServiceTest.rotatesCorrectly 가 별도로 잠금.
 */
@Transactional
class TrackRepositoryReorderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TrackRepository trackRepository;

    private TrackData saveTrack(long playlistId, int orderNumber, String linkId) {
        return trackRepository.save(TrackData.builder()
                .playlistId(new PlaylistId(playlistId))
                .orderNumber(orderNumber)
                .name("track-" + orderNumber)
                .linkId(linkId)
                .duration(Duration.fromString("3:30"))
                .thumbnailImage("thumb.jpg")
                .build());
    }

    // ===== rotatePlayedOrder (E/#3: over-limit skip — k번째 재생 트랙을 최하단으로) =====

    @Test
    @DisplayName("rotatePlayedOrder(k>1) — k번 트랙은 최하단, k 이전은 불변, k 이후는 -1 (갭 없음)")
    void rotatePlayedOrder_k_gt_1() {
        // given — 플레이리스트 5트랙 [1,2,3,4,5]
        long playlistId = 9500L;
        TrackData t1 = saveTrack(playlistId, 1, "r-link-1");
        TrackData t2 = saveTrack(playlistId, 2, "r-link-2");
        TrackData t3 = saveTrack(playlistId, 3, "r-link-3");
        TrackData t4 = saveTrack(playlistId, 4, "r-link-4");
        TrackData t5 = saveTrack(playlistId, 5, "r-link-5");
        flushAndClear();

        // when — k=3 재생 트랙 최하단 이동 (total=5)
        trackRepository.rotatePlayedOrder(playlistId, 3, 5L);
        flushAndClear();

        // then — 갭/중복 없음: 결과 orderNumber 집합 = [1,2,3,4,5]
        List<Integer> allOrders = List.of(
                trackRepository.findById(t1.getId()).orElseThrow().getOrderNumber(),
                trackRepository.findById(t2.getId()).orElseThrow().getOrderNumber(),
                trackRepository.findById(t3.getId()).orElseThrow().getOrderNumber(),
                trackRepository.findById(t4.getId()).orElseThrow().getOrderNumber(),
                trackRepository.findById(t5.getId()).orElseThrow().getOrderNumber());
        assertThat(allOrders).containsExactlyInAnyOrder(1, 2, 3, 4, 5);

        // then — 개별 이동 검증
        // k < 3: 불변
        assertThat(trackRepository.findById(t1.getId()).orElseThrow().getOrderNumber()).isEqualTo(1);
        assertThat(trackRepository.findById(t2.getId()).orElseThrow().getOrderNumber()).isEqualTo(2);
        // k = 3: 최하단(5)으로
        assertThat(trackRepository.findById(t3.getId()).orElseThrow().getOrderNumber()).isEqualTo(5);
        // k > 3: 한 칸씩 앞으로
        assertThat(trackRepository.findById(t4.getId()).orElseThrow().getOrderNumber()).isEqualTo(3);
        assertThat(trackRepository.findById(t5.getId()).orElseThrow().getOrderNumber()).isEqualTo(4);
    }

    @Test
    @DisplayName("rotatePlayedOrder(k=1) — reorderTracks(pid, total)과 산술적으로 동일")
    void rotatePlayedOrder_k_eq_1_equivalent_to_legacy_reorder() {
        // given — 플레이리스트 4트랙 [1,2,3,4]
        long playlistId = 9600L;
        TrackData t1 = saveTrack(playlistId, 1, "s-link-1");
        TrackData t2 = saveTrack(playlistId, 2, "s-link-2");
        TrackData t3 = saveTrack(playlistId, 3, "s-link-3");
        TrackData t4 = saveTrack(playlistId, 4, "s-link-4");
        flushAndClear();

        // when — k=1 (reorderTracks 와 동등)
        trackRepository.rotatePlayedOrder(playlistId, 1, 4L);
        flushAndClear();

        // then — old-1→4, old-2→1, old-3→2, old-4→3 (legacy reorderTracks 결과와 동일)
        assertThat(trackRepository.findById(t1.getId()).orElseThrow().getOrderNumber()).isEqualTo(4);
        assertThat(trackRepository.findById(t2.getId()).orElseThrow().getOrderNumber()).isEqualTo(1);
        assertThat(trackRepository.findById(t3.getId()).orElseThrow().getOrderNumber()).isEqualTo(2);
        assertThat(trackRepository.findById(t4.getId()).orElseThrow().getOrderNumber()).isEqualTo(3);
    }
}
