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
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomPlaybackRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5 end-to-end (2/2): 크루(non-host) 룸 진입 → user_activity_log PARTYROOM_ENTERED row 누적 검증.
 *
 * <p>실제 {@link PartyroomAccessCommandService}.tryEnter 호출을 통해
 * {@link com.pfplaybackend.api.party.domain.event.CrewAccessedEvent}(ENTER)가 publish되고,
 * {@link UserActivityLogListener#on(com.pfplaybackend.api.party.domain.event.CrewAccessedEvent)}
 * 핸들러가 AFTER_COMMIT + @Async로 user_activity_log row를 INSERT하는 wiring을 검증.
 *
 * <p>SEED 패턴은 {@link UserActivityLogListenerPartyroomCreatedIT} (G4 IT) 스타일을 따름 —
 * partyroom (host = HOST_USER_ID) + 진입자 UA(ENTERER_USER_ID) + ThreadLocal AuthContext (GT tier).
 *
 * <p>@TransactionalEventListener(AFTER_COMMIT) + @Async ⇒ Awaitility로 최대 5초 poll.
 */
class UserActivityLogListenerCrewAccessIT extends AbstractIntegrationTest {

    @Autowired private PartyroomAccessCommandService partyroomAccessCommandService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomPlaybackRepository partyroomPlaybackRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private UserActivityLogRepository userActivityLogRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final Long ENTERER_USER_ID = 9981L;
    private static final Long HOST_USER_ID = 9982L;

    private Long partyroomId;

    @BeforeEach
    void seed() {
        // host UA + enterer UA
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(HOST_USER_ID), "host-crew-access-it@x", "h"));
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(ENTERER_USER_ID), "enterer-crew-access-it@x", "h"));

        // partyroom + playback state — host로 active 상태로 시드.
        // tryEnter는 status=ACTIVE 가정. PartyroomData.create는 ACTIVE 상태로 생성.
        PartyroomData partyroom = PartyroomData.create(
                "ual-crew-access-it", "intro", LinkDomain.of("link-ual-crew-access-it"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(HOST_USER_ID));
        this.partyroomId = partyroomRepository.saveAndFlush(partyroom).getId();
        partyroomPlaybackRepository.saveAndFlush(
                PartyroomPlaybackData.createFor(new PartyroomId(this.partyroomId)));

        // ThreadLocal AuthContext — tryEnter가 ThreadLocalContext.getAuthContext()로 enterer userId를 읽음.
        // tier는 enter 검증에 무관 (PartyroomEntrySpecification은 tier 검사 없음).
        ThreadLocalContext.setContext(new AuthContext(new UserId(ENTERER_USER_ID), AuthorityTier.GT));
    }

    @AfterEach
    void cleanup() {
        // listener는 @Async — 본 test의 Awaitility wait가 통과한 시점이면 핸들러 thread가
        // user_activity_log INSERT를 완료한 상태이므로 cleanup-vs-late-INSERT race 없음.
        ThreadLocalContext.clearContext();
        transactionTemplate.executeWithoutResult(status -> {
            userActivityLogRepository.deleteAll();
            crewRepository.deleteAll();
            partyroomPlaybackRepository.deleteAll();
            partyroomRepository.deleteAll();
            userAccountRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("crew enter → user_activity_log PARTYROOM_ENTERED (≤5s)")
    void crew_enter_inserts_PARTYROOM_ENTERED_row() {
        partyroomAccessCommandService.tryEnter(new PartyroomId(partyroomId), CountryCode.of("KR"));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<UserActivityLogData> rows = userActivityLogRepository.findAll().stream()
                            .filter(r -> r.getEventType().equals(UserActivityEventType.PARTYROOM_ENTERED.name()))
                            .toList();
                    assertThat(rows).hasSize(1);
                    UserActivityLogData saved = rows.get(0);
                    assertThat(saved.getUserAccountId()).isEqualTo(ENTERER_USER_ID);
                    assertThat(saved.getPartyroomId()).isEqualTo(partyroomId);
                    // CrewAccessedEvent에 stage_type/duration_sec 부재 — listener는 JsonMetadata.empty() 사용.
                    assertThat(saved.getMetadata().isEmpty()).isTrue();
                });
    }
}
