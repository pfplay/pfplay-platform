package com.pfplaybackend.api.auth.domain.event;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserAccountSignedInEventTest {

    @Test
    @DisplayName("USER actor type")
    void event_user_actor() {
        UserAccountSignedInEvent event = new UserAccountSignedInEvent(
                100L, ProviderType.GOOGLE, UserAccountSignedInEvent.ActorType.USER);

        assertThat(event.getUserAccountId()).isEqualTo(100L);
        assertThat(event.getProvider()).isEqualTo(ProviderType.GOOGLE);
        assertThat(event.getActorType()).isEqualTo(UserAccountSignedInEvent.ActorType.USER);
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getAggregateId()).isEqualTo("100");
    }

    @Test
    @DisplayName("ADMINISTRATOR actor type — 어드민 로그인도 같은 이벤트 사용")
    void event_admin_actor() {
        UserAccountSignedInEvent event = new UserAccountSignedInEvent(
                100L, ProviderType.LOCAL, UserAccountSignedInEvent.ActorType.ADMINISTRATOR);

        assertThat(event.getActorType()).isEqualTo(UserAccountSignedInEvent.ActorType.ADMINISTRATOR);
    }
}
