package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PartyroomCreatedEventTest {

    @Test
    @DisplayName("필드 전달 + DomainEvent 자동 stamp")
    void event_carries_fields_and_auto_stamp() {
        PartyroomCreatedEvent event = new PartyroomCreatedEvent(
                new PartyroomId(1L), 100L, StageType.GENERAL);

        assertThat(event.getPartyroomId().getId()).isEqualTo(1L);
        assertThat(event.getHostUserAccountId()).isEqualTo(100L);
        assertThat(event.getStageType()).isEqualTo(StageType.GENERAL);
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getEventType()).isEqualTo("PartyroomCreatedEvent");
        assertThat(event.getAggregateId()).isEqualTo("1");
    }
}
