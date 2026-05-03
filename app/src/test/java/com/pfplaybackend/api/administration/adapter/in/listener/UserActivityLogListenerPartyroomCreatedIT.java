package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.DjQueueRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomPlaybackRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.application.dto.command.CreatePartyroomCommand;
import com.pfplaybackend.api.party.application.service.PartyroomCommandService;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4 end-to-end: 호스트 createGeneralPartyRoom 호출 → user_activity_log PARTYROOM_CREATED row 누적 검증.
 *
 * <p>실제 {@link PartyroomCommandService}.createGeneralPartyRoom 호출을 통해
 * {@link com.pfplaybackend.api.party.domain.event.PartyroomCreatedEvent}가 publish되고,
 * {@link UserActivityLogListener#on(com.pfplaybackend.api.party.domain.event.PartyroomCreatedEvent)}
 * 핸들러가 AFTER_COMMIT + @Async로 user_activity_log row를 INSERT하는 wiring을 검증.
 *
 * <p>SEED 패턴은 {@link UserActivityLogListenerCrewPenaltyIT} (G6 IT) 스타일을 따름 —
 * host UA + ThreadLocal AuthContext (FM tier — {@link com.pfplaybackend.api.party.domain.policy.PartyroomCreationPolicy}
 * 통과). 파티룸 자체는 service가 생성하므로 시드하지 않음.
 *
 * <p>@TransactionalEventListener(AFTER_COMMIT) + @Async ⇒ Awaitility로 최대 5초 poll.
 */
class UserActivityLogListenerPartyroomCreatedIT extends AbstractIntegrationTest {

    @Autowired private PartyroomCommandService partyroomCommandService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomPlaybackRepository partyroomPlaybackRepository;
    @Autowired private DjQueueRepository djQueueRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private UserActivityLogRepository userActivityLogRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final Long HOST_USER_ID = 8961L;

    @BeforeEach
    void seed() {
        // host user_account — createGeneralPartyRoom의 hostId 기준 audit row 작성을 위해 필요.
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(HOST_USER_ID), "host-partyroom-created-it@x", "h"));

        // ThreadLocal AuthContext — createGeneralPartyRoom가 ThreadLocalContext.getAuthContext()로
        // hostId/tier를 읽음. PartyroomCreationPolicy는 FM tier 요구.
        ThreadLocalContext.setContext(new AuthContext(new UserId(HOST_USER_ID), AuthorityTier.FM));
    }

    @AfterEach
    void cleanup() {
        // listener는 @Async — 본 test의 Awaitility wait가 통과한 시점이면 핸들러 thread가
        // user_activity_log INSERT를 완료한 상태이므로 cleanup-vs-late-INSERT race 없음.
        ThreadLocalContext.clearContext();
        transactionTemplate.executeWithoutResult(status -> {
            userActivityLogRepository.deleteAll();
            crewRepository.deleteAll();
            djQueueRepository.deleteAll();
            partyroomPlaybackRepository.deleteAll();
            partyroomRepository.deleteAll();
            userAccountRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("createGeneralPartyRoom → user_activity_log PARTYROOM_CREATED (≤5s)")
    void create_general_partyroom_inserts_PARTYROOM_CREATED_row() {
        CreatePartyroomCommand command = new CreatePartyroomCommand(
                "ual-partyroom-created-it", "intro", "link-ual-partyroom-created-it", 30);

        partyroomCommandService.createGeneralPartyRoom(command);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<UserActivityLogData> rows = userActivityLogRepository.findAll().stream()
                            .filter(r -> r.getEventType().equals(UserActivityEventType.PARTYROOM_CREATED.name()))
                            .toList();
                    assertThat(rows).hasSize(1);
                    UserActivityLogData saved = rows.get(0);
                    assertThat(saved.getUserAccountId()).isEqualTo(HOST_USER_ID);
                    assertThat(saved.getPartyroomId()).isNotNull();
                    Map<String, Object> meta = saved.getMetadata().data();
                    assertThat(meta).containsEntry("stage_type", "GENERAL");
                });
    }
}
