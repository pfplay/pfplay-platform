package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class PlaybackAggregationAtomicUpdateIT extends AbstractIntegrationTest {

    @Autowired private PlaybackAggregationRepository repository;

    @Test
    @DisplayName("applyAggregationDelta — 정상 +1/-1, 기존 카운터에 누적")
    void apply_normal() {
        PlaybackId pid = new PlaybackId(80001L);
        repository.saveAndFlush(PlaybackAggregationData.createFor(pid));

        int affected = repository.applyAggregationDelta(pid, 5, 2, 3);

        assertThat(affected).isEqualTo(1);
        PlaybackAggregationData reloaded = repository.findById(pid).orElseThrow();
        assertThat(reloaded.getLikeCount()).isEqualTo(5);
        assertThat(reloaded.getDislikeCount()).isEqualTo(2);
        assertThat(reloaded.getGrabCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("applyAggregationDelta — 음수 delta 누적 (정상)")
    void apply_negative() {
        PlaybackId pid = new PlaybackId(80002L);
        repository.saveAndFlush(PlaybackAggregationData.createFor(pid));
        repository.applyAggregationDelta(pid, 10, 0, 0);

        int affected = repository.applyAggregationDelta(pid, -3, 0, 0);

        assertThat(affected).isEqualTo(1);
        assertThat(repository.findById(pid).orElseThrow().getLikeCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("applyAggregationDelta — 존재하지 않는 playbackId → 0 affected")
    void apply_missing() {
        int affected = repository.applyAggregationDelta(new PlaybackId(999_999_999L), 1, 0, 0);
        assertThat(affected).isZero();
    }
}
