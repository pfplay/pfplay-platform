package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportStatusUpdateRequest;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomReportRepository;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * IT for {@link AdminReportCommandService} (PR 13 G4).
 *
 * <p>Verifies end-to-end via Testcontainers MySQL — V13 마이그 + 5 transition matrix(D3) +
 * INVALID_STATE_TRANSITION(RPT-002) + RESOLUTION_NOTE_REQUIRED(RPT-003) + detail response 재사용
 * (G3 buildDetailResponse — cross-context loose-ref join).
 *
 * <p>{@code AdminContext}는 PR 12b2 IT 패턴 mirror — {@code @MockBean}으로 inject + BDD given.
 * SecurityContext 우회로 단순화.
 *
 * <p>{@code g4it.local} 도메인 격리 + scoped DELETE in {@code @AfterEach} — V5 super-admin
 * SEED 보호.
 */
class AdminReportCommandServiceIT extends AbstractIntegrationTest {

    private static final long HOST_UID = 8201L;
    private static final long REPORTER_UID = 8202L;
    private static final long ADMIN_USER_ID = 990199L;
    private static final long REVIEWER_USER_ID = 990188L;

    // FK reviewed_by_administrator_id → administrator. AUTO_INCREMENT id 이므로 시드 후 생성 id 채택.
    private Long adminId;
    private Long firstReviewerAdminId;
    private static final String NOTE = "처리 완료 메모";

    @Autowired AdminReportCommandService service;
    @Autowired AdminReportQueryService queryService;
    @Autowired PartyroomReportRepository reportRepository;
    @Autowired PartyroomRepository partyroomRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired AdministratorRepository administratorRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @PersistenceContext EntityManager em;

    @MockBean AdminContext adminContext;

    private Long partyroomId;
    private Long pendingId;
    private Long reviewingId;
    private Long resolvedId;
    private Long dismissedId;

    @BeforeEach
    void seed() {
        // FK reviewed_by_administrator_id → administrator. actor(현재 검토자) + 첫 검토자 2명 시드.
        adminId = seedAdmin(ADMIN_USER_ID, "admin-g4it@g4it.local", null);
        firstReviewerAdminId = seedAdmin(REVIEWER_USER_ID, "reviewer-admin-g4it@g4it.local", adminId);

        seedMember(HOST_UID, "host-g4it@g4it.local");
        seedMember(REPORTER_UID, "reporter-g4it@g4it.local");
        partyroomId = seedRoom(HOST_UID, "g4-active-room").getId();

        // 4 reports each in different from-state.
        pendingId = saveReport(ReportStatus.PENDING, ReportCategory.SPAM, null);
        reviewingId = saveReport(ReportStatus.REVIEWING, ReportCategory.HARASSMENT, firstReviewerAdminId);
        resolvedId = saveReport(ReportStatus.RESOLVED, ReportCategory.OTHER, firstReviewerAdminId);
        dismissedId = saveReport(ReportStatus.DISMISSED, ReportCategory.SPAM, firstReviewerAdminId);

        given(adminContext.currentAdministratorId()).willReturn(adminId);
    }

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(s -> {
            reportRepository.deleteAll();
            partyroomRepository.deleteAll();
            memberRepository.deleteAll();
            userAccountRepository.deleteAll();
        });
    }

    private void seedMember(long uid, String email) {
        userAccountRepository.saveAndFlush(
                UserAccountData.createForLocal(new UserId(uid), email, "h"));
        memberRepository.saveAndFlush(MemberData.createForUserAccount(uid));
    }

    /** grantedBy=null → super admin, else 일반 admin. 생성된 administrator_id 반환. */
    private Long seedAdmin(long uid, String email, Long grantedBy) {
        userAccountRepository.saveAndFlush(
                UserAccountData.createForLocal(new UserId(uid), email, "h"));
        AdministratorData admin = (grantedBy == null)
                ? AdministratorData.createSuperAdmin(uid)
                : AdministratorData.createAdmin(uid, grantedBy);
        return administratorRepository.saveAndFlush(admin).getAdministratorId();
    }

    private PartyroomData seedRoom(long hostUid, String title) {
        PartyroomData p = PartyroomData.create(
                title, "intro",
                LinkDomain.of("link-" + title),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL,
                new UserId(hostUid));
        return partyroomRepository.saveAndFlush(p);
    }

    /**
     * status/reviewer를 직접 setter 없이 JPQL UPDATE로 override.
     * G3 IT 패턴 mirror — entity는 PENDING factory만 제공하므로 from-state 시드용 우회.
     */
    private Long saveReport(ReportStatus status, ReportCategory category, Long reviewerId) {
        return transactionTemplate.execute(s -> {
            PartyroomReportData r = PartyroomReportData.create(
                    partyroomId, REPORTER_UID, category, "desc-" + category);
            PartyroomReportData saved = reportRepository.saveAndFlush(r);
            // RESOLVED/DISMISSED는 resolvedAt도 set; REVIEWING은 reviewer만; PENDING은 그대로.
            String jpql = "UPDATE PartyroomReportData r SET r.status = :status";
            if (reviewerId != null) {
                jpql += ", r.reviewedByAdministratorId = :reviewer";
            }
            if (status == ReportStatus.RESOLVED || status == ReportStatus.DISMISSED) {
                jpql += ", r.resolvedAt = :resolvedAt, r.resolutionNote = :note";
            }
            jpql += " WHERE r.reportId = :id";
            var q = em.createQuery(jpql)
                    .setParameter("status", status)
                    .setParameter("id", saved.getReportId());
            if (reviewerId != null) {
                q.setParameter("reviewer", reviewerId);
            }
            if (status == ReportStatus.RESOLVED || status == ReportStatus.DISMISSED) {
                q.setParameter("resolvedAt", LocalDateTime.now());
                q.setParameter("note", "seed-note");
            }
            q.executeUpdate();
            em.flush();
            em.clear();
            return saved.getReportId();
        });
    }

    // ============================== happy: 4 transitions ==============================

    @Test
    @DisplayName("PENDING → REVIEWING happy: status + 첫 reviewer 등록 + resolutionNote 미요구")
    void changeStatus_pendingToReviewing_happy() {
        AdminReportDetailResponse response = service.changeStatus(pendingId,
                new AdminReportStatusUpdateRequest(ReportStatus.REVIEWING, null));

        assertThat(response.status()).isEqualTo(ReportStatus.REVIEWING);
        PartyroomReportData persisted = reportRepository.findById(pendingId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.REVIEWING);
        assertThat(persisted.getReviewedByAdministratorId()).isEqualTo(adminId);
        assertThat(persisted.getResolvedAt()).isNull();
        assertThat(persisted.getResolutionNote()).isNull();
    }

    @Test
    @DisplayName("PENDING → DISMISSED (직접 거절) happy: 첫 reviewer 등록 + resolvedAt + resolutionNote 기록")
    void changeStatus_pendingToDismissed_happy() {
        AdminReportDetailResponse response = service.changeStatus(pendingId,
                new AdminReportStatusUpdateRequest(ReportStatus.DISMISSED, NOTE));

        assertThat(response.status()).isEqualTo(ReportStatus.DISMISSED);
        PartyroomReportData persisted = reportRepository.findById(pendingId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.DISMISSED);
        assertThat(persisted.getReviewedByAdministratorId()).isEqualTo(adminId);
        assertThat(persisted.getResolvedAt()).isNotNull();
        assertThat(persisted.getResolutionNote()).isEqualTo(NOTE);
    }

    @Test
    @DisplayName("REVIEWING → RESOLVED happy: resolvedAt + resolutionNote 기록 + 첫 reviewer 보존")
    void changeStatus_reviewingToResolved_happy() {
        AdminReportDetailResponse response = service.changeStatus(reviewingId,
                new AdminReportStatusUpdateRequest(ReportStatus.RESOLVED, NOTE));

        assertThat(response.status()).isEqualTo(ReportStatus.RESOLVED);
        PartyroomReportData persisted = reportRepository.findById(reviewingId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        // D3.2: 첫 검토자(firstReviewerAdminId) 보존, 현재 adminId는 덮어쓰지 않음
        assertThat(persisted.getReviewedByAdministratorId()).isEqualTo(firstReviewerAdminId);
        assertThat(persisted.getResolvedAt()).isNotNull();
        assertThat(persisted.getResolutionNote()).isEqualTo(NOTE);
    }

    @Test
    @DisplayName("REVIEWING → PENDING (보류, hold) happy: status 회귀 + reviewer 보존(audit) + resolvedAt null")
    void changeStatus_reviewingToPendingHold_happy() {
        AdminReportDetailResponse response = service.changeStatus(reviewingId,
                new AdminReportStatusUpdateRequest(ReportStatus.PENDING, null));

        assertThat(response.status()).isEqualTo(ReportStatus.PENDING);
        PartyroomReportData persisted = reportRepository.findById(reviewingId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.PENDING);
        // D3.2: hold 시에도 첫 검토자(audit) 보존
        assertThat(persisted.getReviewedByAdministratorId()).isEqualTo(firstReviewerAdminId);
        assertThat(persisted.getResolvedAt()).isNull();
    }

    // ============================== INVALID_STATE_TRANSITION ==============================

    @Test
    @DisplayName("RESOLVED → DISMISSED 시도 → INVALID_STATE_TRANSITION RPT-002 (terminal 금지)")
    void changeStatus_resolvedToDismissed_throwsInvalid() {
        assertThatThrownBy(() -> service.changeStatus(resolvedId,
                new AdminReportStatusUpdateRequest(ReportStatus.DISMISSED, NOTE)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-002");

        // entity status는 그대로 RESOLVED (rollback 또는 throw 전 mutation 미발생)
        PartyroomReportData persisted = reportRepository.findById(resolvedId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    }

    @Test
    @DisplayName("PENDING → RESOLVED skip 시도 → INVALID_STATE_TRANSITION RPT-002 (matrix 금지)")
    void changeStatus_pendingToResolvedSkip_throwsInvalid() {
        assertThatThrownBy(() -> service.changeStatus(pendingId,
                new AdminReportStatusUpdateRequest(ReportStatus.RESOLVED, NOTE)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-002");

        PartyroomReportData persisted = reportRepository.findById(pendingId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.PENDING);
    }

    // ============================== RESOLUTION_NOTE_REQUIRED ==============================

    @Test
    @DisplayName("REVIEWING → RESOLVED + resolutionNote=\"\" → RESOLUTION_NOTE_REQUIRED RPT-003")
    void changeStatus_resolvedBlankNote_throwsResolutionNoteRequired() {
        assertThatThrownBy(() -> service.changeStatus(reviewingId,
                new AdminReportStatusUpdateRequest(ReportStatus.RESOLVED, "")))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-003");

        // entity는 REVIEWING 그대로 (서비스 guard에서 도메인 호출 전 throw)
        PartyroomReportData persisted = reportRepository.findById(reviewingId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.REVIEWING);
    }

    // ============================== REPORT_NOT_FOUND ==============================

    @Test
    @DisplayName("reportId 부재 → REPORT_NOT_FOUND RPT-001")
    void changeStatus_reportNotFound_throws() {
        assertThatThrownBy(() -> service.changeStatus(999_999L,
                new AdminReportStatusUpdateRequest(ReportStatus.REVIEWING, null)))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-001");
    }

    // ============================== detail response shape (G3 reuse) ==============================

    @Test
    @DisplayName("DISMISSED → REVIEWING 시도 → INVALID_STATE_TRANSITION RPT-002 (DISMISSED terminal 금지)")
    void changeStatus_dismissedTerminal_throwsInvalid() {
        // DISMISSED도 RESOLVED와 함께 terminal — 어떠한 target으로도 전이 불가.
        assertThatThrownBy(() -> service.changeStatus(dismissedId,
                new AdminReportStatusUpdateRequest(ReportStatus.REVIEWING, null)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-002");

        PartyroomReportData persisted = reportRepository.findById(dismissedId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.DISMISSED);
    }

    @Test
    @DisplayName("PATCH 응답 shape이 G3 detail과 동일: cross-context join 4종 populated (reporter email/nickname + partyroom title + host)")
    void changeStatus_responseShape_consistentWithG3Detail() {
        AdminReportDetailResponse response = service.changeStatus(pendingId,
                new AdminReportStatusUpdateRequest(ReportStatus.REVIEWING, null));

        // PATCH 응답이 G3 buildDetailResponse 결과와 정확히 일치 (PATCH 후 detail 재조회 시).
        AdminReportDetailResponse g3Detail = queryService.getDetail(pendingId);

        assertThat(response.reportId()).isEqualTo(g3Detail.reportId());
        assertThat(response.status()).isEqualTo(g3Detail.status());
        assertThat(response.reporter()).isNotNull();
        assertThat(response.reporter().userAccountId()).isEqualTo(REPORTER_UID);
        assertThat(response.reporter().email()).isEqualTo("reporter-g4it@g4it.local");
        assertThat(response.partyroom()).isNotNull();
        assertThat(response.partyroom().partyroomId()).isEqualTo(partyroomId);
        assertThat(response.partyroom().title()).isEqualTo("g4-active-room");
        assertThat(response.partyroom().host()).isNotNull();
        assertThat(response.partyroom().host().userAccountId()).isEqualTo(HOST_UID);
        assertThat(response.review()).isNotNull();
        assertThat(response.review().reviewedByAdministratorId()).isEqualTo(adminId);
    }
}
