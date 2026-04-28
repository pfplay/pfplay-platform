package com.pfplaybackend.api.user.domain.event;

import com.pfplaybackend.api.common.enums.AuthorityTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberTierChangedEventTest {

    @Test
    @DisplayName("필드 5개 + DomainEvent 자동 stamp")
    void event_carries_fields() {
        MemberTierChangedEvent event = new MemberTierChangedEvent(
                100L, 50L, AuthorityTier.AM, AuthorityTier.FM, 999L);

        assertThat(event.getUserAccountId()).isEqualTo(100L);
        assertThat(event.getMemberId()).isEqualTo(50L);
        assertThat(event.getOldTier()).isEqualTo(AuthorityTier.AM);
        assertThat(event.getNewTier()).isEqualTo(AuthorityTier.FM);
        assertThat(event.getByAdministratorId()).isEqualTo(999L);
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getEventType()).isEqualTo("MemberTierChangedEvent");
        assertThat(event.getAggregateId()).isEqualTo("50");
    }
}
