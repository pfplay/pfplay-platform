package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 파티룸 생성 이벤트.
 * - UserActivityLogListener listen → user_activity_log PARTYROOM_CREATED (PR 12a)
 *
 * `hostUserAccountId`는 host의 user_account_id (loose ref). user_activity_log row의
 * subject가 host이므로 audit timeline에서 자연스럽게 노출됨.
 */
@Getter
public class PartyroomCreatedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long hostUserAccountId;
    private final StageType stageType;

    public PartyroomCreatedEvent(PartyroomId partyroomId, Long hostUserAccountId, StageType stageType) {
        super();
        this.partyroomId = partyroomId;
        this.hostUserAccountId = hostUserAccountId;
        this.stageType = stageType;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
