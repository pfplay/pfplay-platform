package com.pfplaybackend.api.administration.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class UserActivityLogIdTest {

    @Test
    @DisplayName("같은 logId + occurredAt이면 equals true + hashCode 일치")
    void equalsAndHashCode_match_when_same_components() {
        LocalDateTime ts = LocalDateTime.of(2026, 4, 28, 12, 0);
        UserActivityLogId a = new UserActivityLogId(1L, ts);
        UserActivityLogId b = new UserActivityLogId(1L, ts);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("다른 logId면 equals false")
    void notEqual_when_different_logId() {
        LocalDateTime ts = LocalDateTime.of(2026, 4, 28, 12, 0);
        assertThat(new UserActivityLogId(1L, ts)).isNotEqualTo(new UserActivityLogId(2L, ts));
    }

    @Test
    @DisplayName("noargs constructor + getter 동작 (JPA 요구)")
    void noargs_constructor_and_getters() {
        UserActivityLogId id = new UserActivityLogId();
        assertThat(id.getLogId()).isNull();
        assertThat(id.getOccurredAt()).isNull();
    }
}
