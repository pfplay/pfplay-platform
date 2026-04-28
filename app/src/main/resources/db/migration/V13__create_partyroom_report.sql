-- =====================================================
-- V13: Administration context — PartyroomReport
--
-- 유저가 파티룸을 신고, 어드민이 검토.
-- Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §4.1
-- Plan: docs/superpowers/plans/2026-04-28-admin-platform-pr13.md G1 Task 1
--
-- D1 결정: 24h 중복 방지는 앱 검증 — day_bucket 컬럼/unique index 도입 안 함.
--          race window는 ms 단위, audit 영향 미미. abuse 패턴 발견 시 별 PR로 evolve.
-- =====================================================

CREATE TABLE partyroom_report (
    report_id                      BIGINT       NOT NULL AUTO_INCREMENT,
    partyroom_id                   BIGINT       NOT NULL,
    reporter_user_account_id       BIGINT       NOT NULL,
    category                       ENUM('INAPPROPRIATE_CONTENT','HARASSMENT','SPAM','COPYRIGHT','OTHER') NOT NULL,
    description                    TEXT         NULL,
    status                         ENUM('PENDING','REVIEWING','RESOLVED','DISMISSED') NOT NULL DEFAULT 'PENDING',
    reviewed_by_administrator_id   BIGINT       NULL,
    resolution_note                TEXT         NULL,
    created_at                     DATETIME     NOT NULL,
    resolved_at                    DATETIME     NULL,
    PRIMARY KEY (report_id),
    CONSTRAINT fk_pr_reviewed_by
        FOREIGN KEY (reviewed_by_administrator_id)
        REFERENCES administrator(administrator_id),
    INDEX idx_pr_status_created (status, created_at DESC),
    INDEX idx_pr_partyroom (partyroom_id, created_at DESC),
    INDEX idx_pr_reporter (reporter_user_account_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
