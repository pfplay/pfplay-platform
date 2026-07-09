package com.pfplaybackend.api.administration.application.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SilenceExitCalculatorTest {
    static long m(long min) { return min * 60_000L; }

    @Test
    void 음악_중_퇴장은_무음카운트_제외() {
        var r = SilenceExitCalculator.compute(
                List.of(new PlaybackInterval(0, m(10))), List.of(m(5)), 0, m(20));
        assertThat(r.exitsDuringSilence()).isEqualTo(0);
        assertThat(r.totalExits()).isEqualTo(1);
    }
    @Test
    void 무음_중_퇴장은_카운트() {
        var r = SilenceExitCalculator.compute(
                List.of(new PlaybackInterval(0, m(10))), List.of(m(15)), 0, m(20));
        assertThat(r.exitsDuringSilence()).isEqualTo(1);
    }
    @Test
    void 경계_half_open_start는_음악_end는_무음() {
        var intervals = List.of(new PlaybackInterval(m(5), m(10)));
        var r = SilenceExitCalculator.compute(intervals, List.of(m(5), m(10)), 0, m(20));
        assertThat(r.exitsDuringSilence()).isEqualTo(1); // @5=음악, @10=무음
    }
    @Test
    void 스킵_클램프_다음트랙_시작으로_종료() {
        var intervals = List.of(new PlaybackInterval(0, m(30)), new PlaybackInterval(m(10), m(40)));
        var r = SilenceExitCalculator.compute(intervals, List.of(m(35)), 0, m(35));
        assertThat(r.exitsDuringSilence()).isEqualTo(1); // @35==now==end → half-open 무음
    }
    @Test
    void now_클램프로_totalSilence_음수불가() {
        var r = SilenceExitCalculator.compute(
                List.of(new PlaybackInterval(0, m(30))), List.of(), 0, m(20));
        assertThat(r.totalSilenceMinutes()).isEqualTo(0);
    }
    @Test
    void straddle_start_클램프_from으로() {
        var r = SilenceExitCalculator.compute(
                List.of(new PlaybackInterval(0, m(40))), List.of(m(12)), m(10), m(30));
        assertThat(r.exitsDuringSilence()).isEqualTo(0);
        assertThat(r.totalSilenceMinutes()).isEqualTo(0);
    }
    @Test
    void 퇴장_없으면_ratio_null() {
        var r = SilenceExitCalculator.compute(List.of(), List.of(), 0, m(20));
        assertThat(r.totalExits()).isEqualTo(0);
        assertThat(r.silenceExitRatio()).isNull();
        assertThat(r.totalSilenceMinutes()).isEqualTo(20);
    }
}
