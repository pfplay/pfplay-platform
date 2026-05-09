package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportListQuery;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportSummaryResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomReportRepository;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.value.Bio;
import com.pfplaybackend.api.user.domain.value.Nickname;
import org.mockito.Mockito;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * Unit test for {@link AdminReportQueryService} (PR 13 G3).
 *
 * <p>Covers list happy path + detail orchestration of 4 cross-context loose-ref reads
 * (reporter member→nickname, reporter userAccount→email, partyroom→title,
 * host member→nickname). Repository mocks; verifies orphan tolerance and REPORT_NOT_FOUND.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.2, §3.3, §5
 */
@ExtendWith(MockitoExtension.class)
class AdminReportQueryServiceTest {

    private static final Long REPORT_ID = 11L;
    private static final Long PARTYROOM_ID = 100L;
    private static final Long REPORTER_UID = 200L;
    private static final Long HOST_UID = 300L;

    @Mock PartyroomReportRepository reportRepository;
    @Mock MemberRepository memberRepository;
    @Mock UserAccountRepository userAccountRepository;
    @Mock PartyroomAggregatePort partyroomAggregatePort;

    @InjectMocks AdminReportQueryService service;

    // ============================== list ==============================

    @Test
    @DisplayName("list happy: repository.findList → Summary 매핑 후 Page 반환")
    void getList_happy_mapsToSummary() {
        PartyroomReportData report = newReport(REPORT_ID, ReportStatus.PENDING, ReportCategory.SPAM);
        Page<PartyroomReportData> page = new PageImpl<>(List.of(report), PageRequest.of(0, 50), 1L);
        given(reportRepository.findList(any(), any(), any(), any(), any(Pageable.class)))
                .willReturn(page);

        AdminReportListQuery query = new AdminReportListQuery(
                List.of(ReportStatus.PENDING), null, null, null);
        Page<AdminReportSummaryResponse> result = service.getList(query, PageRequest.of(0, 50));

        assertThat(result.getTotalElements()).isEqualTo(1L);
        assertThat(result.getContent()).hasSize(1);
        AdminReportSummaryResponse row = result.getContent().get(0);
        assertThat(row.reportId()).isEqualTo(REPORT_ID);
        assertThat(row.partyroomId()).isEqualTo(PARTYROOM_ID);
        assertThat(row.status()).isEqualTo(ReportStatus.PENDING);
        assertThat(row.category()).isEqualTo(ReportCategory.SPAM);
    }

    // ============================== detail happy ==============================

    @Test
    @DisplayName("detail happy: cross-context 4종 모두 populated")
    void getDetail_happy_populatesAllCrossContextFields() {
        PartyroomReportData report = newReport(REPORT_ID, ReportStatus.REVIEWING, ReportCategory.HARASSMENT);
        ReflectionTestUtils.setField(report, "reviewedByAdministratorId", 999L);

        // Build mock-bearing values BEFORE any outer given(...) chain — avoids
        // UnfinishedStubbingException (inner Mockito.when nests inside outer given()).
        MemberData reporterMember = memberWithNickname(REPORTER_UID, "Reporter Nick");
        MemberData hostMember = memberWithNickname(HOST_UID, "Host Nick");
        UserAccountData reporterAccount = stubUserAccount(REPORTER_UID, "reporter@x.com");
        PartyroomData partyroom = stubPartyroom("Hot Room", HOST_UID);

        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(userAccountRepository.findById(eq(new UserId(REPORTER_UID))))
                .willReturn(Optional.of(reporterAccount));
        given(memberRepository.findByUserAccountId(REPORTER_UID))
                .willReturn(Optional.of(reporterMember));
        given(partyroomAggregatePort.findPartyroomById(PARTYROOM_ID))
                .willReturn(Optional.of(partyroom));
        given(memberRepository.findByUserAccountId(HOST_UID))
                .willReturn(Optional.of(hostMember));

        AdminReportDetailResponse response = service.getDetail(REPORT_ID);

        assertThat(response.reportId()).isEqualTo(REPORT_ID);
        assertThat(response.status()).isEqualTo(ReportStatus.REVIEWING);
        assertThat(response.category()).isEqualTo(ReportCategory.HARASSMENT);
        assertThat(response.reporter()).isNotNull();
        assertThat(response.reporter().userAccountId()).isEqualTo(REPORTER_UID);
        assertThat(response.reporter().email()).isEqualTo("reporter@x.com");
        assertThat(response.reporter().nickname()).isEqualTo("Reporter Nick");
        assertThat(response.partyroom()).isNotNull();
        assertThat(response.partyroom().partyroomId()).isEqualTo(PARTYROOM_ID);
        assertThat(response.partyroom().title()).isEqualTo("Hot Room");
        assertThat(response.partyroom().host()).isNotNull();
        assertThat(response.partyroom().host().userAccountId()).isEqualTo(HOST_UID);
        assertThat(response.partyroom().host().nickname()).isEqualTo("Host Nick");
        assertThat(response.review().reviewedByAdministratorId()).isEqualTo(999L);
    }

    // ============================== orphan: reporter member missing ==============================

    @Test
    @DisplayName("detail orphan: reporter member 없음 → reporter.nickname=null, email은 userAccount에서 채움")
    void getDetail_orphanReporterMember_nicknameNull() {
        PartyroomReportData report = newReport(REPORT_ID, ReportStatus.PENDING, ReportCategory.SPAM);
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(userAccountRepository.findById(eq(new UserId(REPORTER_UID))))
                .willReturn(Optional.of(stubUserAccount(REPORTER_UID, "ghost@x.com")));
        given(memberRepository.findByUserAccountId(REPORTER_UID)).willReturn(Optional.empty());
        given(partyroomAggregatePort.findPartyroomById(PARTYROOM_ID))
                .willReturn(Optional.of(stubPartyroom("Room", HOST_UID)));
        given(memberRepository.findByUserAccountId(HOST_UID)).willReturn(Optional.empty());

        AdminReportDetailResponse response = service.getDetail(REPORT_ID);

        assertThat(response.reporter().email()).isEqualTo("ghost@x.com");
        assertThat(response.reporter().nickname()).isNull();
    }

    // ============================== orphan: partyroom missing ==============================

    @Test
    @DisplayName("detail orphan: partyroom 없음 → partyroom.title=null, host=null (nested record build)")
    void getDetail_orphanPartyroom_titleNull() {
        PartyroomReportData report = newReport(REPORT_ID, ReportStatus.RESOLVED, ReportCategory.OTHER);
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(userAccountRepository.findById(any())).willReturn(Optional.empty());
        given(memberRepository.findByUserAccountId(REPORTER_UID)).willReturn(Optional.empty());
        given(partyroomAggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.empty());

        AdminReportDetailResponse response = service.getDetail(REPORT_ID);

        assertThat(response.partyroom()).isNotNull();
        assertThat(response.partyroom().partyroomId()).isEqualTo(PARTYROOM_ID);
        assertThat(response.partyroom().title()).isNull();
        assertThat(response.partyroom().host()).isNull();
    }

    // ============================== not found ==============================

    @Test
    @DisplayName("detail not found: report 부재 → REPORT_NOT_FOUND (RPT-001)")
    void getDetail_reportMissing_throwsNotFound() {
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(REPORT_ID))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-001");
    }

    // ============================== helpers ==============================

    private PartyroomReportData newReport(Long id, ReportStatus status, ReportCategory category) {
        PartyroomReportData r = PartyroomReportData.create(
                PARTYROOM_ID, REPORTER_UID, category, "desc");
        ReflectionTestUtils.setField(r, "reportId", id);
        ReflectionTestUtils.setField(r, "status", status);
        return r;
    }

    private UserAccountData stubUserAccount(Long uid, String email) {
        UserAccountData ua = UserAccountData.createForLocal(new UserId(uid), email, "h");
        ReflectionTestUtils.setField(ua, "userId", new UserId(uid));
        return ua;
    }

    private MemberData memberWithNickname(Long userAccountId, String nickname) {
        // ProfileData has a protected no-arg ctor; mock instead so getBio() returns a real Bio.
        ProfileData profile = Mockito.mock(ProfileData.class);
        Mockito.when(profile.getBio()).thenReturn(new Bio(new Nickname(nickname), null));
        MemberData m = MemberData.createForUserAccount(userAccountId);
        ReflectionTestUtils.setField(m, "profileData", profile);
        return m;
    }

    private PartyroomData stubPartyroom(String title, Long hostUid) {
        return PartyroomData.builder()
                .id(PARTYROOM_ID)
                .hostId(new UserId(hostUid))
                .stageType(StageType.GENERAL)
                .title(title)
                .introduction("intro")
                .linkDomain(LinkDomain.of("x.com"))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .status(PartyroomStatus.ACTIVE)
                .build();
    }
}
