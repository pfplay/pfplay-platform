package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.PartyroomReportCreateRequest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.PartyroomReportCreateResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomReportRepository;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IT for {@link PartyroomReportCommandService} (PR 13 G2).
 *
 * <p>Verifies end-to-end via Testcontainers MySQL — V13 마이그레이션 + JPA mapping + 24h dedup
 * window 동작 확인.
 *
 * <p>g4it.local 도메인 격리 — V5 super-admin SEED 보호. {@code @AfterEach} scoped DELETE로
 * 다른 IT 영향 0 (V13은 신규 테이블이라 partyroom_report.deleteAll만 충분).
 */
class PartyroomReportCommandServiceIT extends AbstractIntegrationTest {

    private static final long HOST_UID = 7901L;
    private static final long REPORTER_UID = 7902L;

    @Autowired PartyroomReportCommandService service;
    @Autowired PartyroomReportRepository reportRepository;
    @Autowired PartyroomRepository partyroomRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @PersistenceContext EntityManager em;

    private Long activeRoomId;
    private Long terminatedRoomId;

    @BeforeEach
    void seed() {
        seedMember(HOST_UID, "host-it@g4it.local");
        seedMember(REPORTER_UID, "reporter-it@g4it.local");
        activeRoomId = seedRoom(HOST_UID, "active-room", PartyroomStatus.ACTIVE).getId();
        terminatedRoomId = seedRoom(HOST_UID, "terminated-room", PartyroomStatus.TERMINATED).getId();
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

    private PartyroomData seedRoom(long hostUid, String title, PartyroomStatus status) {
        PartyroomData p = PartyroomData.create(
                title, "intro",
                LinkDomain.of("link-" + title),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL,
                new UserId(hostUid));
        PartyroomData saved = partyroomRepository.saveAndFlush(p);
        if (status == PartyroomStatus.SUSPENDED) {
            saved.suspend();
            partyroomRepository.saveAndFlush(saved);
        } else if (status == PartyroomStatus.TERMINATED) {
            saved.terminate();
            partyroomRepository.saveAndFlush(saved);
        }
        return saved;
    }

    // ============================== happy 201 ==============================

    @Test
    @DisplayName("happy: SPAM 신고 성공 → DB row 1건 (status=PENDING, createdAt populated)")
    void create_happy_persistsPendingRow() {
        PartyroomReportCreateResponse response = service.create(
                activeRoomId,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, "광고 도배"),
                AuthorityTier.FM,
                REPORTER_UID);

        assertThat(response.reportId()).isNotNull();

        PartyroomReportData row = reportRepository.findById(response.reportId()).orElseThrow();
        assertThat(row.getPartyroomId()).isEqualTo(activeRoomId);
        assertThat(row.getReporterUserAccountId()).isEqualTo(REPORTER_UID);
        assertThat(row.getCategory()).isEqualTo(ReportCategory.SPAM);
        assertThat(row.getDescription()).isEqualTo("광고 도배");
        assertThat(row.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getResolvedAt()).isNull();
        assertThat(row.getReviewedByAdministratorId()).isNull();
    }

    // ============================== 24h dedup window ==============================

    @Test
    @DisplayName("dedup: 24h 내 동일 카테고리 재신고 → DUPLICATE_REPORT (RPT-007)")
    void create_duplicate_within24h_throws() {
        service.create(activeRoomId,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, null),
                AuthorityTier.FM, REPORTER_UID);

        assertThatThrownBy(() -> service.create(activeRoomId,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, null),
                AuthorityTier.FM, REPORTER_UID))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-007");
    }

    @Test
    @DisplayName("dedup: 24h 경과 후 동일 카테고리 재신고 → 허용 (created_at = now-25h pre-seed)")
    void create_duplicate_after24h_allowed() {
        // Seed a 25h-old report bypassing service (so dedup window is past).
        Long staleId = transactionTemplate.execute(s -> {
            PartyroomReportData old = PartyroomReportData.create(
                    activeRoomId, REPORTER_UID, ReportCategory.SPAM, "old");
            PartyroomReportData saved = reportRepository.saveAndFlush(old);
            // Override createdAt to 25h ago via JPQL UPDATE — entity has no setter for createdAt.
            em.createQuery("UPDATE PartyroomReportData r SET r.createdAt = :ts WHERE r.reportId = :id")
                    .setParameter("ts", LocalDateTime.now().minusHours(25))
                    .setParameter("id", saved.getReportId())
                    .executeUpdate();
            em.flush();
            em.clear();
            return saved.getReportId();
        });

        PartyroomReportCreateResponse response = service.create(
                activeRoomId,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, "new"),
                AuthorityTier.FM,
                REPORTER_UID);

        assertThat(response.reportId()).isNotNull().isNotEqualTo(staleId);
        assertThat(reportRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("dedup: 24h 내 다른 카테고리 → 허용")
    void create_differentCategory_allowed() {
        service.create(activeRoomId,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, null),
                AuthorityTier.FM, REPORTER_UID);

        PartyroomReportCreateResponse response = service.create(
                activeRoomId,
                new PartyroomReportCreateRequest(ReportCategory.HARASSMENT, null),
                AuthorityTier.FM,
                REPORTER_UID);

        assertThat(response.reportId()).isNotNull();
        List<PartyroomReportData> all = reportRepository.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(PartyroomReportData::getCategory)
                .containsExactlyInAnyOrder(ReportCategory.SPAM, ReportCategory.HARASSMENT);
    }

    // ============================== self-report ==============================

    @Test
    @DisplayName("self-report: host == reporter → SELF_REPORT_FORBIDDEN (RPT-006)")
    void create_selfReport_throws() {
        assertThatThrownBy(() -> service.create(activeRoomId,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, null),
                AuthorityTier.FM, HOST_UID /* same as host */))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-006");

        assertThat(reportRepository.findAll()).isEmpty();
    }

    // ============================== non-active room ==============================

    @Test
    @DisplayName("non-active: TERMINATED 룸 → PARTYROOM_NOT_REPORTABLE (RPT-005)")
    void create_terminatedRoom_throws() {
        assertThatThrownBy(() -> service.create(terminatedRoomId,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, null),
                AuthorityTier.FM, REPORTER_UID))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-005");

        assertThat(reportRepository.findAll()).isEmpty();
    }

    // ============================== non-existent room ==============================

    @Test
    @DisplayName("non-existent: missing partyroomId → PARTYROOM_NOT_FOUND (PTR-001)")
    void create_nonExistentRoom_throws() {
        assertThatThrownBy(() -> service.create(999_999L,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, null),
                AuthorityTier.FM, REPORTER_UID))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PTR-001");

        assertThat(reportRepository.findAll()).isEmpty();
    }
}
