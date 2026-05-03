package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.PartyroomReportCreateRequest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.PartyroomReportCreateResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomReportRepository;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link PartyroomReportCommandService} (PR 13 G2).
 *
 * <p>Verifies validation order (D6 active → D2 self-report → D1 24h dedup) and
 * exception payloads. Repository interactions are mocked.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.1
 */
@ExtendWith(MockitoExtension.class)
class PartyroomReportCommandServiceTest {

    private static final Long PARTYROOM_ID = 100L;
    private static final Long HOST_USER_ACCOUNT_ID = 200L;
    private static final Long REPORTER_USER_ACCOUNT_ID = 300L;

    @Mock PartyroomReportRepository reportRepository;
    @Mock PartyroomAggregatePort partyroomAggregatePort;

    @InjectMocks PartyroomReportCommandService service;

    private PartyroomData hostedActivePartyroom() {
        return PartyroomData.builder()
                .id(PARTYROOM_ID)
                .hostId(new UserId(HOST_USER_ACCOUNT_ID))
                .stageType(StageType.GENERAL)
                .title("Active Room")
                .introduction("intro")
                .linkDomain(LinkDomain.of("youtube.com"))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .status(com.pfplaybackend.api.party.domain.enums.PartyroomStatus.ACTIVE)
                .build();
    }

    private PartyroomData hostedTerminatedPartyroom() {
        PartyroomData p = hostedActivePartyroom();
        p.terminate();
        return p;
    }

    @BeforeEach
    void setUp() {
        // no-op; per-test stubbing
    }

    @Test
    @DisplayName("happy: SPAM 신고 → 201 + reportId 반환 + repository.save 호출")
    void create_happy_savesAndReturnsId() {
        given(partyroomAggregatePort.findPartyroomById(PARTYROOM_ID))
                .willReturn(Optional.of(hostedActivePartyroom()));
        given(reportRepository.existsByReporterUserAccountIdAndPartyroomIdAndCategoryAndCreatedAtAfter(
                anyLong(), anyLong(),
                any(ReportCategory.class), any(LocalDateTime.class)))
                .willReturn(false);
        given(reportRepository.save(any(PartyroomReportData.class)))
                .willAnswer(inv -> {
                    PartyroomReportData r = inv.getArgument(0);
                    // Simulate persistence by reflectively populating reportId — easier: return a new entity.
                    PartyroomReportData saved = PartyroomReportData.create(
                            r.getPartyroomId(), r.getReporterUserAccountId(),
                            r.getCategory(), r.getDescription());
                    return saved;
                });

        PartyroomReportCreateResponse response = service.create(
                PARTYROOM_ID,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, "스팸 도배"),
                AuthorityTier.FM,
                REPORTER_USER_ACCOUNT_ID);

        assertThat(response).isNotNull();
        verify(reportRepository).save(any(PartyroomReportData.class));
    }

    @Test
    @DisplayName("happy: description=null 허용 — 신고 사유 미기재 케이스")
    void create_happy_descriptionNull() {
        given(partyroomAggregatePort.findPartyroomById(PARTYROOM_ID))
                .willReturn(Optional.of(hostedActivePartyroom()));
        given(reportRepository.existsByReporterUserAccountIdAndPartyroomIdAndCategoryAndCreatedAtAfter(
                anyLong(), anyLong(), any(ReportCategory.class), any(LocalDateTime.class)))
                .willReturn(false);
        given(reportRepository.save(any(PartyroomReportData.class)))
                .willAnswer(inv -> inv.getArgument(0));

        service.create(
                PARTYROOM_ID,
                new PartyroomReportCreateRequest(ReportCategory.OTHER, null),
                AuthorityTier.FM,
                REPORTER_USER_ACCOUNT_ID);

        verify(reportRepository).save(any(PartyroomReportData.class));
    }

    @Test
    @DisplayName("partyroom 부재 → NotFoundException(PTR-001) — repository.save 미호출")
    void create_partyroomMissing_throwsNotFound() {
        given(partyroomAggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(
                PARTYROOM_ID,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, null),
                AuthorityTier.FM,
                REPORTER_USER_ACCOUNT_ID))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PTR-001");

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("partyroom SUSPENDED → BadRequestException(RPT-005) PARTYROOM_NOT_REPORTABLE")
    void create_partyroomNotReportable_throwsBadRequest() {
        PartyroomData suspended = hostedActivePartyroom();
        suspended.suspend();
        given(partyroomAggregatePort.findPartyroomById(PARTYROOM_ID)).willReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.create(
                PARTYROOM_ID,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, null),
                AuthorityTier.FM,
                REPORTER_USER_ACCOUNT_ID))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-005");

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("partyroom TERMINATED → BadRequestException(RPT-005) PARTYROOM_NOT_REPORTABLE")
    void create_partyroomTerminated_throwsBadRequest() {
        given(partyroomAggregatePort.findPartyroomById(PARTYROOM_ID))
                .willReturn(Optional.of(hostedTerminatedPartyroom()));

        assertThatThrownBy(() -> service.create(
                PARTYROOM_ID,
                new PartyroomReportCreateRequest(ReportCategory.HARASSMENT, null),
                AuthorityTier.FM,
                REPORTER_USER_ACCOUNT_ID))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-005");

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("self-report (host == reporter) → BadRequestException(RPT-006)")
    void create_selfReport_throwsBadRequest() {
        given(partyroomAggregatePort.findPartyroomById(PARTYROOM_ID))
                .willReturn(Optional.of(hostedActivePartyroom()));

        assertThatThrownBy(() -> service.create(
                PARTYROOM_ID,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, null),
                AuthorityTier.FM,
                HOST_USER_ACCOUNT_ID /* same as host */))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-006");

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("24h 내 동일 카테고리 재신고 → BadRequestException(RPT-007) DUPLICATE_REPORT")
    void create_duplicate_throwsBadRequest() {
        given(partyroomAggregatePort.findPartyroomById(PARTYROOM_ID))
                .willReturn(Optional.of(hostedActivePartyroom()));
        given(reportRepository.existsByReporterUserAccountIdAndPartyroomIdAndCategoryAndCreatedAtAfter(
                anyLong(), anyLong(), any(ReportCategory.class), any(LocalDateTime.class)))
                .willReturn(true);

        assertThatThrownBy(() -> service.create(
                PARTYROOM_ID,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, null),
                AuthorityTier.FM,
                REPORTER_USER_ACCOUNT_ID))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-007");

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("Guest tier (GT) → ForbiddenException(RPT-008?) — D1.5 Member-only")
    void create_guestForbidden_throwsForbidden() {
        // Guest 차단은 partyroom lookup 이전에 fail-fast (D1.5).
        assertThatThrownBy(() -> service.create(
                PARTYROOM_ID,
                new PartyroomReportCreateRequest(ReportCategory.SPAM, null),
                AuthorityTier.GT,
                REPORTER_USER_ACCOUNT_ID))
                .isInstanceOf(ForbiddenException.class);

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("created entity status = PENDING (factory ensures default)")
    void create_setsPendingStatus() {
        given(partyroomAggregatePort.findPartyroomById(PARTYROOM_ID))
                .willReturn(Optional.of(hostedActivePartyroom()));
        given(reportRepository.existsByReporterUserAccountIdAndPartyroomIdAndCategoryAndCreatedAtAfter(
                anyLong(), anyLong(), any(ReportCategory.class), any(LocalDateTime.class)))
                .willReturn(false);
        given(reportRepository.save(any(PartyroomReportData.class)))
                .willAnswer(inv -> {
                    PartyroomReportData saved = inv.getArgument(0);
                    assertThat(saved.getStatus()).isEqualTo(ReportStatus.PENDING);
                    assertThat(saved.getCreatedAt()).isNotNull();
                    assertThat(saved.getReporterUserAccountId()).isEqualTo(REPORTER_USER_ACCOUNT_ID);
                    assertThat(saved.getPartyroomId()).isEqualTo(PARTYROOM_ID);
                    return saved;
                });

        service.create(
                PARTYROOM_ID,
                new PartyroomReportCreateRequest(ReportCategory.COPYRIGHT, "저작권"),
                AuthorityTier.FM,
                REPORTER_USER_ACCOUNT_ID);
    }

}
