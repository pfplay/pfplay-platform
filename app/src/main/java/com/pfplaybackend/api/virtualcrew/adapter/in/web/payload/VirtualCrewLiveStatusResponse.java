package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewAdminService;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;

/**
 * 룸의 가상 DJ live 상태 응답.
 */
public record VirtualCrewLiveStatusResponse(
        VirtualCrewStatus status,
        Integer targetCount,
        Integer djBotCount,
        Long songPackId,
        int currentBotDjCount
) {
    public static VirtualCrewLiveStatusResponse from(VirtualCrewAdminService.LiveStatus s) {
        return new VirtualCrewLiveStatusResponse(
                s.status(), s.targetCount(), s.djBotCount(), s.songPackId(), s.currentBotDjCount());
    }
}
