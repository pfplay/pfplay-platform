package com.pfplaybackend.api.virtualcrew.domain.service;

import java.util.List;

/**
 * 필터 통과 트랙을 크루(DJ) 봇들에게 slot 단위로 분배하는 순수 산식.
 *
 * <p>입력은 트랙 리스트와 정수뿐이며 부작용·의존성이 없다 — 단위 테스트로 전 경우를 고정한다.
 * 배치 서비스({@code BotPlacementService})는 {@link #effectiveDjTarget}로 실제 DJ 목표를
 * 트랙 수에 맞춰 clamp 한 뒤, 각 slot 인덱스에 대해 {@link #chunkFor}로 연속 조각을 골라
 * 봇 플레이리스트에 복사한다.
 *
 * <p>분배 규칙: {@code trackCount}를 {@code djBotCount} 조각으로 나눈다. 각 조각 크기는
 * {@code base = trackCount / djBotCount} 또는 {@code base + 1}(앞쪽 {@code remainder}개 조각).
 * 예) 10트랙 3조각 → [4, 3, 3].
 */
public final class TrackDistribution {

    private TrackDistribution() {
    }

    /**
     * 트랙 수에 clamp 된 실제 DJ 목표. 빈 조각(트랙 없는 slot)이 생기지 않도록,
     * DJ 봇은 {@code [0, effectiveDjTarget)} 범위 slot 에만 배정한다.
     *
     * @param djBotCount            설정된 크루(DJ) 봇 수
     * @param filteredTrackCount 룸 시간제한 통과 트랙 수
     * @return {@code min(djBotCount, filteredTrackCount)}
     */
    public static int effectiveDjTarget(int djBotCount, int filteredTrackCount) {
        return Math.min(djBotCount, filteredTrackCount);
    }

    /**
     * {@code slotIndex} 번째 조각의 트랙 부분리스트(불변 복사).
     *
     * <p>{@code slotIndex} 는 {@code [0, djBotCount)} 범위여야 한다(호출측 보장). 그 범위 밖은
     * 빈 조각이 되므로 배정하지 않는다.
     *
     * @param tracks    분배 대상 트랙(정렬된 상태)
     * @param djBotCount   조각 수(= 실제 DJ 목표)
     * @param slotIndex 조각 인덱스
     * @return 해당 조각의 트랙(불변 리스트)
     */
    public static <T> List<T> chunkFor(List<T> tracks, int djBotCount, int slotIndex) {
        int n = tracks.size();
        int base = n / djBotCount;
        int rem = n % djBotCount;
        int start = slotIndex * base + Math.min(slotIndex, rem);
        int size = base + (slotIndex < rem ? 1 : 0);
        return List.copyOf(tracks.subList(start, start + size));
    }
}
