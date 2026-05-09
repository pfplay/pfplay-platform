package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.party.domain.value.PlaybackId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackAggregationDataTest {

    @Test
    @DisplayName("createFor — 팩토리 메서드로 생성 시 모든 카운트가 0으로 초기화된다")
    void createForDefaultZero() {
        PlaybackAggregationData aggregation = PlaybackAggregationData.createFor(new PlaybackId(1L));

        assertThat(aggregation.getPlaybackId()).isEqualTo(new PlaybackId(1L));
        assertThat(aggregation.getLikeCount()).isZero();
        assertThat(aggregation.getDislikeCount()).isZero();
        assertThat(aggregation.getGrabCount()).isZero();
    }
}
