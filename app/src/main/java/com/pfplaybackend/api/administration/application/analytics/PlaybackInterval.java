package com.pfplaybackend.api.administration.application.analytics;

/** playback 트랙의 활성 구간 재구성용 입력. 모든 시각 epoch millis(KST 변환 완료). */
public record PlaybackInterval(long createdAtMs, long endTimeMs) {}
