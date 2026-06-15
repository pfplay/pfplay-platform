package com.pfplaybackend.api.virtualdj.application.dto;

/**
 * 봇 풀 배치 현황 1행 — 파티룸 별로 현재 활성 상태로 배치된 봇 수를 담는다.
 *
 * <p>{@link com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository#findPlacements()}
 * 의 결과 단위. 어드민 봇 풀 요약 응답에서 {@code placements} 배열로 직렬화된다.
 */
public record PoolPlacementRow(
        Long partyroomId,
        String partyroomTitle,
        long botCount
) {}
