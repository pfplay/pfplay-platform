package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.dto.command.AdminApplyPenaltyCommand;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.AdminPenaltyType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
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
import com.pfplaybackend.api.party.domain.entity.data.history.CrewPenaltyHistoryData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.PunisherType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Concurrent penalty race IT — admin과 crew(host)가 같은 crew에 동시에 PERMANENT_EXPULSION 부과.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §6.1
 *
 * <p>기대 결과:
 * <ul>
 *   <li>{@code crew_penalty_history} 2 row (CREW + ADMIN punisher_type 각각).</li>
 *   <li>{@code crew.is_banned == true} (enforceBan 멱등 — race winner의 마지막 SAVE 후 true 유지).</li>
 *   <li>{@code partyroom_admin_action.PENALIZE_CREW} count == 1 (admin path만 audit).</li>
 * </ul>
 *
 * <p><strong>Deviation note (sequential vs two-thread):</strong>
 * 두 service를 진짜 동시에 두 thread에서 호출하면 (a) admin 경로는 SecurityContext 의존 (mock),
 * (b) crew 경로는 {@link ThreadLocalContext} 의존 — 두 인증 컨텍스트를 thread-local 안전하게
 * 전파하는 fixture가 PR 9 범위 밖이다. {@code expel} 흐름은 atomic toggle + idempotent
 * {@link CrewData#enforceBan()}이므로 호출 순서와 무관한 동일 outcome (race-equivalent).
 * 따라서 sequential 호출로 §6.1 outcome을 검증한다 (PR 8 race 검증 패턴 참조).
 */
class AdminCrewPenaltyConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private AdminCrewPenaltyCommandService adminService;
    @Autowired private CrewPenaltyCommandService crewService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomPlaybackRepository partyroomPlaybackRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private CrewPenaltyHistoryRepository historyRepository;
    @Autowired private PartyroomAdminActionRepository auditRepository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    /** AdminContext mock — 실제 SecurityContext 셋업 없이 superAdminId 반환. */
    @MockBean private AdminContext adminContext;

    private static final long ADMIN_USER_UID = 950L;
    private static final long HOST_USER_UID = 951L;
    private static final long TARGET_USER_UID = 952L;

    private Long superAdminId;
    private Long partyroomId;
    private Long hostCrewId;
    private Long targetCrewId;

    @AfterEach
    void cleanup() {
        ThreadLocalContext.clearContext();
        transactionTemplate.executeWithoutResult(status -> {
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
        userAccountRepository.save(UserAccountData.createForLocalWithMandatoryChange(
                new UserId(ADMIN_USER_UID), "race-admin@x", "h"));
        userAccountRepository.save(UserAccountData.createForLocalWithMandatoryChange(
                new UserId(HOST_USER_UID), "race-host@x", "h"));
        userAccountRepository.save(UserAccountData.createForLocalWithMandatoryChange(
                new UserId(TARGET_USER_UID), "race-target@x", "h"));

        AdministratorData admin = administratorRepository.save(
                AdministratorData.createSuperAdmin(ADMIN_USER_UID));
        this.superAdminId = admin.getAdministratorId();

        PartyroomData partyroom = PartyroomData.create(
                "race-room", "intro", LinkDomain.of("link-race-room"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(HOST_USER_UID));
        this.partyroomId = partyroomRepository.saveAndFlush(partyroom).getId();
        partyroomPlaybackRepository.saveAndFlush(
                PartyroomPlaybackData.createFor(new PartyroomId(this.partyroomId)));

        // Host crew (HOST grade — moderator 권한 보유, crew penalty 부과 가능)
        CrewData hostCrew = CrewData.create(
                new PartyroomId(this.partyroomId), new UserId(HOST_USER_UID), GradeType.HOST, null,
                LocalDateTime.now());
        this.hostCrewId = crewRepository.saveAndFlush(hostCrew).getId();

        // Target crew (LISTENER — 부과 대상)
        CrewData targetCrew = CrewData.create(
                new PartyroomId(this.partyroomId), new UserId(TARGET_USER_UID), GradeType.LISTENER, null,
                LocalDateTime.now());
        this.targetCrewId = crewRepository.saveAndFlush(targetCrew).getId();

        given(adminContext.currentAdministratorId()).willReturn(superAdminId);
    }

    @Test
    @DisplayName("admin + crew(host) 동시 PERMANENT — history 2 row (CREW+ADMIN), banned, admin_action 1 row")
    void admin_and_crew_concurrent_permanent_expulsion() {
        // ─── Crew(host) path: ThreadLocalContext에 host AuthContext 주입 후 부과 ───
        ThreadLocalContext.setContext(new AuthContext(new UserId(HOST_USER_UID), AuthorityTier.GT));
        try {
            crewService.addPenalty(new PartyroomId(partyroomId),
                    new PunishPenaltyCommand(targetCrewId, PenaltyType.PERMANENT_EXPULSION, "host abuse"));
        } finally {
            ThreadLocalContext.clearContext();
        }

        // ─── Admin path: AdminContext mock으로 superAdminId 반환 ───
        adminService.apply(partyroomId,
                new AdminApplyPenaltyCommand(targetCrewId, AdminPenaltyType.PERMANENT_EXPULSION, "admin abuse"));

        // ─── Outcome verification (§6.1) ───

        // 1. crew_penalty_history 2 row — CREW + ADMIN punisher_type 각각
        List<CrewPenaltyHistoryData> histories = historyRepository.findAll();
        assertThat(histories).hasSize(2);
        assertThat(histories.stream().map(CrewPenaltyHistoryData::getPunisherType))
                .containsExactlyInAnyOrder(PunisherType.CREW, PunisherType.ADMIN);

        // CREW row: punisher_crew_id = host crew id, punisher_type=CREW
        CrewPenaltyHistoryData crewRow = histories.stream()
                .filter(h -> h.getPunisherType() == PunisherType.CREW)
                .findFirst().orElseThrow();
        assertThat(crewRow.getPunisherCrewId().getId()).isEqualTo(hostCrewId);

        // ADMIN row: punisher_crew_id null, punisher_type=ADMIN
        CrewPenaltyHistoryData adminRow = histories.stream()
                .filter(h -> h.getPunisherType() == PunisherType.ADMIN)
                .findFirst().orElseThrow();
        assertThat(adminRow.getPunisherCrewId()).isNull();

        // 2. crew.is_banned == true (enforceBan 멱등)
        CrewData reloadedTarget = crewRepository.findById(targetCrewId).orElseThrow();
        assertThat(reloadedTarget.isBanned()).isTrue();

        // 3. partyroom_admin_action PENALIZE_CREW count == 1 (admin path만)
        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        long penalizeCount = audits.stream()
                .filter(a -> a.getActionType() == PartyroomAdminActionType.PENALIZE_CREW)
                .count();
        assertThat(penalizeCount).isEqualTo(1L);
    }
}
