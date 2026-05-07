package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

@Getter
public class PartyroomRestoredEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;

    public PartyroomRestoredEvent(PartyroomId partyroomId, Long administratorId) {
        super();
        this.partyroomId = partyroomId;
        this.administratorId = administratorId;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
