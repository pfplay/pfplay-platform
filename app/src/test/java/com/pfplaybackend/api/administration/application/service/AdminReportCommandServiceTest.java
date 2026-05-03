package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportStatusUpdateRequest;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomReportRepository;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import com.pfplaybackend.api.common.exception.http.AbstractHTTPException;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link AdminReportCommandService} (PR 13 G4).
 *
 * <p>Uses real {@link PartyroomReportData} entities (factory-built via
 * {@link PartyroomReportData#create(Long, Long, ReportCategory, String)} +
 * helper transitions) so {@code report.startReview/resolve/dismiss/hold} flow
 * exercises true domain mechanics. Repository / queryService / adminContext
 * are mocked.
 *
 * <p>Mirror of {@code AdminMemberTierCommandServiceTest} (PR 12b2).
 */
@ExtendWith(MockitoExtension.class)
class AdminReportCommandServiceTest {

    private static final Long REPORT_ID = 77L;
    private static final Long PARTYROOM_ID = 100L;
    private static final Long REPORTER_USER_ACCOUNT_ID = 200L;
    private static final Long ADMIN_ID = 11L;
    private static final String NOTE = "처리 완료 메모";

    @Mock PartyroomReportRepository reportRepository;
    @Mock AdminReportQueryService queryService;
    @Mock AdminContext adminContext;

    @InjectMocks AdminReportCommandService service;

    // ============================== happy: 4 transitions ==============================

    @Test
    @DisplayName("changeStatus PENDING → REVIEWING: startReview 호출 + save + buildDetailResponse 재사용")
    void changeStatus_pendingToReviewing_callsStartReview() {
        PartyroomReportData report = pending();
        AdminReportDetailResponse stub = stubResponse();
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        given(queryService.buildDetailResponse(report)).willReturn(stub);

        AdminReportDetailResponse result = service.changeStatus(REPORT_ID,
                new AdminReportStatusUpdateRequest(ReportStatus.REVIEWING, null));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.REVIEWING);
        assertThat(report.getReviewedByAdministratorId()).isEqualTo(ADMIN_ID);
        verify(reportRepository).save(report);
        verify(queryService).buildDetailResponse(report);
        assertThat(result).isSameAs(stub);
    }

    @Test
    @DisplayName("changeStatus REVIEWING → RESOLVED: resolve 호출 + note 기록 + save + buildDetailResponse")
    void changeStatus_reviewingToResolved_callsResolve() {
        PartyroomReportData report = reviewing(ADMIN_ID);
        AdminReportDetailResponse stub = stubResponse();
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        given(queryService.buildDetailResponse(report)).willReturn(stub);

        service.changeStatus(REPORT_ID,
                new AdminReportStatusUpdateRequest(ReportStatus.RESOLVED, NOTE));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(report.getResolutionNote()).isEqualTo(NOTE);
        assertThat(report.getResolvedAt()).isNotNull();
        verify(reportRepository).save(report);
        verify(queryService).buildDetailResponse(report);
    }

    @Test
    @DisplayName("changeStatus PENDING → DISMISSED (직접 거절): dismiss 호출 + 첫 reviewer 등록 + note 기록")
    void changeStatus_pendingToDismissed_callsDismissDirect() {
        PartyroomReportData report = pending();
        AdminReportDetailResponse stub = stubResponse();
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        given(queryService.buildDetailResponse(report)).willReturn(stub);

        service.changeStatus(REPORT_ID,
                new AdminReportStatusUpdateRequest(ReportStatus.DISMISSED, NOTE));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.DISMISSED);
        assertThat(report.getReviewedByAdministratorId()).isEqualTo(ADMIN_ID);
        assertThat(report.getResolutionNote()).isEqualTo(NOTE);
        assertThat(report.getResolvedAt()).isNotNull();
        verify(reportRepository).save(report);
    }

    @Test
    @DisplayName("changeStatus REVIEWING → PENDING (보류): hold 호출 + reviewer 보존 + resolvedAt 유지 null")
    void changeStatus_reviewingToPending_callsHold() {
        PartyroomReportData report = reviewing(ADMIN_ID);
        AdminReportDetailResponse stub = stubResponse();
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);
        given(queryService.buildDetailResponse(report)).willReturn(stub);

        service.changeStatus(REPORT_ID,
                new AdminReportStatusUpdateRequest(ReportStatus.PENDING, null));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
        // D3.2: 보류 시에도 첫 검토자 audit 가시 — 누가 검토했었는지 보존
        assertThat(report.getReviewedByAdministratorId()).isEqualTo(ADMIN_ID);
        assertThat(report.getResolvedAt()).isNull();
        verify(reportRepository).save(report);
    }

    // ============================== INVALID_STATE_TRANSITION ==============================

    @Test
    @DisplayName("changeStatus RESOLVED 진입 from PENDING (matrix 금지): 도메인 guard에서 RPT-002")
    void changeStatus_pendingToResolved_throwsInvalidStateTransition() {
        PartyroomReportData report = pending();
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);

        assertThatThrownBy(() -> service.changeStatus(REPORT_ID,
                new AdminReportStatusUpdateRequest(ReportStatus.RESOLVED, NOTE)))
                .isInstanceOf(AbstractHTTPException.class)
                .extracting("errorCode").isEqualTo("RPT-002");

        // 도메인 guard에서 throw → save / buildDetailResponse 호출 안 함
        verify(reportRepository, never()).save(any());
        verify(queryService, never()).buildDetailResponse(any());
    }

    @Test
    @DisplayName("changeStatus DISMISSED 진입 from RESOLVED (terminal 금지): 도메인 guard에서 RPT-002")
    void changeStatus_terminalSource_throwsInvalidStateTransition() {
        PartyroomReportData report = resolved(ADMIN_ID);
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);

        assertThatThrownBy(() -> service.changeStatus(REPORT_ID,
                new AdminReportStatusUpdateRequest(ReportStatus.DISMISSED, NOTE)))
                .isInstanceOf(AbstractHTTPException.class)
                .extracting("errorCode").isEqualTo("RPT-002");

        verify(reportRepository, never()).save(any());
        verify(queryService, never()).buildDetailResponse(any());
    }

    // ============================== RESOLUTION_NOTE_REQUIRED (service guard, D3.1) ==============================

    @Test
    @DisplayName("changeStatus terminal RESOLVED + note=null → RESOLUTION_NOTE_REQUIRED RPT-003 (도메인 호출 전)")
    void changeStatus_resolvedNullNote_throwsResolutionNoteRequired() {
        PartyroomReportData report = reviewing(ADMIN_ID);
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);

        assertThatThrownBy(() -> service.changeStatus(REPORT_ID,
                new AdminReportStatusUpdateRequest(ReportStatus.RESOLVED, null)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-003");

        // domain method가 호출되지 않았으므로 entity는 REVIEWING 그대로 + persist/buildResponse 미실행
        assertThat(report.getStatus()).isEqualTo(ReportStatus.REVIEWING);
        verify(reportRepository, never()).save(any());
        verify(queryService, never()).buildDetailResponse(any());
    }

    @Test
    @DisplayName("changeStatus terminal DISMISSED + note=공백 → RESOLUTION_NOTE_REQUIRED RPT-003")
    void changeStatus_dismissedBlankNote_throwsResolutionNoteRequired() {
        PartyroomReportData report = pending();
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(adminContext.currentAdministratorId()).willReturn(ADMIN_ID);

        assertThatThrownBy(() -> service.changeStatus(REPORT_ID,
                new AdminReportStatusUpdateRequest(ReportStatus.DISMISSED, "   ")))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-003");

        assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
        verify(reportRepository, never()).save(any());
    }

    // ============================== REPORT_NOT_FOUND ==============================

    @Test
    @DisplayName("changeStatus reportId 부재 → REPORT_NOT_FOUND RPT-001 + adminContext 미사용")
    void changeStatus_reportNotFound_throws() {
        given(reportRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(999L,
                new AdminReportStatusUpdateRequest(ReportStatus.REVIEWING, null)))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RPT-001");

        verify(reportRepository, never()).save(any());
        verify(queryService, never()).buildDetailResponse(any());
    }

    // ============================== fixtures ==============================

    private static PartyroomReportData pending() {
        return PartyroomReportData.create(
                PARTYROOM_ID, REPORTER_USER_ACCOUNT_ID, ReportCategory.HARASSMENT, "괴롭힘");
    }

    private static PartyroomReportData reviewing(Long adminId) {
        PartyroomReportData r = pending();
        r.startReview(adminId);
        return r;
    }

    private static PartyroomReportData resolved(Long adminId) {
        PartyroomReportData r = reviewing(adminId);
        r.resolve(adminId, NOTE);
        return r;
    }

    private static AdminReportDetailResponse stubResponse() {
        return new AdminReportDetailResponse(
                REPORT_ID, ReportStatus.PENDING, ReportCategory.HARASSMENT, "stub",
                new AdminReportDetailResponse.Reporter(REPORTER_USER_ACCOUNT_ID, null, null),
                new AdminReportDetailResponse.Partyroom(PARTYROOM_ID, null, null),
                new AdminReportDetailResponse.Review(null, null, null),
                java.time.LocalDateTime.now());
    }
}
