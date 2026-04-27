-- =====================================================
-- V7: Administration context — AdminAction aggregate
-- Spec: docs/superpowers/specs/2026-04-27-admin-platform-pr8-design.md §3
-- Plan: docs/superpowers/plans/2026-04-27-admin-platform-pr8.md Task 1
--
-- 어드민의 시스템 액션 감사 로그. Append-only.
-- 교차 컨텍스트 참조(partyroom_id, target_id)는 FK 없이 값 저장.
-- =====================================================

CREATE TABLE partyroom_admin_action (
    action_id          BIGINT       NOT NULL AUTO_INCREMENT,
    administrator_id   BIGINT       NOT NULL,
    action_type        VARCHAR(32)  NOT NULL,
    target_type        VARCHAR(16)  NOT NULL,
    target_id          BIGINT       NOT NULL,
    partyroom_id       BIGINT       NULL,
    reason             TEXT         NULL,
    metadata           JSON         NULL,
    occurred_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (action_id),
    CONSTRAINT fk_paa_administrator
        FOREIGN KEY (administrator_id)
        REFERENCES administrator(administrator_id),
    INDEX idx_paa_partyroom_time (partyroom_id, occurred_at DESC),
    INDEX idx_paa_administrator_time (administrator_id, occurred_at DESC),
    INDEX idx_paa_target (target_type, target_id, occurred_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
