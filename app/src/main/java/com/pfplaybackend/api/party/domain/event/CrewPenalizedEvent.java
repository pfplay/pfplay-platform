package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 크루(host/moderator)가 부과한 페널티 이벤트.
 * - UserActivityLogListener listen → user_activity_log PENALIZED_IN_PARTYROOM (PR 12a)
 *
 * `punishedUserAccountId`는 PR 12a에서 추가 (G6) — administration BC가 user_account_id 기준
 * audit row를 만들 때 cross-BC lookup race 회피.
 */
@Getter
public class CrewPenalizedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final CrewId punisherCrewId;
    private final CrewId punishedCrewId;
    private final Long punishedUserAccountId;        // PR 12a 추가
    private final String detail;
    private final PenaltyType penaltyType;

    public CrewPenalizedEvent(PartyroomId partyroomId, CrewId punisherCrewId, CrewId punishedCrewId,
                               Long punishedUserAccountId, String detail, PenaltyType penaltyType) {
        this.partyroomId = partyroomId;
        this.punisherCrewId = punisherCrewId;
        this.punishedCrewId = punishedCrewId;
        this.punishedUserAccountId = punishedUserAccountId;
        this.detail = detail;
        this.penaltyType = penaltyType;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
