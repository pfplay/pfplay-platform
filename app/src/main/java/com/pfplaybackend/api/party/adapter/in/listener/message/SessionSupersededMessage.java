package com.pfplaybackend.api.party.adapter.in.listener.message;

import java.io.Serializable;

/**
 * 멀티 디바이스 세션 승계 알림 페이로드 (#369). 밀려난 유저의 개인 큐(/user/sub/session)로 발송된다.
 *
 * <p>같은 유저의 여러 세션(밀려난 옛 기기 A + 새 기기 B)이 동일 principal 을 공유하므로 이 알림은
 * 양쪽 모두에 도달한다. 수신 클라이언트는 {@code supersededPartyroomId}(밀려난 방)와
 * {@code newPartyroomId}(새 방)로 자신이 밀려난 쪽인지 판별한다 — 옛 방에 있던 세션만 밀려남 처리한다.
 */
public record SessionSupersededMessage(
        String type,
        long newPartyroomId,
        long supersededPartyroomId,
        long occurredAt
) implements Serializable {

    public static SessionSupersededMessage of(long newPartyroomId, long supersededPartyroomId, long occurredAt) {
        return new SessionSupersededMessage("SESSION_SUPERSEDED", newPartyroomId, supersededPartyroomId, occurredAt);
    }
}
