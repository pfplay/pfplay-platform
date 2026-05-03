package com.pfplaybackend.api.auth.domain.event;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.event.DomainEvent;
import lombok.Getter;

/**
 * user_account 로그인 성공 이벤트.
 * - UserActivityLogListener listen → user_activity_log SIGNED_IN (PR 12a)
 *
 * actorType:
 *  - USER: 일반 사용자 OAuth 로그인 (AuthService publish)
 *  - ADMINISTRATOR: 어드민 LOCAL 로그인 (AdminLoginService publish)
 *
 * 어드민/유저 timeline을 같은 테이블에 통합. metadata.actor_type으로 구분.
 */
@Getter
public class UserAccountSignedInEvent extends DomainEvent {
    private final Long userAccountId;
    private final ProviderType provider;
    private final ActorType actorType;

    public UserAccountSignedInEvent(Long userAccountId, ProviderType provider, ActorType actorType) {
        super();
        this.userAccountId = userAccountId;
        this.provider = provider;
        this.actorType = actorType;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(userAccountId);
    }

    public enum ActorType { USER, ADMINISTRATOR }
}
