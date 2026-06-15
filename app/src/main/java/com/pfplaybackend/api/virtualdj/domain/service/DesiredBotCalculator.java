package com.pfplaybackend.api.virtualdj.domain.service;

/**
 * 룸의 목표 봇 DJ 수를 결정하는 순수 산식 (Chunk 4 reconcile 핵심).
 *
 * <p>입력은 모두 정수이며 부작용·의존성이 없다 — 단위 테스트로 전 경우를 고정한다.
 * 오케스트레이터({@code *Orchestrator*})는 사람 DJ 수를 query 서비스로 읽은 뒤 이 산식으로
 * 목표 봇 수를 구하고, 현재 봇 수와의 차이만큼 투입/제거한다.
 *
 * <p>의미:
 * <ul>
 *   <li>사람 DJ 가 0명이면 목표만큼 봇을 채운다.</li>
 *   <li>사람 DJ 가 1명이면 사람이 외롭지 않게 동반 하한선(floor) 은 지키되, 가급적
 *       {@code target - 1} 로 사람에게 자리를 양보한다 (둘 중 큰 값을 target 으로 캡).</li>
 *   <li>사람 DJ 가 2명 이상이면 단순히 부족분({@code target - human}, 음수면 0)만 채운다.</li>
 * </ul>
 */
public final class DesiredBotCalculator {

    private DesiredBotCalculator() {
    }

    /**
     * 목표 봇 DJ 수.
     *
     * @param human  현재 사람(비-봇) DJ 수
     * @param target 룸의 목표 총 DJ 수(설정값)
     * @param floor  동반 진입 하한선(설정값)
     * @return 룸이 가져야 할 봇 DJ 수 (0 이상)
     */
    public static int desiredBot(int human, int target, int floor) {
        if (human == 0) return target;
        if (human == 1) return Math.min(target, Math.max(floor, target - 1));
        return Math.max(0, target - human);
    }
}
