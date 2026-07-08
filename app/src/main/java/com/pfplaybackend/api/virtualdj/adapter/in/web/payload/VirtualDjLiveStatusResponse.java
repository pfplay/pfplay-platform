package com.pfplaybackend.api.virtualdj.adapter.in.web.payload;

import com.pfplaybackend.api.virtualdj.application.service.VirtualDjAdminService;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;

/**
 * 룸의 가상 DJ live 상태 응답.
 */
public record VirtualDjLiveStatusResponse(
        VirtualDjStatus status,
        Integer targetCount,
        Integer djBotCount,
        Long songPackId,
        int currentBotDjCount
) {
    public static VirtualDjLiveStatusResponse from(VirtualDjAdminService.LiveStatus s) {
        return new VirtualDjLiveStatusResponse(
                s.status(), s.targetCount(), s.djBotCount(), s.songPackId(), s.currentBotDjCount());
    }
}
