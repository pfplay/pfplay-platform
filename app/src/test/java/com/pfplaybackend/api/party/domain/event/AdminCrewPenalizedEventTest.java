package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCrewPenalizedEventTest {

    @Test
    @DisplayName("punishedUserAccountId 포함 — listener cross-BC lookup 회피")
    void event_carries_punishedUserAccountId() {
        AdminCrewPenalizedEvent event = new AdminCrewPenalizedEvent(
                new PartyroomId(1L), 100L, new CrewId(50L),
                999L,                                // punishedUserAccountId
                PenaltyType.PERMANENT_EXPULSION, 200L, "abuse");

        assertThat(event.getPartyroomId().getId()).isEqualTo(1L);
        assertThat(event.getAdministratorId()).isEqualTo(100L);
        assertThat(event.getPunishedCrewId().getId()).isEqualTo(50L);
        assertThat(event.getPunishedUserAccountId()).isEqualTo(999L);
        assertThat(event.getPenaltyType()).isEqualTo(PenaltyType.PERMANENT_EXPULSION);
        assertThat(event.getCrewPenaltyHistoryId()).isEqualTo(200L);
        assertThat(event.getReason()).isEqualTo("abuse");
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getAggregateId()).isEqualTo("1");
    }
}
