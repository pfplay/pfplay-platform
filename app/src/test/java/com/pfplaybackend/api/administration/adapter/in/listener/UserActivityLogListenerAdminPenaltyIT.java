package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.dto.command.AdminApplyPenaltyCommand;
import com.pfplaybackend.api.administration.application.service.AdminCrewPenaltyCommandService;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.enums.AdminPenaltyType;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewPenaltyHistoryRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomPlaybackRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * G3 end-to-end: 어드민 PERMANENT_EXPULSION 부과 → user_activity_log PENALIZED_IN_PARTYROOM row 누적 검증.
 *
 * <p>실제 {@link AdminCrewPenaltyCommandService}.apply 호출을 통해 {@link
 * com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent}가 publish되고,
 * {@link UserActivityLogListener#on(com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent)}
 * 핸들러가 AFTER_COMMIT + @Async로 user_activity_log row를 INSERT하는 wiring을 검증.
 *
 * <p>SEED 패턴은 PR 9 {@link com.pfplaybackend.api.administration.application.service.AdminCrewPenaltyCommandServiceIT}
 * 컨벤션 동일 (admin + main partyroom + target crew + playback state).
 *
 * <p>@TransactionalEventListener(AFTER_COMMIT) + @Async ⇒ Awaitility로 최대 5초 poll.
 */
class UserActivityLogListenerAdminPenaltyIT extends AbstractIntegrationTest {

    @Autowired private AdminCrewPenaltyCommandService service;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomPlaybackRepository partyroomPlaybackRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private CrewPenaltyHistoryRepository historyRepository;
    @Autowired private PartyroomAdminActionRepository auditRepository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private UserActivityLogRepository userActivityLogRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockBean private AdminContext adminContext;

    private static final Long ADMIN_USER_ID = 950L;
    private static final Long HOST_USER_ID = 951L;
    private static final Long TARGET_USER_ID = 952L;

    private Long superAdminId;
    private Long partyroomId;
    private Long targetCrewId;

    @AfterEach
    void cleanup() {
        // listener는 @Async — 본 test의 Awaitility wait가 통과한 시점이면 핸들러 thread가
        // user_activity_log INSERT를 완료한 상태이므로 cleanup-vs-late-INSERT race 없음.
        transactionTemplate.executeWithoutResult(status -> {
            userActivityLogRepository.deleteAll();
            auditRepository.deleteAll();
            historyRepository.deleteAll();
            crewRepository.deleteAll();
            partyroomPlaybackRepository.deleteAll();
            partyroomRepository.deleteAll();
            administratorRepository.deleteAll();
            userAccountRepository.deleteAll();
        });
    }

    @BeforeEach
    void seed() {
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(ADMIN_USER_ID), "admin-ual-it@x", "h"));
        AdministratorData superAdmin = administratorRepository.save(
                AdministratorData.createSuperAdmin(ADMIN_USER_ID));
        this.superAdminId = superAdmin.getAdministratorId();

        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(HOST_USER_ID), "host-ual-it@x", "h"));
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(TARGET_USER_ID), "target-ual-it@x", "h"));

        PartyroomData partyroom = PartyroomData.create(
                "ual-penalty-it", "intro", LinkDomain.of("link-ual-penalty-it"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(HOST_USER_ID));
        this.partyroomId = partyroomRepository.saveAndFlush(partyroom).getId();
        partyroomPlaybackRepository.saveAndFlush(
                PartyroomPlaybackData.createFor(new PartyroomId(this.partyroomId)));

        CrewData targetCrew = CrewData.create(
                new PartyroomId(this.partyroomId), new UserId(TARGET_USER_ID), GradeType.LISTENER, null,
                LocalDateTime.now());
        this.targetCrewId = crewRepository.saveAndFlush(targetCrew).getId();

        given(adminContext.currentAdministratorId()).willReturn(superAdminId);
    }

    @Test
    @DisplayName("admin PERMANENT_EXPULSION → user_activity_log PENALIZED_IN_PARTYROOM (by=ADMIN, ≤5s)")
    void admin_permanent_expulsion_inserts_user_activity_log_row() {
        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                targetCrewId, AdminPenaltyType.PERMANENT_EXPULSION, "abuse");

        service.apply(partyroomId, cmd);

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
                    assertThat(meta).containsEntry("by", "ADMIN");
                    assertThat(meta).containsEntry("penalty_type", "PERMANENT_EXPULSION");
                    assertThat(((Number) meta.get("by_administrator_id")).longValue())
                            .isEqualTo(superAdminId);
                });
    }
}
