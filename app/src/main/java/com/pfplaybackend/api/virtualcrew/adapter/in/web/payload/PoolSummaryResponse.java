package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewAdminService;

import java.util.List;

/**
 * 봇 풀 요약 응답 — 전체 봇 수 / idle 수 / 파티룸별 배치 현황.
 */
public record PoolSummaryResponse(long total, long idle, List<Placement> placed) {

    /**
     * 파티룸 1곳의 봇 배치 현황.
     */
    public record Placement(Long partyroomId, String partyroomTitle, long botCount) {}

    public static PoolSummaryResponse from(VirtualCrewAdminService.PoolSummary s) {
        List<Placement> placed = s.placed().stream()
                .map(p -> new Placement(p.partyroomId(), p.partyroomTitle(), p.botCount()))
                .toList();
        return new PoolSummaryResponse(s.total(), s.idle(), placed);
    }
}
