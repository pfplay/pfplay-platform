package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

@Getter
public class PartyroomNoticeUpdatedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final String content;

    public PartyroomNoticeUpdatedEvent(PartyroomId partyroomId, String content) {
        this.partyroomId = partyroomId;
        this.content = content;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
