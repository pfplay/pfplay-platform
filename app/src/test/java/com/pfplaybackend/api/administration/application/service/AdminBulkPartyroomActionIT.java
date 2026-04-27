package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.BulkPartyroomActionRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.BulkPartyroomActionResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.BulkActionType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT for {@link AdminBulkPartyroomActionService} — 실제 DB + listener 동작.
 *
 * <p>핵심 검증:
 * - per-item TX commit: 항목별 상태 mutation + audit row가 각각 commit됨.
 * - skipErrors=true: 한 항목 실패 시 나머지 진행.
 * - skipErrors=false: 첫 실패에서 break, 후속 ids 미처리.
 * - 이미 TERMINATED인 룸은 ILLEGAL_STATE_TRANSITION으로 실패 — strict guard (PR 7).
 */
class AdminBulkPartyroomActionIT extends AbstractIntegrationTest {

    @Autowired private AdminBulkPartyroomActionService bulkService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomAdminActionRepository auditRepository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private UserAccountRepository userAccountRepository;

    private Long superAdminId;
    private Long activeRoomA;
    private Long terminatedRoom;
    private Long activeRoomC;

    @BeforeEach
    void seed() {
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(800L), "bulk-it@x", "h"));
        AdministratorData superAdmin = administratorRepository.save(
                AdministratorData.createSuperAdmin(800L));
        this.superAdminId = superAdmin.getAdministratorId();

        PartyroomData a = PartyroomData.create(
                "bulk-a", "intro", LinkDomain.of("bulk-link-a"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(800L));
        this.activeRoomA = partyroomRepository.saveAndFlush(a).getId();

        PartyroomData b = PartyroomData.create(
                "bulk-b", "intro", LinkDomain.of("bulk-link-b"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(800L));
        // 먼저 terminate 시켜 TERMINATED 상태로 만든다.
        b.terminate();
        this.terminatedRoom = partyroomRepository.saveAndFlush(b).getId();

        PartyroomData c = PartyroomData.create(
                "bulk-c", "intro", LinkDomain.of("bulk-link-c"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(800L));
        this.activeRoomC = partyroomRepository.saveAndFlush(c).getId();
    }

    @Test
    @DisplayName("skipErrors=true bulk TERMINATE — 2 succeed, 1 fail (already terminated)")
    void bulkTerminate_skipErrorsTrue_continuesPastFailure() {
        BulkPartyroomActionRequest req = new BulkPartyroomActionRequest(
                List.of(activeRoomA, terminatedRoom, activeRoomC),
                BulkActionType.TERMINATE,
                "policy violation",
                true);

        BulkPartyroomActionResponse resp = bulkService.execute(req, superAdminId);

        assertThat(resp.results()).hasSize(3);
        assertThat(resp.results().get(0).success()).isTrue();
        assertThat(resp.results().get(0).partyroomId()).isEqualTo(activeRoomA);

        assertThat(resp.results().get(1).success()).isFalse();
        assertThat(resp.results().get(1).partyroomId()).isEqualTo(terminatedRoom);
        assertThat(resp.results().get(1).error()).isNotBlank();

        assertThat(resp.results().get(2).success()).isTrue();
        assertThat(resp.results().get(2).partyroomId()).isEqualTo(activeRoomC);

        // per-item state mutation은 commit됨.
        assertThat(partyroomRepository.findById(activeRoomA).orElseThrow().getStatus())
                .isEqualTo(PartyroomStatus.TERMINATED);
        assertThat(partyroomRepository.findById(activeRoomC).orElseThrow().getStatus())
                .isEqualTo(PartyroomStatus.TERMINATED);
        // 이미 TERMINATED는 그대로.
        assertThat(partyroomRepository.findById(terminatedRoom).orElseThrow().getStatus())
                .isEqualTo(PartyroomStatus.TERMINATED);

        // audit rows: 성공한 2건만 INSERT — 실패 항목은 자기 TX가 롤백되므로 audit도 없음.
        List<PartyroomAdminActionData> auditsA =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(activeRoomA);
        assertThat(auditsA).hasSize(1);
        assertThat(auditsA.get(0).getActionType()).isEqualTo(PartyroomAdminActionType.TERMINATE_PARTYROOM);

        List<PartyroomAdminActionData> auditsC =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(activeRoomC);
        assertThat(auditsC).hasSize(1);
        assertThat(auditsC.get(0).getActionType()).isEqualTo(PartyroomAdminActionType.TERMINATE_PARTYROOM);

        List<PartyroomAdminActionData> auditsTerm =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(terminatedRoom);
        assertThat(auditsTerm).isEmpty();
    }

    @Test
    @DisplayName("skipErrors=false bulk TERMINATE — 첫 실패에서 break")
    void bulkTerminate_skipErrorsFalse_breaksOnFirstFailure() {
        // 의도적으로 첫 항목을 TERMINATED로 두어 즉시 실패시킨다.
        BulkPartyroomActionRequest req = new BulkPartyroomActionRequest(
                List.of(terminatedRoom, activeRoomA, activeRoomC),
                BulkActionType.TERMINATE,
                "policy violation",
                false);

        BulkPartyroomActionResponse resp = bulkService.execute(req, superAdminId);

        assertThat(resp.results()).hasSize(1);
        assertThat(resp.results().get(0).success()).isFalse();
        assertThat(resp.results().get(0).partyroomId()).isEqualTo(terminatedRoom);

        // 후속 ids 미처리 — 상태 변화 없음.
        assertThat(partyroomRepository.findById(activeRoomA).orElseThrow().getStatus())
                .isEqualTo(PartyroomStatus.ACTIVE);
        assertThat(partyroomRepository.findById(activeRoomC).orElseThrow().getStatus())
                .isEqualTo(PartyroomStatus.ACTIVE);

        // audit도 없음.
        assertThat(auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(activeRoomA)).isEmpty();
        assertThat(auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(activeRoomC)).isEmpty();
    }
}
