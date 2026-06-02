package com.pfplaybackend.api.virtualdj.application.port;

/**
 * 셋→봇 랜덤 배분의 인덱스 선택을 추상화한다. 운영은 ThreadLocalRandom,
 * 테스트는 결정적 stub 을 주입해 분배 결과를 검증 가능하게 한다.
 */
public interface Randomizer {
    /** {@code [0, bound)} 범위의 인덱스를 반환한다. {@code bound <= 0} 이면 IllegalArgumentException. */
    int nextIndex(int bound);
}
