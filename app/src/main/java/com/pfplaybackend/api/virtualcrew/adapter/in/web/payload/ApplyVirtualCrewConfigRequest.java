package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 룸별 가상 DJ config 적용 요청.
 *
 * <p>{@code status=MANAGED} 면 {@code targetCount}/{@code djBotCount} 가 필요하고
 * (서비스에서 단언), {@code songPackId} 는 nullable(미설정 시 reconcile 이 SKIP_NO_SONG_PACK).
 * OFF 면 나머지 필드는 무시된다.
 *
 * <p>{@code targetCount} 는 1 이상 — 0 은 봇 없는 MANAGED 로 의미가 없다(OFF + drain 경로 사용).
 * {@code djBotCount} 는 0 이상이며 {@code targetCount} 이하여야 한다(서비스에서 단언).
 */
public record ApplyVirtualCrewConfigRequest(
        @NotNull VirtualCrewStatus status,
        @Min(1) Integer targetCount,
        @Min(0) Integer djBotCount,
        Long songPackId
) {}
