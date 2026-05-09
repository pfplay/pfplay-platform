package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import com.pfplaybackend.api.administration.domain.enums.ReportStatus;

import java.time.LocalDateTime;

/**
 * C-2 detail 응답.
 *
 * <p>Cross-context loose-ref join 4종(reporter member×nickname, reporter userAccount×email,
 * partyroom×title, host member×nickname)을 service에서 조립. 외부 BC 엔티티가 부재하면
 * 해당 필드는 {@code null} — orphan tolerance(D7). nested record 자체는 항상 build,
 * 내부 nullable 필드만 null로 채운다 (클라이언트 렌더링 일관성).
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.3, §5
 */
public record AdminReportDetailResponse(
        Long reportId,
        ReportStatus status,
        ReportCategory category,
        String description,
        Reporter reporter,
        Partyroom partyroom,
        Review review,
        LocalDateTime createdAt
) {
    /** Reporter user_account + member 합성. orphan 시 email/nickname 각각 null. */
    public record Reporter(Long userAccountId, String email, String nickname) {}

    /** Partyroom title + host nickname 합성. orphan 시 title/host=null. */
    public record Partyroom(Long partyroomId, String title, Host host) {}

    /** Partyroom host member nickname. */
    public record Host(Long userAccountId, String nickname) {}

    /** Review 상태 메타. PENDING/REVIEWING은 부분 null, terminal은 모두 populated. */
    public record Review(Long reviewedByAdministratorId, String resolutionNote, LocalDateTime resolvedAt) {}
}
