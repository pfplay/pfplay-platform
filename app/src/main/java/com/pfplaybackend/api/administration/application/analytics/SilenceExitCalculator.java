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
