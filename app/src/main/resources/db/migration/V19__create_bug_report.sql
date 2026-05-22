-- =====================================================
-- V19: Administration context — BugReport (VOC 1차 도입)
--
-- 사용자가 자유텍스트로 버그를 제보, 어드민이 read-only 조회.
-- Spec: docs/superpowers/specs/2026-05-21-voc-bug-report-design.md §3-1
-- =====================================================

CREATE TABLE bug_report (
    bug_report_id              BIGINT       NOT NULL AUTO_INCREMENT,
    reporter_user_account_id   BIGINT       NOT NULL,
    content                    TEXT         NOT NULL,
    page_url                   VARCHAR(500) NULL,
    user_agent                 VARCHAR(500) NULL,
    partyroom_id               BIGINT       NULL,
    created_at                 DATETIME     NOT NULL,
    PRIMARY KEY (bug_report_id),
    INDEX idx_br_created (created_at DESC),
    INDEX idx_br_reporter (reporter_user_account_id, created_at DESC),
    INDEX idx_br_partyroom (partyroom_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
