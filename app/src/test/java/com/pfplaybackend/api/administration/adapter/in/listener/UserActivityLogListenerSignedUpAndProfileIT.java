package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.user.application.service.MemberSignService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2 end-to-end: 회원가입 → user_activity_log row 누적 검증.
 *
 * @TransactionalEventListener(AFTER_COMMIT) + @Async ⇒ 비즈니스 TX commit 후 별 thread INSERT.
 * Awaitility로 최대 5초 poll (CI 부하 고려).
 */
class UserActivityLogListenerSignedUpAndProfileIT extends AbstractIntegrationTest {

    @Autowired MemberSignService memberSignService;
    @Autowired UserActivityLogRepository repository;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("회원가입 시 SIGNED_UP row 1건 INSERT (async, ≤5s)")
    void registerMember_inserts_SIGNED_UP_row() {
        memberSignService.getMemberOrCreate("ual-test@example.com", ProviderType.GOOGLE);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<UserActivityLogData> rows = repository.findAll();
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).getEventType())
                            .isEqualTo(UserActivityEventType.SIGNED_UP.name());
                    assertThat(rows.get(0).getMetadata().data())
                            .containsEntry("provider", "GOOGLE");
                });
    }

    // PROFILE_UPDATED end-to-end는 별 IT로 추가 가능. 본 IT는 G2 minimal.
}
