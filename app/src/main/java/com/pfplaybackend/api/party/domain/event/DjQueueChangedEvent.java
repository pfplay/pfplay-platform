package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.enums.DjChangeType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

@Getter
public class DjQueueChangedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final DjChangeType changeType;
    private final CrewId affectedCrewId;
    private final Integer playbackTimeLimitMinutes; // DEACTIVATE 한정 limit-only, 그 외 null

    public DjQueueChangedEvent(PartyroomId partyroomId, DjChangeType changeType, CrewId affectedCrewId) {
        this(partyroomId, changeType, affectedCrewId, null);
    }

    public DjQueueChangedEvent(PartyroomId partyroomId, DjChangeType changeType, CrewId affectedCrewId,
                               Integer playbackTimeLimitMinutes) {
        this.partyroomId = partyroomId;
        this.changeType = changeType;
        this.affectedCrewId = affectedCrewId;
        this.playbackTimeLimitMinutes = playbackTimeLimitMinutes;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
