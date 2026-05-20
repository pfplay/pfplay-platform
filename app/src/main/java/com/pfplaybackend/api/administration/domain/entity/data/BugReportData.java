package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.common.domain.annotation.AggregateRoot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 VOC(버그 리포팅) aggregate root.
 *
 * Lifecycle: INSERT-only (1차 도입, 답변 워크플로 out-of-scope).
 * Spec: docs/superpowers/specs/2026-05-21-voc-bug-report-design.md §3-1
 */
@AggregateRoot
@Entity
@Table(name = "bug_report", indexes = {
        @Index(name = "idx_br_created", columnList = "created_at DESC"),
        @Index(name = "idx_br_reporter", columnList = "reporter_user_account_id, created_at DESC"),
        @Index(name = "idx_br_partyroom", columnList = "partyroom_id, created_at DESC")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BugReportData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bug_report_id")
    private Long bugReportId;

    @Column(name = "reporter_user_account_id", nullable = false)
    private Long reporterUserAccountId;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "page_url", length = 500)
    private String pageUrl;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "partyroom_id")
    private Long partyroomId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static BugReportData create(Long reporterUserAccountId, String content,
                                       String pageUrl, String userAgent, Long partyroomId,
                                       LocalDateTime now) {
        BugReportData d = new BugReportData();
        d.reporterUserAccountId = reporterUserAccountId;
        d.content = content;
        d.pageUrl = pageUrl;
        d.userAgent = userAgent;
        d.partyroomId = partyroomId;
        d.createdAt = now;
        return d;
    }
}
