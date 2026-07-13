package com.pfplaybackend.api.playlist.domain.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackCursorPolicyTest {

    @Test
    @DisplayName("startIndexAfterCursor — 커서가 중간에 있으면 바로 다음 인덱스")
    void startFromMiddle() {
        List<Long> ordered = List.of(10L, 20L, 30L, 40L);
        assertThat(PlaybackCursorPolicy.startIndexAfterCursor(ordered, 20L)).isEqualTo(2);
    }

    @Test
    @DisplayName("startIndexAfterCursor — 커서가 마지막이면 wrap 하여 0")
    void startWrapsFromLast() {
        List<Long> ordered = List.of(10L, 20L, 30L);
        assertThat(PlaybackCursorPolicy.startIndexAfterCursor(ordered, 30L)).isEqualTo(0);
    }

    @Test
    @DisplayName("startIndexAfterCursor — 커서가 null이면 0(맨 앞)")
    void startFromNullCursor() {
        List<Long> ordered = List.of(10L, 20L, 30L);
        assertThat(PlaybackCursorPolicy.startIndexAfterCursor(ordered, null)).isEqualTo(0);
    }

    @Test
    @DisplayName("startIndexAfterCursor — 커서가 목록에 없으면(삭제) 0(맨 앞)")
    void startFromDeletedCursor() {
        List<Long> ordered = List.of(10L, 20L, 30L);
        assertThat(PlaybackCursorPolicy.startIndexAfterCursor(ordered, 999L)).isEqualTo(0);
    }

    @Test
    @DisplayName("startIndexAfterCursor — 단일 트랙이면 커서가 그 트랙이어도 wrap 하여 0")
    void startSingleTrackWrapsToItself() {
        List<Long> ordered = List.of(10L);
        assertThat(PlaybackCursorPolicy.startIndexAfterCursor(ordered, 10L)).isEqualTo(0);
        assertThat(PlaybackCursorPolicy.startIndexAfterCursor(ordered, null)).isEqualTo(0);
    }

    @Test
    @DisplayName("startIndexAfterCursor — 빈 목록이면 0")
    void startEmpty() {
        assertThat(PlaybackCursorPolicy.startIndexAfterCursor(List.of(), 10L)).isEqualTo(0);
        assertThat(PlaybackCursorPolicy.startIndexAfterCursor(List.of(), null)).isEqualTo(0);
    }
}
