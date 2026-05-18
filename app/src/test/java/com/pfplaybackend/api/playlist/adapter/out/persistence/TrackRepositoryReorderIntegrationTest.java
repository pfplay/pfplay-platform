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
 * 산술 핵심인 {@link TrackRepository#reorderTracks} 의 SQL 동작을 잠근다.
 *
 * <p>이 동작은 명시적 "skip→재정렬" 로직이 아니라 재생 시작(getFirstTrack)의 부수효과라
 * 회귀 테스트로 묶지 않으면 재생/큐 로직 리팩터 시 조용히 깨질 수 있다.
 * DJ 큐 회전 산술은 PartyroomAggregateServiceTest.rotatesCorrectly,
 * getFirstTrack wiring 은 TrackCommandServiceTest.getFirstTrackReturnsFirstTrackAndRotates 가 별도로 잠금.
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

    @Test
    @DisplayName("reorderTracks — orderNumber=1 트랙은 맨 뒤(total)로, 나머지는 한 칸씩 앞으로 이동한다")
    void rotatesFirstTrackToBottom() {
        // given — 한 플레이리스트의 트랙 순서 [1,2,3,4]
        long playlistId = 9001L;
        TrackData t1 = saveTrack(playlistId, 1, "link-1");
        TrackData t2 = saveTrack(playlistId, 2, "link-2");
        TrackData t3 = saveTrack(playlistId, 3, "link-3");
        TrackData t4 = saveTrack(playlistId, 4, "link-4");
        flushAndClear();

        // when — 재생 시작 시점에 호출되는 회전 (total=4)
        trackRepository.reorderTracks(playlistId, 4L);
        flushAndClear();

        // then — 재생된 #1 트랙이 최하단으로, 나머지는 -1
        assertThat(trackRepository.findById(t1.getId()).orElseThrow().getOrderNumber()).isEqualTo(4);
        assertThat(trackRepository.findById(t2.getId()).orElseThrow().getOrderNumber()).isEqualTo(1);
        assertThat(trackRepository.findById(t3.getId()).orElseThrow().getOrderNumber()).isEqualTo(2);
        assertThat(trackRepository.findById(t4.getId()).orElseThrow().getOrderNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("reorderTracks — 다른 플레이리스트의 트랙 순서는 영향받지 않는다 (playlist_id 격리)")
    void doesNotAffectOtherPlaylists() {
        // given
        long target = 9100L;
        long other = 9200L;
        TrackData targetFirst = saveTrack(target, 1, "t-link-1");
        TrackData targetSecond = saveTrack(target, 2, "t-link-2");
        TrackData otherFirst = saveTrack(other, 1, "o-link-1");
        TrackData otherSecond = saveTrack(other, 2, "o-link-2");
        flushAndClear();

        // when
        trackRepository.reorderTracks(target, 2L);
        flushAndClear();

        // then — target 만 회전, other 는 불변
        assertThat(trackRepository.findById(targetFirst.getId()).orElseThrow().getOrderNumber()).isEqualTo(2);
        assertThat(trackRepository.findById(targetSecond.getId()).orElseThrow().getOrderNumber()).isEqualTo(1);
        assertThat(trackRepository.findById(otherFirst.getId()).orElseThrow().getOrderNumber()).isEqualTo(1);
        assertThat(trackRepository.findById(otherSecond.getId()).orElseThrow().getOrderNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("reorderTracks — 단일 트랙(total=1)이면 orderNumber=1 그대로 (skip 후 같은 트랙 재생, 의도된 동작)")
    void singleTrackStaysAtOne() {
        // given
        long playlistId = 9300L;
        TrackData only = saveTrack(playlistId, 1, "only-link");
        flushAndClear();

        // when
        trackRepository.reorderTracks(playlistId, 1L);
        flushAndClear();

        // then — CASE WHEN orderNumber=1 THEN 1: 그대로. 트랙이 하나면 skip 해도 같은 트랙
        assertThat(trackRepository.findById(only.getId()).orElseThrow().getOrderNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("reorderTracks — 회전을 두 번 적용하면 #1·#2가 차례로 최하단으로 (skip 누적 시 순서 보존)")
    void repeatedRotationPreservesCyclicOrder() {
        // given — [1,2,3]
        long playlistId = 9400L;
        TrackData a = saveTrack(playlistId, 1, "a");
        TrackData b = saveTrack(playlistId, 2, "b");
        TrackData c = saveTrack(playlistId, 3, "c");
        flushAndClear();

        // when — 연속 skip 2회
        trackRepository.reorderTracks(playlistId, 3L); // a:1->3, b:2->1, c:3->2
        flushAndClear();
        trackRepository.reorderTracks(playlistId, 3L); // b:1->3, c:2->1, a:3->2
        flushAndClear();

        // then — 순환 순서 보존 (b가 최하단, c가 다음 재생 대상)
        List<Integer> orders = List.of(
                trackRepository.findById(a.getId()).orElseThrow().getOrderNumber(),
                trackRepository.findById(b.getId()).orElseThrow().getOrderNumber(),
                trackRepository.findById(c.getId()).orElseThrow().getOrderNumber());
        assertThat(orders).containsExactly(2, 3, 1);
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
