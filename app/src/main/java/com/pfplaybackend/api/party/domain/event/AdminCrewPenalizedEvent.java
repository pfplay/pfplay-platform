package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 어드민이 부과한 크루 페널티 이벤트.
 * - PartyroomAdminActionListener listen → admin_action PENALIZE_CREW (PR 9)
 * - UserActivityLogListener listen → user_activity_log PENALIZED_IN_PARTYROOM (PR 12a)
 *
 * `punishedUserAccountId`는 PR 12a에서 추가 — administration BC가 user_account_id 기준 audit row를
 * 만들 때 cross-BC lookup race를 회피하기 위해 이벤트가 self-contain.
 *
 * occurredAt은 DomainEvent 기반 클래스가 LocalDateTime.now()로 자동 설정.
 */
@Getter
public class AdminCrewPenalizedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;
    private final CrewId punishedCrewId;
    private final Long punishedUserAccountId;        // PR 12a 추가
    private final PenaltyType penaltyType;
    private final Long crewPenaltyHistoryId;
    private final String reason;

    public AdminCrewPenalizedEvent(PartyroomId partyroomId, Long administratorId,
                                   CrewId punishedCrewId, Long punishedUserAccountId,
                                   PenaltyType penaltyType, Long crewPenaltyHistoryId, String reason) {
        super();
        this.partyroomId = partyroomId;
        this.administratorId = administratorId;
        this.punishedCrewId = punishedCrewId;
        this.punishedUserAccountId = punishedUserAccountId;
        this.penaltyType = penaltyType;
        this.crewPenaltyHistoryId = crewPenaltyHistoryId;
        this.reason = reason;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
