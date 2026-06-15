package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.virtualdj.domain.service.DesiredBotCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class DesiredBotCalculatorTest {

    @DisplayName("desiredBot 산식 — (human, target, floor) → 목표 봇 수")
    @ParameterizedTest(name = "human={0}, target={1}, floor={2} → {3}")
    @CsvSource({
            // (T=2, floor=1)
            "0, 2, 1, 2",
            "1, 2, 1, 1",
            "2, 2, 1, 0",
            "3, 2, 1, 0",
            // edge: (T=2, floor=3, human=1) → min(2, max(3,1)) = 2
            "1, 2, 3, 2",
            // general subtraction branch: human=3, target=5, floor=1 → max(0, 5-3) = 2
            "3, 5, 1, 2",
            // degenerate: human=0, target=0, floor=0 → 0
            "0, 0, 0, 0",
    })
    void desiredBot(int human, int target, int floor, int expected) {
        assertThat(DesiredBotCalculator.desiredBot(human, target, floor)).isEqualTo(expected);
    }
}
