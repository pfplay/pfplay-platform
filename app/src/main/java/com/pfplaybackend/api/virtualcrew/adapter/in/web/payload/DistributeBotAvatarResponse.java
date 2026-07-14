package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import com.pfplaybackend.api.virtualcrew.application.service.BotAvatarAssigner;

import java.util.List;

/**
 * 봇 아바타 일괄 배분 결과 — 실제 적용된 (봇, 바디) 페어 목록.
 *
 * <p>{@code userId}는 TSID라 JS 정밀도 손실을 피하려 문자열로 직렬화한다({@link BotRosterItemResponse} 참고).
 */
public record DistributeBotAvatarResponse(List<Assigned> assigned) {
    public record Assigned(String userId, String avatarBodyUri) {}

    public static DistributeBotAvatarResponse from(List<BotAvatarAssigner.Assigned> xs) {
        return new DistributeBotAvatarResponse(xs.stream()
                .map(a -> new Assigned(String.valueOf(a.userId()), a.avatarBodyUri())).toList());
    }
}
