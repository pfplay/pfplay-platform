package com.pfplaybackend.api.user.domain.event;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UserAccountWithdrawnEventTest {

    @Test
    void getters_exposeFields() {
        var event = new UserAccountWithdrawnEvent(42L, "withdrawn-42@withdrawn.local");

        assertThat(event.getUserAccountId()).isEqualTo(42L);
        assertThat(event.getAnonymizedEmail()).isEqualTo("withdrawn-42@withdrawn.local");
    }

    @Test
    void getAggregateId_returnsUserAccountIdAsString() {
        var event = new UserAccountWithdrawnEvent(42L, "withdrawn-42@withdrawn.local");

        assertThat(event.getAggregateId()).isEqualTo("42");
    }
}
