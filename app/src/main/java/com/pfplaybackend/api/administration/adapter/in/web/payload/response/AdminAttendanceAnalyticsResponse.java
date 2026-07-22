package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import java.time.LocalDate;
import java.util.List;

/**
 * #361 어드민 대시보드 v1 — 전역 일별 입퇴장 분석 응답.
 *
 * <p>{@code exitRecordRate} = totalExited/totalEntered. 퇴장 이벤트는 유실될 수 있으므로
 * (신호 유실 유령 — #356 계보) 1.0 미만이 정상이며, 급락은 이탈 품질/데이터 품질 신호다.
 * 입장 0건이면 null.
 */
public record AdminAttendanceAnalyticsResponse(
        int days,
        boolean excludeBots,
        Summary summary,
        List<Daily> daily
) {
    public record Summary(
            long totalEntered,
            long totalExited,
            long uniqueVisitors,
            long activeRoomCount,
            Double exitRecordRate
    ) {}

    public record Daily(
            LocalDate date,
            long entered,
            long exited,
            long uniqueVisitors
    ) {}
}
