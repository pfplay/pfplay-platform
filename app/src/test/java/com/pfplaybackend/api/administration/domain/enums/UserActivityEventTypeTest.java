package com.pfplaybackend.api.administration.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V10 user_activity_log.event_type 컬럼은 VARCHAR(64). enum 추가 시 64자 한도 위반을
 * 컴파일/테스트 단계에서 차단하는 가드.
 */
class UserActivityEventTypeTest {

    @Test
    @DisplayName("모든 enum name이 user_activity_log.event_type VARCHAR(64) 한도 내")
    void all_event_types_fit_varchar64() {
        for (UserActivityEventType type : UserActivityEventType.values()) {
            assertThat(type.name().length())
                    .as("event_type '%s' must fit VARCHAR(64)", type.name())
                    .isLessThanOrEqualTo(64);
        }
    }
}
