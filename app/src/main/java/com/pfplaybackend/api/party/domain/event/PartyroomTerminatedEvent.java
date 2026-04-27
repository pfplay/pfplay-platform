package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 어드민 경로의 파티룸 종료 이벤트. host 자발 종료(PartyroomClosedEvent)와 별개.
 *
 * - PartyroomAdminActionListener listen → admin_action TERMINATE_PARTYROOM
 * - PartyroomCounterListener listen → crew_count = 0 reset
 * - DomainEventRedisRelay listen → ROOM_TERMINATED topic
 *
 * occurredAt은 DomainEvent 기반 클래스가 LocalDateTime.now()로 자동 설정.
 */
@Getter
public class PartyroomTerminatedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;
    private final String reason;

    public PartyroomTerminatedEvent(PartyroomId partyroomId, Long administratorId, String reason) {
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
