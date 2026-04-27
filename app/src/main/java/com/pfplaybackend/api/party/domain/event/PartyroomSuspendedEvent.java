package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

@Getter
public class PartyroomSuspendedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;
    private final String reason;

    public PartyroomSuspendedEvent(PartyroomId partyroomId, Long administratorId, String reason) {
        super();
        this.partyroomId = partyroomId;
        this.administratorId = administratorId;
        this.reason = reason;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
