package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewPenaltyHistoryRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomPlaybackRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.application.dto.command.PunishPenaltyCommand;
import com.pfplaybackend.api.party.application.service.CrewPenaltyCommandService;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.StageType;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G6 end-to-end: 크루(host) PERMANENT_EXPULSION 부과 → user_activity_log PENALIZED_IN_PARTYROOM row 누적 검증.
 *
 * <p>실제 {@link CrewPenaltyCommandService}.addPenalty 호출을 통해
 * {@link com.pfplaybackend.api.party.domain.event.CrewPenalizedEvent}가 publish되고,
 * {@link UserActivityLogListener#on(com.pfplaybackend.api.party.domain.event.CrewPenalizedEvent)}
 * 핸들러가 AFTER_COMMIT + @Async로 user_activity_log row를 INSERT하는 wiring을 검증.
 *
 * <p>SEED 패턴은 {@link UserActivityLogListenerAdminPenaltyIT} (G3 IT) 컨벤션 동일
 * (host UA + target UA + partyroom + playback state). 추가로 host CrewData(HOST grade)를 시드하고,
 * {@link ThreadLocalContext}로 host의 {@link AuthContext}를 셋업한다 — 어드민과 달리 크루 페널티는
 * SecurityContext가 아닌 ThreadLocal에서 punisher userId를 읽음.
 *
 * <p>@TransactionalEventListener(AFTER_COMMIT) + @Async ⇒ Awaitility로 최대 5초 poll.
 */
class UserActivityLogListenerCrewPenaltyIT extends AbstractIntegrationTest {

    @Autowired private CrewPenaltyCommandService crewPenaltyCommandService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomPlaybackRepository partyroomPlaybackRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private CrewPenaltyHistoryRepository historyRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private UserActivityLogRepository userActivityLogRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final Long HOST_USER_ID = 8951L;
    private static final Long TARGET_USER_ID = 8952L;

    private Long partyroomId;
    private Long targetCrewId;

    @AfterEach
    void cleanup() {
        // listener는 @Async — 본 test의 Awaitility wait가 통과한 시점이면 핸들러 thread가
        // user_activity_log INSERT를 완료한 상태이므로 cleanup-vs-late-INSERT race 없음.
        ThreadLocalContext.clearContext();
        transactionTemplate.executeWithoutResult(status -> {
            userActivityLogRepository.deleteAll();
            historyRepository.deleteAll();
            crewRepository.deleteAll();
            partyroomPlaybackRepository.deleteAll();
            partyroomRepository.deleteAll();
            userAccountRepository.deleteAll();
        });
    }

    @BeforeEach
    void seed() {
        // host + target user_account
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(HOST_USER_ID), "host-crew-penalty-it@x", "h"));
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(TARGET_USER_ID), "target-crew-penalty-it@x", "h"));

        // partyroom + playback state
        PartyroomData partyroom = PartyroomData.create(
                "ual-crew-penalty-it", "intro", LinkDomain.of("link-ual-crew-penalty-it"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(HOST_USER_ID));
        this.partyroomId = partyroomRepository.saveAndFlush(partyroom).getId();
        partyroomPlaybackRepository.saveAndFlush(
                PartyroomPlaybackData.createFor(new PartyroomId(this.partyroomId)));

        // host crew (HOST grade — MODERATOR 이상 필요 + target보다 상위 등급 필요)
        CrewData hostCrew = CrewData.create(
                new PartyroomId(this.partyroomId), new UserId(HOST_USER_ID), GradeType.HOST, null,
                LocalDateTime.now());
        crewRepository.saveAndFlush(hostCrew);

        // target crew (CLUBBER grade — host보다 하위)
        CrewData targetCrew = CrewData.create(
                new PartyroomId(this.partyroomId), new UserId(TARGET_USER_ID), GradeType.CLUBBER, null,
                LocalDateTime.now());
        this.targetCrewId = crewRepository.saveAndFlush(targetCrew).getId();

        // ThreadLocal AuthContext — CrewPenaltyCommandService.addPenalty가 ThreadLocalContext.getAuthContext()로
        // punisher userId를 읽음 (어드민 path와의 차이점).
        ThreadLocalContext.setContext(new AuthContext(new UserId(HOST_USER_ID), AuthorityTier.FM));
    }

    @Test
    @DisplayName("crew PERMANENT_EXPULSION → user_activity_log PENALIZED_IN_PARTYROOM (by=CREW, ≤5s)")
    void crew_permanent_expulsion_inserts_user_activity_log_row() {
        PunishPenaltyCommand cmd = new PunishPenaltyCommand(
                targetCrewId, PenaltyType.PERMANENT_EXPULSION, "abuse");

        crewPenaltyCommandService.addPenalty(new PartyroomId(partyroomId), cmd);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<UserActivityLogData> rows = userActivityLogRepository.findAll().stream()
                            .filter(r -> r.getEventType().equals(UserActivityEventType.PENALIZED_IN_PARTYROOM.name()))
                            .toList();
                    assertThat(rows).hasSize(1);
                    UserActivityLogData saved = rows.get(0);
                    assertThat(saved.getUserAccountId()).isEqualTo(TARGET_USER_ID);
                    assertThat(saved.getPartyroomId()).isEqualTo(partyroomId);
                    Map<String, Object> meta = saved.getMetadata().data();
                    assertThat(meta).containsEntry("by", "CREW");
                    assertThat(meta).containsEntry("penalty_type", "PERMANENT_EXPULSION");
                    assertThat(meta).doesNotContainKey("by_administrator_id");
                });
    }
}
