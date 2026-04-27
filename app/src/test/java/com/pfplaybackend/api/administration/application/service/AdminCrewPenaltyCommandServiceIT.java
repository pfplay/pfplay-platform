package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.dto.command.AdminApplyPenaltyCommand;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.AdminActionTargetType;
import com.pfplaybackend.api.administration.domain.enums.AdminPenaltyType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewPenaltyHistoryRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomPlaybackRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.history.CrewPenaltyHistoryData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.PunisherType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.exception.PenaltyException;
import com.pfplaybackend.api.party.domain.value.CrewId;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * End-to-end IT for {@link AdminCrewPenaltyCommandService} — 실제 DB + listener 동작.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §7.2.
 *
 * <p>핵심 검증 (8 cases):
 * <ul>
 *   <li>apply PERMANENT_EXPULSION: history INSERT (punisher_type=ADMIN, punisher_crew_id=null)
 *       + admin_action PENALIZE_CREW (metadata에 penalty_type + crew_penalty_history_id) — atomic.</li>
 *   <li>apply ONE_TIME_EXPULSION: history INSERT 없음 — admin_action만 발생, metadata는 penalty_type만.</li>
 *   <li>apply: TERMINATED 룸 거부 (FORBIDDEN/ALREADY_TERMINATED, no side effects).</li>
 *   <li>apply: 다른 룸 crew 거부 (NotFound/CrewException.NOT_FOUND_ROOM).</li>
 *   <li>apply: 이미 ban된 crew에 PERMANENT 재부과 — idempotent + 새 history row 생성 (audit 완전성).</li>
 *   <li>release: ADMIN-applied row → history.released=true, releasedByCrewId=null,
 *       crew.is_banned=false, admin_action RELEASE_CREW_PENALTY 추가.</li>
 *   <li>release: CREW-applied row → CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE 거부.</li>
 *   <li>release: TERMINATED 룸은 release 허용 (§4.2 cleanup 시점 보장).</li>
 * </ul>
 *
 * <p>패턴 — PR 8 {@link AdminBulkPartyroomActionIT} 컨벤션 따름:
 * - {@link AbstractIntegrationTest} 상속 (Testcontainers MySQL/Redis + ddl-auto=create-drop).
 * - 각 테스트 종료 후 explicit cleanup (per-item TX commit이 outer @Transactional rollback을 회피).
 * - {@code AdminContext}는 {@code @MockBean}으로 주입 — JWT/SecurityContext 셋업 불필요.
 */
class AdminCrewPenaltyCommandServiceIT extends AbstractIntegrationTest {

    @Autowired private AdminCrewPenaltyCommandService service;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomPlaybackRepository partyroomPlaybackRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private CrewPenaltyHistoryRepository historyRepository;
    @Autowired private PartyroomAdminActionRepository auditRepository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    /**
     * Real {@link AdminContext} reads from SecurityContext — IT는 SecurityContext를 셋업하지 않으므로
     * AdminContextTest에서 검증한 IllegalStateException이 발생한다. WebMvc/Service 단위에서는
     * {@code @MockBean}으로 stub하는 것이 정설 (PR 8 {@code AbstractAdminWebMvcTest:66}).
     */
    @MockBean private AdminContext adminContext;

    private Long superAdminId;
    private Long partyroomId;
    private Long targetCrewId;
    private Long otherPartyroomId;
    private Long otherCrewId;

    @AfterEach
    void cleanup() {
        // service.apply/release는 @Transactional이지만 outer test transaction이 없는 IT 환경에서
        // explicit cleanup 필요 — bulk IT 패턴 동일.
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
        // user + admin 셋업
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(900L), "admin-penalty-it@x", "h"));
        AdministratorData superAdmin = administratorRepository.save(
                AdministratorData.createSuperAdmin(900L));
        this.superAdminId = superAdmin.getAdministratorId();

        // host (target crew의 parent room)
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(901L), "host-penalty-it@x", "h"));
        // target crew member
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(902L), "target-penalty-it@x", "h"));
        // other room host
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(903L), "other-host-penalty-it@x", "h"));

        // 메인 partyroom + 활성 crew (target)
        PartyroomData partyroom = PartyroomData.create(
                "penalty-it", "intro", LinkDomain.of("link-penalty-it"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(901L));
        this.partyroomId = partyroomRepository.saveAndFlush(partyroom).getId();
        // PartyroomCommandService.create와 동일하게 playback state 동반 INSERT —
        // expel 흐름이 handleDjQueueOnLeave → findPlaybackState 호출하므로 필수.
        partyroomPlaybackRepository.saveAndFlush(
                PartyroomPlaybackData.createFor(new PartyroomId(this.partyroomId)));

        CrewData targetCrew = CrewData.create(
                new PartyroomId(this.partyroomId), new UserId(902L), GradeType.LISTENER, null,
                LocalDateTime.now());
        this.targetCrewId = crewRepository.saveAndFlush(targetCrew).getId();

        // other partyroom + other crew (다른 룸 거부 검증용)
        PartyroomData otherPartyroom = PartyroomData.create(
                "penalty-it-other", "intro", LinkDomain.of("link-penalty-it-other"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(903L));
        this.otherPartyroomId = partyroomRepository.saveAndFlush(otherPartyroom).getId();
        partyroomPlaybackRepository.saveAndFlush(
                PartyroomPlaybackData.createFor(new PartyroomId(this.otherPartyroomId)));

        CrewData otherCrew = CrewData.create(
                new PartyroomId(this.otherPartyroomId), new UserId(903L), GradeType.LISTENER, null,
                LocalDateTime.now());
        this.otherCrewId = crewRepository.saveAndFlush(otherCrew).getId();

        // SecurityContext 우회: AdminContext mock — 모든 호출에서 superAdminId 반환.
        given(adminContext.currentAdministratorId()).willReturn(superAdminId);
    }

    @Test
    @DisplayName("apply - PERMANENT: history (punisher_type=ADMIN, crew_id=null) + admin_action PENALIZE_CREW (metadata both)")
    void apply_permanent_atomic() {
        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                targetCrewId, AdminPenaltyType.PERMANENT_EXPULSION, "admin abuse");

        Long historyId = service.apply(partyroomId, cmd);

        assertThat(historyId).isNotNull();

        // history row 생성 확인
        List<CrewPenaltyHistoryData> histories = historyRepository.findAll();
        assertThat(histories).hasSize(1);
        CrewPenaltyHistoryData history = histories.get(0);
        assertThat(history.getId()).isEqualTo(historyId);
        assertThat(history.getPunisherType()).isEqualTo(PunisherType.ADMIN);
        assertThat(history.getPunisherCrewId()).isNull();
        assertThat(history.getPunishedCrewId()).isEqualTo(new CrewId(targetCrewId));
        assertThat(history.getPenaltyType()).isEqualTo(PenaltyType.PERMANENT_EXPULSION);
        assertThat(history.getPartyroomId()).isEqualTo(new PartyroomId(partyroomId));
        assertThat(history.isReleased()).isFalse();
        assertThat(history.getPenaltyReason()).isEqualTo("admin abuse");

        // admin_action 동시 commit
        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(1);
        PartyroomAdminActionData audit = audits.get(0);
        assertThat(audit.getActionType()).isEqualTo(PartyroomAdminActionType.PENALIZE_CREW);
        assertThat(audit.getTargetType()).isEqualTo(AdminActionTargetType.CREW);
        assertThat(audit.getTargetId()).isEqualTo(targetCrewId);
        assertThat(audit.getPartyroomId()).isEqualTo(partyroomId);
        assertThat(audit.getAdministratorId()).isEqualTo(superAdminId);
        assertThat(audit.getReason()).isEqualTo("admin abuse");
        Map<String, Object> meta = audit.getMetadata().data();
        assertThat(meta).containsEntry("penalty_type", PenaltyType.PERMANENT_EXPULSION.name());
        // listener는 historyId(Long) 그대로 저장 — JSON deserialize 시 Number로 환원되므로 numeric 비교.
        assertThat(((Number) meta.get("crew_penalty_history_id")).longValue()).isEqualTo(historyId);

        // 부수효과: crew banned + inactive
        CrewData reloaded = crewRepository.findById(targetCrewId).orElseThrow();
        assertThat(reloaded.isBanned()).isTrue();
    }

    @Test
    @DisplayName("apply - ONE_TIME: no history INSERT, admin_action 1 row with metadata only penalty_type")
    void apply_one_time_no_history() {
        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                targetCrewId, AdminPenaltyType.ONE_TIME_EXPULSION, "warn");

        Long historyId = service.apply(partyroomId, cmd);

        assertThat(historyId).isNull();
        assertThat(historyRepository.count()).isZero();

        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(1);
        PartyroomAdminActionData audit = audits.get(0);
        assertThat(audit.getActionType()).isEqualTo(PartyroomAdminActionType.PENALIZE_CREW);
        Map<String, Object> meta = audit.getMetadata().data();
        assertThat(meta).containsEntry("penalty_type", PenaltyType.ONE_TIME_EXPULSION.name());
        assertThat(meta).doesNotContainKey("crew_penalty_history_id");

        // ONE_TIME은 ban 부과 안 함 — crew 상태는 inactive지만 unbanned
        CrewData reloaded = crewRepository.findById(targetCrewId).orElseThrow();
        assertThat(reloaded.isBanned()).isFalse();
    }

    @Test
    @DisplayName("apply - TERMINATED partyroom: ALREADY_TERMINATED (FORBIDDEN), no side effects")
    void apply_terminated_rejected() {
        // partyroom을 미리 terminate
        transactionTemplate.executeWithoutResult(status -> {
            PartyroomData p = partyroomRepository.findById(partyroomId).orElseThrow();
            p.terminate();
            partyroomRepository.saveAndFlush(p);
        });

        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                targetCrewId, AdminPenaltyType.PERMANENT_EXPULSION, "x");

        assertThatThrownBy(() -> service.apply(partyroomId, cmd))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(PartyroomException.ALREADY_TERMINATED.getMessage());

        assertThat(historyRepository.count()).isZero();
        assertThat(auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId)).isEmpty();
    }

    @Test
    @DisplayName("apply - crew belongs to different partyroom: CrewException.NOT_FOUND_ROOM, no side effects")
    void apply_other_partyroom_crew_rejected() {
        // path partyroomId는 main, crewId는 other 룸의 crew
        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                otherCrewId, AdminPenaltyType.PERMANENT_EXPULSION, "x");

        assertThatThrownBy(() -> service.apply(partyroomId, cmd))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(CrewException.NOT_FOUND_ROOM.getMessage());

        assertThat(historyRepository.count()).isZero();
        assertThat(auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId)).isEmpty();
        // other room의 crew는 ban되지 않음
        CrewData reloaded = crewRepository.findById(otherCrewId).orElseThrow();
        assertThat(reloaded.isBanned()).isFalse();
    }

    @Test
    @DisplayName("apply - already banned crew: PERMANENT 재부과 시 새 history row + idempotent ban")
    void apply_already_banned_creates_new_history() {
        // pre: PERMANENT history row + crew.enforceBan
        Long preExistingId = transactionTemplate.execute(status -> {
            CrewData crew = crewRepository.findById(targetCrewId).orElseThrow();
            crew.enforceBan();
            crewRepository.saveAndFlush(crew);

            CrewPenaltyHistoryData pre = CrewPenaltyHistoryData.builder()
                    .partyroomId(new PartyroomId(partyroomId))
                    .punishedCrewId(new CrewId(targetCrewId))
                    .punisherCrewId(null)
                    .punisherType(PunisherType.ADMIN)
                    .penaltyReason("first")
                    .penaltyDate(LocalDateTime.now())
                    .penaltyType(PenaltyType.PERMANENT_EXPULSION)
                    .released(false)
                    .build();
            return historyRepository.saveAndFlush(pre).getId();
        });

        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                targetCrewId, AdminPenaltyType.PERMANENT_EXPULSION, "second");

        Long secondId = service.apply(partyroomId, cmd);

        assertThat(secondId).isNotNull().isNotEqualTo(preExistingId);

        // 두 history row 모두 존재 (audit 완전성)
        List<CrewPenaltyHistoryData> all = historyRepository.findAll();
        assertThat(all).hasSize(2);

        // crew는 여전히 banned (idempotent)
        CrewData reloaded = crewRepository.findById(targetCrewId).orElseThrow();
        assertThat(reloaded.isBanned()).isTrue();

        // admin_action도 새로 1 row INSERT
        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(1);
        Map<String, Object> meta = audits.get(0).getMetadata().data();
        assertThat(((Number) meta.get("crew_penalty_history_id")).longValue()).isEqualTo(secondId);
    }

    @Test
    @DisplayName("release - ADMIN row: released=true, releasedByCrewId=null, crew unban + admin_action RELEASE_CREW_PENALTY")
    void release_admin_row() {
        // apply PERMANENT 먼저
        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                targetCrewId, AdminPenaltyType.PERMANENT_EXPULSION, "abuse");
        Long historyId = service.apply(partyroomId, cmd);
        assertThat(historyId).isNotNull();

        service.release(partyroomId, historyId);

        // history row release 마킹 (admin signal: released_by_crew_id IS NULL)
        CrewPenaltyHistoryData reloaded = historyRepository.findById(historyId).orElseThrow();
        assertThat(reloaded.isReleased()).isTrue();
        assertThat(reloaded.getReleasedByCrewId()).isNull();
        assertThat(reloaded.getReleaseDate()).isNotNull();

        // crew unbanned
        CrewData reloadedCrew = crewRepository.findById(targetCrewId).orElseThrow();
        assertThat(reloadedCrew.isBanned()).isFalse();

        // admin_action: PENALIZE_CREW + RELEASE_CREW_PENALTY (역순)
        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(2);
        PartyroomAdminActionData releaseAudit = audits.get(0);
        assertThat(releaseAudit.getActionType()).isEqualTo(PartyroomAdminActionType.RELEASE_CREW_PENALTY);
        assertThat(releaseAudit.getTargetType()).isEqualTo(AdminActionTargetType.CREW);
        assertThat(releaseAudit.getTargetId()).isEqualTo(targetCrewId);
        Map<String, Object> meta = releaseAudit.getMetadata().data();
        assertThat(((Number) meta.get("crew_penalty_history_id")).longValue()).isEqualTo(historyId);
    }

    @Test
    @DisplayName("release - CREW row: CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE, no side effects")
    void release_crew_row_blocked() {
        // 직접 CREW-applied row INSERT
        Long historyId = transactionTemplate.execute(status -> {
            CrewData target = crewRepository.findById(targetCrewId).orElseThrow();
            target.enforceBan();
            crewRepository.saveAndFlush(target);

            CrewPenaltyHistoryData pre = CrewPenaltyHistoryData.builder()
                    .partyroomId(new PartyroomId(partyroomId))
                    .punishedCrewId(new CrewId(targetCrewId))
                    .punisherCrewId(new CrewId(targetCrewId)) // self-applied (테스트 픽스처)
                    .punisherType(PunisherType.CREW)
                    .penaltyReason("crew applied")
                    .penaltyDate(LocalDateTime.now())
                    .penaltyType(PenaltyType.PERMANENT_EXPULSION)
                    .released(false)
                    .build();
            return historyRepository.saveAndFlush(pre).getId();
        });

        assertThatThrownBy(() -> service.release(partyroomId, historyId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(PenaltyException.CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE.getMessage());

        // history는 여전히 unreleased
        CrewPenaltyHistoryData reloaded = historyRepository.findById(historyId).orElseThrow();
        assertThat(reloaded.isReleased()).isFalse();
        // crew는 여전히 banned
        CrewData crew = crewRepository.findById(targetCrewId).orElseThrow();
        assertThat(crew.isBanned()).isTrue();
        // RELEASE_CREW_PENALTY admin_action 없음
        assertThat(auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId)).isEmpty();
    }

    @Test
    @DisplayName("release - TERMINATED partyroom: ADMIN row release 허용 (§4.2 cleanup 시점)")
    void release_terminated_partyroom_allowed() {
        // apply PERMANENT 먼저
        AdminApplyPenaltyCommand cmd = new AdminApplyPenaltyCommand(
                targetCrewId, AdminPenaltyType.PERMANENT_EXPULSION, "abuse");
        Long historyId = service.apply(partyroomId, cmd);

        // partyroom terminate
        transactionTemplate.executeWithoutResult(status -> {
            PartyroomData p = partyroomRepository.findById(partyroomId).orElseThrow();
            p.terminate();
            partyroomRepository.saveAndFlush(p);
        });

        // release 허용
        service.release(partyroomId, historyId);

        CrewPenaltyHistoryData reloaded = historyRepository.findById(historyId).orElseThrow();
        assertThat(reloaded.isReleased()).isTrue();
        CrewData crew = crewRepository.findById(targetCrewId).orElseThrow();
        assertThat(crew.isBanned()).isFalse();

        // RELEASE_CREW_PENALTY 1 row 추가
        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0).getActionType()).isEqualTo(PartyroomAdminActionType.RELEASE_CREW_PENALTY);
    }
}
