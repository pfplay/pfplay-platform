package com.pfplaybackend.api.user.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import lombok.Getter;

@Getter
public class UserAccountWithdrawnEvent extends DomainEvent {
    private final Long userAccountId;
    private final String anonymizedEmail; // post-anonymization placeholder

    public UserAccountWithdrawnEvent(Long userAccountId, String anonymizedEmail) {
        this.userAccountId = userAccountId;
        this.anonymizedEmail = anonymizedEmail;
    }

    @Override
    public String getAggregateId() {
        return userAccountId.toString();
    }
}
