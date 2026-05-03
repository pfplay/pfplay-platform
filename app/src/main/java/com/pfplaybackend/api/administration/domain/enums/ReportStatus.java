package com.pfplaybackend.api.administration.domain.enums;

/**
 * 파티룸 신고(partyroom_report) 처리 상태 enum.
 *
 * Lifecycle (D3):
 *   PENDING ──▶ REVIEWING ──▶ RESOLVED (terminal)
 *      │           │       └▶ DISMISSED (terminal)
 *      │           └─▶ PENDING (보류, hold)
 *      └─▶ DISMISSED (직접 거절)
 *
 * Terminal(RESOLVED/DISMISSED)에서는 변경 금지. {@code from == to}는 거부(idempotent silent 안 함).
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.4 / §4.2
 */
public enum ReportStatus {
    PENDING, REVIEWING, RESOLVED, DISMISSED;

    public boolean isTerminal() {
        return this == RESOLVED || this == DISMISSED;
    }

    public boolean isOpen() {
        return !isTerminal();
    }

    /**
     * 본 status에서 target status로 전이 가능 여부.
     * {@code from == to} 는 false(no-op 거부).
     */
    public boolean canTransitionTo(ReportStatus target) {
        if (this == target) return false;
        return switch (this) {
            case PENDING -> target == REVIEWING || target == DISMISSED;
            case REVIEWING -> target == RESOLVED || target == DISMISSED || target == PENDING;
            case RESOLVED, DISMISSED -> false;
        };
    }
}
