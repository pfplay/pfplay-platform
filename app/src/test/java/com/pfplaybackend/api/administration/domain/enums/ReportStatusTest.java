package com.pfplaybackend.api.administration.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.pfplaybackend.api.administration.domain.enums.ReportStatus.DISMISSED;
import static com.pfplaybackend.api.administration.domain.enums.ReportStatus.PENDING;
import static com.pfplaybackend.api.administration.domain.enums.ReportStatus.RESOLVED;
import static com.pfplaybackend.api.administration.domain.enums.ReportStatus.REVIEWING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * ReportStatus 전이 매트릭스 + isTerminal/isOpen 단언.
 * Spec: 2026-04-28-admin-platform-pr13-design.md §3.4 / §4.2
 */
class ReportStatusTest {

    @ParameterizedTest(name = "{0} -> {1} == {2}")
    @MethodSource("transitionMatrix")
    @DisplayName("canTransitionTo matrix — 4×4 = 16 케이스")
    void canTransitionTo_matrix(ReportStatus from, ReportStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }

    static Stream<Arguments> transitionMatrix() {
        return Stream.of(
                // PENDING -> ?
                arguments(PENDING, PENDING, false),
                arguments(PENDING, REVIEWING, true),
                arguments(PENDING, RESOLVED, false),
                arguments(PENDING, DISMISSED, true),
                // REVIEWING -> ?
                arguments(REVIEWING, PENDING, true),
                arguments(REVIEWING, REVIEWING, false),
                arguments(REVIEWING, RESOLVED, true),
                arguments(REVIEWING, DISMISSED, true),
                // RESOLVED -> ? (terminal — 모두 false)
                arguments(RESOLVED, PENDING, false),
                arguments(RESOLVED, REVIEWING, false),
                arguments(RESOLVED, RESOLVED, false),
                arguments(RESOLVED, DISMISSED, false),
                // DISMISSED -> ? (terminal — 모두 false)
                arguments(DISMISSED, PENDING, false),
                arguments(DISMISSED, REVIEWING, false),
                arguments(DISMISSED, RESOLVED, false),
                arguments(DISMISSED, DISMISSED, false)
        );
    }

    @Test
    @DisplayName("isTerminal: RESOLVED, DISMISSED만 true")
    void isTerminal_classification() {
        assertThat(PENDING.isTerminal()).isFalse();
        assertThat(REVIEWING.isTerminal()).isFalse();
        assertThat(RESOLVED.isTerminal()).isTrue();
        assertThat(DISMISSED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("isOpen: PENDING, REVIEWING만 true (isTerminal과 정확 반대)")
    void isOpen_isInverseOfTerminal() {
        for (ReportStatus s : ReportStatus.values()) {
            assertThat(s.isOpen()).isEqualTo(!s.isTerminal());
        }
    }
}
