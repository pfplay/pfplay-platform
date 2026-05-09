package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportListQuery;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportSummaryResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomReportRepository;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
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
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IT for {@link AdminReportQueryService} (PR 13 G3).
 *
 * <p>Verifies end-to-end via Testcontainers MySQL — V13 마이그 + JPA mapping + QueryDSL list query
 * + cross-context loose-ref join (member, userAccount, partyroom) + orphan tolerance.
 *
 * <p>g4it.local 도메인 격리 + scoped DELETE in {@code @AfterEach} — V5 super-admin SEED 보호.
 */
class AdminReportQueryServiceIT extends AbstractIntegrationTest {

    private static final long HOST_UID = 8101L;
    private static final long REPORTER_UID = 8102L;

    @Autowired AdminReportQueryService service;
    @Autowired PartyroomReportRepository reportRepository;
    @Autowired PartyroomRepository partyroomRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @PersistenceContext EntityManager em;

    private Long partyroomId;
    private Long pendingSpamId;
    private Long pendingHarassmentId;
    private Long reviewingHarassmentId;
    private Long resolvedSpamId;
    private Long dismissedOtherId;

    @BeforeEach
    void seed() {
        seedMember(HOST_UID, "host-g3@g4it.local");
        seedMember(REPORTER_UID, "reporter-g3@g4it.local");
        partyroomId = seedRoom(HOST_UID, "g3-active-room").getId();

        // Seed 5 reports with varying status/category/createdAt.
        pendingSpamId = saveReport(ReportStatus.PENDING, ReportCategory.SPAM,
                LocalDateTime.now().minusHours(1));
        pendingHarassmentId = saveReport(ReportStatus.PENDING, ReportCategory.HARASSMENT,
                LocalDateTime.now().minusHours(2));
        reviewingHarassmentId = saveReport(ReportStatus.REVIEWING, ReportCategory.HARASSMENT,
                LocalDateTime.now().minusHours(3));
        resolvedSpamId = saveReport(ReportStatus.RESOLVED, ReportCategory.SPAM,
                LocalDateTime.now().minusHours(4));
        dismissedOtherId = saveReport(ReportStatus.DISMISSED, ReportCategory.OTHER,
                LocalDateTime.now().minusHours(5));
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

    private PartyroomData seedRoom(long hostUid, String title) {
        PartyroomData p = PartyroomData.create(
                title, "intro",
                LinkDomain.of("link-" + title),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL,
                new UserId(hostUid));
        return partyroomRepository.saveAndFlush(p);
    }

    private Long saveReport(ReportStatus status, ReportCategory category, LocalDateTime createdAt) {
        return transactionTemplate.execute(s -> {
            PartyroomReportData r = PartyroomReportData.create(
                    partyroomId, REPORTER_UID, category, "desc-" + category);
            PartyroomReportData saved = reportRepository.saveAndFlush(r);
            // Override status + createdAt directly via JPQL (entity has no setter for these post-create).
            em.createQuery("UPDATE PartyroomReportData r SET r.status = :status, "
                            + "r.createdAt = :createdAt WHERE r.reportId = :id")
                    .setParameter("status", status)
                    .setParameter("createdAt", createdAt)
                    .setParameter("id", saved.getReportId())
                    .executeUpdate();
            em.flush();
            em.clear();
            return saved.getReportId();
        });
    }

    // ============================== list ==============================

    @Test
    @DisplayName("list status=PENDING → 2건 (PENDING SPAM + PENDING HARASSMENT)")
    void getList_filterStatusPending_returnsOnlyPending() {
        AdminReportListQuery query = new AdminReportListQuery(
                List.of(ReportStatus.PENDING), null, null, null);
        Page<AdminReportSummaryResponse> page = service.getList(
                query, PageRequest.of(0, 50, Sort.by("createdAt").descending()));

        assertThat(page.getTotalElements()).isEqualTo(2L);
        assertThat(page.getContent())
                .extracting(AdminReportSummaryResponse::status)
                .containsOnly(ReportStatus.PENDING);
    }

    @Test
    @DisplayName("list category=HARASSMENT (multi-status) → 2건")
    void getList_filterCategoryHarassment_returnsBothStatuses() {
        AdminReportListQuery query = new AdminReportListQuery(
                null, List.of(ReportCategory.HARASSMENT), null, null);
        Page<AdminReportSummaryResponse> page = service.getList(
                query, PageRequest.of(0, 50, Sort.by("createdAt").descending()));

        assertThat(page.getTotalElements()).isEqualTo(2L);
        assertThat(page.getContent())
                .extracting(AdminReportSummaryResponse::category)
                .containsOnly(ReportCategory.HARASSMENT);
    }

    @Test
    @DisplayName("list pagination: size=2 → 3 pages × 2/2/1 (5 reports)")
    void getList_pagination_paginatesAcrossPages() {
        AdminReportListQuery query = new AdminReportListQuery(null, null, null, null);
        Page<AdminReportSummaryResponse> page0 = service.getList(
                query, PageRequest.of(0, 2, Sort.by("createdAt").descending()));

        assertThat(page0.getTotalElements()).isEqualTo(5L);
        assertThat(page0.getTotalPages()).isEqualTo(3);
        assertThat(page0.getContent()).hasSize(2);

        Page<AdminReportSummaryResponse> page2 = service.getList(
                query, PageRequest.of(2, 2, Sort.by("createdAt").descending()));
        assertThat(page2.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("list sort created_at_asc — 가장 오래된(dismissedOther = -5h)이 첫번째")
    void getList_sortAsc_oldestFirst() {
        AdminReportListQuery query = new AdminReportListQuery(null, null, null, null);
        Page<AdminReportSummaryResponse> page = service.getList(
                query, PageRequest.of(0, 50, Sort.by("createdAt").ascending()));

        assertThat(page.getContent().get(0).reportId()).isEqualTo(dismissedOtherId);
        assertThat(page.getContent().get(page.getContent().size() - 1).reportId())
                .isEqualTo(pendingSpamId);
    }

    @Test
    @DisplayName("list sort created_at_desc — 가장 최근(pendingSpam = -1h)이 첫번째")
    void getList_sortDesc_newestFirst() {
        AdminReportListQuery query = new AdminReportListQuery(null, null, null, null);
        Page<AdminReportSummaryResponse> page = service.getList(
                query, PageRequest.of(0, 50, Sort.by("createdAt").descending()));

        assertThat(page.getContent().get(0).reportId()).isEqualTo(pendingSpamId);
        assertThat(page.getContent().get(page.getContent().size() - 1).reportId())
                .isEqualTo(dismissedOtherId);
    }

    @Test
    @DisplayName("list createdTo end-of-day inclusion: 23:59:59 레코드도 createdTo 당일 필터에 포함")
    void getList_filterCreatedTo_endOfDayBoundaryInclusive() {
        // Seed an extra report with createdAt = today 23:59:59 — boundary record that must be
        // included when filtering createdTo = today. Regression guard for `lt(createdTo.plusDays(1).atStartOfDay())`
        // — naive `loe(createdTo.atStartOfDay())` would exclude this.
        LocalDate today = LocalDate.now();
        LocalDateTime endOfDay = today.atTime(23, 59, 59);
        Long boundaryId = saveReport(ReportStatus.PENDING, ReportCategory.OTHER, endOfDay);

        AdminReportListQuery query = new AdminReportListQuery(null, null, today, today);
        Page<AdminReportSummaryResponse> page = service.getList(
                query, PageRequest.of(0, 50, Sort.by("createdAt").descending()));

        assertThat(page.getContent())
                .extracting(AdminReportSummaryResponse::reportId)
                .contains(boundaryId);
    }

    @Test
    @DisplayName("list createdFrom~createdTo 범위 필터 happy: 범위 밖 레코드 제외")
    void getList_filterCreatedRange_happyExcludesOutOfRange() {
        // Seed two extra reports — one before window (2 days ago), one inside (today).
        Long beforeId = saveReport(ReportStatus.PENDING, ReportCategory.OTHER,
                LocalDate.now().minusDays(2).atTime(10, 0));
        Long insideId = saveReport(ReportStatus.PENDING, ReportCategory.OTHER,
                LocalDate.now().atTime(10, 0));

        AdminReportListQuery query = new AdminReportListQuery(
                null, null, LocalDate.now().minusDays(1), LocalDate.now());
        Page<AdminReportSummaryResponse> page = service.getList(
                query, PageRequest.of(0, 50, Sort.by("createdAt").descending()));

        assertThat(page.getContent())
                .extracting(AdminReportSummaryResponse::reportId)
                .contains(insideId)
                .doesNotContain(beforeId);
    }

    // ============================== detail ==============================

    @Test
    @DisplayName("detail happy: cross-context join — reporter.email/nickname + partyroom.title + host.nickname")
    void getDetail_happy_crossContextJoin() {
        AdminReportDetailResponse response = service.getDetail(reviewingHarassmentId);

        assertThat(response.reportId()).isEqualTo(reviewingHarassmentId);
        assertThat(response.status()).isEqualTo(ReportStatus.REVIEWING);
        assertThat(response.category()).isEqualTo(ReportCategory.HARASSMENT);
        assertThat(response.reporter()).isNotNull();
        assertThat(response.reporter().userAccountId()).isEqualTo(REPORTER_UID);
        assertThat(response.reporter().email()).isEqualTo("reporter-g3@g4it.local");
        assertThat(response.partyroom()).isNotNull();
        assertThat(response.partyroom().partyroomId()).isEqualTo(partyroomId);
        assertThat(response.partyroom().title()).isEqualTo("g3-active-room");
        assertThat(response.partyroom().host()).isNotNull();
        assertThat(response.partyroom().host().userAccountId()).isEqualTo(HOST_UID);
    }

    @Test
    @DisplayName("detail orphan reporter: member 삭제 → reporter.nickname=null, email은 userAccount 잔존이라 populated")
    void getDetail_orphanReporterMember_emailKept() {
        // Delete the reporter Member row but keep the UserAccount row.
        transactionTemplate.executeWithoutResult(s -> {
            memberRepository.findByUserAccountId(REPORTER_UID).ifPresent(memberRepository::delete);
        });

        AdminReportDetailResponse response = service.getDetail(reviewingHarassmentId);

        assertThat(response.reporter().email()).isEqualTo("reporter-g3@g4it.local");
        assertThat(response.reporter().nickname()).isNull();
    }

    @Test
    @DisplayName("detail orphan partyroom: partyroom 삭제 → title=null, host=null (nested record build)")
    void getDetail_orphanPartyroom_titleNull() {
        transactionTemplate.executeWithoutResult(s -> partyroomRepository.deleteById(partyroomId));

        AdminReportDetailResponse response = service.getDetail(reviewingHarassmentId);

        assertThat(response.partyroom()).isNotNull();
        assertThat(response.partyroom().partyroomId()).isEqualTo(partyroomId);
        assertThat(response.partyroom().title()).isNull();
        assertThat(response.partyroom().host()).isNull();
    }

    @Test
    @DisplayName("detail not-found → REPORT_NOT_FOUND (RPT-001)")
    void getDetail_reportMissing_throwsNotFound() {
        assertThatThrownBy(() -> service.getDetail(999_999L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-001");
    }
}
