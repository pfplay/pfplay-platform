package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;
import com.pfplaybackend.api.administration.domain.exception.AdminReportException;
import com.pfplaybackend.api.common.domain.annotation.AggregateRoot;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 파티룸 신고 aggregate root.
 *
 * Lifecycle: PENDING → REVIEWING → RESOLVED/DISMISSED. terminal에서 변경 금지.
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §4.1
 *
 * Note: BaseEntity 미상속 (Plan G1 Task 1 §3 옵션 A) — V13 DDL의 created_at만 보유,
 *       updated_at 부재(신고 lifecycle은 resolved_at으로 충분).
 */
@AggregateRoot
@Entity
@Table(name = "partyroom_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartyroomReportData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "partyroom_id", nullable = false)
    private Long partyroomId;

    @Column(name = "reporter_user_account_id", nullable = false)
    private Long reporterUserAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private ReportCategory category;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReportStatus status;

    @Column(name = "reviewed_by_administrator_id")
    private Long reviewedByAdministratorId;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /**
     * 신고 생성 factory. status=PENDING, createdAt=now.
     * reviewedByAdministratorId / resolutionNote / resolvedAt은 null.
     */
    public static PartyroomReportData create(Long partyroomId,
                                             Long reporterUserAccountId,
                                             ReportCategory category,
                                             String description) {
        PartyroomReportData r = new PartyroomReportData();
        r.partyroomId = partyroomId;
        r.reporterUserAccountId = reporterUserAccountId;
        r.category = category;
        r.description = description;
        r.status = ReportStatus.PENDING;
        r.createdAt = LocalDateTime.now();
        return r;
    }

    /**
     * REVIEWING 진입. PENDING에서만 허용.
     * 첫 검토자만 reviewedByAdministratorId에 기록 — 이후 hold→재진입 시 첫 admin 보존 (D3.2).
     */
    public void startReview(Long byAdministratorId) {
        guardTransition(ReportStatus.REVIEWING);
        this.status = ReportStatus.REVIEWING;
        if (this.reviewedByAdministratorId == null) {
            this.reviewedByAdministratorId = byAdministratorId;
        }
    }

    /**
     * RESOLVED 진입(terminal). REVIEWING에서만 허용(matrix).
     * resolvedAt = now, resolutionNote 기록.
     *
     * 호출자 책임: resolutionNote 비어있지 않음 보장 (G4 controller `@NotBlank` 또는 service guard).
     * 도메인은 빈 note도 그대로 영속 — D3.1 결정에 따라 검증은 application layer.
     *
     * byAdministratorId 파라미터는 callsite symmetry 목적. resolve는 항상 REVIEWING에서 호출되므로
     * (matrix 보장) 기존 reviewedByAdministratorId가 startReview 시점에 set됨 — overwrite 안 함.
     */
    public void resolve(Long byAdministratorId, String resolutionNote) {
        guardTransition(ReportStatus.RESOLVED);
        this.resolutionNote = resolutionNote;
        this.status = ReportStatus.RESOLVED;
        this.resolvedAt = LocalDateTime.now();
    }

    /**
     * DISMISSED 진입(terminal). PENDING/REVIEWING 양쪽에서 허용(matrix).
     * PENDING→DISMISSED 직접 전이 시 reviewedByAdministratorId가 처음 set됨 (D3.2).
     * REVIEWING→DISMISSED 시 startReview 때 set된 reviewer 보존.
     *
     * 호출자 책임: resolutionNote 비어있지 않음 보장 (G4 controller `@NotBlank` 또는 service guard).
     */
    public void dismiss(Long byAdministratorId, String resolutionNote) {
        guardTransition(ReportStatus.DISMISSED);
        if (this.reviewedByAdministratorId == null) {
            this.reviewedByAdministratorId = byAdministratorId;
        }
        this.resolutionNote = resolutionNote;
        this.status = ReportStatus.DISMISSED;
        this.resolvedAt = LocalDateTime.now();
    }

    /**
     * REVIEWING → PENDING 회귀(보류). reviewedByAdministratorId 보존(D3.2).
     *
     * byAdministratorId 파라미터는 startReview/resolve/dismiss와의 callsite symmetry 목적.
     * 본 메서드는 audit 가시성 위해 첫 검토자(reviewedByAdministratorId)를 그대로 두므로 사용하지 않음.
     */
    @SuppressWarnings("unused")
    public void hold(Long byAdministratorId) {
        guardTransition(ReportStatus.PENDING);
        this.status = ReportStatus.PENDING;
    }

    private void guardTransition(ReportStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw ExceptionCreator.create(AdminReportException.INVALID_STATE_TRANSITION);
        }
    }
}
