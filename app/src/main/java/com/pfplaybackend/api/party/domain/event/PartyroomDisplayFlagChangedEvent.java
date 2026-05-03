package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

@Getter
public class PartyroomDisplayFlagChangedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;
    private final DisplayFlag oldFlag;
    private final DisplayFlag newFlag;

    public PartyroomDisplayFlagChangedEvent(PartyroomId partyroomId, Long administratorId,
                                            DisplayFlag oldFlag, DisplayFlag newFlag) {
        super();
        this.partyroomId = partyroomId;
        this.administratorId = administratorId;
        this.oldFlag = oldFlag;
        this.newFlag = newFlag;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
