package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 어드민이 부과한 크루 페널티 이벤트.
 * - PartyroomAdminActionListener listen → admin_action PENALIZE_CREW
 * occurredAt은 DomainEvent 기반 클래스가 LocalDateTime.now()로 자동 설정.
 *
 * administratorId를 party-domain event에 포함하는 것은 PR 8 PartyroomTerminatedEvent
 * 등이 잡은 선례 — admin id는 party domain에서 loose ref(integer)로 다룸.
 */
@Getter
public class AdminCrewPenalizedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;
    private final CrewId punishedCrewId;
    private final PenaltyType penaltyType;
    private final Long crewPenaltyHistoryId;   // PERMANENT_EXPULSION일 때만 non-null, ONE_TIME은 null
    private final String reason;

    public AdminCrewPenalizedEvent(PartyroomId partyroomId, Long administratorId,
                                   CrewId punishedCrewId, PenaltyType penaltyType,
                                   Long crewPenaltyHistoryId, String reason) {
        super();
        this.partyroomId = partyroomId;
        this.administratorId = administratorId;
        this.punishedCrewId = punishedCrewId;
        this.penaltyType = penaltyType;
        this.crewPenaltyHistoryId = crewPenaltyHistoryId;
        this.reason = reason;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
