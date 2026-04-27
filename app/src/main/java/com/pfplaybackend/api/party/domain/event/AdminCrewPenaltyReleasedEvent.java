package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 어드민이 부과한 페널티의 해제 이벤트.
 * - PartyroomAdminActionListener listen → admin_action RELEASE_CREW_PENALTY
 */
@Getter
public class AdminCrewPenaltyReleasedEvent extends DomainEvent {
    private final Long administratorId;
    private final PartyroomId partyroomId;
    private final CrewId releasedCrewId;
    private final Long crewPenaltyHistoryId;

    public AdminCrewPenaltyReleasedEvent(Long administratorId, PartyroomId partyroomId,
                                         CrewId releasedCrewId, Long crewPenaltyHistoryId) {
        super();
        this.administratorId = administratorId;
        this.partyroomId = partyroomId;
        this.releasedCrewId = releasedCrewId;
        this.crewPenaltyHistoryId = crewPenaltyHistoryId;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
