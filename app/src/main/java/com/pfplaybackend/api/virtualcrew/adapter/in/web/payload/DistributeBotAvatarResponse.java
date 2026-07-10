package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import com.pfplaybackend.api.virtualcrew.application.service.BotAvatarAssigner;

import java.util.List;

/** 봇 아바타 일괄 배분 결과 — 실제 적용된 (봇, 바디) 페어 목록. */
public record DistributeBotAvatarResponse(List<Assigned> assigned) {
    public record Assigned(Long userId, String avatarBodyUri) {}

    public static DistributeBotAvatarResponse from(List<BotAvatarAssigner.Assigned> xs) {
        return new DistributeBotAvatarResponse(xs.stream()
                .map(a -> new Assigned(a.userId(), a.avatarBodyUri())).toList());
    }
}
