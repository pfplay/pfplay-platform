package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class UserActivityLogRepositoryIT extends AbstractIntegrationTest {

    @Autowired UserActivityLogRepository repository;

    @Test
    @DisplayName("V10 마이그레이션 후 row 저장 + 읽기 round-trip")
    void save_and_findAll_roundtrip() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 28, 12, 0);
        UserActivityLogData saved = repository.save(UserActivityLogData.of(
                100L, UserActivityEventType.SIGNED_IN, null,
                JsonMetadata.of(Map.of("provider", "GOOGLE", "actor_type", "USER")),
                now));

        assertThat(saved.getLogId()).isNotNull();
        assertThat(saved.getOccurredAt()).isEqualTo(now);

        List<UserActivityLogData> all = repository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getEventType()).isEqualTo("SIGNED_IN");
        assertThat(all.get(0).getMetadata().data())
                .containsEntry("provider", "GOOGLE")
                .containsEntry("actor_type", "USER");
    }

    @Test
    @DisplayName("partyroom_id nullable 허용")
    void save_allows_null_partyroomId() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 28, 12, 0);
        repository.save(UserActivityLogData.of(
                100L, UserActivityEventType.SIGNED_UP, null,
                JsonMetadata.of(Map.of("provider", "LOCAL")), now));

        assertThat(repository.findAll().get(0).getPartyroomId()).isNull();
    }
}
