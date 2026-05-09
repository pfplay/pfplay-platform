package com.pfplaybackend.api.common.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {

    @Test
    void clockBean_hasKstZone() {
        Clock clock = new ClockConfig().clock();
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void kstConstant_matchesAsiaSeoul() {
        assertThat(ClockConfig.KST).isEqualTo(ZoneId.of("Asia/Seoul"));
    }
}
