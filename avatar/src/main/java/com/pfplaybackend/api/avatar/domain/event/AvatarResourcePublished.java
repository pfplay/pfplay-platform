package com.pfplaybackend.api.avatar.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import lombok.Getter;

/**
 * 아바타 리소스(body/face) PUBLISHED 전이 이벤트.
 *
 * <ul>
 *   <li>Administration 리스너 → {@code partyroom_admin_action} INSERT
 *       ({@code action_type='PUBLISH_AVATAR_RESOURCE'}, {@code partyroom_id=null}).
 *   <li>(향후) User Profile 피커 캐시 무효화 — 캐시 도입 시점에.
 * </ul>
 *
 * <p>Spec §6.I-5 / §8.2.1.
 */
@Getter
public class AvatarResourcePublished extends DomainEvent {
    private final AvatarResourceType resourceType;
    private final Long resourceId;
    private final String resourceUri;
    private final Long administratorId;

    public AvatarResourcePublished(AvatarResourceType resourceType,
                                   Long resourceId,
                                   String resourceUri,
                                   Long administratorId) {
        super();
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.resourceUri = resourceUri;
        this.administratorId = administratorId;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(resourceId);
    }
}
