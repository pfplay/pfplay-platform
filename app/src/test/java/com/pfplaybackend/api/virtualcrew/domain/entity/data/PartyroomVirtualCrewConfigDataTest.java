package com.pfplaybackend.api.virtualcrew.domain.entity.data;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class PartyroomVirtualCrewConfigDataTest {
    @Test
    void markSelfUpdated_setsWatermark() {
        PartyroomVirtualCrewConfigData cfg = PartyroomVirtualCrewConfigData.create(1L);
        assertThat(cfg.getLastSelfUpdateAt()).isNull();
        LocalDateTime t = LocalDateTime.of(2026, 6, 3, 12, 0);
        cfg.markSelfUpdated(t);
        assertThat(cfg.getLastSelfUpdateAt()).isEqualTo(t);
    }
}
