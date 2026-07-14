package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewAdminService;

import java.util.List;

/**
 * 봇 일괄 제거 결과 — 실제 탈퇴된 봇 수 + userId 목록(로스터에 없던 id 는 제외).
 *
 * <p>{@code removedUserIds}는 TSID라 JS 정밀도 손실을 피하려 문자열로 직렬화한다({@link BotRosterItemResponse} 참고).
 */
public record RemoveBotsResponse(int removed, List<String> removedUserIds) {

    public static RemoveBotsResponse from(VirtualCrewAdminService.BotRemovalResult result) {
        return new RemoveBotsResponse(result.removed(),
                result.removedUserIds().stream().map(String::valueOf).toList());
    }
}
