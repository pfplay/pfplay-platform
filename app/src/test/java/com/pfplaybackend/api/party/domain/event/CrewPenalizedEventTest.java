package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrewPenalizedEventTest {

    @Test
    @DisplayName("punishedUserAccountId 포함 — listener cross-BC lookup 회피")
    void event_carries_punishedUserAccountId() {
        CrewPenalizedEvent event = new CrewPenalizedEvent(
                new PartyroomId(1L), new CrewId(10L), new CrewId(50L),
                999L,                                      // punishedUserAccountId
                "abuse", PenaltyType.PERMANENT_EXPULSION);

        assertThat(event.getPartyroomId().getId()).isEqualTo(1L);
        assertThat(event.getPunisherCrewId().getId()).isEqualTo(10L);
        assertThat(event.getPunishedCrewId().getId()).isEqualTo(50L);
        assertThat(event.getPunishedUserAccountId()).isEqualTo(999L);
        assertThat(event.getDetail()).isEqualTo("abuse");
        assertThat(event.getPenaltyType()).isEqualTo(PenaltyType.PERMANENT_EXPULSION);
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getAggregateId()).isEqualTo("1");
    }
}
