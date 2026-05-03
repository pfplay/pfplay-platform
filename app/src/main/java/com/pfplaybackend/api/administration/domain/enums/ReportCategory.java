package com.pfplaybackend.api.administration.domain.enums;

/**
 * 파티룸 신고 카테고리 enum.
 * V13 partyroom_report.category ENUM 컬럼과 정확 일치(name() 매칭).
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §4.1
 */
public enum ReportCategory {
    INAPPROPRIATE_CONTENT,
    HARASSMENT,
    SPAM,
    COPYRIGHT,
    OTHER
}
