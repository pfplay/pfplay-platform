package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import com.pfplaybackend.api.administration.application.dto.AttendanceAnalytics;

/**
 * B-3 룸 행동분석 응답 (② 입퇴장 집계 + ③ 무음 이탈 근사).
 * silenceExit.approximate 는 항상 true — playback 트랙구간 근사 지표임을 명시.
 * silenceExit.totalExits == attendance.totalExited (같은 윈도우·같은 이벤트원, 항상 동일).
 */
public record PartyroomAnalyticsResponse(int windowDays,
                                         AttendanceAnalytics attendance,
                                         SilenceExit silenceExit) {
    public record SilenceExit(boolean approximate, long totalExits,
                              long exitsDuringSilence, Double silenceExitRatio,
                              long totalSilenceMinutes) {}
}
