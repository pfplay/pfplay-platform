package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.party.domain.enums.DjChangeType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DjQueueChangedEventTest {

    @Test
    @DisplayName("3-arg ctor → playbackTimeLimitMinutes null (기존 5 publish 사이트 호환)")
    void threeArg_limitNull() {
        var e = new DjQueueChangedEvent(new PartyroomId(1L), DjChangeType.ROTATE, new CrewId(2L));
        assertThat(e.getChangeType()).isEqualTo(DjChangeType.ROTATE);
        assertThat(e.getAffectedCrewId()).isNotNull();
        assertThat(e.getPlaybackTimeLimitMinutes()).isNull();
    }

    @Test
    @DisplayName("4-arg ctor → DEACTIVATE + limit 보존")
    void fourArg_limitSet() {
        var e = new DjQueueChangedEvent(new PartyroomId(1L), DjChangeType.DEACTIVATE, null, 5);
        assertThat(e.getChangeType()).isEqualTo(DjChangeType.DEACTIVATE);
        assertThat(e.getPlaybackTimeLimitMinutes()).isEqualTo(5);
    }
}
