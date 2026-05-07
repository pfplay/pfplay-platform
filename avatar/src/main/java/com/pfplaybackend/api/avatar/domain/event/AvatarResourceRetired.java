package com.pfplaybackend.api.avatar.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import lombok.Getter;

/**
 * 아바타 리소스(body/face) RETIRED 전이 이벤트.
 *
 * <p>Administration 리스너 → {@code partyroom_admin_action} INSERT
 * ({@code action_type='RETIRE_AVATAR_RESOURCE'}, {@code reason}, {@code partyroom_id=null}).
 *
 * <p>Spec §6.I-5 / §8.2.1.
 */
@Getter
public class AvatarResourceRetired extends DomainEvent {
    private final AvatarResourceType resourceType;
    private final Long resourceId;
    private final String reason;
    private final Long administratorId;

    public AvatarResourceRetired(AvatarResourceType resourceType,
                                 Long resourceId,
                                 String reason,
                                 Long administratorId) {
        super();
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.reason = reason;
        this.administratorId = administratorId;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(resourceId);
    }
}
