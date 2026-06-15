package com.pfplaybackend.api.virtualdj.domain.entity.data;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class PartyroomVirtualDjConfigDataTest {
    @Test
    void markSelfUpdated_setsWatermark() {
        PartyroomVirtualDjConfigData cfg = PartyroomVirtualDjConfigData.create(1L);
        assertThat(cfg.getLastSelfUpdateAt()).isNull();
        LocalDateTime t = LocalDateTime.of(2026, 6, 3, 12, 0);
        cfg.markSelfUpdated(t);
        assertThat(cfg.getLastSelfUpdateAt()).isEqualTo(t);
    }
}
