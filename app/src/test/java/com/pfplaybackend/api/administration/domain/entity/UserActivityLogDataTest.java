package com.pfplaybackend.api.administration.domain.entity;

import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserActivityLogDataTest {

    @Test
    @DisplayName("of() 정적 팩토리: 모든 필드 매핑 + eventType.name() 직렬화")
    void of_factory_maps_all_fields() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 28, 12, 0);
        JsonMetadata meta = JsonMetadata.of(Map.of("provider", "GOOGLE"));

        UserActivityLogData d = UserActivityLogData.of(
                100L, UserActivityEventType.SIGNED_IN, 5L, meta, now);

        assertThat(d.getUserAccountId()).isEqualTo(100L);
        assertThat(d.getEventType()).isEqualTo("SIGNED_IN");
        assertThat(d.getPartyroomId()).isEqualTo(5L);
        assertThat(d.getMetadata()).isEqualTo(meta);
        assertThat(d.getOccurredAt()).isEqualTo(now);
        assertThat(d.getLogId()).isNull();
    }

    @Test
    @DisplayName("of() 정적 팩토리: partyroomId/metadata null 허용")
    void of_factory_allows_null_partyroomId_and_metadata() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 28, 12, 0);

        UserActivityLogData d = UserActivityLogData.of(
                100L, UserActivityEventType.SIGNED_UP, null, null, now);

        assertThat(d.getPartyroomId()).isNull();
        assertThat(d.getMetadata()).isNull();
    }
}
