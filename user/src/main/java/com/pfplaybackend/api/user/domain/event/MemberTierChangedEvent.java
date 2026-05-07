package com.pfplaybackend.api.user.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import lombok.Getter;

/**
 * Member의 authority_tier가 변경된 도메인 이벤트.
 * - UserActivityLogListener listen → user_activity_log TIER_CHANGED + ADMIN_ACTED_ON 2 row (PR 12b1).
 *
 * `userAccountId`는 listener의 audit row subject.
 * `memberId`는 DDD aggregate 식별자(`getAggregateId()`)로만 사용 — listener metadata 미기록 (spec §11 #10).
 * `byAdministratorId`는 ADMIN_ACTED_ON metadata에 기록.
 *
 * 발행 source: PR 12b2 `AdminMemberTierCommandService.changeTier()` (현재 미구현).
 */
@Getter
public class MemberTierChangedEvent extends DomainEvent {
    private final Long userAccountId;
    private final Long memberId;
    private final AuthorityTier oldTier;
    private final AuthorityTier newTier;
    private final Long byAdministratorId;

    public MemberTierChangedEvent(Long userAccountId, Long memberId,
                                  AuthorityTier oldTier, AuthorityTier newTier,
                                  Long byAdministratorId) {
        super();
        this.userAccountId = userAccountId;
        this.memberId = memberId;
        this.oldTier = oldTier;
        this.newTier = newTier;
        this.byAdministratorId = byAdministratorId;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(memberId);
    }
}
