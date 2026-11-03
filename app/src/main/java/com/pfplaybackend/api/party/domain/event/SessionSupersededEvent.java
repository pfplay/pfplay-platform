package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 멀티 디바이스 세션 승계(new-session-wins) 발생 이벤트 (#369).
 *
 * <p>같은 유저가 다른 방으로 입장해 기존 활성 crew 가 auto-exit 된 순간 발행된다.
 * 밀려난 유저의 개인 큐로 SESSION_SUPERSEDED 알림을 보내는 AFTER_COMMIT 리스너가 소비한다.
 * 밀려남 자체(crew EXIT)는 서버가 이미 완결했으며 이 이벤트/알림은 순수 UX 신호다
 * (정합성은 서버 권위 + 재연결 스냅샷 resync 가 보장).
 */
@Getter
public class SessionSupersededEvent extends DomainEvent {
    private final UserId userId;
    private final PartyroomId supersededPartyroomId;
    private final PartyroomId newPartyroomId;
    private final long occurredAtEpochMilli;

    public SessionSupersededEvent(UserId userId, PartyroomId supersededPartyroomId,
                                  PartyroomId newPartyroomId, long occurredAtEpochMilli) {
        this.userId = userId;
        this.supersededPartyroomId = supersededPartyroomId;
        this.newPartyroomId = newPartyroomId;
        this.occurredAtEpochMilli = occurredAtEpochMilli;
    }

    @Override
    public String getAggregateId() {
        return userId.toString();
    }
}
