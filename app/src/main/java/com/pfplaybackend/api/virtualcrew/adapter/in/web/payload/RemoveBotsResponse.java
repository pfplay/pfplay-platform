package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewAdminService;

import java.util.List;

/** 봇 일괄 제거 결과 — 실제 탈퇴된 봇 수 + userId 목록(로스터에 없던 id 는 제외). */
public record RemoveBotsResponse(int removed, List<Long> removedUserIds) {

    public static RemoveBotsResponse from(VirtualCrewAdminService.BotRemovalResult result) {
        return new RemoveBotsResponse(result.removed(), result.removedUserIds());
    }
}
