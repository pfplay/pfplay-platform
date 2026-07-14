package com.pfplaybackend.api.administration.application.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SilenceExitCalculator {

    private SilenceExitCalculator() {}

    public record Result(long totalExits, long exitsDuringSilence,
                         Double silenceExitRatio, long totalSilenceMinutes) {}

    /**
     * 무음 이탈 지표 계산 (근사). 모든 시각 epoch millis.
     *
     * @param rawIntervals playback 트랙 구간. <b>createdAtMs ASC 정렬 전제</b> — skip-clamp가
     *                     {@code next.createdAtMs()}를 raw 순서로 참조하므로, 미정렬 입력이면
     *                     구간이 잘못 폐기될 수 있다(현재 caller {@code findPlaybackForInterval}가 ASC 보장).
     * @param exitMsList   윈도우 내 EXIT occurred_at(ms). <b>{@code [fromMs, nowMs)} 안의 값 전제</b> —
     *                     윈도우 밖 값이 섞이면 전부 무음으로 분류되어 silence 지표가 부풀려진다.
     */
    public static Result compute(List<PlaybackInterval> rawIntervals,
                                 List<Long> exitMsList, long fromMs, long nowMs) {
        List<long[]> clamped = new ArrayList<>();
        for (int i = 0; i < rawIntervals.size(); i++) {
            PlaybackInterval it = rawIntervals.get(i);
            long start = Math.max(it.createdAtMs(), fromMs);
            long rawEnd = it.endTimeMs();
            if (i + 1 < rawIntervals.size()) {
                rawEnd = Math.min(rawEnd, rawIntervals.get(i + 1).createdAtMs());
            }
            long end = Math.min(rawEnd, nowMs);
            if (start < end) {
                clamped.add(new long[]{start, end});
            }
        }
        clamped.sort(Comparator.comparingLong(a -> a[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] iv : clamped) {
            if (!merged.isEmpty() && iv[0] <= merged.get(merged.size() - 1)[1]) {
                long[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], iv[1]);
            } else {
                merged.add(new long[]{iv[0], iv[1]});
            }
        }
        long silence = 0;
        for (long exit : exitMsList) {
            if (!coveredByMusic(merged, exit)) silence++;
        }
        long totalExits = exitMsList.size();
        Double ratio = totalExits == 0 ? null
                : BigDecimal.valueOf((double) silence / totalExits)
                        .setScale(2, RoundingMode.HALF_UP).doubleValue();
        long musicMs = 0;
        for (long[] iv : merged) musicMs += (iv[1] - iv[0]);
        long silenceMs = (nowMs - fromMs) - musicMs;
        long silenceMinutes = Math.round(silenceMs / 60_000.0);
        return new Result(totalExits, silence, ratio, silenceMinutes);
    }

    private static boolean coveredByMusic(List<long[]> merged, long exit) {
        int lo = 0, hi = merged.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long[] iv = merged.get(mid);
            if (exit < iv[0]) hi = mid - 1;
            else if (exit >= iv[1]) lo = mid + 1;   // half-open: end 미포함
            else return true;
        }
        return false;
    }
}
