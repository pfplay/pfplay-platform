package com.pfplaybackend.api.playlist.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 트랙 순서 배치 연산 통합 테스트.
 *
 * <p>회전({@code rotatePlayedOrder}) 기반 #222 잠금은 "재생 회전 제거 + 영속 커서" 재설계로 대체됐다.
 * 신규 모델에서 재생은 {@code order_number} 를 변경하지 않으며(아래 #262 회귀가 잠금),
 * 신규 곡 추가(add-to-head)의 산술은 {@link TrackRepository#shiftAllOrdersDown} 가 담당한다.
 */
@Transactional
class TrackRepositoryReorderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TrackRepository trackRepository;
    @Autowired
    private PlaylistRepository playlistRepository;

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
    @DisplayName("shiftAllOrdersDown — 플레이리스트 전체 order_number 를 +1 한다 (add-to-head 용)")
    void shiftAllOrdersDown_incrementsAll() {
        // given — [1,2,3]
        long playlistId = 9500L;
        TrackData t1 = saveTrack(playlistId, 1, "h-link-1");
        TrackData t2 = saveTrack(playlistId, 2, "h-link-2");
        TrackData t3 = saveTrack(playlistId, 3, "h-link-3");
        flushAndClear();

        // when
        trackRepository.shiftAllOrdersDown(playlistId);
        flushAndClear();

        // then — [2,3,4]
        assertThat(trackRepository.findById(t1.getId()).orElseThrow().getOrderNumber()).isEqualTo(2);
        assertThat(trackRepository.findById(t2.getId()).orElseThrow().getOrderNumber()).isEqualTo(3);
        assertThat(trackRepository.findById(t3.getId()).orElseThrow().getOrderNumber()).isEqualTo(4);
    }

    @Test
    @DisplayName("#262 회귀 — 재생 커서 갱신(advancePlaybackCursor)은 track.order_number 를 변경하지 않는다")
    void cursorAdvance_doesNotMutateTrackOrder() {
        // given — 플레이리스트 + 트랙 [1,2,3]
        PlaylistData playlist = playlistRepository.save(
                PlaylistData.create(0, "p", PlaylistType.PLAYLIST, new UserId(777L)));
        long playlistId = playlist.getId();
        TrackData t1 = saveTrack(playlistId, 1, "c-link-1");
        TrackData t2 = saveTrack(playlistId, 2, "c-link-2");
        TrackData t3 = saveTrack(playlistId, 3, "c-link-3");
        flushAndClear();

        // when — 커서를 여러 번 advance (재생 시뮬레이션)
        playlistRepository.updateLastPlayedTrackId(playlistId, t1.getId());
        playlistRepository.updateLastPlayedTrackId(playlistId, t2.getId());
        flushAndClear();

        // then — order_number 불변(회전 제거로 #262 staleness 소멸), 커서만 갱신
        assertThat(trackRepository.findById(t1.getId()).orElseThrow().getOrderNumber()).isEqualTo(1);
        assertThat(trackRepository.findById(t2.getId()).orElseThrow().getOrderNumber()).isEqualTo(2);
        assertThat(trackRepository.findById(t3.getId()).orElseThrow().getOrderNumber()).isEqualTo(3);
        assertThat(playlistRepository.findById(playlistId).orElseThrow().getLastPlayedTrackId()).isEqualTo(t2.getId());
    }
}
