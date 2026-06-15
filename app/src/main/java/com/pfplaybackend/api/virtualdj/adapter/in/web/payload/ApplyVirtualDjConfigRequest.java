package com.pfplaybackend.api.virtualdj.adapter.in.web.payload;

import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 룸별 가상 DJ config 적용 요청.
 *
 * <p>{@code status=MANAGED} 면 {@code targetCount}/{@code companionFloor} 가 필요하고
 * (서비스에서 단언), {@code songPackId} 는 nullable(미설정 시 reconcile 이 SKIP_NO_SONG_PACK).
 * FROZEN/OFF 면 나머지 필드는 무시된다.
 *
 * <p>{@code targetCount} 는 1 이상 — 0 은 봇 없는 MANAGED 로 의미가 없다(OFF + drain 경로 사용).
 */
public record ApplyVirtualDjConfigRequest(
        @NotNull VirtualDjStatus status,
        @Min(1) Integer targetCount,
        @Min(0) Integer companionFloor,
        Long songPackId
) {}
