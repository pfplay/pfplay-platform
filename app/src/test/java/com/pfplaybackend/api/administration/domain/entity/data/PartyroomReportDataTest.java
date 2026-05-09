package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import com.pfplaybackend.api.common.exception.http.AbstractHTTPException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * PartyroomReportData 도메인 메서드 단위 테스트 매트릭스.
 * Spec: 2026-04-28-admin-platform-pr13-design.md §4.3
 *
 * Helper {@link #pending()} / {@link #reviewing(Long)} / {@link #resolved(Long)} / {@link #dismissed(Long)}
 * 으로 시작 상태 셋업 후 도메인 메서드 호출 + status/audit 필드 단언.
 */
class PartyroomReportDataTest {

    private static final Long PARTYROOM_ID = 100L;
    private static final Long REPORTER_USER_ACCOUNT_ID = 200L;
    private static final Long ADMIN_ID = 11L;
    private static final Long OTHER_ADMIN_ID = 22L;
    private static final String NOTE = "확인 후 처리 완료";

    // ========== factory ==========

    @Test
    @DisplayName("create: status=PENDING, createdAt 기록, audit 필드는 null")
    void create_setsPendingAndCreatedAt() {
        PartyroomReportData r = PartyroomReportData.create(
                PARTYROOM_ID, REPORTER_USER_ACCOUNT_ID, ReportCategory.SPAM, "스팸");

        assertThat(r.getPartyroomId()).isEqualTo(PARTYROOM_ID);
        assertThat(r.getReporterUserAccountId()).isEqualTo(REPORTER_USER_ACCOUNT_ID);
        assertThat(r.getCategory()).isEqualTo(ReportCategory.SPAM);
        assertThat(r.getDescription()).isEqualTo("스팸");
        assertThat(r.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(r.getCreatedAt()).isNotNull();
        assertThat(r.getReviewedByAdministratorId()).isNull();
        assertThat(r.getResolutionNote()).isNull();
        assertThat(r.getResolvedAt()).isNull();
        assertThat(r.getReportId()).isNull(); // assigned on persist
    }

    // ========== startReview ==========

    @Test
    @DisplayName("startReview from PENDING: status=REVIEWING, reviewer 기록")
    void startReview_fromPending_setsReviewerAndStatus() {
        PartyroomReportData r = pending();

        r.startReview(ADMIN_ID);

        assertThat(r.getStatus()).isEqualTo(ReportStatus.REVIEWING);
        assertThat(r.getReviewedByAdministratorId()).isEqualTo(ADMIN_ID);
        assertThat(r.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("startReview from REVIEWING: INVALID_STATE_TRANSITION")
    void startReview_fromReviewing_throwsInvalidTransition() {
        PartyroomReportData r = reviewing(ADMIN_ID);

        assertThatThrownBy(() -> r.startReview(OTHER_ADMIN_ID))
                .isInstanceOf(AbstractHTTPException.class)
                .extracting("errorCode").isEqualTo("RPT-002");
    }

    // ========== resolve ==========

    @Test
    @DisplayName("resolve from REVIEWING: status=RESOLVED, resolvedAt+note 기록")
    void resolve_fromReviewing_setsTerminalState() {
        PartyroomReportData r = reviewing(ADMIN_ID);

        r.resolve(ADMIN_ID, NOTE);

        assertThat(r.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(r.getResolvedAt()).isNotNull();
        assertThat(r.getResolutionNote()).isEqualTo(NOTE);
        assertThat(r.getReviewedByAdministratorId()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("resolve from PENDING: matrix 금지 → INVALID_STATE_TRANSITION")
    void resolve_fromPending_throwsInvalidTransition() {
        PartyroomReportData r = pending();

        assertThatThrownBy(() -> r.resolve(ADMIN_ID, NOTE))
                .isInstanceOf(AbstractHTTPException.class)
                .extracting("errorCode").isEqualTo("RPT-002");
    }

    // ========== dismiss ==========

    @Test
    @DisplayName("dismiss from PENDING (직접 거절): status=DISMISSED, reviewer/resolvedAt set")
    void dismiss_fromPending_directDismissSetsReviewerAndResolvedAt() {
        PartyroomReportData r = pending();

        r.dismiss(ADMIN_ID, NOTE);

        assertThat(r.getStatus()).isEqualTo(ReportStatus.DISMISSED);
        assertThat(r.getReviewedByAdministratorId()).isEqualTo(ADMIN_ID);
        assertThat(r.getResolvedAt()).isNotNull();
        assertThat(r.getResolutionNote()).isEqualTo(NOTE);
    }

    @Test
    @DisplayName("dismiss from REVIEWING: status=DISMISSED, 첫 admin 보존")
    void dismiss_fromReviewing_setsTerminalState() {
        PartyroomReportData r = reviewing(ADMIN_ID);

        r.dismiss(OTHER_ADMIN_ID, NOTE);

        assertThat(r.getStatus()).isEqualTo(ReportStatus.DISMISSED);
        assertThat(r.getResolvedAt()).isNotNull();
        assertThat(r.getResolutionNote()).isEqualTo(NOTE);
        // D3.2: 첫 검토자(ADMIN_ID) 보존, OTHER_ADMIN_ID는 덮어쓰지 않음
        assertThat(r.getReviewedByAdministratorId()).isEqualTo(ADMIN_ID);
    }

    // ========== hold ==========

    @Test
    @DisplayName("hold from REVIEWING: status=PENDING, reviewer 보존(audit)")
    void hold_fromReviewing_revertsToPendingPreservingReviewer() {
        PartyroomReportData r = reviewing(ADMIN_ID);

        r.hold(OTHER_ADMIN_ID);

        assertThat(r.getStatus()).isEqualTo(ReportStatus.PENDING);
        // D3.2: 보류 시에도 첫 검토자 audit 가시 — 누가 검토했었는지 보존
        assertThat(r.getReviewedByAdministratorId()).isEqualTo(ADMIN_ID);
        assertThat(r.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("hold from PENDING: matrix 금지 → INVALID_STATE_TRANSITION")
    void hold_fromPending_throwsInvalidTransition() {
        PartyroomReportData r = pending();

        assertThatThrownBy(() -> r.hold(ADMIN_ID))
                .isInstanceOf(AbstractHTTPException.class)
                .extracting("errorCode").isEqualTo("RPT-002");
    }

    // ========== terminal-from parameterized ==========

    @ParameterizedTest(name = "[{index}] from {0} -> {1} throws RPT-002")
    @MethodSource("terminalSeeds")
    @DisplayName("terminal(RESOLVED/DISMISSED) 시작 시 모든 도메인 메서드 호출 → INVALID_STATE_TRANSITION")
    void anyTransition_fromTerminal_throwsInvalidTransition(
            ReportStatus terminalState,
            String methodName,
            Consumer<PartyroomReportData> invocation) {

        PartyroomReportData r = (terminalState == ReportStatus.RESOLVED)
                ? resolved(ADMIN_ID)
                : dismissed(ADMIN_ID);
        assertThat(r.getStatus()).isEqualTo(terminalState);

        assertThatThrownBy(() -> invocation.accept(r))
                .as("from %s, method %s must throw INVALID_STATE_TRANSITION", terminalState, methodName)
                .isInstanceOf(AbstractHTTPException.class)
                .extracting("errorCode").isEqualTo("RPT-002");
    }

    static Stream<Arguments> terminalSeeds() {
        Consumer<PartyroomReportData> startReview = r -> r.startReview(OTHER_ADMIN_ID);
        Consumer<PartyroomReportData> resolve = r -> r.resolve(OTHER_ADMIN_ID, NOTE);
        Consumer<PartyroomReportData> dismiss = r -> r.dismiss(OTHER_ADMIN_ID, NOTE);
        Consumer<PartyroomReportData> hold = r -> r.hold(OTHER_ADMIN_ID);

        return Stream.of(
                arguments(ReportStatus.RESOLVED, "startReview", startReview),
                arguments(ReportStatus.RESOLVED, "resolve", resolve),
                arguments(ReportStatus.RESOLVED, "dismiss", dismiss),
                arguments(ReportStatus.RESOLVED, "hold", hold),
                arguments(ReportStatus.DISMISSED, "startReview", startReview),
                arguments(ReportStatus.DISMISSED, "resolve", resolve),
                arguments(ReportStatus.DISMISSED, "dismiss", dismiss),
                arguments(ReportStatus.DISMISSED, "hold", hold)
        );
    }

    // ========== fixtures ==========

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

    private static PartyroomReportData dismissed(Long adminId) {
        PartyroomReportData r = pending();
        r.dismiss(adminId, NOTE);
        return r;
    }
}
